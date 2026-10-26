/*
 * Copyright 2022, OpenRemote Inc.
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
package org.openremote.model.manager;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import org.openremote.model.Constants;
import org.openremote.model.file.FileInfo;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;

@Tag(
    name = "Configuration",
    description =
        "Read public Manager UI configuration and administer its configuration and images")
@Path("configuration")
public interface ConfigurationResource {

  @PUT
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Path("manager")
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "updateConfiguration",
      summary = "Update manager configuration",
      description =
          "Replaces manager_config.json and returns the stored configuration. This administrative change affects Manager UI branding and behavior. This operation is restricted to a super administrator.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.ServerError
  ManagerAppConfig update(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description =
                  "Complete public Manager UI configuration to persist as manager_config.json.")
          ManagerAppConfig managerConfiguration);

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Path("manager/image")
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "imageUpload",
      summary = "Upload a Manager configuration image",
      description =
          "Validates and stores the supplied image FileInfo at the requested relative path,"
              + " replacing an existing image, then returns its Manager API path. This operation"
              + " is restricted to a super administrator.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.ServerError
  String imageUpload(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description = "Relative storage path and filename for the image.",
              example = "images/logo.svg",
              required = true)
          @QueryParam("path")
          String path,
      @RequestBody(
              required = true,
              description = "File metadata and Base64 or text contents to store.")
          FileInfo fileInfo);

  @GET
  @Produces(APPLICATION_JSON)
  @Path("manager")
  @Operation(
      operationId = "getManagerConfig",
      summary = "Retrieve the manager configuration JSON",
      description =
          "Returns the public Manager application configuration used by clients before and after authentication.")
  @OpenApiResponses.Ok
  ManagerAppConfig getManagerConfig();

  @GET
  @Path("manager/image/{filename: .+}")
  @Operation(
      operationId = "getManagerConfigImage",
      summary = "Retrieve a Manager configuration image",
      description =
          "Streams a configuration image using a fixed media type determined by its filename"
              + " extension. Only png, jpg, jpeg, ico, and svg extensions are served, ignoring case;"
              + " unsupported extensions return 404.")
  @ApiResponse(
      responseCode = "200",
      description = "The image file",
      content = {
        @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
        @Content(
            mediaType = "image/vnd.microsoft.icon",
            schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "image/svg+xml", schema = @Schema(type = "string", format = "binary"))
      })
  @OpenApiResponses.NotFound
  @OpenApiResponses.ServerError
  Object getManagerConfigImage(
      @Parameter(
              description = "Relative image path below the Manager configuration image directory.",
              example = "images/logo.svg")
          @PathParam("filename")
          String fileName);
}
