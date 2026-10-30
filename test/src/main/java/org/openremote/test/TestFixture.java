/*
 * Copyright 2020, OpenRemote Inc.
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
package org.openremote.test;

import java.util.List;
import org.openremote.agent.protocol.io.AbstractNettyIOClient;
import org.openremote.container.Container;
import org.openremote.container.util.LogUtil;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.gateway.GatewayConnection;
import org.openremote.model.rules.AssetRuleset;
import org.openremote.model.rules.GlobalRuleset;
import org.openremote.model.rules.RealmRuleset;
import org.openremote.model.security.User;
import org.spockframework.runtime.extension.IGlobalExtension;

/** Used to store state for tests to help improve test performance */
public class TestFixture implements IGlobalExtension {

  // Store the container here to allow stopping it after all tests run
  protected static Container container;
  public static List<GlobalRuleset> globalRulesets;
  public static List<RealmRuleset> realmRulesets;
  public static List<AssetRuleset> assetRulesets;
  public static List<GatewayConnection> gatewayConnections;
  public static List<Asset<?>> assets;
  public static List<UserAssetLink> userAssets;
  public static List<User> users;

  @Override
  public void start() {
    LogUtil.initialiseJUL();

    // Force RECONNECT times to be short to improve test run times
    AbstractNettyIOClient.RECONNECT_DELAY_INITIAL_MILLIS = 50;
    AbstractNettyIOClient.RECONNECT_DELAY_MAX_MILLIS = 50;
  }

  @Override
  public void stop() {
    if (container != null) {
      container.stop();
    }
  }
}
