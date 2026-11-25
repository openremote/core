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
import { type Asset, type GatewayTunnelInfo, GatewayTunnelInfoType } from "@openremote/model";

export const gatewayAsset = {
  id: "testGatewayAsset000001",
  name: "Warehouse gateway",
  realm: "master",
  type: "GatewayAsset",
} satisfies Asset;

export const unavailableGatewayId = "testGatewayAsset000002";

export const gatewayTunnels = [
  {
    id: "warehouse-web",
    gatewayId: gatewayAsset.id,
    realm: gatewayAsset.realm,
    type: GatewayTunnelInfoType.HTTPS,
    target: "localhost",
    targetPort: 443,
  },
  {
    id: "warehouse-service",
    gatewayId: gatewayAsset.id,
    realm: gatewayAsset.realm,
    type: GatewayTunnelInfoType.TCP,
    target: "localhost",
    targetPort: 8443,
  },
  {
    id: "unavailable-gateway",
    gatewayId: unavailableGatewayId,
    realm: gatewayAsset.realm,
    type: GatewayTunnelInfoType.HTTPS,
    target: "localhost",
    targetPort: 443,
  },
] satisfies GatewayTunnelInfo[];
