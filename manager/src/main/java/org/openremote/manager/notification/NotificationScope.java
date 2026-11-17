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
package org.openremote.manager.notification;

import java.util.Arrays;
import java.util.List;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityProvider;
import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.notification.Notification;

final class NotificationScope {

  private NotificationScope() {}

  /**
   * Restricted recipients must be directly linked to the scoped asset; unrestricted recipients need
   * no asset link. The realm must be the recipient's realm. Callers are responsible for enforcing
   * realm authorization.
   */
  static boolean isUserInAssetScope(
      String realm,
      String userId,
      String scopedAssetId,
      ManagerIdentityProvider identityProvider,
      AssetStorageService assetStorageService) {
    boolean restrictedRecipient =
        Arrays.asList(identityProvider.getUserRealmRoles(realm, userId))
            .contains(Constants.RESTRICTED_USER_REALM_ROLE);
    return !restrictedRecipient || assetStorageService.isUserAssets(userId, List.of(scopedAssetId));
  }

  /** Resolves the realm for realm- and asset-scoped rules, or {@code null} for other sources. */
  static String resolveRulesRealm(
      Notification.Source source, String sourceId, AssetStorageService assetStorageService) {
    return switch (source) {
      case REALM_RULESET -> sourceId;
      case ASSET_RULESET -> {
        Asset<?> scopedAsset = assetStorageService.find(sourceId, false);
        if (scopedAsset == null) {
          throw new NotificationProcessingException(
              NotificationProcessingException.Reason.INSUFFICIENT_ACCESS,
              "Rule's scoped asset does not exist");
        }
        yield scopedAsset.getRealm();
      }
      default -> null;
    };
  }
}
