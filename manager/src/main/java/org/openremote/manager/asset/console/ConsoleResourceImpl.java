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
package org.openremote.manager.asset.console;

import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetEvent;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.asset.impl.ConsoleAsset;
import org.openremote.model.asset.impl.GroupAsset;
import org.openremote.model.console.ConsoleProviders;
import org.openremote.model.console.ConsoleRegistration;
import org.openremote.model.console.ConsoleResource;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.ParentPredicate;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.security.Realm;
import org.openremote.model.util.TextUtil;

public class ConsoleResourceImpl extends ManagerWebResource implements ConsoleResource {

  public static final String CONSOLE_PARENT_ASSET_NAME = "Consoles";
  protected Map<String, String> realmConsoleParentMap = new ConcurrentHashMap<>();
  protected AssetStorageService assetStorageService;

  public ConsoleResourceImpl(
      TimerService timerService,
      ManagerIdentityService identityService,
      AssetStorageService assetStorageService,
      ClientEventService clientEventService) {
    super(timerService, identityService);
    this.assetStorageService = assetStorageService;

    // Subscribe for asset events
    clientEventService.addSubscription(AssetEvent.class, null, this::onAssetChange);
  }

  protected void onAssetChange(AssetEvent event) {
    // Remove any parent console asset mapping if the asset gets deleted
    if (event.getCause() == AssetEvent.Cause.DELETE) {
      realmConsoleParentMap.values().remove(event.getId());
    }
  }

  @Override
  public ConsoleRegistration register(
      RequestParams requestParams, ConsoleRegistration consoleRegistration) {

    if (getRequestRealm() == null) {
      throw new BadRequestException("Invalid realm");
    }

    if (isAuthenticated() && !isRealmAccessibleByUser(getRequestRealmName())) {
      throw new WebApplicationException(
          "Console can only be registered in an accessible realm", FORBIDDEN);
    }

    ConsoleAsset consoleAsset = null;
    boolean existingConsole = false;

    // If console registration has an id and asset exists then ensure asset type is console
    if (!TextUtil.isNullOrEmpty(consoleRegistration.getId())) {
      Asset<?> existingAsset = assetStorageService.find(consoleRegistration.getId(), true);
      if (existingAsset != null && !(existingAsset instanceof ConsoleAsset)) {
        throw new BadRequestException(
            "Console registration ID is not for a Console asset: " + consoleRegistration.getId());
      }
      if (existingAsset == null) {
        throw new WebApplicationException(
            "Console registration ID does not exist: " + consoleRegistration.getId(), CONFLICT);
      }
      consoleAsset = (ConsoleAsset) existingAsset;
      existingConsole = true;
    }

    if (existingConsole) {
      if (!Objects.equals(consoleAsset.getRealm(), getRequestRealmName())) {
        throw new WebApplicationException(
            "Console registration ID is not in the request realm: " + consoleRegistration.getId(),
            FORBIDDEN);
      }
      if (!isAuthenticated()) {
        throw new WebApplicationException(
            "Anonymous requests cannot update existing console registrations", FORBIDDEN);
      }
      if (assetStorageService
          .findUserAssetLinks(getAuthenticatedRealmName(), getUserId(), consoleAsset.getId())
          .isEmpty()) {
        throw new WebApplicationException(
            "User is not linked to this console registration", FORBIDDEN);
      }
    }

    boolean mergeConsole = false;

    if (consoleAsset == null) {
      mergeConsole = true;
      consoleAsset = initConsoleAsset(consoleRegistration);
      consoleAsset.setRealm(getRequestRealmName());
      consoleAsset.setParentId(getConsoleParentAssetId(getRequestRealmName()));
      consoleAsset.setId(consoleRegistration.getId());
      if (!isAuthenticated()) {
        // Anonymous registration
        consoleAsset.setAccessPublicRead(true);
      }
    }

    if (mergeConsole
        || !Objects.equals(
            consoleAsset.getConsoleName().orElse(null), consoleRegistration.getName())) {
      mergeConsole = true;
      consoleAsset.setConsoleName(consoleRegistration.getName());
    }

    boolean providersChanged =
        mergeConsole
            || !consoleAsset
                .getConsoleProviders()
                .map(providers -> providers.equals(consoleRegistration.getProviders()))
                .orElseGet(() -> consoleRegistration.getProviders() != null);
    if (providersChanged) {
      mergeConsole = true;
      consoleAsset.setConsoleProviders(new ConsoleProviders(consoleRegistration.getProviders()));
    }

    if (mergeConsole
        || !Objects.equals(
            consoleAsset.getConsoleVersion().orElse(null), consoleRegistration.getVersion())) {
      mergeConsole = true;
      consoleAsset.setConsoleVersion(consoleRegistration.getVersion());
    }

    if (mergeConsole
        || !Objects.equals(
            consoleAsset.getConsolePlatform().orElse(null), consoleRegistration.getPlatform())) {
      mergeConsole = true;
      consoleAsset.setConsolePlatform(consoleRegistration.getPlatform());
    }

    if (mergeConsole) {
      consoleAsset = assetStorageService.merge(consoleAsset);
    }
    consoleRegistration.setId(consoleAsset.getId());

    // New authenticated legacy registrations are linked to this user. Existing registrations must
    // already be linked.
    if (isAuthenticated() && !existingConsole) {
      assetStorageService.storeUserAssetLinks(
          List.of(
              new UserAssetLink(getAuthenticatedRealmName(), getUserId(), consoleAsset.getId())));
    }

    return consoleRegistration;
  }

  public static ConsoleAsset initConsoleAsset(ConsoleRegistration consoleRegistration) {
    return new ConsoleAsset(consoleRegistration.getName());
  }

  /** This is synchronized to ensure only a single parent is created. */
  public synchronized String getConsoleParentAssetId(String realm) {

    String id = realmConsoleParentMap.get(realm);

    if (TextUtil.isNullOrEmpty(id)) {
      Asset<?> consoleParent = getConsoleParentAsset(assetStorageService, getRequestRealm());
      id = consoleParent.getId();
      realmConsoleParentMap.put(realm, id);
    }

    return id;
  }

  public static Asset<?> getConsoleParentAsset(
      AssetStorageService assetStorageService, Realm realm) {

    // Look for a group asset with a child type of console in the realm root
    GroupAsset consoleParent =
        (GroupAsset)
            assetStorageService.find(
                new AssetQuery()
                    .select(new AssetQuery.Select().excludeAttributes())
                    .names(CONSOLE_PARENT_ASSET_NAME)
                    .parents(new ParentPredicate(null))
                    .types(GroupAsset.class)
                    .realm(new RealmPredicate(realm.getName()))
                    .attributes(
                        new AttributePredicate(
                            "childAssetType",
                            new StringPredicate(ConsoleAsset.DESCRIPTOR.getName()))));

    if (consoleParent == null) {
      consoleParent = new GroupAsset(CONSOLE_PARENT_ASSET_NAME, ConsoleAsset.class);
      consoleParent.setChildAssetType(ConsoleAsset.DESCRIPTOR.getName());
      consoleParent.setRealm(realm.getName());
      consoleParent = assetStorageService.merge(consoleParent);
    }
    return consoleParent;
  }
}
