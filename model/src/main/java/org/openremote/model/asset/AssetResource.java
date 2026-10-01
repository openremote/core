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
package org.openremote.model.asset;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.openremote.model.http.OpenApiDescriptions.*;
import static org.openremote.model.http.OpenApiExamples.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import java.util.List;
import org.openremote.model.Constants;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.attribute.AttributeWriteResult;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.util.TsIgnore;
import org.openremote.model.value.MetaItemType;

/**
 * Asset<?> access rules:
 *
 * <ul>
 *   <li>The superuser (the admin in the master realm) may access all assets.
 *   <li>A regular user may have roles that allow read, write, or no access to any assets within its
 *       authenticated realm.
 *   <li>A <em>restricted</em> user is linked to a subset of assets within its authenticated realm
 *       and may have roles that allow read and/or write access to some asset details (see {@link
 *       UserAssetLink}).
 * </ul>
 *
 * <p>The only operations, always limited to linked assets, a restricted user is able to perform
 * are:
 *
 * <ul>
 *   <li>{@link #getCurrentUserAssets}
 *   <li>{@link #queryAssets}
 *   <li>{@link #get}
 *   <li>{@link #update}
 *   <li>{@link #writeAttributeValue}
 * </ul>
 */
@Tag(
    name = "Asset",
    description = "Query and manage assets, attributes, hierarchy, and user-to-asset links")
@Path("asset")
public interface AssetResource {

  @TsIgnore
  class Util {
    public static final String WRITE_ATTRIBUTE_HTTP_METHOD = "PUT";

    public static String getWriteAttributeUrl(AttributeRef attributeRef) {
      return "/asset/" + attributeRef.getId() + "/attribute/" + attributeRef.getName();
    }
  }

  // TODO This returns the same as #queryAssets, can it be removed?

