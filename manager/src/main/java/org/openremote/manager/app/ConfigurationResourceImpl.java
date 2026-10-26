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
package org.openremote.manager.app;

import static org.openremote.manager.app.ConfigurationService.MANAGER_CONFIG_IMAGE_URL_PREFIX;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Logger;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.WebService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.file.FileInfo;
import org.openremote.model.http.RequestParams;
import org.openremote.model.manager.ConfigurationResource;
import org.openremote.model.manager.ManagerAppConfig;

public class ConfigurationResourceImpl extends ManagerWebResource implements ConfigurationResource {

  protected ConfigurationService configurationService;
  private static final Logger LOG = Logger.getLogger(WebService.class.getName());

  public ConfigurationResourceImpl(
      TimerService timerService,
      ManagerIdentityService identityService,
      ConfigurationService configurationService) {
    super(timerService, identityService);
    this.configurationService = configurationService;
  }

  @Override
  public ManagerAppConfig update(
      RequestParams requestParams, ManagerAppConfig managerConfiguration) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can update the manager configuration");
    }

    try {
      this.configurationService.saveManagerConfig(managerConfiguration);
    } catch (Exception e) {
      LOG.warning("Couldn't store manager_config.json:" + e.getMessage());
      throw new InternalServerErrorException("Error updating manager_config.json");
    }
    return managerConfiguration;
  }

  @Override
  public String imageUpload(RequestParams requestParams, String path, FileInfo fileInfo) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can upload images");
    }

    try {
      String storedPath = configurationService.saveManagerConfigImage(path, fileInfo);
      return MANAGER_CONFIG_IMAGE_URL_PREFIX + storedPath;
    } catch (IllegalArgumentException e) {
      LOG.warning(() -> "Image upload rejected for path '" + path + "': " + e.getMessage());
      throw new BadRequestException(
          Response.status(Response.Status.BAD_REQUEST)
              .type(MediaType.TEXT_PLAIN_TYPE)
              .entity(e.getMessage())
              .build());
    } catch (Exception e) {
      LOG.warning("Save image failed '" + path + "': " + e.getMessage());
      throw new InternalServerErrorException("Error storing image");
    }
  }

  @Override
  public ManagerAppConfig getManagerConfig() {
    return configurationService.getManagerConfig();
  }

  @Override
  public Object getManagerConfigImage(String fileName) {
    try {
      var imageFile = configurationService.getManagerConfigImage(fileName);
      if (imageFile.isEmpty()) {
        return Response.status(Response.Status.NOT_FOUND).entity("Image not Found").build();
      }
      String mimeType = ImageUploadValidator.getMediaType(imageFile.get().getName());
      return Response.ok(imageFile.get(), mimeType)
          .header("X-Content-Type-Options", "nosniff")
          // Preserve embedded SVG styling while isolating documents and blocking active content.
          .header(
              "Content-Security-Policy",
              "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action"
                  + " 'none'")
          .build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("Error retrieving image")
          .build();
    }
  }
}
