/*
 * Copyright 2026, OpenRemote Inc.
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
package org.openremote.manager.notification;

import static jakarta.ws.rs.core.Response.Status.*;
import static org.openremote.model.notification.Notification.Source.CLIENT;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.http.RequestParams;
import org.openremote.model.notification.AbstractNotificationMessage;
import org.openremote.model.notification.LocalizedNotificationMessage;
import org.openremote.model.notification.Notification;
import org.openremote.model.notification.NotificationResource;
import org.openremote.model.notification.PushNotificationMessage;
import org.openremote.model.notification.SentNotification;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.security.User;
import org.openremote.model.util.ValueUtil;

public class NotificationResourceImpl extends ManagerWebResource implements NotificationResource {

  private static final Logger LOG = Logger.getLogger(NotificationResourceImpl.class.getName());

  /**
   * Restricted users can only read the notifications that involve them, so they may never delete
   * any.
   */
  protected static final String RESTRICTED_DELETE_DENIED =
      "Restricted users cannot delete notifications";

  protected final NotificationService notificationService;
  protected final MessageBrokerService messageBrokerService;
  protected final AssetStorageService assetStorageService;

  public NotificationResourceImpl(
      TimerService timerService,
      ManagerIdentityService identityService,
      NotificationService notificationService,
      MessageBrokerService messageBrokerService,
      AssetStorageService assetStorageService) {
    super(timerService, identityService);
    this.notificationService = notificationService;
    this.messageBrokerService = messageBrokerService;
    this.assetStorageService = assetStorageService;
  }

  @Override
  public SentNotification[] getNotifications(
      RequestParams requestParams,
      Long id,
      String type,
      Long from,
      Long to,
      String realmId,
      String userId,
      String assetId,
      Notification.Source source,
      SentNotification.SortField sort,
      Boolean descending,
      Integer offset,
      Integer limit) {
    AuthContext authContext = getAuthContext();
    realmId = resolveAndAuthoriseRealm(authContext, realmId);

    try {
      List<SentNotification> notifications =
          notificationService.getNotifications(
              id != null ? Collections.singletonList(id) : null,
              type != null ? Collections.singletonList(type) : null,
              from != null ? Instant.ofEpochMilli(from) : null,
              to != null ? Instant.ofEpochMilli(to) : null,
              realmId != null ? Collections.singletonList(realmId) : null,
              userId != null ? Collections.singletonList(userId) : null,
              assetId != null ? Collections.singletonList(assetId) : null,
              source,
              sort,
              descending != null && descending,
              offset,
              limit,
              authContext);
      sanitiseNotifications(notifications, authContext);
      return notifications.toArray(new SentNotification[0]);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException("Invalid criteria set", BAD_REQUEST);
    }
  }

  @Override
  public void removeNotifications(
      RequestParams requestParams,
      Long id,
      String type,
      Long from,
      Long to,
      String realmId,
      String userId,
      String assetId) {
    throwIfRestrictedUser(RESTRICTED_DELETE_DENIED);
    String realm = resolveAndAuthoriseRealm(getAuthContext(), realmId);

    if (id != null) {
      throwIfNotRealmActiveAndAccessible(getNotification(id).getRealm());
    }
    if (userId != null) {
      User user = identityService.getIdentityProvider().getUser(userId);
      if (user == null) {
        throw new WebApplicationException("User not found", NOT_FOUND);
      }
      throwIfNotRealmActiveAndAccessible(user.getRealm());
    }
    if (assetId != null) {
      Asset<?> asset = assetStorageService.find(assetId, false);
      if (asset == null) {
        throw new WebApplicationException("Asset not found", NOT_FOUND);
      }
      throwIfNotRealmActiveAndAccessible(asset.getRealm());
    }

    try {
      notificationService.removeNotifications(
          id,
          type,
          from != null ? Instant.ofEpochMilli(from) : null,
          to != null ? Instant.ofEpochMilli(to) : null,
          realm,
          userId,
          assetId);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException("Invalid criteria set", BAD_REQUEST);
    }
  }

  @Override
  public void removeNotification(RequestParams requestParams, Long notificationId) {
    if (notificationId == null) {
      throw new WebApplicationException("Missing notification ID", BAD_REQUEST);
    }

    throwIfRestrictedUser(RESTRICTED_DELETE_DENIED);
    throwIfNotRealmActiveAndAccessible(getNotification(notificationId).getRealm());

    notificationService.removeNotification(notificationId);
  }

  protected SentNotification getNotification(Long notificationId) {
    SentNotification sentNotification = notificationService.getSentNotification(notificationId);
    if (sentNotification == null) {
      throw new WebApplicationException("Notification not found", NOT_FOUND);
    }
    return sentNotification;
  }

  @Override
  public void sendNotification(RequestParams requestParams, Notification notification) {

    Map<String, Object> headers = new HashMap<>();
    headers.put(Notification.HEADER_SOURCE, CLIENT);

    if (isAuthenticated()) {
      headers.put(Constants.AUTH_CONTEXT, getAuthContext());
    }

    boolean success =
        messageBrokerService
            .getFluentProducerTemplate()
            .withBody(notification)
            .withHeaders(headers)
            .to(NotificationService.NOTIFICATION_QUEUE)
            .request(Boolean.class);

    if (!success) {
      throw new WebApplicationException(BAD_REQUEST);
    }
  }

  @Override
  public void notificationDelivered(
      RequestParams requestParams, String targetId, Long notificationId) {
    if (notificationId == null) {
      throw new WebApplicationException("Missing notification ID", BAD_REQUEST);
    }

    SentNotification sentNotification = notificationService.getSentNotification(notificationId);
    verifyAccess(sentNotification, targetId);
    notificationService.setNotificationDelivered(notificationId);
  }

  @Override
  public void notificationAcknowledged(
      RequestParams requestParams, String targetId, Long notificationId, JsonNode acknowledgement) {
    if (notificationId == null) {
      throw new WebApplicationException("Missing notification ID", BAD_REQUEST);
    }

    SentNotification sentNotification = notificationService.getSentNotification(notificationId);
    verifyAccess(sentNotification, targetId);
    notificationService.setNotificationAcknowledged(
        notificationId,
        acknowledgement == null ? null : ValueUtil.asJSON(acknowledgement).orElse(null));
  }

  protected void verifyAccess(SentNotification sentNotification, String targetId) {
    if (sentNotification == null) {
      LOG.fine("DENIED: Notification not found");
      throw new WebApplicationException(NOT_FOUND);
    }

    if (sentNotification.getTargetId() == null
        || !sentNotification.getTargetId().equals(targetId)) {
      LOG.fine("DENIED: Notification target ID doesn't match supplied target ID");
      throw new WebApplicationException(NOT_FOUND);
    }

    if (isSuperUser()) {
      LOG.finest("ALLOWED: Request from super user so allowing");
      return;
    }

    // Anonymous requests can only be actioned against public assets
    if (!isAuthenticated()) {
      if (sentNotification.getTarget() != Notification.TargetType.ASSET) {
        LOG.fine("DENIED: Anonymous request to update a notification not sent to a public asset");
        throw new WebApplicationException(
            "Anonymous request can only update public assets", FORBIDDEN);
      }

      // Check asset is public read and not linked to any users
      Asset<?> asset =
          assetStorageService.find(sentNotification.getTargetId(), false, AssetQuery.Access.PUBLIC);
      if (asset == null) {
        LOG.fine(
            "DENIED: Anonymous request to update a notification sent to an asset that doesn't exist or isn't public");
        throw new WebApplicationException(
            "Anonymous request can only update public assets not linked to a user", FORBIDDEN);
      }

      // Disabled until console permissions finalized
      //            if (assetStorageService.isUserAsset(asset.getId())) {
      //                LOG.fine("DENIED: Anonymous request to update a notification sent to an
      // asset that is linked to one or more users");
      //                throw new WebApplicationException("Anonymous request can only update public
      // assets not linked to a user", FORBIDDEN);
      //            }
    } else {
      // Regular users can only update notifications sent to them or assets in their realm
      // Restricted users can only update notifications sent to them or assets linked to them
      boolean isRestrictedUser = isRestrictedUser();
      switch (sentNotification.getTarget()) {
        case REALM:
          // What does it mean when a notification has been sent to a realm - who can acknowledge
          // them?
          if (isRestrictedUser) {
            LOG.fine("DENIED: Restricted user request to update a notification sent to a realm");
            throw new WebApplicationException(
                "Restricted users cannot update a realm notification", FORBIDDEN);
          }
          break;
        case USER:
          if (!sentNotification.getTargetId().equals(getUserId())) {
            LOG.fine("DENIED: User request to update a notification sent to a different user");
            throw new WebApplicationException(
                "Regular and restricted users can only update user notifications sent to themselves",
                FORBIDDEN);
          }
          break;
        case ASSET:
          Asset<?> asset = assetStorageService.find(sentNotification.getTargetId(), false);
          if (asset == null) {
            LOG.fine(
                "DENIED: User request to update a notification sent to an asset that doesn't exist");
            throw new WebApplicationException("Asset not found", NOT_FOUND);
          }
          if (!asset.getRealm().equals(getAuthenticatedRealmName())) {
            LOG.fine(
                "DENIED: User request to update a notification sent to an asset that is in another realm");
            throw new WebApplicationException("Asset not in users realm", FORBIDDEN);
          }
          if (isRestrictedUser && !assetStorageService.isUserAsset(getUserId(), asset.getId())) {
            LOG.fine(
                "DENIED: Restricted user request to update a notification sent to an asset that isn't linked to themselves");
            throw new WebApplicationException("Asset not linked to restricted user", FORBIDDEN);
          }
          break;
      }
    }
  }

  @Override
  public long getNotificationsCount(
      RequestParams requestParams,
      String type,
      Long from,
      Long to,
      String realmId,
      String userId,
      String assetId,
      Notification.Source source) {
    AuthContext authContext = getAuthContext();
    realmId = resolveAndAuthoriseRealm(authContext, realmId);

    try {
      return notificationService.getNotificationsCount(
          type != null ? Collections.singletonList(type) : null,
          from != null ? Instant.ofEpochMilli(from) : null,
          to != null ? Instant.ofEpochMilli(to) : null,
          realmId != null ? Collections.singletonList(realmId) : null,
          userId != null ? Collections.singletonList(userId) : null,
          assetId != null ? Collections.singletonList(assetId) : null,
          source,
          authContext);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException("Invalid criteria set", BAD_REQUEST);
    }
  }

  /**
   * Resolves the realm a request applies to and verifies the caller may access it. Superusers may
   * target any realm (or all realms when {@code realmId} is null); other callers are confined to
   * their own realm and may not target a realm they cannot access (which would otherwise let them
   * read or delete another realm's notifications by passing its ID).
   */
  protected String resolveAndAuthoriseRealm(AuthContext authContext, String realmId) {
    if (authContext == null || authContext.isSuperUser()) {
      return realmId;
    }
    if (realmId == null) {
      return authContext.getAuthenticatedRealmName();
    }
    throwIfNotRealmActiveAndAccessible(realmId);
    return realmId;
  }

  /**
   * Strips data the caller isn't allowed to see: user IDs (CLIENT source, USER target) and CUSTOM
   * target IDs (which can contain email addresses) without read:users, asset IDs (ASSET_RULESET
   * source, ASSET target) without read:assets. REALM_RULESET source IDs are realm names and stay
   * visible; realm access is enforced separately. Delivery details stored on the message after
   * handler resolution (push device tokens) are stripped for every caller.
   */
  protected void sanitiseNotifications(
      List<SentNotification> notifications, AuthContext authContext) {
    boolean canReadUsers =
        authContext != null
            && (authContext.isSuperUser()
                || authContext.hasResourceRole(
                    Constants.READ_ADMIN_ROLE, Constants.KEYCLOAK_CLIENT_ID)
                || authContext.hasResourceRole(
                    Constants.READ_USERS_ROLE, Constants.KEYCLOAK_CLIENT_ID));
    boolean canReadAssets =
        authContext != null
            && (authContext.isSuperUser()
                || authContext.hasResourceRole(
                    Constants.READ_ADMIN_ROLE, Constants.KEYCLOAK_CLIENT_ID)
                || authContext.hasResourceRole(
                    Constants.READ_ASSETS_ROLE, Constants.KEYCLOAK_CLIENT_ID));

    notifications.forEach(n -> sanitiseNotification(n, canReadUsers, canReadAssets));
  }

  protected void sanitiseNotification(
      SentNotification n, boolean canReadUsers, boolean canReadAssets) {
    sanitiseMessage(n.getMessage());

    if (!canReadUsers) {
      if (n.getSource() == Notification.Source.CLIENT) {
        redactErrorId(n, n.getSourceId());
        n.setSourceId(null);
      }
      // Custom targets can carry raw email addresses
      if (n.getTarget() == Notification.TargetType.USER
          || n.getTarget() == Notification.TargetType.CUSTOM) {
        redactErrorId(n, n.getTargetId());
        n.setTargetId(null);
      }
    }
    if (!canReadAssets) {
      if (n.getSource() == Notification.Source.ASSET_RULESET) {
        redactErrorId(n, n.getSourceId());
        n.setSourceId(null);
      }
      if (n.getTarget() == Notification.TargetType.ASSET) {
        redactErrorId(n, n.getTargetId());
        n.setTargetId(null);
      }
    }
  }

  /**
   * Redacts a stripped id (asset/user id or custom email address) from the stored error message,
   * which handlers embed verbatim (e.g. "No recipients set for asset: {id}"). The error text is
   * kept so the row still reports as failed.
   */
  protected void redactErrorId(SentNotification n, String id) {
    if (n.getError() != null && id != null && !id.isEmpty()) {
      n.setError(n.getError().replace(id, "***"));
    }
  }

  /**
   * Removes delivery-only fields from a stored message: push messages carry the resolved FCM device
   * token in their target after sending, which must never leave the server. Recurses into localized
   * messages.
   */
  protected void sanitiseMessage(AbstractNotificationMessage message) {
    if (message instanceof PushNotificationMessage pushMessage) {
      pushMessage.setTarget(null);
      pushMessage.setTargetType(null);
    } else if (message instanceof LocalizedNotificationMessage localizedMessage
        && localizedMessage.getMessages() != null) {
      localizedMessage.getMessages().values().forEach(this::sanitiseMessage);
    }
  }
}
