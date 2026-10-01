/*
 * Copyright 2016, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openremote.manager.asset;

import static jakarta.ws.rs.core.Response.Status.*;
import static org.openremote.manager.asset.AssetProcessingService.ATTRIBUTE_EVENT_PROCESSOR;
import static org.openremote.model.query.AssetQuery.Access;
import static org.openremote.model.value.MetaItemType.*;

import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.jboss.resteasy.plugins.validation.ResteasyViolationExceptionImpl;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetResource;
import org.openremote.model.asset.AssetTree;
import org.openremote.model.asset.AttributeConfigurationDocument;
import org.openremote.model.asset.AttributeConfigurationImportFailure;
import org.openremote.model.asset.AttributeConfigurationImportPreview;
import org.openremote.model.asset.AttributeConfigurationImportRequest;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.attribute.*;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.security.ClientRole;
import org.openremote.model.util.AttributeConfigurationUtil;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public class AssetResourceImpl extends ManagerWebResource implements AssetResource {

  private static final Logger LOG = Logger.getLogger(AssetResourceImpl.class.getName());

  protected final AssetStorageService assetStorageService;
  protected final MessageBrokerService messageBrokerService;
  protected final ClientEventService clientEventService;

  public AssetResourceImpl(
      TimerService timerService,
      ManagerIdentityService identityService,
      AssetStorageService assetStorageService,
      MessageBrokerService messageBrokerService,
      ClientEventService clientEventService) {
    super(timerService, identityService);
    this.assetStorageService = assetStorageService;
    this.messageBrokerService = messageBrokerService;
    this.clientEventService = clientEventService;
  }

  @Override
  public Asset<?>[] getCurrentUserAssets(RequestParams requestParams) {
    try {
      if (isSuperUser()) {
        return new Asset<?>[0];
      }

      if (!isAuthenticated()) {
        throw new NotAuthorizedException("Must be authenticated");
      }

      AssetQuery query = new AssetQuery().userIds(getUserId());

      if (!assetStorageService.authorizeAssetQuery(
          query, getAuthContext(), getRequestRealmName())) {
        throw new ForbiddenException("User not authorized to execute specified query");
      }

      List<Asset<?>> assets = assetStorageService.findAll(query);

      // Compress response (the request attribute enables the interceptor)
      request.setAttribute(HttpHeaders.CONTENT_ENCODING, "gzip");

      return assets.toArray(new Asset[0]);
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  @Override
  public UserAssetLink[] getUserAssetLinks(
      RequestParams requestParams, String realm, String userId, String assetId) {
    try {
      realm = TextUtil.isNullOrEmpty(realm) ? getAuthenticatedRealmName() : realm;
      boolean hasAdminReadRole =
          hasResourceRole(ClientRole.READ_ADMIN.getValue(), Constants.KEYCLOAK_CLIENT_ID);

      if (realm == null) throw new WebApplicationException(BAD_REQUEST);

      if (!(isSuperUser() || getAuthenticatedRealmName().equals(realm)))
        throw new WebApplicationException(FORBIDDEN);

      if (!hasAdminReadRole && userId != null && !Objects.equals(getUserId(), userId)) {
        throw new ForbiddenException(
            "Can only retrieve own asset links unless you have role '"
                + ClientRole.READ_ADMIN
                + "'");
      }

      if (userId != null && !identityService.getIdentityProvider().isUserInRealm(userId, realm))
        throw new WebApplicationException(BAD_REQUEST);

      UserAssetLink[] result =
          assetStorageService
              .findUserAssetLinks(realm, userId, assetId)
              .toArray(new UserAssetLink[0]);

      // Compress response (the request attribute enables the interceptor)
      request.setAttribute(HttpHeaders.CONTENT_ENCODING, "gzip");

      return result;

    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  @Override
  public void createUserAssetLinks(
      RequestParams requestParams, List<UserAssetLink> userAssetLinks) {

    // Restricted users cannot create or delete links
    if (isRestrictedUser()) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // Check all links are for the same user and realm
    String realm = userAssetLinks.getFirst().getId().getRealm();
    String userId = userAssetLinks.getFirst().getId().getUserId();
    String[] assetIds = new String[userAssetLinks.size()];

    IntStream.range(0, userAssetLinks.size())
        .forEach(
            i -> {
              UserAssetLink userAssetLink = userAssetLinks.get(i);
              assetIds[i] = userAssetLink.getId().getAssetId();

              if (!userAssetLink.getId().getRealm().equals(realm)
                  || !userAssetLink.getId().getUserId().equals(userId)) {
                throw new BadRequestException("All user asset links must be for the same user");
              }
            });

    if (!isSuperUser() && !realm.equals(getAuthenticatedRealmName())) {
      throw new WebApplicationException(FORBIDDEN);
    }

    if (!identityService.getIdentityProvider().isUserInRealm(userId, realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }

    List<Asset<?>> assets =
        assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select().excludeAttributes())
                .realm(new RealmPredicate(realm))
                .ids(assetIds));

    if (assets.size() != userAssetLinks.size()) {
      throw new BadRequestException("One or more asset IDs are invalid");
    }

    try {
      assetStorageService.storeUserAssetLinks(userAssetLinks);
    } catch (Exception e) {
      throw new WebApplicationException(BAD_REQUEST);
    }
  }

  @Override
  public void deleteUserAssetLink(
      RequestParams requestParams, String realm, String userId, String assetId) {
    deleteUserAssetLinks(
        requestParams, Collections.singletonList(new UserAssetLink(realm, userId, assetId)));
  }

  @Override
  public void deleteAllUserAssetLinks(RequestParams requestParams, String realm, String userId) {
    // Restricted users cannot create or delete links
    if (isRestrictedUser()) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // Regular users in a different realm can not delete links
    if (!isSuperUser() && !getAuthenticatedRealm().getName().equals(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // User must be in the same realm as the requested realm
    if (!identityService.getIdentityProvider().isUserInRealm(userId, realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }

    assetStorageService.deleteUserAssetLinks(userId);
  }

  @Override
  public void deleteUserAssetLinks(
      RequestParams requestParams, List<UserAssetLink> userAssetLinks) {
    // Restricted users cannot create or delete links
    if (isRestrictedUser()) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // Check all links are for the same user and realm
    String realm = userAssetLinks.getFirst().getId().getRealm();
    String userId = userAssetLinks.getFirst().getId().getUserId();

    if (userAssetLinks.stream()
        .anyMatch(
            userAssetLink ->
                !userAssetLink.getId().getRealm().equals(realm)
                    || !userAssetLink.getId().getUserId().equals(userId))) {
      throw new BadRequestException("All user asset links must be for the same user");
    }

    // Regular users in a different realm can not delete links
    if (!isSuperUser() && !getAuthenticatedRealm().getName().equals(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // If delete count doesn't equal link count an exception will be thrown
    try {
      assetStorageService.deleteUserAssetLinks(userAssetLinks);
    } catch (Exception e) {
      LOG.log(Level.INFO, "Failed to delete user asset links", e);
      throw new BadRequestException();
    }
  }

  @Override
  public Asset<?> getPartial(RequestParams requestParams, String assetId) {
    return get(requestParams, assetId, false);
  }

  @Override
  public Asset<?> get(RequestParams requestParams, String assetId) {
    return get(requestParams, assetId, true);
  }

  public Asset<?> get(RequestParams requestParams, String assetId, boolean loadComplete) {
    try {
      Asset<?> asset;

      // Check restricted
      if (isRestrictedUser()) {
        if (!assetStorageService.isUserAsset(getUserId(), assetId)) {
          LOG.fine(
              "Forbidden access for restricted user: username="
                  + getUsername()
                  + ", assetID="
                  + assetId);
          throw new WebApplicationException(FORBIDDEN);
        }
        asset = assetStorageService.find(assetId, loadComplete, Access.PROTECTED);
      } else {
        asset = assetStorageService.find(assetId, loadComplete);
      }

      if (asset == null) throw new WebApplicationException(NOT_FOUND);

      if (!isRealmActiveAndAccessible(asset.getRealm())) {
        LOG.fine(
            "Forbidden access (realm '"
                + asset.getRealm()
                + "' nonexistent, inactive or inaccessible) for user: "
                + getUsername());
        throw new WebApplicationException(FORBIDDEN);
      }

      // Compress response (the request attribute enables the interceptor)
      request.setAttribute(HttpHeaders.CONTENT_ENCODING, "gzip");

      return asset;

    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  // TODO: AssetResource update should not overwrite the existing asset
  @Override
  public Asset<?> update(RequestParams requestParams, String assetId, Asset<?> asset) {

    LOG.fine("Updating asset: assetID=" + assetId);

    try {
      Asset<?> storageAsset = assetStorageService.find(assetId, true);

      if (storageAsset == null) {
        LOG.fine("Asset not found: assetID=" + assetId);
        throw new WebApplicationException(NOT_FOUND);
      }

      // Realm of asset must be accessible
      if (!isRealmActiveAndAccessible(storageAsset.getRealm())) {
        LOG.fine(
            "Realm '"
                + storageAsset.getRealm()
                + "' is nonexistent, inactive or inaccessible: username="
                + getUsername()
                + ", assetID="
                + assetId);
        throw new WebApplicationException(FORBIDDEN);
      }

      if (!storageAsset.getRealm().equals(asset.getRealm())) {
        LOG.fine(
            "Cannot change asset's realm: existingRealm="
                + storageAsset.getRealm()
                + ", requestedRealm="
                + asset.getRealm());
        throw new WebApplicationException(FORBIDDEN);
      }

      if (!storageAsset.getType().equals(asset.getType())) {
        LOG.fine(
            "Cannot change asset's type: existingType="
                + storageAsset.getType()
                + ", requestedType="
                + asset.getType());
        throw new WebApplicationException(FORBIDDEN);
      }

      boolean isRestrictedUser = isRestrictedUser();

      // The asset that will ultimately be stored (override/ignore some values for restricted users)
      storageAsset.setVersion(asset.getVersion());

      if (!isRestrictedUser) {
        storageAsset.setName(asset.getName());
        storageAsset.setParentId(asset.getParentId());
        storageAsset.setAccessPublicRead(asset.isAccessPublicRead());
        storageAsset.setAttributes(asset.getAttributes());
      }

      // For restricted users, merge existing and updated attributes depending on write permissions
      if (isRestrictedUser) {

        if (!assetStorageService.isUserAsset(getUserId(), assetId)) {
          throw new WebApplicationException(FORBIDDEN);
        }

        // Merge updated with existing attributes
        for (Attribute<?> updatedAttribute : asset.getAttributes().values()) {

          // Proper validation happens on merge(), here we only need the name to continue
          String updatedAttributeName = updatedAttribute.getName();

          // Check if attribute is present on the asset in storage
          Optional<Attribute<Object>> serverAttribute =
              storageAsset.getAttribute(updatedAttributeName);
          if (serverAttribute.isPresent()) {
            Attribute<?> existingAttribute = serverAttribute.get();

            // If the existing attribute is not writable by restricted client, ignore it
            if (!existingAttribute.getMetaValue(ACCESS_RESTRICTED_WRITE).orElse(false)) {
              LOG.fine(
                  "Existing attribute not writable by restricted client, ignoring update of: "
                      + updatedAttributeName);
              continue;
            }

            // Merge updated with existing meta items (modifying a copy)
            MetaMap updatedMetaItems = updatedAttribute.getMeta();
            // Ensure access meta is not modified
            updatedMetaItems.removeIf(
                mi -> {
                  if (mi.getName().equals(ACCESS_RESTRICTED_READ.getName())) {
                    return true;
                  }
                  if (mi.getName().equals(ACCESS_RESTRICTED_WRITE.getName())) {
                    return true;
                  }
                  if (mi.getName().equals(ACCESS_PUBLIC_READ.getName())) {
                    return true;
                  }
                  if (mi.getName().equals(ACCESS_PUBLIC_WRITE.getName())) {
                    return true;
                  }
                  return false;
                });

            MetaMap existingMetaItems = ValueUtil.clone(existingAttribute.getMeta());

            existingMetaItems.addOrReplace(updatedMetaItems);

            // Replace existing with updated attribute
            updatedAttribute.setMeta(existingMetaItems);
            storageAsset.getAttributes().addOrReplace(updatedAttribute);

          } else {

            // An attribute added by a restricted user must be readable by restricted users
            updatedAttribute.addOrReplaceMeta(new MetaItem<>(ACCESS_RESTRICTED_READ, true));

            // An attribute added by a restricted user must be writable by restricted users
            updatedAttribute.addOrReplaceMeta(new MetaItem<>(ACCESS_RESTRICTED_WRITE, true));

            // Add the new attribute
            storageAsset.getAttributes().addOrReplace(updatedAttribute);
          }
        }

        // Remove missing attributes
        storageAsset
            .getAttributes()
            .removeIf(
                existingAttribute ->
                    !asset.hasAttribute(existingAttribute.getName())
                        && existingAttribute.getMetaValue(ACCESS_RESTRICTED_WRITE).orElse(false));
      }

      //            // If attribute is type RULES_TEMPLATE_FILTER, enforce meta item RULE_STATE
      //            // TODO Only done for update(Asset) and not create(Asset) as we don't need that
      // right now
      //            // TODO Implement "Saved Filter/Searches" properly, allowing restricted users to
      // create rule state flags is not great
      //            resultAsset .getAttributes().stream().forEach(attribute -> {
      //                if (attribute.getType().map(attributeType -> attributeType ==
      // ValueType.RULES_TEMPLATE_FILTER).orElse(false)
      //                    && !attribute.hasMetaItem(MetaItemType.RULE_STATE)) {
      //                    attribute.addMeta(new MetaItem<>(MetaItemType.RULE_STATE, true));
      //                }
      //            });

      // Store the result
      return assetStorageService.merge(storageAsset, isRestrictedUser ? getUsername() : null);

    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, FORBIDDEN);
    } catch (ConstraintViolationException ex) {
      throw new ResteasyViolationExceptionImpl(
          ex.getConstraintViolations(), requestParams.headers.getAcceptableMediaTypes());
    } catch (OptimisticLockException opEx) {
      throw new WebApplicationException(
          "Refresh the asset from the server and try to update the changes again", opEx, CONFLICT);
    }
  }

  @Override
  public AttributeConfigurationDocument exportAttributeConfiguration(
      RequestParams requestParams, String assetId, List<String> attributeNames) {
    // Reuses the read access rules of #get, including the protected view for a restricted user
    return AttributeConfigurationUtil.export(get(requestParams, assetId), attributeNames);
  }

  @Override
  public AttributeConfigurationImportPreview previewAttributeConfigurationImport(
      RequestParams requestParams, String assetId, AttributeConfigurationImportRequest request) {

    Asset<?> storageAsset = assetStorageService.find(assetId, true);

    if (storageAsset == null) {
      LOG.fine("Asset not found: assetID=" + assetId);
      throw new WebApplicationException(NOT_FOUND);
    }

    throwIfNotRealmActiveAndAccessible(storageAsset.getRealm());

    if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), assetId)) {
      LOG.fine(
          "Forbidden access for restricted user: username="
              + getUsername()
              + ", assetID="
              + assetId);
      throw new WebApplicationException(FORBIDDEN);
    }

    if (request == null || request.getDraft() == null) {
      throw new WebApplicationException("An asset draft is required", BAD_REQUEST);
    }

    Asset<?> draft = request.getDraft();

    if (!Objects.equals(assetId, draft.getId())
        || !Objects.equals(storageAsset.getRealm(), draft.getRealm())
        || !Objects.equals(storageAsset.getType(), draft.getType())) {
      throw importFailure(
          AttributeConfigurationImportPreview.failed(
              AttributeConfigurationImportFailure.DRAFT_MISMATCH));
    }

    AttributeConfigurationImportPreview preview =
        AttributeConfigurationUtil.preview(draft, request.getDocument());

    if (preview.getFailure() != null) {
      throw importFailure(preview);
    }

    return preview;
  }

  protected WebApplicationException importFailure(AttributeConfigurationImportPreview preview) {
    Response.Status status =
        preview.getFailure() == AttributeConfigurationImportFailure.DRAFT_MISMATCH
            ? CONFLICT
            : BAD_REQUEST;

    return new WebApplicationException(
        Response.status(status).entity(preview).type(MediaType.APPLICATION_JSON_TYPE).build());
  }

  @Override
  public AttributeWriteResult writeAttributeValue(
      RequestParams requestParams, String assetId, String attributeName, Object value) {
    return writeAttributeValue(requestParams, assetId, attributeName, null, value);
  }

  @Override
  public AttributeWriteResult writeAttributeValue(
      RequestParams requestParams,
      String assetId,
      String attributeName,
      Long timestamp,
      Object value) {
    if (value instanceof NullNode) {
      value = null;
    }

    AttributeEvent event = new AttributeEvent(assetId, attributeName, value, timestamp);

    // Check authorisation
    if (!clientEventService.authorizeEventWrite(getRequestRealmName(), getAuthContext(), event)) {
      throw new ForbiddenException("Cannot write specified attribute: " + event);
    }

    // Process asynchronously but block for a little while waiting for the result
    AttributeWriteResult result = doAttributeWrite(event);

    if (result.getFailure() != null) {
      Response.Status status =
          switch (result.getFailure()) {
            case ASSET_NOT_FOUND, ATTRIBUTE_NOT_FOUND -> NOT_FOUND;
            case INVALID_VALUE -> NOT_ACCEPTABLE;
            case QUEUE_FULL -> TOO_MANY_REQUESTS;
            default -> BAD_REQUEST;
          };
      throw new WebApplicationException(
          Response.status(status).entity(result).type(MediaType.APPLICATION_JSON_TYPE).build());
    }

    return result;
  }

  @Override
  public AttributeWriteResult[] writeAttributeValues(
      RequestParams requestParams, AttributeState[] attributeStates) {
    return writeAttributeEvents(
        requestParams,
        Arrays.stream(attributeStates).map(AttributeEvent::new).toArray(AttributeEvent[]::new));
  }

  @Override
  public AttributeWriteResult[] writeAttributeEvents(
      RequestParams requestParams, AttributeEvent[] attributeEvents) {
    // Process asynchronously but block for a little while waiting for the result
    return Arrays.stream(attributeEvents)
        .map(
            event -> {
              if (!clientEventService.authorizeEventWrite(
                  getRequestRealmName(), getAuthContext(), event)) {
                return new AttributeWriteResult(
                    event.getRef(), AttributeWriteFailure.INSUFFICIENT_ACCESS);
              }
              return doAttributeWrite(event);
            })
        .toArray(AttributeWriteResult[]::new);
  }

  @Override
  public Asset<?> create(RequestParams requestParams, Asset<?> asset) {
    try {
      if (isRestrictedUser()) {
        throw new WebApplicationException(FORBIDDEN);
      }

      if (asset == null) {
        LOG.finest("No asset in request");
        throw new WebApplicationException(BAD_REQUEST);
      }

      // If there was no realm provided (create was called by regular user in manager UI), use the
      // auth realm
      if (asset.getRealm() == null || asset.getRealm().isEmpty()) {
        asset.setRealm(getAuthenticatedRealm().getName());
      } else if (!isRealmActiveAndAccessible(asset.getRealm())) {
        LOG.fine("Forbidden access for user '" + getUsername() + "', can't create: " + asset);
        throw new WebApplicationException(FORBIDDEN);
      }

      Asset<?> newAsset = ValueUtil.clone(asset);

      // Allow client to set identifier
      if (asset.getId() != null) {
        newAsset.setId(asset.getId());
      }

      return assetStorageService.merge(newAsset);

    } catch (ConstraintViolationException ex) {
      throw new ResteasyViolationExceptionImpl(
          ex.getConstraintViolations(), requestParams.headers.getAcceptableMediaTypes());
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  @Override
  public void delete(RequestParams requestParams, List<String> assetIds) {

    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("Deleting assets: " + assetIds);
    }

    try {
      if (assetIds == null || assetIds.isEmpty()) {
        throw new WebApplicationException(BAD_REQUEST);
      }

      if (isRestrictedUser()) {
        throw new WebApplicationException(FORBIDDEN);
      }

      List<Asset<?>> assets =
          assetStorageService.findAll(
              new AssetQuery()
                  .ids(assetIds.toArray(new String[0]))
                  .select(new AssetQuery.Select().excludeAttributes()));
      if (assets == null || assets.size() != assetIds.size()) {
        LOG.fine("Request to delete one or more invalid assets");
        throw new WebApplicationException(BAD_REQUEST);
      }

      if (assets.stream()
          .map(Asset::getRealm)
          .distinct()
          .anyMatch(asset -> !isRealmActiveAndAccessible(asset))) {
        LOG.fine(
            "One or more assets in an nonexistent, inactive or inaccessible realm: username="
                + getUsername());
        throw new WebApplicationException(FORBIDDEN);
      }

      if (!assetStorageService.delete(assetIds, false)) {
        throw new WebApplicationException(BAD_REQUEST);
      }
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  @Override
  public Asset<?>[] queryAssets(RequestParams requestParams, AssetQuery query) {
    if (query == null) {
      query = new AssetQuery();
    } else {
      // Don't allow deleted assets in query
      query.includeDeletePending(false);
    }

    if (!assetStorageService.authorizeAssetQuery(query, getAuthContext(), getRequestRealmName())) {
      throw new ForbiddenException("User not authorized to execute specified query");
    }

    List<Asset<?>> result = assetStorageService.findAll(query);

    // Compress response (the request attribute enables the interceptor)
    request.setAttribute(HttpHeaders.CONTENT_ENCODING, "gzip");

    return result.toArray(new Asset[0]);
  }

  @Override
  public AssetTree queryAssetTree(RequestParams requestParams, AssetQuery query) {
    if (query == null) {
      query = new AssetQuery();
    } else {
      // Don't allow deleted assets in query
      query.includeDeletePending(false);
    }

    if (!assetStorageService.authorizeAssetQuery(query, getAuthContext(), getRequestRealmName())) {
      throw new ForbiddenException("User not authorized to execute specified query");
    }

    AssetTree result = assetStorageService.queryAssetTree(query);

    // Compress response (the request attribute enables the interceptor)
    request.setAttribute(HttpHeaders.CONTENT_ENCODING, "gzip");

    return result;
  }

  @Override
  public Integer queryCount(RequestParams requestParams, AssetQuery query) {
    if (query == null) {
      query = new AssetQuery();
    }

    if (!assetStorageService.authorizeAssetQuery(query, getAuthContext(), getRequestRealmName())) {
      throw new ForbiddenException("User not authorized to execute specified query");
    }

    return assetStorageService.count(query);
  }

  protected AttributeWriteResult doAttributeWrite(AttributeEvent event) {
    AttributeWriteFailure failure = null;

    if (event.getTimestamp() <= 0) {
      event.setTimestamp(timerService.getCurrentTimeMillis());
    }

    try {
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine("Write attribute value request: " + event);
      }

      // Process synchronously - need to directly use the ATTRIBUTE_EVENT_QUEUE as the client
      // inbound queue
      // has multiple consumers and so doesn't support In/Out MEP
      event.setSource(AssetResource.class.getSimpleName());
      Object result =
          messageBrokerService
              .getFluentProducerTemplate()
              .withBody(event)
              .to(ATTRIBUTE_EVENT_PROCESSOR)
              .request();

      if (result instanceof AssetProcessingException processingException) {
        failure = processingException.getReason();
      }

    } catch (AssetProcessingException e) {
      failure = e.getReason();
    } catch (IllegalStateException ex) {
      failure = AttributeWriteFailure.UNKNOWN;
    }

    return new AttributeWriteResult(event.getRef(), failure);
  }

  @Override
  public void updateParent(RequestParams requestParams, String parentId, List<String> assetIds) {
    try {
      List<Asset<?>> validatedChildAssets = validateBatchParentMutation(assetIds, parentId, true);

      String targetParentId = parentId;
      LOG.fine(
          "Updating parent for assets: count="
              + validatedChildAssets.size()
              + ", newParentID="
              + targetParentId);

      for (Asset<?> asset : validatedChildAssets) {
        asset.setParentId(targetParentId);
        LOG.fine(
            "Updating asset parent: assetID=" + asset.getId() + ", newParentID=" + targetParentId);
        assetStorageService.merge(asset);
      }
    } catch (IllegalStateException | ConstraintViolationException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }

  private List<Asset<?>> validateBatchParentMutation(
      List<String> assetIds, String parentId, boolean validateParent) {
    if (isRestrictedUser()) {
      throw new WebApplicationException(FORBIDDEN);
    }

    List<String> normalizedAssetIds = normalizeBatchParentMutationAssetIds(assetIds);

    if (validateParent) {
      if (TextUtil.isNullOrEmpty(parentId)) {
        throw new WebApplicationException(BAD_REQUEST);
      }

      if (normalizedAssetIds.contains(parentId)) {
        throw new WebApplicationException(BAD_REQUEST);
      }
    }

    List<Asset<?>> childAssets =
        assetStorageService.findAll(
            new AssetQuery().ids(normalizedAssetIds.toArray(String[]::new)));

    if (childAssets.size() != normalizedAssetIds.size()) {
      LOG.fine("Request to update parent of one or more invalid assets");
      throw new WebApplicationException(BAD_REQUEST);
    }

    String childRealm = null;

    for (Asset<?> childAsset : childAssets) {
      String childAssetRealm = childAsset.getRealm();

      if (!isRealmActiveAndAccessible(childAssetRealm)) {
        LOG.fine(
            "Child asset in a nonexistent, inactive or inaccessible realm: username="
                + getUsername()
                + ", assetID="
                + childAsset.getId());
        throw new WebApplicationException(FORBIDDEN);
      }

      if (childRealm == null) {
        childRealm = childAssetRealm;
      } else if (validateParent && !childRealm.equals(childAssetRealm)) {
        LOG.fine(
            "Request to update parent for child assets in multiple realms: username="
                + getUsername());
        throw new WebApplicationException(BAD_REQUEST);
      }
    }
    Asset<?> parentAsset = null;

    if (validateParent) {
      parentAsset = assetStorageService.find(parentId, true);

      if (parentAsset == null) {
        LOG.fine("Parent asset not found: parentID=" + parentId);
        throw new WebApplicationException(BAD_REQUEST);
      }

      if (!isRealmActiveAndAccessible(parentAsset.getRealm())) {
        LOG.fine(
            "Parent asset in a nonexistent, inactive or inaccessible realm: username="
                + getUsername()
                + ", parentID="
                + parentId);
        throw new WebApplicationException(FORBIDDEN);
      }

      if (!childRealm.equals(parentAsset.getRealm())) {
        LOG.fine(
            "Parent asset realm does not match child asset realm: childRealm="
                + childRealm
                + ", parentRealm="
                + parentAsset.getRealm());
        throw new WebApplicationException(BAD_REQUEST);
      }
    }

    return childAssets;
  }

  private List<String> normalizeBatchParentMutationAssetIds(List<String> assetIds) {
    if (assetIds == null || assetIds.isEmpty()) {
      throw new WebApplicationException(BAD_REQUEST);
    }

    LinkedHashSet<String> normalizedAssetIds = new LinkedHashSet<>();

    for (String assetId : assetIds) {
      if (TextUtil.isNullOrEmpty(assetId)) {
        throw new WebApplicationException(BAD_REQUEST);
      }

      normalizedAssetIds.add(assetId);
    }

    return new ArrayList<>(normalizedAssetIds);
  }

  @Override
  public void updateNoneParent(RequestParams requestParams, List<String> assetIds) {
    try {
      List<Asset<?>> validatedChildAssets = validateBatchParentMutation(assetIds, null, false);

      LOG.fine(
          "Updating parent for assets: count="
              + validatedChildAssets.size()
              + ", newParentID=NONE");

      for (Asset<?> asset : validatedChildAssets) {
        asset.setParentId(null);
        LOG.fine("Updating asset parent: assetID=" + asset.getId() + ", newParentID=NONE");
        assetStorageService.merge(asset);
      }
    } catch (IllegalStateException | ConstraintViolationException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    }
  }
}
