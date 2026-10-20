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
package org.openremote.manager.map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openremote.container.web.WebResource;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.http.RequestParams;
import org.openremote.model.manager.MapConfig;
import org.openremote.model.map.MapResource;

public class MapResourceImpl extends WebResource implements MapResource {

  private static final Logger LOG = Logger.getLogger(MapResourceImpl.class.getName());
  protected final MapService mapService;
  protected final ManagerIdentityService identityService;

  public MapResourceImpl(MapService mapService, ManagerIdentityService identityService) {
    this.mapService = mapService;
    this.identityService = identityService;
  }

  @Override
  public ObjectNode saveSettings(RequestParams requestParams, MapConfig mapConfig) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can save map settings");
    }
    return mapService.saveMapConfig(mapConfig);
  }

  @Override
  public ObjectNode getSettings(RequestParams requestParams) {
    return mapService.getMapSettings(
        getRequestRealmName(), requestParams.getExternalSchemeHostAndPort());
  }

  @Override
  public Response getTile(int zoom, int column, int row) {
    byte[] tile = mapService.getMapTile(zoom, column, row);
    if (tile != null) {
      return Response.ok(tile)
          // Tell the browser the content is already GZIPPED
          .header(HttpHeaders.CONTENT_ENCODING, "gzip")
          .build();
    } else {
      throw new WebApplicationException(Response.Status.NO_CONTENT);
    }
  }

  @Override
  public ObjectNode uploadMap(RequestParams requestParams, String filename) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can upload images");
    }

    // Starting with Resteasy 6.2.15.Final, request.getContentLength() returns -1 if Content-Length
    // header is not set
    if (request.getContentLength() > mapService.customMapLimit) {
      throw new WebApplicationException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
    }

    try (InputStream stream = request.getInputStream()) {
      mapService.saveUploadedFile(filename, stream);
      return mapService.getMapSettings(
          getRequestRealmName(), requestParams.getExternalSchemeHostAndPort());
    } catch (IOException e) {
      LOG.log(Level.INFO, "Failed to save custom map tiles", e);
      throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
    } catch (IllegalArgumentException e) {
      LOG.log(Level.INFO, "Invalid filename specified", e);
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
  }

  @Override
  public ObjectNode getCustomMapInfo() {
    try {
      return mapService.getCustomMapInfo();
    } catch (IOException e) {
      throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public ObjectNode deleteMap(RequestParams requestParams) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can delete maps");
    }

    try {
      mapService.deleteUploadedFile();
      return mapService.getMapSettings(
          getRequestRealmName(), requestParams.getExternalSchemeHostAndPort());
    } catch (IOException e) {
      LOG.log(Level.INFO, "Failed to delete custom map tiles", e);
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
  }
}
