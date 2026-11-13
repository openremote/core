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
package org.openremote.model.gateway;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;
import org.openremote.model.event.shared.SharedEvent;

/**
 * Indicates that the gateway has connected and the central manager wants to begin synchronization
 */
public class GatewayInitStartEvent extends SharedEvent {
  public static final String TYPE = "gateway-init-start";
  protected GatewayTunnelInfo[] activeTunnels;
  // The version of the gateway API that the central instance is using
  protected String version;
  // The public hostname of the tunnel SSH server
  protected String tunnelHostname;
  // The public port of the tunnel SSH server
  protected Integer tunnelPort;

  @JsonCreator
  public GatewayInitStartEvent(
      GatewayTunnelInfo[] activeTunnels,
      String version,
      String tunnelHostname,
      Integer tunnelPort) {
    this.activeTunnels = activeTunnels;
    this.version = version;
    this.tunnelHostname = tunnelHostname;
    this.tunnelPort = tunnelPort;
  }

  public GatewayTunnelInfo[] getActiveTunnels() {
    return activeTunnels;
  }

  public String getVersion() {
    return version;
  }

  public String getTunnelHostname() {
    return tunnelHostname;
  }

  public Integer getTunnelPort() {
    return tunnelPort;
  }

  @Override
  public String toString() {
    return "GatewayInitStartEvent{"
        + "activeTunnels="
        + Arrays.toString(activeTunnels)
        + ", version='"
        + version
        + '\''
        + ", tunnelHostname='"
        + tunnelHostname
        + '\''
        + ", tunnelPort="
        + tunnelPort
        + ", timestamp="
        + timestamp
        + '}';
  }
}