  /**
   * Retrieve the linked assets of the currently authenticated user. If the request is made by the
   * superuser, an empty result is returned. If the request is made by a regular user, but the user
   * has no linked assets and is therefore not restricted, the assets without parent (root assets)
   * of the authenticated realm are returned. Note that the assets returned from this operation are
   * not completely loaded and the {@link Asset#path} and {@link Asset#attributes} are empty. Call
   * {@link #get} to retrieve all asset details.
   */
  @GET
  @Path("user/current")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ASSETS_ROLE})
  @Operation(
      operationId = "getCurrentUserAssets",
      summary = "Retrieve assets accessible to the current user",
      description =
          "Returns partial linked assets for a restricted user, root assets for an unrestricted realm user, and an empty array for a super user. Attributes and paths are omitted; use getAsset for full details.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  Asset<?>[] getCurrentUserAssets(@BeanParam RequestParams requestParams);

  /**
   * Retrieve links between assets and users.
   *
   * <p>The <code>realm</code> parameter is required, <code>userId</code> and <code>assetId</code>
   * can be null.
   *
   * <p>If the authenticated user is the superuser, assigned assets from any realm can be retrieved.
   * Otherwise the authenticated realm must be the same as the given realm. A 403 status is returned
   * if a regular user tries to get asset/user links in a realm different than its authenticated
   * realm, or if the user is restricted. A 404 status is returned if the realm doesn't exist. A 400
   * status code is returned if the given user is not in the given realm. An empty result is
   * returned if the user or asset doesn't exist.
   *
   * <p>TODO: We could return the assets of a restricted user
   */
  @GET
  @Path("user/link")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ASSETS_ROLE})
  @Operation(
      operationId = "getUserAssetLinks",
      summary = "Retrieve links between assets and users",
      description =
          "Returns user-asset links in the required realm, optionally filtered by userId and assetId. Missing users or assets produce an empty result; restricted users cannot call this operation.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  UserAssetLink[] getUserAssetLinks(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description = REALM + " Defaults to the authenticated realm.",
              example = EXAMPLE_REALM)
          @QueryParam("realm")
          String realm,
      @Parameter(description = "Only return links for this user.", example = EXAMPLE_USER_ID)
          @QueryParam("userId")
          String userId,
      @Parameter(description = "Only return links for this asset.", example = EXAMPLE_ASSET_ID)
          @QueryParam("assetId")
          String assetId);

  /**
   * Create all of the specified links; they must all be for the same realm and user.
   *
   * <p>If the authenticated user is the superuser, asset/user links in any realm can be created.
   * Otherwise assets must be in the same realm as the authenticated user. A 403 status is returned
   * if a regular user tries to create an asset/user link in a realm different than its
   * authenticated realm, or if the user is restricted. A 400 status is returned if the user or
   * asset or realm doesn't exist, or if the user is not in the realm.
   */
  @POST
  @Path("user/link")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "createUserAssetLinks",
      summary = "Create links between users and assets",
      description =
          "Creates all supplied links as one operation. Every item must identify the same realm and user, and every referenced realm, user, and asset must exist.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void createUserAssetLinks(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Links to create. Every item must have the same realm and user ID.")
          List<UserAssetLink> userAssets);

  /**
   * Delete a link between asset and user.
   *
   * <p>The <code>realm</code> is required.
   *
   * <p>If the authenticated user is the superuser, asset/user links from any realm can be deleted.
   * Otherwise assets must be in the same realm as the authenticated user. A 403 status is returned
   * if a regular user tries to delete an asset/user link in a realm different than its
   * authenticated realm, or if the user is restricted. A 400 status is returned if the user or
   * asset or realm doesn't exist.
   */
  @DELETE
  @Path("user/link/{realm}/{userId}/{assetId}")
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "deleteUserAssetLink",
      summary = "Delete a link between an asset and user",
      description =
          "Removes the link identified by realm, user, and asset. The caller must be allowed to administer asset links in that realm.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void deleteUserAssetLink(
      @BeanParam RequestParams requestParams,
      @Parameter(description = REALM, example = EXAMPLE_REALM) @PathParam("realm") String realm,
      @Parameter(description = USER_ID, example = EXAMPLE_USER_ID) @PathParam("userId")
          String userId,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId);

  /**
   * Delete all of the specified links; they must all be for the same realm and user.
   *
   * <p>If the authenticated user is the superuser, asset/user links from any realm can be deleted.
   * Otherwise assets must be in the same realm as the authenticated user. A 403 status is returned
   * if a regular user tries to delete an asset/user link in a realm different than its
   * authenticated realm, or if the user is restricted. A 400 status is returned if the user or
   * asset or realm doesn't exist.
   */
  @POST
  @Path("user/link/delete")
  @Consumes(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "deleteUserAssetLinks",
      summary = "Delete user asset links",
      description = "Removes all supplied links. Every item must identify the same realm and user.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void deleteUserAssetLinks(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Links to remove. Every item must have the same realm and user ID.")
          List<UserAssetLink> userAssets);

  @DELETE
  @Path("user/link/{realm}/{userId}")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "deleteAllUserAssetLinks",
      summary = "Delete all links for a user",
      description = "Removes every asset link belonging to the user in the requested realm.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void deleteAllUserAssetLinks(
      @BeanParam RequestParams requestParams,
      @Parameter(description = REALM, example = EXAMPLE_REALM) @PathParam("realm") String realm,
      @Parameter(description = USER_ID, example = EXAMPLE_USER_ID) @PathParam("userId")
          String userId);

  /**
   * Retrieve the asset. Regular users can only access assets in their authenticated realm, the
   * superuser can access assets in other (all) realms. A 403 status is returned if a regular user
   * tries to access an asset in a realm different than its authenticated realm, or if the user is
   * restricted and the asset is not linked to the user. All asset details (path, attributes) will
   * be populated, the asset is loaded completely.
   */
  @GET
  @Path("{assetId}")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ASSETS_ROLE})
  @Operation(
      operationId = "getAsset",
      summary = "Retrieve an asset",
      description =
          "Returns a fully loaded asset including its path and attributes. Access is constrained to the caller's realm and, for restricted users, linked assets.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  Asset<?> get(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId);

  /** Same as {@link #get} but only returns a partially loaded asset (no attributes or path) */
  @GET
  @Path("partial/{assetId}")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ASSETS_ROLE})
  @Operation(
      operationId = "getPartialAsset",
      summary = "Retrieve a partially loaded asset",
      description =
          "Returns the asset identity and properties without loading attributes or path data. The same access rules as getAsset apply.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  Asset<?> getPartial(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId);

  /**
   * Updates the asset. Regular users can only update assets in their authenticated realm, the
   * superuser can update assets in other (all) realms. A 403 status is returned if a regular user
   * tries to update an asset in a realm different than its authenticated realm, or if the original
   * or target realm is not accessible. A 403 status is returned if the user is restricted and the
   * asset is not linked to the user. A 400 status is returned if the asset's parent doesn't exist.
   * A 400 status is returned if a restricted user attempts to write private meta items of any
   * attributes. If a restricted user tries to write asset properties or dynamic attributes or meta
   * items of dynamic attributes which are not writable by a restricted user, such data is ignored.
   * For more details on limitations of restricted users, see {@link UserAssetLink}.
   */
  @PUT
  @Path("{assetId}")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "updateAsset",
      summary = "Update an asset",
      description =
          "Replaces mutable asset data while preserving the path identifier. Realm, parent, restricted-user, and private-metadata rules are validated; disallowed restricted-user fields may be ignored.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  @OpenApiResponses.Conflict
  Asset<?> update(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId,
      @RequestBody(
              required = true,
              description =
                  "Complete asset representation to store. The body ID, when present, must match the path ID; type and realm cannot be changed.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      examples =
                          @ExampleObject(
                              name = "Update an asset",
                              summary = "Rename an asset and update its attribute values",
                              value = ASSET_UPDATE)))
          Asset<?> asset);

  /**
   * Retrieve the attribute configuration of the asset as a user editable document, holding the type
   * and metadata of every attribute that has metadata. Repeat the attributeName query parameter to
   * export a subset of them. Access follows the same rules as {@link #get}.
   */
  @GET
  @Path("{assetId}/attributeConfiguration")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ASSETS_ROLE})
  @Operation(
      operationId = "exportAttributeConfiguration",
      summary = "Export the attribute configuration of an asset",
      description =
          "Returns a versioned document holding the type and metadata of each attribute that has metadata, limited to the requested attribute names when any are given. Attributes without metadata are never included.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  AttributeConfigurationDocument exportAttributeConfiguration(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId,
      @Parameter(
              description =
                  "Attribute names to export. Repeat the query parameter for multiple attributes, omit it to export all attributes that have metadata.",
              style = ParameterStyle.FORM,
              explode = Explode.TRUE)
          @QueryParam("attributeName")
          List<String> attributeNames);

  /**
   * Validate an attribute configuration document against an asset draft and report what importing
   * it would do, without storing anything. The returned patched attributes are for the client to
   * apply to the draft once the user confirms, so the same access as {@link #update} is required. A
   * 400 status is returned if no draft is given or the document cannot be imported, and a 409
   * status if the draft no longer identifies the persisted asset. A rejected document is answered
   * with the preview, whose failure names the reason.
   */
  @POST
  @Path("{assetId}/attributeConfiguration/import")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "previewAttributeConfigurationImport",
      summary = "Preview an attribute configuration import",
      description =
          "Validates the document, matches its attributes against the draft by name and type, and returns the compatibility report together with the draft attributes holding the imported metadata. Nothing is stored; the client applies the patch after the user confirms.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @ApiResponse(
      responseCode = "400",
      description =
          "The draft is missing, or the document cannot be imported; a rejected document is answered with the preview, whose failure names the reason",
      content = {
        @Content(
            mediaType = APPLICATION_JSON,
            schema = @Schema(implementation = AttributeConfigurationImportPreview.class)),
        @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class))
      })
  @OpenApiResponses.NotFound
  @ApiResponse(
      responseCode = "409",
      description =
          "The draft no longer identifies the persisted asset; the preview failure is DRAFT_MISMATCH",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeConfigurationImportPreview.class)))
  AttributeConfigurationImportPreview previewAttributeConfigurationImport(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId,
      @RequestBody(
              required = true,
              description =
                  "The asset draft being edited and the contents of the selected configuration file.")
          @NotNull AttributeConfigurationImportRequest request);

  /**
   * Updates an attribute of an asset. Regular users can only update assets in their authenticated
   * realm, the superuser can update assets in other (all) realms. A 403 status is returned if a
   * regular user tries to update an asset in a realm different than its authenticated realm, or if
   * the user is restricted and the asset to update is not in the set of linked assets of the
   * restricted user.
   *
   * <p>If the asset or attribute doesn't exist then a 404 status is returned.
   *
   * <p>If an attribute is marked as {@link MetaItemType#ACCESS_PUBLIC_WRITE} then the attribute can
   * be written publicly
   *
   * <p>This operation is ultimately asynchronous, any call will return before the actual attribute
   * value is changed in any storage or downstream processors. Thus any constraint violation or
   * processing error will not be returned from this method, query the system later to determine the
   * actual state and outcome of the write operation. The version of the asset entity will not be
   * incremented by this operation, thus concurrent updates can overwrite data undetected ("last
   * commit wins").
   */
  @PUT
  @Path("{assetId}/attribute/{attributeName}")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "writeAttributeValue",
      summary = "Write a value to one asset attribute",
      description =
          "Submits an asynchronous attribute write using the current server time. The result reports whether the event was accepted, not whether downstream processing ultimately succeeded. Anonymous writes require public-write metadata.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Forbidden
  @OpenApiResponses.NotFound
  @ApiResponse(
      responseCode = "400",
      description = "The attribute write failed",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  @ApiResponse(
      responseCode = "406",
      description = "The supplied value is not valid for the attribute",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  @ApiResponse(
      responseCode = "429",
      description = "The attribute event queue is full; retry the write later",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  AttributeWriteResult writeAttributeValue(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId,
      @Parameter(description = ATTRIBUTE_NAME, example = EXAMPLE_ATTRIBUTE_NAME)
          @PathParam("attributeName")
          String attributeName,
      @RequestBody(
              required = true,
              description =
                  "Any JSON value accepted by the attribute's value descriptor. JSON null clears the value.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(nullable = true),
                      examples =
                          @ExampleObject(
                              name = "Numeric value",
                              summary = "Write a number attribute",
                              value = ATTRIBUTE_VALUE)))
          Object value);

  @PUT
  // TODO: Using {timestamp:(\\d+)?} does not correctly tokenize when using the assetResource proxy
  // client in Groovy tests.
  @Path("{assetId}/attribute/{attributeName}/{timestamp}")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "writeAttributeValueWithTimestamp",
      summary = "Write a timestamped value to one asset attribute",
      description =
          "Submits an asynchronous attribute write using the supplied Unix timestamp in milliseconds. The result reports acceptance only; anonymous writes require public-write metadata.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Forbidden
  @OpenApiResponses.NotFound
  @ApiResponse(
      responseCode = "400",
      description = "The attribute write failed",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  @ApiResponse(
      responseCode = "406",
      description = "The supplied value is not valid for the attribute",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  @ApiResponse(
      responseCode = "429",
      description = "The attribute event queue is full; retry the write later",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = AttributeWriteResult.class)))
  AttributeWriteResult writeAttributeValue(
      @BeanParam RequestParams requestParams,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @PathParam("assetId")
          String assetId,
      @Parameter(description = ATTRIBUTE_NAME, example = EXAMPLE_ATTRIBUTE_NAME)
          @PathParam("attributeName")
          String attributeName,
      @Parameter(description = TIMESTAMP, example = EXAMPLE_TIMESTAMP) @PathParam("timestamp")
          Long timestamp,
      @RequestBody(
              required = true,
              description =
                  "Any JSON value accepted by the attribute's value descriptor. JSON null clears the value.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(nullable = true),
                      examples =
                          @ExampleObject(
                              name = "Timestamped numeric value",
                              summary = "Write a number with the path timestamp",
                              value = ATTRIBUTE_VALUE)))
          Object value);

  @PUT
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Path("attributes")
  @Operation(
      operationId = "writeAttributeValues",
      summary = "Write values to multiple asset attributes",
      description =
          "Submits a batch of attribute states using current server time and returns one result per input item. Authorization and processing failures are encoded in each AttributeWriteResult rather than returned as an HTTP error.")
  @OpenApiResponses.Ok
  AttributeWriteResult[] writeAttributeValues(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description =
                  "Attribute references and values to write using the current server time.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      array = @ArraySchema(schema = @Schema(implementation = AttributeState.class)),
                      examples =
                          @ExampleObject(
                              name = "Write two attributes",
                              summary = "Update temperature and humidity on one asset",
                              value = ATTRIBUTE_STATES)))
          AttributeState[] attributeStates);

  @PUT
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Path("attributes/timestamp")
  @Operation(
      operationId = "writeAttributeEvents",
      summary = "Write timestamped values to multiple asset attributes",
      description =
          "Submits a batch of complete attribute events and returns one result per input item. Supplied timestamps are preserved; authorization and processing failures are encoded per result rather than returned as an HTTP error.")
  @OpenApiResponses.Ok
  AttributeWriteResult[] writeAttributeEvents(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Complete attribute events to write, including their timestamps.")
          AttributeEvent[] attributeEvents);

  /**
   * Creates an asset. The identifier value of the asset can be provided, it should be a globally
   * unique string value, and must be 22 characters long. If no identifier value is provided, a
   * unique value will be generated by the system upon insert. Regular users can only create assets
   * in their authenticated realm, the superuser can create assets in other (all) realms. A 403
   * status is returned if a regular user tries to create an asset in a realm different than its
   * authenticated realm, or if the user is restricted. A 400 status is returned if the asset's
   * parent or realm doesn't exist or if an ID is provided and an asset with this ID already exists.
   */
  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "createAsset",
      summary = "Create an asset",
      description =
          "Creates an asset in an accessible realm and returns the persisted representation. The server generates an ID when absent; supplied IDs must be globally unique and 22 characters long.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  Asset<?> create(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description =
                  "Asset to create. Omit id to let the server generate one; realm defaults to the authenticated realm.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      examples =
                          @ExampleObject(
                              name = "Create a Thing asset",
                              summary = "Create a sensor with temperature and humidity attributes",
                              value = ASSET_CREATE)))
          Asset<?> asset);

  /**
   * Deletes an asset. Regular users can only delete assets in their authenticated realm, the
   * superuser can delete assets in other (all) realms. A 403 status is returned if a regular user
   * tries to delete an asset in a realm different than its authenticated realm, or if the user is
   * restricted.
   */
  @DELETE
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "deleteAsset",
      summary = "Delete assets",
      description =
          "Permanently deletes every asset identified by repeated assetId query parameters, subject to realm and restricted-user access rules.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void delete(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description = "Asset IDs to delete. Repeat the query parameter for multiple assets.",
              example = EXAMPLE_ASSET_ID,
              required = true,
              style = ParameterStyle.FORM,
              explode = Explode.TRUE)
          @QueryParam("assetId")
          List<String> assetIds);

  /**
   * Retrieve assets using an {@link AssetQuery}.
   *
   * <p>If the authenticated user is the superuser then assets referenced in the query or returned
   * by the query can be in any realm. Otherwise assets must be in the same realm as the
   * authenticated user, and for a restricted user, the assets must be linked to the user. An empty
   * result is returned if the user does not have access to the assets. What is populated on the
   * returned assets is determined by the {@link AssetQuery#select} value.
   */
  @POST
  @Path("query")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "queryAssets",
      summary = "Retrieve assets using a query",
      description =
          "Executes an AssetQuery after constraining realm and linked-asset access. Anonymous callers receive public-readable assets only; the query select clause controls which fields are populated.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Forbidden
  Asset<?>[] queryAssets(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              description =
                  "Optional asset query. An omitted or empty query selects all assets visible to the caller.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(implementation = AssetQuery.class),
                      examples =
                          @ExampleObject(
                              name = "Find sensors",
                              summary =
                                  "Find Thing assets with sensor in their name and select two attributes",
                              value = ASSET_QUERY)))
          AssetQuery query);

  /**
   * Retrieve assets using an {@link AssetQuery}, returning an optimized structure for tree display.
   * This wraps the existing {@link #queryAssets} endpoint, but returns an optimized structure for
   * tree display.
   *
   * <p>If the authenticated user is the superuser then assets referenced in the query or returned
   * by the query can be in any realm. Otherwise assets must be in the same realm as the
   * authenticated user, and for a restricted user, the assets must be linked to the user. An empty
   * result is returned if the user does not have access to the assets. What is populated on the
   * returned assets is determined by the {@link AssetQuery#select} value.
   */
  @POST
  @Path("tree")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "queryAssetTree",
      summary = "Retrieve an optimized asset tree using a query",
      description =
          "Applies the same authorization and selection rules as queryAssets, then returns a hierarchy optimized for tree display rather than a flat asset array.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Forbidden
  AssetTree queryAssetTree(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              description =
                  "Optional asset query controlling the roots, filters, and fields included in the returned tree.")
          AssetQuery query);

  /** Retrieve the amount of assets using an {@link AssetQuery}. */
  @POST
  @Path("count")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "queryCount",
      summary = "Count assets using a query (can also be used to count assets pending deletion)",
      description =
          "Returns only the number of assets matching an AssetQuery after applying the caller's realm, public, and linked-asset access constraints.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Forbidden
  Integer queryCount(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              description =
                  "Optional asset query to count. An omitted or empty query counts all assets visible to the caller.")
          AssetQuery query);

  /** Change parent for a set of asset */
  @PUT
  @Path("{parentAssetId}/child")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "updateAssetParent",
      summary = "Move assets below a new parent",
      description =
          "Sets parentAssetId as the parent of every asset listed in repeated assetIds query parameters. Parent and children must satisfy realm, access, and hierarchy constraints.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void updateParent(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Asset that will become the new parent.", example = EXAMPLE_ASSET_ID)
          @PathParam("parentAssetId")
          @NotNull(message = "Parent reference required") String parentId,
      @Parameter(
              description =
                  "Child asset IDs to move. Repeat the query parameter for multiple assets.",
              example = EXAMPLE_ASSET_ID,
              required = true,
              style = ParameterStyle.FORM,
              explode = Explode.TRUE)
          @QueryParam("assetIds")
          @Size(min = 1, message = "At least one child to update parent reference") List<String> assetIds);

  /** Remove parent reference from each asset referenced in the query parameter assetIds */
  @DELETE
  @Path("/parent")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ASSETS_ROLE})
  @Operation(
      operationId = "deleteAssetsParent",
      summary = "Move assets to the realm root",
      description =
          "Clears the parent reference of every asset listed in repeated assetIds query parameters.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void updateNoneParent(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description =
                  "Asset IDs to move to the realm root. Repeat the query parameter for multiple assets.",
              example = EXAMPLE_ASSET_ID,
              required = true,
              style = ParameterStyle.FORM,
              explode = Explode.TRUE)
          @QueryParam("assetIds")
          @Size(min = 1, message = "At least one child to update parent reference") List<String> assetIds);
}
