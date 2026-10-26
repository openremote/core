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
package org.openremote.manager.syslog;

import static org.openremote.model.syslog.SyslogConfig.DEFAULT_LIMIT;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.openremote.container.web.WebResource;
import org.openremote.model.http.RequestParams;
import org.openremote.model.syslog.*;
import org.openremote.model.util.Pair;

public class SyslogResourceImpl extends WebResource implements SyslogResource {

  protected final SyslogService syslogService;

  public SyslogResourceImpl(SyslogService syslogService) {
    this.syslogService = syslogService;
  }

  @Override
  public Response getEvents(
      @BeanParam RequestParams requestParams,
      SyslogLevel level,
      Integer perPage,
      Integer page,
      Long from,
      Long to,
      List<SyslogCategory> categories,
      List<String> subCategories) {

    perPage = perPage != null ? perPage : DEFAULT_LIMIT;
    page = page != null ? page : 1;

    Pair<Long, List<SyslogEvent>> result =
        syslogService.getEvents(
            level,
            perPage,
            page,
            from != null ? Instant.ofEpochMilli(from) : null,
            to != null ? Instant.ofEpochMilli(to) : null,
            categories,
            subCategories);

    if (result == null) {
      return Response.ok(Collections.emptyList()).build();
    }

    long lastPage = (result.key / perPage) + 1L;
    Response.ResponseBuilder rb = Response.ok(result.value.toArray(new SyslogEvent[0]));

    UriBuilder requestUriBuilder = requestParams.getExternalBaseUriBuilder();

    if (page != lastPage) {
      rb.link(requestUriBuilder.replaceQueryParam("page", page + 1).build(), "next");
    }

    rb.link(requestUriBuilder.replaceQueryParam("page", lastPage).build(), "last");
    return rb.build();
  }

  @Override
  public void clearEvents(@BeanParam RequestParams requestParams) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can clear stored events");
    }
    syslogService.clearStoredEvents();
  }

  @Override
  public SyslogConfig getConfig(@BeanParam RequestParams requestParams) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can read the logging configuration");
    }
    return syslogService.getConfig();
  }

  @Override
  public void updateConfig(@BeanParam RequestParams requestParams, SyslogConfig config) {
    if (!isSuperUser()) {
      throw new ForbiddenException("Only super admin can update the logging configuration");
    }
    syslogService.setConfig(config);
  }
}
