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
package org.openremote.manager.rules.facade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.notification.NotificationService;
import org.openremote.manager.rules.RulesEngineId;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.PathPredicate;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.rules.AssetRuleset;
import org.openremote.model.rules.RealmRuleset;
import org.openremote.model.rules.Ruleset;
import org.openremote.model.rules.Users;
import org.openremote.model.security.User;

/**
 * Restricts rule RHS access to the scope of the engine (a rule in asset scope can not access users
 * in global scope).
 */
public class UsersFacade<T extends Ruleset> extends Users {

  protected final RulesEngineId<T> rulesEngineId;
  protected final AssetStorageService assetStorageService;
  protected final NotificationService notificationService;
  protected final ManagerIdentityService identityService;

  public UsersFacade(
      RulesEngineId<T> rulesEngineId,
      AssetStorageService assetStorageService,
      NotificationService notificationService,
      ManagerIdentityService identityService) {
    this.rulesEngineId = rulesEngineId;
    this.assetStorageService = assetStorageService;
    this.notificationService = notificationService;
    this.identityService = identityService;
  }

  @Override
  public Stream<String> getResults(UserQuery userQuery) {
    // Rule targets can reuse the caller's query. Isolate nested predicates as well, since the
    // facade and identity provider both modify filters during execution.
    if (userQuery == null) {
      throw new IllegalArgumentException("Unable to copy user query");
    }
    userQuery = new UserQuery(userQuery);

    // Do security checks to ensure correct scoping
    // No restriction for global rulesets
    if (RealmRuleset.class.isAssignableFrom(rulesEngineId.getScope())) {
      // Restrict realm
      userQuery.realmPredicate =
          new RealmPredicate(
              rulesEngineId
                  .getRealm()
                  .orElseThrow(
                      () -> new IllegalArgumentException("Realm ID missing: " + rulesEngineId)));
    } else if (AssetRuleset.class.isAssignableFrom(rulesEngineId.getScope())) {
      // Asset links must not expose users from outside the rule's realm.
      userQuery.realmPredicate =
          new RealmPredicate(
              rulesEngineId
                  .getRealm()
                  .orElseThrow(
                      () -> new IllegalArgumentException("Realm ID missing: " + rulesEngineId)));
      String assetId =
          rulesEngineId
              .getAssetId()
              .orElseThrow(
                  () -> new IllegalArgumentException("Asset ID missing: " + rulesEngineId));

      if (userQuery.pathPredicate == null || userQuery.pathPredicate.path == null) {
        userQuery.pathPredicate = new PathPredicate(assetId);
      } else if (!Arrays.asList(userQuery.pathPredicate.path).contains(assetId)) {
        // Retain caller restrictions without duplicating an already supplied scope asset.
        List<String> path = new ArrayList<>(Arrays.asList(userQuery.pathPredicate.path));
        path.add(assetId);
        userQuery.pathPredicate.path = path.toArray(new String[0]);
      }
    }

    // Preserve caller filters and avoid duplicating a full exclusion already supplied by the
    // caller.
    UserQuery.AttributeValuePredicate systemAccountExclusion =
        new UserQuery.AttributeValuePredicate(
            true, new StringPredicate(User.SYSTEM_ACCOUNT_ATTRIBUTE), null);
    boolean excludesSystemAccounts =
        userQuery.attributes != null
            && Arrays.stream(userQuery.attributes)
                .anyMatch(
                    attribute ->
                        attribute.negated
                            && attribute.value == null
                            && systemAccountExclusion.name.equals(attribute.name));
    if (!excludesSystemAccounts) {
      UserQuery.AttributeValuePredicate[] attributes =
          userQuery.attributes == null
              ? new UserQuery.AttributeValuePredicate[1]
              : Arrays.copyOf(userQuery.attributes, userQuery.attributes.length + 1);
      attributes[attributes.length - 1] = systemAccountExclusion;
      userQuery.attributes(attributes);
    }

    // Prevent service users being retrieved
    userQuery.serviceUsers(false);

    return Arrays.stream(identityService.getIdentityProvider().queryUsers(userQuery))
        .map(User::getId);
  }
}
