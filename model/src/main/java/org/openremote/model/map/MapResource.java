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
package org.openremote.model.map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.openremote.model.Constants;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;
import org.openremote.model.manager.MapConfig;

@Tag(
    name = "Map",
    description =
        "Configure the Manager map and serve map styles, vector tiles, and custom MBTiles data")
@Path("map")
public interface MapResource {

  /** Saves the settings for maps */
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "saveSettings",
      summary = "Update map settings",
      description =
          "Stores the map configuration and returns the resulting public MapLibre settings. This operation is restricted to a super administrator.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  ObjectNode saveSettings(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Map provider, style, bounds, and custom-map settings to persist.")
          MapConfig mapConfig);

  /**
   * Returns style used to initialise MapLibre GL
   *
   * @return
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "getSettings",
      summary = "Retrieve the style used for MapLibre GL",
      description =
          "Returns realm-aware public MapLibre settings with externally reachable URLs derived from proxy headers.")
  @OpenApiResponses.Ok
  ObjectNode getSettings(@BeanParam RequestParams requestParams);

  /** Gets vector tile data for MapLibre GL */
  @GET
  @Produces("application/vnd.mapbox-vector-tile")
  @Path("tile/{zoom}/{column}/{row}")
  @Operation(
      operationId = "getTile",
      summary = "Retrieve vector tile data for MapLibre GL",
      description =
          "Returns a pre-compressed Mapbox vector tile for the requested zoom, column, and row coordinates.")
  @ApiResponse(
      responseCode = "200",
      description = "Gzip-compressed vector tile",
      headers =
          @Header(
              name = "Content-Encoding",
              description = "Indicates that the tile bytes are already compressed",
              schema = @Schema(type = "string", allowableValues = "gzip")),
      content =
          @Content(
              mediaType = "application/vnd.mapbox-vector-tile",
              schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "204", description = "No tile exists at the requested coordinates")
  @OpenApiResponses.BadRequest
  Response getTile(
      @Parameter(description = "Web Mercator zoom level.", example = "14") @PathParam("zoom")
          int zoom,
      @Parameter(description = "Web Mercator tile X coordinate.", example = "8392")
          @PathParam("column")
          int column,
      @Parameter(description = "Web Mercator tile Y coordinate.", example = "5469")
          @PathParam("row")
          int row);

  /** Saves mbtiles file */
  @POST
  @Path("upload")
  @Consumes(MediaType.APPLICATION_OCTET_STREAM)
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "uploadMap",
      summary = "Upload a custom MBTiles map",
      description =
          "Stores the raw request body as the realm's custom MBTiles database and returns the resulting public map settings. This operation is restricted to a super administrator.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.PayloadTooLarge
  @OpenApiResponses.ServerError
  @RequestBody(
      required = true,
      description = "Raw MBTiles SQLite database bytes.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_OCTET_STREAM,
              schema = @Schema(type = "string", format = "binary")))
  ObjectNode uploadMap(
      @BeanParam RequestParams requestParams,
      @Parameter(
              description = "Original MBTiles filename used for validation and storage.",
              example = "building.mbtiles",
              required = true)
          @QueryParam("filename")
          String filename);

  /** Retrieve if the map is custom and custom map limit */
  @GET
  @Path("getCustomMapInfo")
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Constants.READ_ADMIN_ROLE})
  @Operation(
      operationId = "getCustomMapInfo",
      summary = "Retrieve custom-map status and upload limit",
      description =
          "Reports whether custom MBTiles data is installed and the maximum accepted upload size.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.ServerError
  ObjectNode getCustomMapInfo();

  /** Removes mbtiles file */
  @DELETE
  @Path("deleteMap")
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_ADMIN_ROLE})
  @Operation(
      operationId = "deleteMap",
      summary = "Remove the custom MBTiles map",
      description =
          "Deletes the installed custom MBTiles database and returns the fallback public map settings. This operation is restricted to a super administrator.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  ObjectNode deleteMap(@BeanParam RequestParams requestParams);
}
