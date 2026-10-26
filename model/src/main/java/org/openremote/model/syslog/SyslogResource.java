/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.model.syslog;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.openremote.model.Constants;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;

@Tag(
    name = "Syslog",
    description = "Query and clear stored Manager logs and maintain syslog storage settings")
@Path("syslog")
@OpenApiResponses.Authenticated
public interface SyslogResource {

  @GET
  @Path("event")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_LOGS_ROLE})
  @Operation(
      operationId = "getEvents",
      summary = "Retrieve stored syslog events",
      description =
          "Returns a page of log events filtered by severity, Unix-millisecond time range, categories, and subcategories. RFC 8288 Link headers point to the next and last pages.")
  @ApiResponse(
      responseCode = "200",
      description = "The requested page of syslog events",
      headers =
          @Header(
              name = "Link",
              description = "RFC 8288 pagination links for the next and last pages",
              schema = @Schema(type = "string")),
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              array = @ArraySchema(schema = @Schema(implementation = SyslogEvent.class))))
  @OpenApiResponses.BadRequest
  @SuppressWarnings({"unusable-by-js"})
  Response getEvents(
      @BeanParam RequestParams requestParams,
      @Parameter(description = "Minimum log severity to return.", example = "INFO")
          @QueryParam("level")
          SyslogLevel level,
      @Parameter(description = "Maximum number of events per page.", example = "100")
          @QueryParam("per_page")
          Integer perPage,
      @Parameter(description = "One-based result page number.", example = "1") @QueryParam("page")
          Integer page,
      @Parameter(
              description = "Inclusive lower bound for event timestamps, in Unix milliseconds.",
              example = "1767225600000")
          @QueryParam("from")
          Long from,
      @Parameter(
              description = "Exclusive upper bound for event timestamps, in Unix milliseconds.",
              example = "1767312000000")
          @QueryParam("to")
          Long to,
      @Parameter(
              description =
                  "Log categories to include; repeat the query parameter for multiple values.",
              style = ParameterStyle.FORM,
              explode = Explode.TRUE)
          @QueryParam("category")
          List<SyslogCategory> categories,
      @Parameter(
              description =
                  "Log subcategories to include; repeat the query parameter for multiple values.",
              style = ParameterStyle.FORM,
              explode = Explode.TRUE,
              example = "org.openremote.manager.asset")
          @QueryParam("subCategory")
          List<String> subCategories);

  @DELETE
  @Path("event")
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "clearEvents",
      summary = "Clear stored syslog events",
      description =
          "Permanently removes all events from the configured syslog storage. This operation is restricted to a super administrator.")
  @OpenApiResponses.NoContent
  void clearEvents(@BeanParam RequestParams requestParams);

  @GET
  @Path("config")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ADMIN_ROLE})
  @Operation(
      operationId = "getConfig",
      summary = "Retrieve the syslog configuration",
      description =
          "Returns the current log-storage and retention configuration. This operation is restricted to a super administrator.")
  @OpenApiResponses.Ok
  SyslogConfig getConfig(@BeanParam RequestParams requestParams);

  @PUT
  @Path("config")
  @Consumes(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "updateConfig",
      summary = "Update the syslog configuration",
      description =
          "Validates and applies log-storage and retention settings. This operation is restricted to a super administrator.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.BadRequest
  void updateConfig(
      @BeanParam RequestParams requestParams,
      @Valid @RequestBody(
              required = true,
              description = "Log level, storage, and retention settings to apply.")
          SyslogConfig config);
}
