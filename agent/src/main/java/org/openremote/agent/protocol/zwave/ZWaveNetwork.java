/*
 * Copyright 2019, OpenRemote Inc.
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
package org.openremote.agent.protocol.zwave;

import static java.util.stream.Collectors.toList;
import static org.openremote.model.value.MetaItemType.AGENT_LINK;
import static org.openremote.protocol.zwave.model.ZWNodeInitializerListener.NodeInitState.INITIALIZATION_FINISHED;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.openremote.controller.exception.ConfigurationException;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetTreeNode;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.impl.ColourRGB;
import org.openremote.protocol.zwave.ConnectionException;
import org.openremote.protocol.zwave.model.Controller;
import org.openremote.protocol.zwave.model.ZWEndPoint;
import org.openremote.protocol.zwave.model.ZWManufacturerID;
import org.openremote.protocol.zwave.model.ZWaveNode;
import org.openremote.protocol.zwave.model.commandclasses.*;
import org.openremote.protocol.zwave.model.commandclasses.channel.Channel;
import org.openremote.protocol.zwave.model.commandclasses.channel.value.*;
import org.openremote.protocol.zwave.port.ZWavePortConfiguration;

public class ZWaveNetwork {

  protected Controller controller;
  private final List<Consumer<ConnectionStatus>> connectionStatusConsumers =
      new CopyOnWriteArrayList<>();
  private final Map<Consumer<Value>, ChannelConsumerLink> consumerLinkMap = new HashMap<>();
  protected String serialPort;
  protected ZWaveSerialIOClient ioClient;

  public ZWaveNetwork(String serialPort) {
    this.serialPort = serialPort;
  }

  public synchronized void connect() {
    if (controller != null) {
      return;
    }

    // Need this config object for the Z-Wave lib
    ZWavePortConfiguration configuration = new ZWavePortConfiguration();
    configuration.setCommLayer(ZWavePortConfiguration.CommLayer.NETTY);
    configuration.setComPort(serialPort);

    ioClient = new ZWaveSerialIOClient(serialPort);
    ioClient.addConnectionStatusConsumer(this::onConnectionStatusChanged);

    controller = new Controller(NettyConnectionManager.create(configuration, ioClient));

    try {
      controller.connect();
    } catch (ConfigurationException | ConnectionException e) {
      disposeClient();
      controller = null;
      ioClient = null;
      onConnectionStatusChanged(ConnectionStatus.ERROR);
    }
  }

  public synchronized void disconnect() {
    if (controller == null) {
      return;
    }

    try {
      controller.disconnect();
    } catch (ConnectionException e) {
      ZWaveProtocol.LOG.log(
          Level.WARNING, "Exception thrown while disconnecting the controller", e);
    } finally {
      disposeClient();
      ioClient = null;
      controller = null;
      onConnectionStatusChanged(ConnectionStatus.DISCONNECTED);
    }
  }

  public synchronized ConnectionStatus getConnectionStatus() {
    if (ioClient != null) {
      return ioClient.getConnectionStatus();
    } else {
      return ConnectionStatus.DISCONNECTED;
    }
  }

  public synchronized void addConnectionStatusConsumer(
      Consumer<ConnectionStatus> connectionStatusConsumer) {
    connectionStatusConsumers.add(connectionStatusConsumer);
  }

  public synchronized void removeConnectionStatusConsumer(
      Consumer<ConnectionStatus> connectionStatusConsumer) {
    connectionStatusConsumers.remove(connectionStatusConsumer);
  }

  public synchronized void addSensorValueConsumer(
      int nodeId, int endpoint, String channelName, Consumer<Value> consumer) {
    ChannelConsumerLink link =
        ChannelConsumerLink.createLink(nodeId, endpoint, channelName, consumer, controller);
    consumerLinkMap.put(consumer, link);
  }

  public synchronized void removeSensorValueConsumer(Consumer<Value> consumer) {
    ChannelConsumerLink link = consumerLinkMap.get(consumer);
    if (link != null) {
      consumerLinkMap.remove(consumer);
      link.unlink();
    }
  }

  public synchronized void writeChannel(int nodeId, int endpoint, String linkName, Object value) {
    Channel channel = findChannel(nodeId, endpoint, linkName);
    if (channel != null && value != null) {
      ValueType type = channel.getValueType();
      Value zwValue = toZWValue(type, value);

      if (zwValue != null) {
        channel.executeSetCommand(zwValue);
      }
    }
  }

  private void disposeClient() {
    if (ioClient != null) {
      ioClient.removeConnectionStatusConsumer(this::onConnectionStatusChanged);
      ioClient.disconnect(); // Ensure it's disconnected - don't know what Z-Wave lib does
    }
  }

  protected void onConnectionStatusChanged(ConnectionStatus status) {
    connectionStatusConsumers.forEach(consumer -> consumer.accept(status));
  }

  protected Value toZWValue(ValueType type, Object value) {
    Value zwValue = null;

    switch (type) {
      case STRING:
        zwValue = ValueUtil.getString(value).map(StringValue::new).orElse(null);
        break;
      case NUMBER:
        zwValue = ValueUtil.getDouble(value).map(NumberValue::new).orElse(null);
        break;
      case INTEGER:
        zwValue = ValueUtil.getInteger(value).map(NumberValue::new).orElse(null);
        break;
      case BOOLEAN:
        zwValue = ValueUtil.getBoolean(value).map(BooleanValue::new).orElse(null);
        break;
      case ARRAY:
        if (value instanceof ColourRGB) {
          ArrayValue zwArray = new ArrayValue();
          zwArray.add(new NumberValue(((ColourRGB) value).getR()));
          zwArray.add(new NumberValue(((ColourRGB) value).getG()));
          zwArray.add(new NumberValue(((ColourRGB) value).getB()));
          zwValue = zwArray;
        }
        break;
        /*
        zwValue = ValueUtil.getValue(value, Object[].class).map(arrValue -> {

            ArrayValue zwArray = new ArrayValue();

            zwArray.addAll(
                Arrays.stream(arrValue)
                    .map(arrValueItem -> {
                        if (arrValueItem == null) {
                            return null;
                        }

                        // Just assume the item's data type is the desired type - bit strange but no other data
                        ValueType valueType = null;
                        Class<?> itemType = arrValueItem.getClass();
                        if (ValueUtil.isArray(itemType)) {
                            valueType = ValueType.ARRAY;
                        } else if (ValueUtil.isNumber(itemType)) {
                            valueType = ValueType.NUMBER;
                        } else if (ValueUtil.isBoolean(itemType)) {
                            valueType = ValueType.BOOLEAN;
                        } else if (ValueUtil.isString(itemType)) {
                            valueType = ValueType.STRING;
                        }
                        if (valueType == null) {
                            return null;
                        }

                        return toZWValue(valueType, arrValueItem);
                    }).toArray(Value[]::new)
            );
            return zwArray;
        }).orElse(null);
         */
    }

    return zwValue;
  }

  private static void addAttributeChannelMetaItems(
      String agentId, Attribute<?> attribute, Channel channel) {
    int nodeId =
        channel.getCommandClass() != null ? channel.getCommandClass().getContext().getNodeID() : 0;
    int endpoint =
        channel.getCommandClass() != null
            ? channel.getCommandClass().getContext().getDestEndPoint()
            : 0;
    String linkValue = channel.getLinkName();

    ZWaveAgentLink agentLink = new ZWaveAgentLink(agentId, nodeId, endpoint, linkValue);

    attribute.addOrReplaceMeta(
        new MetaItem<>(AGENT_LINK, agentLink),
        new MetaItem<>(MetaItemType.LABEL, channel.getDisplayName()));

    if (channel.isReadOnly()) {
      attribute.getMeta().add(new MetaItem<>(MetaItemType.READ_ONLY, true));
    }
  }

  private Channel findChannel(int nodeId, int endpointNumber, String channelName) {
    Channel channel = null;
    if (controller != null) {
      channel = controller.findChannel(nodeId, endpointNumber, channelName);
    }
    return channel;
  }

  public synchronized AssetTreeNode[] discoverDevices(ZWaveAgent agent) {
    if (controller == null) {
      return new AssetTreeNode[0];
    }

    // Filter nodes

    List<ZWaveNode> nodes =
        controller.getNodes().stream()
            .filter(node -> node.getState() == INITIALIZATION_FINISHED)
            .filter(
                node -> {
                  // Do not add devices that have already been discovered
                  List<ZWCommandClass> cmdClasses =
                      new ArrayList<>(node.getSupportedCommandClasses());
                  for (ZWEndPoint curEndpoint : node.getEndPoints()) {
                    cmdClasses.addAll(curEndpoint.getCmdClasses());
                  }
                  return cmdClasses.stream()
                      .map(ZWCommandClass::getChannels)
                      .flatMap(List<Channel>::stream)
                      .noneMatch(
                          channel ->
                              consumerLinkMap.values().stream()
                                  .anyMatch(link -> link.getChannel() == channel));
                })
            .collect(toList());

    List<AssetTreeNode> assetNodes =
        nodes.stream()
            .map(
                node -> {

                  // Root device

                  AssetTreeNode deviceNode =
                      createDeviceNode(
                          agent.getId(),
                          node.getSupportedCommandClasses(),
                          node.getGenericDeviceClassID()
                              .getDisplayName(node.getSpecificDeviceClassID()));

                  // Device Info

                  Asset<?> deviceInfoAsset = new ThingAsset("Info");
                  deviceInfoAsset
                      .getAttributes()
                      .addAll(createNodeInfoAttributes(agent.getId(), node));
                  deviceNode.addChild(new AssetTreeNode(deviceInfoAsset));

                  // Sub device

                  for (ZWEndPoint curEndpoint : node.getEndPoints()) {
                    String subDeviceName =
                        curEndpoint
                                .getGenericDeviceClassID()
                                .getDisplayName(curEndpoint.getSpecificDeviceClassID())
                            + " - "
                            + curEndpoint.getEndPointNumber();
                    AssetTreeNode subDeviceNode =
                        createDeviceNode(agent.getId(), curEndpoint.getCmdClasses(), subDeviceName);
                    deviceNode.addChild(subDeviceNode);
                  }

                  // Parameters

                  List<ZWParameterItem> parameters = node.getParameters();
                  if (parameters.size() > 0) {
                    AssetTreeNode parameterListNode =
                        new AssetTreeNode(new ThingAsset("Parameters"));
                    deviceNode.addChild(parameterListNode);

                    List<AssetTreeNode> parameterNodes =
                        parameters.stream()
                            .filter(parameter -> parameter.getChannels().size() > 0)
                            .map(
                                parameter -> {
                                  int number = parameter.getNumber();
                                  String parameterLabel =
                                      number + " : " + parameter.getDisplayName();
                                  String description = parameter.getDescription();
                                  Asset<?> parameterAsset = new ThingAsset(parameterLabel);
                                  AssetTreeNode parameterNode = new AssetTreeNode(parameterAsset);

                                  List<Attribute<?>> attributes =
                                      parameter.getChannels().stream()
                                          .map(
                                              channel -> {
                                                Attribute<?> attribute =
                                                    TypeMapper.createAttribute(
                                                        channel.getName(),
                                                        channel.getChannelType());
                                                addAttributeChannelMetaItems(
                                                    agent.getId(), attribute, channel);
                                                addValidRangeMeta(attribute, parameter);
                                                return attribute;
                                              })
                                          .collect(toList());

                                  if (description != null && description.length() > 0) {
                                    Attribute<String> descriptionAttribute =
                                        new Attribute<>(
                                            "description",
                                            org.openremote.model.value.ValueType.TEXT,
                                            description);
                                    descriptionAttribute.addMeta(
                                        new MetaItem<>(MetaItemType.LABEL, "Description"),
                                        new MetaItem<>(MetaItemType.READ_ONLY, true));
                                    attributes.add(descriptionAttribute);
                                  }
                                  parameterAsset.getAttributes().addAll(attributes);
                                  return parameterNode;
                                })
                            .collect(toList());

                    parameterNodes.forEach(parameterListNode::addChild);
                  }

                  return deviceNode;
                })
            .collect(toList());

    // Z-Wave Controller

    Asset<?> networkManagementAsset = new ThingAsset("Z-Wave Controller");
    List<Attribute<?>> attributes =
        controller.getSystemCommandManager().getChannels().stream()
            .filter(
                channel ->
                    consumerLinkMap.values().stream()
                        .noneMatch(link -> link.getChannel() == channel))
            .map(
                channel -> {
                  Attribute<?> attribute =
                      TypeMapper.createAttribute(channel.getName(), channel.getChannelType());
                  addAttributeChannelMetaItems(agent.getId(), attribute, channel);
                  return attribute;
                })
            .collect(toList());
    if (attributes.size() > 0) {
      networkManagementAsset.getAttributes().addAll(attributes);
      assetNodes.add(new AssetTreeNode(networkManagementAsset));
    }

    return assetNodes.toArray(new AssetTreeNode[0]);
  }

  private AssetTreeNode createDeviceNode(
      String agentId, List<ZWCommandClass> cmdClasses, String name) {

    Asset<?> device = new ThingAsset(name);

    List<Attribute<?>> attributes =
        cmdClasses.stream()
            .filter(
                commandClass ->
                    commandClass.getID().toRaw()
                            != ZWCommandClassID.COMMAND_CLASS_CONFIGURATION.toRaw()
                        && commandClass.getID().toRaw()
                            != ZWCommandClassID.COMMAND_CLASS_ZWAVEPLUS_INFO.toRaw()
                        && commandClass.getID().toRaw()
                            != ZWCommandClassID.COMMAND_CLASS_NO_OPERATION.toRaw()
                        && commandClass.getID().toRaw()
                            != ZWCommandClassID.COMMAND_CLASS_BASIC.toRaw())
            .map(ZWCommandClass::getChannels)
            .flatMap(List<Channel>::stream)
            .map(
                channel -> {
                  int endpoint = channel.getCommandClass().getContext().getDestEndPoint();
                  String attributeName = channel.getName() + (endpoint == 0 ? "" : "_" + endpoint);
                  String displayName =
                      channel.getDisplayName() + (endpoint == 0 ? "" : " - " + endpoint);
                  Attribute<?> attribute =
                      TypeMapper.createAttribute(attributeName, channel.getChannelType());
                  addAttributeChannelMetaItems(agentId, attribute, channel);
                  attribute.addOrReplaceMeta(new MetaItem<>(MetaItemType.LABEL, displayName));
                  return attribute;
                })
            .collect(toList());

    device.getAttributes().addOrReplace(attributes);

    return new AssetTreeNode(device);
  }

  private static List<Attribute<?>> createNodeInfoAttributes(String agentId, ZWaveNode node) {
    List<Attribute<?>> attributes = new ArrayList<>();

    Attribute<?> nodeIdAttrib =
        new Attribute<>("nodeId", org.openremote.model.value.ValueType.INTEGER, node.getNodeID())
            .addMeta(
                new MetaItem<>(MetaItemType.LABEL, "Node ID"),
                new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(nodeIdAttrib);

    Attribute<?> manufacturerIdAttrib =
        new Attribute<>(
            "manufacturerId",
            org.openremote.model.value.ValueType.TEXT,
            String.format("0x%04X", node.getManufacturerId()));
    manufacturerIdAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Manufacturer ID"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(manufacturerIdAttrib);

    Attribute<?> manufacturerAttrib =
        new Attribute<>(
            "manufacturerName",
            org.openremote.model.value.ValueType.TEXT,
            ZWManufacturerID.fromRaw(node.getManufacturerId()).getName());
    manufacturerAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Manufacturer"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(manufacturerAttrib);

    Attribute<?> flirsAttrib =
        new Attribute<>(
            "isFlirs", org.openremote.model.value.ValueType.BOOLEAN, node.getNodeInfo().isFLIRS());
    flirsAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "FLIRS"), new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(flirsAttrib);

    Attribute<?> routingAttrib =
        new Attribute<>(
            "isRouting",
            org.openremote.model.value.ValueType.BOOLEAN,
            node.getNodeInfo().isRouting());
    routingAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Routing"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(routingAttrib);

    Attribute<?> listeningAttrib =
        new Attribute<>(
            "isListening",
            org.openremote.model.value.ValueType.BOOLEAN,
            node.getNodeInfo().isListening());
    listeningAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Listening"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(listeningAttrib);

    Attribute<?> productTypeIdAttrib =
        new Attribute<>(
            "productTypeId",
            org.openremote.model.value.ValueType.TEXT,
            String.format("0x%04X", node.getProductTypeID()));
    productTypeIdAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Product Type ID"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(productTypeIdAttrib);

    Attribute<?> productIdAttrib =
        new Attribute<>(
            "productId",
            org.openremote.model.value.ValueType.TEXT,
            String.format("0x%04X", node.getProductID()));
    productIdAttrib.addMeta(
        new MetaItem<>(MetaItemType.LABEL, "Product ID"),
        new MetaItem<>(MetaItemType.READ_ONLY, true));
    attributes.add(productIdAttrib);

    String modelName = node.getModelName();
    if (modelName != null) {
      Attribute<?> modelNameAttrib =
          new Attribute<>("modelName", org.openremote.model.value.ValueType.TEXT, modelName);
      modelNameAttrib.addMeta(
          new MetaItem<>(MetaItemType.LABEL, "Model"),
          new MetaItem<>(MetaItemType.READ_ONLY, true));
      attributes.add(modelNameAttrib);
    }

    // Z-Wave Plus Info

    List<Attribute<?>> zwPlusAttributes =
        node.getSupportedCommandClasses().stream()
            .filter(
                commandClass ->
                    commandClass.getID().toRaw()
                        == ZWCommandClassID.COMMAND_CLASS_ZWAVEPLUS_INFO.toRaw())
            .map(ZWCommandClass::getChannels)
            .flatMap(List<Channel>::stream)
            .map(
                channel -> {
                  String attributeName = channel.getName();
                  Attribute<?> attribute =
                      TypeMapper.createAttribute(attributeName, channel.getChannelType());
                  addAttributeChannelMetaItems(agentId, attribute, channel);
                  return attribute;
                })
            .collect(toList());

    attributes.addAll(zwPlusAttributes);

    return attributes;
  }

  // TODO: How to deal with runtime/instance
  private void addValidRangeMeta(Attribute<?> attribute, ZWParameterItem parameter) {
    Long min = null;
    Long max = null;
    if (parameter instanceof ZWParameterByte) {
      min = ((ZWParameterByte) parameter).getMinValue();
      max = ((ZWParameterByte) parameter).getMaxValue();
    } else if (parameter instanceof ZWParameterShort) {
      min = ((ZWParameterShort) parameter).getMinValue();
      max = ((ZWParameterShort) parameter).getMaxValue();
    } else if (parameter instanceof ZWParameterInt) {
      min = ((ZWParameterInt) parameter).getMinValue();
      max = ((ZWParameterInt) parameter).getMaxValue();
    }
  }
}
