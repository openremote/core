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
package org.openremote.model;

import org.openremote.model.util.TsIgnore;

/**
 * The {@link Container} is a registry of services, the order of services in a container is
 * important and is determined by the {@link #getPriority} value; when starting the {@link
 * Container} using the auto service discovery mechanism. If the container is started with an
 * explicit list of services then the insertion order is used.
 *
 * <p>Service startup lifecycle:
 *
 * <ol>
 *   <li>{@link #init} in registry insertion order
 *   <li>{@link #start} in registry insertion order
 *   <li>{@link #startIngress} in registry insertion order
 * </ol>
 *
 * <p>The container completes each startup phase for all services before moving to the next.
 *
 * <p>Service shutdown lifecycle:
 *
 * <ol>
 *   <li>{@link #stop} in <b>reverse</b> registry order
 * </ol>
 */
@TsIgnore
public interface ContainerService {

  int DEFAULT_PRIORITY = 1000;
  int HIGH_PRIORITY = Integer.MIN_VALUE + 1000;
  int MED_PRIORITY = 0;
  int LOW_PRIORITY = Integer.MAX_VALUE - 1000;

  /**
   * Gets the priority of this service, which determines the order within each startup phase when
   * services are auto-discovered. Services with a lower priority run first.
   */
  default int getPriority() {
    return DEFAULT_PRIORITY;
  }

  /**
   * All services are initialized in the order they have been added to the container (if container
   * started with explicit list of services) otherwise they are initialized in order of {@link
   * #getPriority}.
   */
  void init(Container container) throws Exception;

  /**
   * After initialization, services are started in the order they have been added to the container
   * (if container started with explicit list of services) otherwise they are started in order of
   * {@link #getPriority}.
   */
  void start(Container container) throws Exception;

  /**
   * Opens public listeners after every service has completed {@link #start}. Services should prepare
   * their handlers and event consumers during {@link #init} and {@link #start}.
   */
  default void startIngress(Container container) throws Exception {}

  /**
   * When the container is shutting down, it stops all services in the reverse order they were
   * started.
   */
  void stop(Container container) throws Exception;
}
