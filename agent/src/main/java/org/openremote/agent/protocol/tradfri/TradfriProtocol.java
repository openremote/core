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
package org.openremote.agent.protocol.tradfri;

import static org.openremote.model.asset.impl.LightAsset.BRIGHTNESS;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;
import static org.openremote.model.value.MetaItemType.AGENT_LINK;

import java.util.*;
import java.util.logging.Logger;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.agent.protocol.tradfri.device.Device;
import org.openremote.agent.protocol.tradfri.device.Gateway;
import org.openremote.agent.protocol.tradfri.device.event.EventHandler;
import org.openremote.agent.protocol.tradfri.device.event.GatewayEvent;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.agent.DefaultAgentLink;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.UniqueIdentifierGenerator;
import org.openremote.model.value.ValueHolder;

/**
 * Protocol for communicating with the IKEA TRÅDFRI gateway; devices are represented as {@link
 * Asset}s under the linked {@link Agent}; {@link Asset}s are automatically added/removed depending
 * on whether they are available on the gateway, for a given {@link Agent} a device {@link Asset}
 * will have a consistent ID.
 */
public class TradfriProtocol extends AbstractProtocol<TradfriAgent, DefaultAgentLink> {

  /** The logger for the IKEA TRÅDFRI protocol. */
  private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, TradfriProtocol.class);

  /** The display name for the IKEA TRÅDFRI protocol. */
  public static final String PROTOCOL_DISPLAY_NAME = "IKEA TRÅDFRI";

  protected TradfriConnection tradfriConnection;
  protected HashMap<String, Device> tradfriDevices = new HashMap<>();

  public TradfriProtocol(TradfriAgent agent) {
    super(agent);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  public String getProtocolInstanceUri() {
    return "tradfri://" + agent.getHost();
  }

  @Override
  protected void doStart(Container container) throws Exception {

    String host =
        agent
            .getHost()
            .orElseThrow(
                () -> {
                  String msg = "Host is not defined so cannot start protocol: " + this;
                  LOG.warning(msg);
                  return new IllegalArgumentException(msg);
                });

    String securityCode = agent.getSecurityCode().orElse("");

    tradfriConnection = new TradfriConnection(host, securityCode);
    tradfriConnection.addConnectionStatusConsumer(this::setConnectionStatus);

    // Connect to the gateway

    LOG.fine("Connecting the gateway: " + this);
    Gateway gateway = tradfriConnection.connect();

    if (gateway != null) {

      // Subscribe to device changes on the gateway
      EventHandler<GatewayEvent> gatewayEventHandler =
          new EventHandler<GatewayEvent>() {
            @Override
            public void handle(GatewayEvent event) {
              Device[] newDevices = event.getGateway().getDevices();
              if (newDevices == null) return;
              synchroniseAssets(newDevices);
            }
          };
      gateway.addEventHandler(gatewayEventHandler);

      // Do initial sync of devices
      Device[] devices = gateway.getDevices();

      if (devices != null) {
        synchroniseAssets(devices);
      }
    } else {
      setConnectionStatus(ConnectionStatus.ERROR);
    }
  }

  @Override
  protected void doStop(Container container) throws Exception {

    if (tradfriConnection == null) {
      return;
    }

    tradfriConnection.removeConnectionStatusConsumer(this::setConnectionStatus);

    for (Device device : tradfriDevices.values()) {
      device.getEventHandlers().clear();
      device.disableObserve();
    }

    tradfriDevices.clear();
    tradfriConnection.disconnect();
  }

  @Override
  protected void doLinkAttribute(
      String assetId, Attribute<?> attribute, DefaultAgentLink agentLink) {
    // Nothing to do here as assets are already connected to devices
  }

  @Override
  protected void doUnlinkAttribute(
      String assetId, Attribute<?> attribute, DefaultAgentLink agentLink) {
    // Nothing to do here
  }

  @Override
  protected void doLinkedAttributeWrite(
      DefaultAgentLink agentLink, AttributeEvent event, Object processedValue) {

    Device device = tradfriDevices.get(event.getRef().getId());

    if (device != null) {
      tradfriConnection.controlDevice(device, event);
    }
  }

  protected void synchroniseAssets(Device[] devices) {
    LOG.fine("Synchronizing assets with gateway devices: " + this);
    boolean isFirstRun = tradfriDevices == null;

    if (isFirstRun) {
      tradfriDevices = new HashMap<>(devices.length);

      // Find all existing child assets of this agent that have a deviceId attribute
      List<Asset<?>> childAssets =
          assetService.findAssets(new AssetQuery().attributeName(TradfriAsset.DEVICE_ID.getName()));

      List<String> obsoleteAssetIds =
          childAssets.stream()
              .map(
                  asset -> {
                    Integer deviceId =
                        asset
                            .getAttribute(TradfriAsset.DEVICE_ID)
                            .flatMap(ValueHolder::getValue)
                            .orElse(null);
                    boolean isObsolete =
                        deviceId != null
                            && Arrays.stream(devices)
                                .noneMatch(device -> deviceId.equals(device.getInstanceId()));
                    return isObsolete ? asset.getId() : null;
                  })
              .filter(Objects::nonNull)
              .toList();

      if (!obsoleteAssetIds.isEmpty()) {
        LOG.finest("Removing " + obsoleteAssetIds.size() + " obsolete asset(s): " + this);
        assetService.deleteAssets(obsoleteAssetIds.toArray(new String[0]));
      }

      // Put devices with existing asset into the device map others will be picked up by following
      // logic
      Arrays.stream(devices)
          .forEach(
              device -> {
                String assetId = getDeviceAssetId(device);
                Optional<Asset<?>> existingAsset =
                    childAssets.stream().filter(asset -> asset.getId().equals(assetId)).findFirst();
                existingAsset.ifPresent(asset -> addDevice((TradfriAsset) asset, device));
              });
    } else {
      // Remove obsolete devices
      List<String> obsoleteAssetIds = new ArrayList<>();

      tradfriDevices.forEach(
          (key, value) -> {
            boolean isObsolete =
                Arrays.stream(devices)
                    .noneMatch(
                        device -> Objects.equals(device.getInstanceId(), value.getInstanceId()));
            if (isObsolete) {
              LOG.info("Removing obsolete device asset: " + key);
              obsoleteAssetIds.add(key);
            }
          });

      if (!obsoleteAssetIds.isEmpty()) {
        LOG.finest("Removing " + obsoleteAssetIds.size() + " obsolete asset(s): " + this);
        obsoleteAssetIds.forEach(this::removeDevice);
        assetService.deleteAssets(obsoleteAssetIds.toArray(new String[0]));
      }
    }

    // Create assets for new devices
    Arrays.stream(devices)
        .filter(device -> !tradfriDevices.containsKey(getDeviceAssetId(device)))
        .forEach(
            device -> {
              LOG.info("Creating device asset for device ID=" + device.getInstanceId());
              Asset<?> asset = createDeviceAsset(device);
              addDevice((TradfriAsset) asset, device);

              if (asset == null) {
                LOG.warning("Failed to create asset for device ID=" + device.getInstanceId());
                return;
              }

              assetService.mergeAsset(asset);
            });
  }

  protected void addDevice(TradfriAsset asset, Device device) {
    tradfriDevices.put(asset != null ? asset.getId() : getDeviceAssetId(device), device);

    if (asset != null) {
      asset.initialiseAttributes(device);
      assetService.mergeAsset((Asset<?>) asset);
    }
  }

  protected void removeDevice(String assetId) {
    Device device = tradfriDevices.remove(assetId);
    device.disableObserve();
    device.getEventHandlers().clear();
  }

  private Asset<?> createDeviceAsset(Device device) {

    Asset<?> asset = null;
    String name =
        (!TextUtil.isNullOrEmpty(device.getName()) ? device.getName() : "Unnamed")
            + " "
            + device.getInstanceId();

    if (device.isPlug()) {
      TradfriPlugAsset plugAsset = new TradfriPlugAsset(name);
      plugAsset.setDeviceId(device.getInstanceId());
      asset = plugAsset;

    } else if (device.isLight()) {
      TradfriLightAsset lightAsset = new TradfriLightAsset(name);
      lightAsset.setDeviceId(device.getInstanceId());

      // Add agent links
      lightAsset
          .getAttributes()
          .get(BRIGHTNESS)
          .ifPresent(
              attribute ->
                  attribute.addOrReplaceMeta(
                      new MetaItem<>(AGENT_LINK, new DefaultAgentLink(agent.getId()))));
      asset = lightAsset;
    }

    if (asset != null) {
      asset.setId(getDeviceAssetId(device));
      asset.setParentId(agent.getId());
    }
    return asset;
  }

  protected String getDeviceAssetId(Device device) {
    return UniqueIdentifierGenerator.generateId(
        "tradfri_" + agent.getId() + device.getInstanceId());
  }
}
