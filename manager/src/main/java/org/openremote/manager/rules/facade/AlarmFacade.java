/*
 * Copyright 2024, OpenRemote Inc.
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
package org.openremote.manager.rules.facade;

import static org.openremote.model.Constants.RESTRICTED_USER_REALM_ROLE;

import jakarta.persistence.EntityNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openremote.manager.alarm.AlarmService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.rules.RulesEngineId;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.alarm.Alarm;
import org.openremote.model.alarm.Alarm.Source;
import org.openremote.model.alarm.SentAlarm;
import org.openremote.model.rules.Alarms;
import org.openremote.model.rules.AssetRuleset;
import org.openremote.model.rules.GlobalRuleset;
import org.openremote.model.rules.RealmRuleset;
import org.openremote.model.rules.Ruleset;
import org.openremote.model.security.User;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public class AlarmFacade<T extends Ruleset> extends Alarms {
  private static final Logger LOG = Logger.getLogger(AlarmFacade.class.getName());

  protected final RulesEngineId<T> rulesEngineId;
  protected final AlarmService alarmService;
  protected final AssetStorageService assetStorageService;
  protected final ManagerIdentityService identityService;

  public AlarmFacade(
      RulesEngineId<T> rulesEngineId,
      AlarmService alarmService,
      AssetStorageService assetStorageService,
      ManagerIdentityService identityService) {
    this.rulesEngineId = rulesEngineId;
    this.alarmService = alarmService;
    this.assetStorageService = assetStorageService;
    this.identityService = identityService;
  }

  @Override
  public Long create(Alarm alarm, List<String> assetIds) {
    List<String> targetIds = assetIds == null ? List.of() : assetIds.stream().distinct().toList();

    if (rulesEngineId.getScope() == AssetRuleset.class && targetIds.isEmpty()) {
      LOG.warning("Cannot create an asset-scoped alarm without target assets: " + rulesEngineId);
      return null;
    }

    String realm = resolveCreationRealm(alarm, targetIds);
    if (realm == null) {
      LOG.warning(
          "Cannot create a global alarm without a realm or target assets: " + rulesEngineId);
      return null;
    }

    if (!targetIds.isEmpty()) {
      // Preserve the existing validation errors for missing and foreign-realm assets.
      alarmService.validateAssetsInRealm(realm, targetIds);
    }
    if (!areAssetsInScope(targetIds)
        || !isAssigneeAllowed(realm, alarm.getAssigneeId(), targetIds)) {
      return null;
    }

    Alarm.Source source;

    if (rulesEngineId.getScope() == GlobalRuleset.class) {
      // JSON actions can reuse this definition with targets in another realm on the next execution.
      alarm = ValueUtil.clone(alarm);
      source = Source.GLOBAL_RULESET;
    } else if (rulesEngineId.getScope() == RealmRuleset.class) {
      source = Source.REALM_RULESET;
    } else {
      source = Source.ASSET_RULESET;
    }

    alarm.setRealm(realm);
    alarm.setSource(source);

    return alarmService.sendAlarm(alarm, targetIds).getId();
  }

  @Override
  public void linkAssets(List<String> assetIds, Long alarmId) {
    if (assetIds == null || assetIds.isEmpty()) {
      return;
    }

    SentAlarm alarm = alarmService.getAlarm(alarmId);
    String realm =
        rulesEngineId.getScope() == GlobalRuleset.class
            ? alarm.getRealm()
            : rulesEngineId
                .getRealm()
                .orElseThrow(() -> new IllegalStateException("Realm ruleset must have a realm ID"));
    if (!realm.equals(alarm.getRealm())) {
      throw new AlarmService.AlarmAssetLinkRealmMismatchException(
          "Alarm '" + alarmId + "' is not in realm '" + realm + "'");
    }

    List<String> targetIds = assetIds.stream().distinct().toList();
    alarmService.validateAssetsInRealm(realm, targetIds);
    List<String> existingAssetIds =
        alarmService.getAssetLinks(alarmId, realm).stream()
            .map(link -> link.getId().getAssetId())
            .toList();
    if (rulesEngineId.getScope() == AssetRuleset.class && existingAssetIds.isEmpty()) {
      LOG.warning(
          "Cannot establish asset scope for unlinked alarm: " + alarmId + ", " + rulesEngineId);
      return;
    }

    List<String> allAssetIds =
        Stream.concat(existingAssetIds.stream(), targetIds.stream()).distinct().toList();
    if (!areAssetsInScope(allAssetIds)
        || !isAssigneeAllowed(realm, alarm.getAssigneeId(), allAssetIds)) {
      return;
    }

    alarmService.linkAssets(targetIds, realm, alarmId);
  }

  private String resolveCreationRealm(Alarm alarm, List<String> targetIds) {
    if (rulesEngineId.getScope() != GlobalRuleset.class) {
      return rulesEngineId.getRealm().orElseThrow();
    }
    if (!TextUtil.isNullOrEmpty(alarm.getRealm())) {
      return alarm.getRealm();
    }
    if (targetIds.isEmpty()) {
      return null;
    }

    String firstAssetId = targetIds.getFirst();
    if (TextUtil.isNullOrEmpty(firstAssetId)) {
      throw new IllegalArgumentException("Missing asset ID");
    }
    // Creation validates the complete target list against this realm before persisting anything.
    String realm = assetStorageService.getAssetRealms(List.of(firstAssetId)).get(firstAssetId);
    if (realm == null) {
      throw new EntityNotFoundException("Asset '" + firstAssetId + "' does not exist");
    }
    return realm;
  }

  private boolean areAssetsInScope(List<String> assetIds) {
    if (rulesEngineId.getScope() == AssetRuleset.class) {
      for (String assetId : assetIds) {
        if (!FacadeHelper.doesRuleEngineScopeAllowAccess(
            rulesEngineId, assetId, assetStorageService)) {
          LOG.warning("Alarm asset is outside engine scope: " + assetId + ", " + rulesEngineId);
          return false;
        }
      }
    }
    return true;
  }

  private boolean isAssigneeAllowed(String realm, String assigneeId, List<String> assetIds) {
    if (assigneeId == null) {
      return true;
    }
    User assignee = identityService.getIdentityProvider().getUser(assigneeId);
    if (assignee == null || !realm.equals(assignee.getRealm())) {
      LOG.warning(
          "Alarm assignee is missing or belongs to another realm: "
              + assigneeId
              + ", "
              + rulesEngineId);
      return false;
    }
    boolean restricted =
        Arrays.asList(identityService.getIdentityProvider().getUserRealmRoles(realm, assigneeId))
            .contains(RESTRICTED_USER_REALM_ROLE);
    if (restricted && !assetStorageService.isUserAssets(assigneeId, assetIds)) {
      LOG.warning(
          "Restricted alarm assignee lacks links to all target assets: "
              + assigneeId
              + ", "
              + rulesEngineId);
      return false;
    }
    return true;
  }
}
