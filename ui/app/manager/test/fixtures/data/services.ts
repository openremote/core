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
import { type ExternalService, ExternalServiceStatus } from "@openremote/model";

/**
 * A service registered in the given realm.
 */
export function getRealmService(realm: string): ExternalService {
  return {
    serviceId: `${realm}-service`,
    instanceId: 1,
    label: `${realm} service`,
    realm,
    status: ExternalServiceStatus.AVAILABLE,
  };
}
