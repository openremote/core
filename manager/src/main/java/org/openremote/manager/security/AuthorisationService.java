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
package org.openremote.manager.security;

import org.openremote.model.Container;
import org.openremote.model.ContainerService;

/**
 * This service is intended to form a centralized authorization service for all resources and
 * actions within the system.
 */
public class AuthorisationService implements ContainerService {

  protected ManagerIdentityService managerIdentityService;

  @Override
  public void init(Container container) throws Exception {
    managerIdentityService = container.getService(ManagerIdentityService.class);
  }

  @Override
  public void start(Container container) throws Exception {}

  @Override
  public void stop(Container container) throws Exception {}
}
