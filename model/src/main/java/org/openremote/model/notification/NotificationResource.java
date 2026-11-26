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
package org.openremote.model.notification;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.openremote.model.http.OpenApiDescriptions.*;
import static org.openremote.model.http.OpenApiExamples.PUSH_NOTIFICATION;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import org.openremote.model.Constants;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;

@Tag(
    name = "Notification",
    description =
        "Send notifications and query, delete, deliver, or acknowledge their delivery records")
@Path("notification")
public interface NotificationResource {

  /**
   * Gets sent notifications matching the supplied criteria; optionally limiting the scope by {@link
   * AbstractNotificationMessage} type, sent datetime, realm, target user/asset, {@link
   * Notification.Source} and/or pagination (offset/limit). Restricted users only ever see
   * notifications they sent or that target them or their realm.
   */
  @GET
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_NOTIFICATIONS_ROLE})
  @Operation(
      operationId = "getNotifications",
      summary = "Retrieve sent notifications matching filter criteria",
      description =
          "Returns sent-notification records filtered by identity, message type, time, realm, target, source, sorting, and pagination. Results are realm-scoped and sensitive target/source identifiers are redacted when the caller lacks user or asset read permission.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  SentNotification[] getNotifications(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Return only this sent-notification record.", example = "42")
          @QueryParam("id")
          Long id,
      @Parameter(description = "Notification message type discriminator.", example = "push")
          @QueryParam("type")
          String type,
      @Parameter(
              description = "Inclusive lower bound for the sent timestamp, in Unix milliseconds.",
              example = EXAMPLE_TIMESTAMP)
          @QueryParam("from")
          Long fromTimestamp,
      @Parameter(
              description = "Exclusive upper bound for the sent timestamp, in Unix milliseconds.",
              example = "1767312000000")
          @QueryParam("to")
          Long toTimestamp,
      @Parameter(
              description = "Return notifications associated with this realm.",
              example = EXAMPLE_REALM)
          @QueryParam("realmId")
          String realmId,
      @Parameter(description = USER_ID, example = EXAMPLE_USER_ID) @QueryParam("userId")
          String userId,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @QueryParam("assetId")
          String assetId,
      @Parameter(description = "Origin that created the notification.") @QueryParam("source")
          Notification.Source source,
      @Parameter(description = "Field used to order the result set.") @QueryParam("sort")
          SentNotification.SortField sort,
      @Parameter(description = "Reverse the selected sort order.", example = "true")
          @QueryParam("descending")
          Boolean descending,
      @Parameter(description = "Number of matching records to skip.", example = "0")
          @QueryParam("offset")
          Integer offset,
      @Parameter(description = "Maximum number of records to return.", example = "100")
          @QueryParam("limit")
          Integer limit);

  /**
   * Removes all sent notifications that have been sent to the specified targets; optionally
   * limiting the scope of the request by {@link AbstractNotificationMessage} type and/or sent
   * datetime. If type(s) or timestamp are not set then it is assumed no type or time constraint is
   * required. Can also provide a list of notification IDs to delete specific notifications.
   */
  @DELETE
  @RolesAllowed({Constants.WRITE_NOTIFICATIONS_ROLE})
  @Operation(
      operationId = "removeNotifications",
      summary = "Delete sent notifications matching filter criteria",
      description =
          "Permanently removes all records matching the optional ID, type, time, realm, user, and asset filters. This bulk operation is restricted to a super user.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void removeNotifications(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Delete only this sent-notification record.", example = "42")
          @QueryParam("id")
          Long id,
      @Parameter(
              description = "Delete records with this notification message type.",
              example = "push")
          @QueryParam("type")
          String type,
      @Parameter(
              description = "Inclusive lower bound for the sent timestamp, in Unix milliseconds.",
              example = EXAMPLE_TIMESTAMP)
          @QueryParam("from")
          Long fromTimestamp,
      @Parameter(
              description = "Exclusive upper bound for the sent timestamp, in Unix milliseconds.",
              example = "1767312000000")
          @QueryParam("to")
          Long toTimestamp,
      @Parameter(
              description = "Delete records associated with this realm.",
              example = EXAMPLE_REALM)
          @QueryParam("realmId")
          String realmId,
      @Parameter(description = USER_ID, example = EXAMPLE_USER_ID) @QueryParam("userId")
          String userId,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @QueryParam("assetId")
          String assetId);

  /** Remove a specific sent notification by ID. */
  @DELETE
  @Path("{notificationId}")
  @RolesAllowed({Constants.WRITE_NOTIFICATIONS_ROLE})
  @Operation(
      operationId = "removeNotification",
      summary = "Delete a sent notification",
      description =
          "Permanently removes one sent-notification record. This operation is restricted to a super user and is idempotent when the ID does not exist.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void removeNotification(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Numeric sent-notification identifier.", example = "42")
          @PathParam("notificationId")
          Long notificationId);

  /**
   * Send a notification to one or more targets; the authorization of the requesting user will
   * determine whether or not the targets can be contacted; if one or more targets are not
   * accessible due to permissions then the entire request will fail with a 403 response.
   */
  @POST
  @Path("alert")
  @Consumes(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_NOTIFICATIONS_ROLE})
  @Operation(
      operationId = "sendNotification",
      summary = "Send a notification to one or more targets",
      description =
          "Queues a notification for delivery. Authorization is checked for every target; if any target is inaccessible, the complete request fails rather than sending a partial set.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  void sendNotification(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Message, source, and one or more delivery targets to queue.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(implementation = Notification.class),
                      examples =
                          @ExampleObject(
                              name = "Push notification",
                              summary = "Send a high-priority asset alert to one user",
                              value = PUSH_NOTIFICATION)))
          Notification notification);

  /**
   * Allows a target to mark a notification as delivered.
   *
   * <p>The requesting user must have permission to acknowledge the specified notification otherwise
   * a 403 response is returned.
   */
  @PUT
  @Path("{notificationId}/delivered")
  @Operation(
      operationId = "notificationDelivered",
      summary = "Mark a notification as delivered",
      description =
          "Marks one target's notification as delivered after matching both notificationId and targetId. Anonymous callers may update notifications sent to a public asset; authenticated callers are constrained to their own user, realm, or accessible assets.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Forbidden
  @OpenApiResponses.NotFound
  void notificationDelivered(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description =
                  "User, asset, or realm target identifier whose delivery state is updated.",
              example = EXAMPLE_USER_ID,
              required = true)
          @QueryParam("targetId")
          String targetId,
      @Parameter(description = "Numeric sent-notification identifier.", example = "42")
          @PathParam("notificationId")
          Long notificationId);

  /**
   * Allows a target to acknowledge a notification with an optional acknowledgement value.
   *
   * <p>The requesting user must have permission to acknowledge the specified notification otherwise
   * a 403 response is returned.
   */
  @PUT
  @Path("{notificationId}/acknowledged")
  @Consumes(APPLICATION_JSON)
  @Operation(
      operationId = "notificationAcknowledged",
      summary = "Acknowledge a notification",
      description =
          "Marks one target's notification as acknowledged and stores the optional JSON acknowledgement value. Access rules are identical to notificationDelivered.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Forbidden
  @OpenApiResponses.NotFound
  void notificationAcknowledged(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description =
                  "User, asset, or realm target identifier whose acknowledgement state is updated.",
              example = EXAMPLE_USER_ID,
              required = true)
          @QueryParam("targetId")
          String targetId,
      @Parameter(description = "Numeric sent-notification identifier.", example = "42")
          @PathParam("notificationId")
          Long notificationId,
      @RequestBody(
              description = "Optional arbitrary JSON acknowledgement value.",
              content = @Content(schema = @Schema(nullable = true)))
          JsonNode acknowledgement);

  /**
   * Counts sent notifications matching the supplied criteria; uses the same scoping and access
   * rules as {@link #getNotifications}.
   */
  @GET
  @Path("count")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_NOTIFICATIONS_ROLE})
  @Operation(
      operationId = "getNotificationsCount",
      summary = "Count sent notifications matching filter criteria",
      description =
          "Returns the count for the same realm-scoped type, time, target, and source filters supported by getNotifications without loading notification records.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  long getNotificationsCount(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Notification message type discriminator.", example = "push")
          @QueryParam("type")
          String type,
      @Parameter(
              description = "Inclusive lower bound for the sent timestamp, in Unix milliseconds.",
              example = EXAMPLE_TIMESTAMP)
          @QueryParam("from")
          Long fromTimestamp,
      @Parameter(
              description = "Exclusive upper bound for the sent timestamp, in Unix milliseconds.",
              example = "1767312000000")
          @QueryParam("to")
          Long toTimestamp,
      @Parameter(
              description = "Count notifications associated with this realm.",
              example = EXAMPLE_REALM)
          @QueryParam("realmId")
          String realmId,
      @Parameter(description = USER_ID, example = EXAMPLE_USER_ID) @QueryParam("userId")
          String userId,
      @Parameter(description = ASSET_ID, example = EXAMPLE_ASSET_ID) @QueryParam("assetId")
          String assetId,
      @Parameter(description = "Origin that created the notification.") @QueryParam("source")
          Notification.Source source);
}
