/*
 * Copyright 2025, OpenRemote Inc.
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
package org.openremote.agent.protocol.lorawan;

import static org.openremote.agent.protocol.lorawan.LoRaWANAgent.*;
import static org.openremote.agent.protocol.lorawan.LoRaWANConstants.*;
import static org.openremote.model.asset.agent.Agent.HOST;
import static org.openremote.model.asset.agent.Agent.PORT;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;
import static org.openremote.model.util.TextUtil.isNullOrEmpty;
import static org.openremote.model.value.MetaItemType.AGENT_LINK;
import static org.openremote.model.value.MetaItemType.AGENT_LINK_CONFIG;
import static org.openremote.model.value.MetaItemType.STORE_DATA_POINTS;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openremote.agent.protocol.mqtt.MQTTAgent;
import org.openremote.agent.protocol.mqtt.MQTTAgentLink;
import org.openremote.agent.protocol.mqtt.MQTTProtocol;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetTreeNode;
import org.openremote.model.asset.agent.Protocol;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.attribute.MetaMap;
import org.openremote.model.protocol.ProtocolAssetImport;
import org.openremote.model.protocol.ProtocolAssetService;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.JsonPathFilter;
import org.openremote.model.value.RegexValueFilter;
import org.openremote.model.value.ValueFilter;
import org.openremote.model.value.ValueType;

public abstract class AbstractLoRaWANProtocol<
        S extends AbstractLoRaWANProtocol<S, T>, T extends LoRaWANAgent<T, S>>
    implements Protocol<T>, ProtocolAssetImport {

  private static final Logger LOG =
      SyslogCategory.getLogger(PROTOCOL, AbstractLoRaWANProtocol.class);

  protected volatile ExecutorService executorService;
  protected volatile ProtocolAssetService assetService;
  private final MQTTProtocol mqttProtocol;
  private final T agent;
  protected final Set<String> devEuiSet = new CopyOnWriteArraySet<>();
  protected volatile Container container;

  public AbstractLoRaWANProtocol(T agent) {
    this.agent = agent;

    MQTTAgent mqttAgent = new MQTTAgent(agent.getName());

    mqttAgent.setId(agent.getId());
    agent.getMqttHost().ifPresent(mqttAgent::setHost);
    agent.getMqttPort().ifPresent(mqttAgent::setPort);
    agent.getClientId().ifPresent(mqttAgent::setClientId);
    agent.isSecureMode().ifPresent(mqttAgent::setSecureMode);
    agent.getPublishQoS().ifPresent(mqttAgent::setPublishQos);
    agent.getSubscribeQoS().ifPresent(mqttAgent::setSubscribeQos);
    agent.getCertificateAlias().ifPresent(mqttAgent::setCertificateAlias);
    agent.isResumeSession().ifPresent(mqttAgent::setResumeSession);
    agent.isWebsocketMode().ifPresent(mqttAgent::setWebsocketMode);
    agent.getWebsocketPath().ifPresent(mqttAgent::setWebsocketPath);
    agent.getWebsocketQuery().ifPresent(mqttAgent::setWebsocketQuery);
    agent.getLastWillTopic().ifPresent(mqttAgent::setLastWillTopic);
    agent.getLastWillPayload().ifPresent(mqttAgent::setLastWillPayload);
    agent.isLastWillRetain().ifPresent(mqttAgent::setLastWillRetain);
    agent.getUsernamePassword().ifPresent(mqttAgent::setUsernamePassword);
    mqttAgent.setWildcardSubscriptionTopics(
        createWildcardSubscriptionTopicList().toArray(new String[0]));

    this.mqttProtocol = createMqttClientProtocol(mqttAgent);
  }

  @Override
  public Map<AttributeRef, Attribute<?>> getLinkedAttributes() {
    return mqttProtocol.getLinkedAttributes();
  }

  @Override
  public void linkAttribute(String assetId, Attribute<?> attribute) throws Exception {
    mqttProtocol.linkAttribute(assetId, attribute);

    if (ATTRIBUTE_NAME_DEV_EUI.equals(attribute.getName())) {
      attribute
          .getValue(String.class)
          .map(String::trim)
          .map(String::toUpperCase)
          .filter(s -> !s.isEmpty())
          .ifPresent(devEuiSet::add);
    }
  }

  @Override
  public void unlinkAttribute(String assetId, Attribute<?> attribute) throws Exception {
    mqttProtocol.unlinkAttribute(assetId, attribute);

    if (ATTRIBUTE_NAME_DEV_EUI.equals(attribute.getName())) {
      attribute
          .getValue(String.class)
          .map(String::trim)
          .map(String::toUpperCase)
          .filter(s -> !s.isEmpty())
          .ifPresent(
              devEUI -> {
                devEuiSet.remove(devEUI);
                doUnlinkDevice(devEUI);
              });
    }
  }

  @Override
  public void start(Container container) throws Exception {
    this.container = container;
    executorService = container.getExecutor();
  }

  @Override
  public T getAgent() {
    return agent;
  }

  @Override
  public void updateLinkedAttribute(AttributeRef attributeRef, Object value, long timestamp) {
    mqttProtocol.updateLinkedAttribute(attributeRef, value, timestamp);
  }

  @Override
  public void updateLinkedAttribute(AttributeRef attributeRef, Object value) {
    mqttProtocol.updateLinkedAttribute(attributeRef, value);
  }

  @Override
  public void setAssetService(ProtocolAssetService assetService) {
    this.assetService = assetService;
    mqttProtocol.setAssetService(assetService);
  }

  @Override
  public void processLinkedAttributeWrite(AttributeEvent event) {
    mqttProtocol.processLinkedAttributeWrite(event);
  }

  @Override
  public boolean onAgentAttributeChanged(AttributeEvent event) {
    if (MQTT_HOST.getName().equals(event.getName())
        || MQTT_PORT.getName().equals(event.getName())
        || PORT.getName().equals(event.getName())
        || HOST.getName().equals(event.getName())
        || APPLICATION_ID.getName().equals(event.getName())) {
      return true;
    }

    return mqttProtocol.onAgentAttributeChanged(event);
  }

  @Override
  public Future<Void> startAssetImport(byte[] fileData, Consumer<AssetTreeNode[]> assetConsumer) {
    return executorService.submit(
        () -> {
          if (!checkCSVImportPrerequisites()) {
            assetConsumer.accept(new AssetTreeNode[0]);
            return;
          }

          CsvMapper csvMapper = new CsvMapper();
          CsvSchema schema =
              CsvSchema.builder()
                  .addColumn("devEUI")
                  .addColumn("name")
                  .addColumn("assetTypeName")
                  .addColumn("vendor_id")
                  .addColumn("model_id")
                  .addColumn("firmwareVersion")
                  .setUseHeader(false)
                  .build();

          try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileData)) {
            List<DeviceRecord> csvEntries =
                csvMapper
                    .readerFor(DeviceRecord.class)
                    .with(schema)
                    .<DeviceRecord>readValues(inputStream)
                    .readAll();

            AssetTreeNode[] assetTreeNodes =
                csvEntries.stream()
                    .filter(this::validateDeviceRecord)
                    .filter(this::duplicateAssetCheck)
                    .map(this::createAsset)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(AssetTreeNode::new)
                    .toArray(AssetTreeNode[]::new);

            assetConsumer.accept(assetTreeNodes);
          } catch (IOException e) {
            LOG.log(Level.WARNING, "CSV import failed for: " + getProtocolInstanceUri(), e);
          }
        },
        null);
  }

  protected MQTTProtocol getMqttProtocol() {
    return mqttProtocol;
  }

  protected void startMqttProtocol(Container container) throws Exception {
    if (mqttProtocol != null) {
      mqttProtocol.start(container);
    }
  }

  protected void stopMqttProtocol(Container container) {
    if (mqttProtocol != null) {
      mqttProtocol.stop(container);
    }
  }

  protected void doUnlinkDevice(String devEui) {}

  protected boolean checkCSVImportPrerequisites() {
    Optional<String> applicationId = getAgent().getApplicationId();
    boolean isOk = applicationId.map(id -> !id.trim().isEmpty()).orElse(false);
    if (!isOk) {
      LOG.warning(
          "CSV import failed because agent attribute '"
              + APPLICATION_ID.getName()
              + "'  is missing for: "
              + getProtocolInstanceUri());
    }
    return isOk;
  }

  protected abstract MQTTProtocol createMqttClientProtocol(MQTTAgent agent);

  protected abstract List<String> createWildcardSubscriptionTopicList();

  protected abstract boolean configureMQTTSubscriptionTopic(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord);

  protected abstract boolean configureMQTTPublishTopic(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord);

  protected abstract boolean configureMQTTMessageMatchFilterAndPredicate(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord);

  protected abstract boolean configureMQTTWriteValueTemplate(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord);

  protected abstract String generateAssetId(String devEui);

  protected boolean configureMQTTValueFilter(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }
    getAgentConfigJsonPath(attribute)
        .ifPresent(
            jsonPath ->
                agentLink.setValueFilters(
                    new ValueFilter[] {new JsonPathFilter(jsonPath, false, false)}));
    getAgentConfigRegex(attribute)
        .ifPresent(
            pattern ->
                agentLink.setValueFilters(
                    new ValueFilter[] {new RegexValueFilter(pattern, false, false, 1, 0)}));
    return true;
  }

  protected boolean configureMQTTValueConverter(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }
    getAgentConfigValueConverter(attribute).ifPresent(agentLink::setValueConverter);
    return true;
  }

  protected boolean configureMQTTWriteValueConverter(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }
    getAgentConfigWriteValueConverter(attribute).ifPresent(agentLink::setWriteValueConverter);
    return true;
  }

  protected Optional<ValueType.ObjectMap> getAgentConfig(Attribute<?> attribute) {
    return Optional.ofNullable(attribute)
        .map(Attribute::getMeta)
        .flatMap(metaMap -> metaMap.get(AGENT_LINK_CONFIG))
        .flatMap(metItem -> metItem.getValue());
  }

  protected Optional<Integer> getAgentConfigUplinkPort(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_UPLINK_PORT))
        .filter(port -> port instanceof Integer)
        .map(port -> (Integer) port);
  }

  protected Optional<Integer> getAgentConfigDownlinkPort(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_DOWNLINK_PORT))
        .filter(port -> port instanceof Integer)
        .map(port -> (Integer) port);
  }

  protected Optional<String> getAgentConfigJsonPath(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_VALUE_FILTER_JSON_PATH))
        .filter(jsonPath -> jsonPath instanceof String)
        .map(jsonPath -> ((String) jsonPath).trim())
        .filter(jsonPath -> !jsonPath.isEmpty());
  }

  protected Optional<String> getAgentConfigRegex(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_VALUE_FILTER_REGEX))
        .filter(regex -> regex instanceof String)
        .map(regex -> ((String) regex).trim())
        .filter(regex -> !regex.isEmpty());
  }

  protected Optional<ValueType.ObjectMap> getAgentConfigValueConverter(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_VALUE_CONVERTER))
        .filter(valueConverter -> valueConverter instanceof ValueType.ObjectMap)
        .map(valueConverter -> (ValueType.ObjectMap) valueConverter);
  }

  protected Optional<ValueType.ObjectMap> getAgentConfigWriteValueConverter(
      Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_WRITE_VALUE_CONVERTER))
        .filter(valueConverter -> valueConverter instanceof ValueType.ObjectMap)
        .map(valueConverter -> (ValueType.ObjectMap) valueConverter);
  }

  protected Optional<String> getAgentConfigWriteObjectValueTemplate(Attribute<?> attribute) {
    return getAgentConfig(attribute)
        .map(map -> map.get(AGENT_LINK_CONFIG_WRITE_OBJECT_VALUE_TEMPLATE))
        .filter(valueConverter -> valueConverter instanceof String)
        .map(valueConverter -> ((String) valueConverter).trim())
        .filter(valueConverter -> !valueConverter.isEmpty());
  }

  private boolean validateDeviceRecord(DeviceRecord record) {
    boolean isValid = false;
    if (record != null) {
      isValid = record.isValid();
      if (!isValid) {
        LOG.warning(
            "CSV import skipped an invalid device record "
                + record
                + " for: "
                + getProtocolInstanceUri());
      }
    }
    return isValid;
  }

  private boolean duplicateAssetCheck(DeviceRecord record) {
    if (record == null) {
      return false;
    }

    if (!duplicateAssetCheck(record.getDevEUI())) {
      LOG.info(
          "CSV import skipped a device record "
              + record
              + " because an asset already existed for: "
              + getProtocolInstanceUri());
      return false;
    }

    return true;
  }

  protected boolean duplicateAssetCheck(String devEUI) {
    return Optional.ofNullable(devEUI)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(String::toUpperCase)
        .map(s -> !devEuiSet.contains(s))
        .orElse(false);
  }

  protected Optional<Asset<?>> createAsset(DeviceRecord deviceRecord) {
    if (deviceRecord == null) {
      return Optional.empty();
    }

    return Optional.ofNullable(deviceRecord.getAssetTypeName())
        .flatMap(name -> resolveAssetClass(name, deviceRecord))
        .flatMap(clazz -> instantiateAsset(clazz, deviceRecord))
        .flatMap(asset -> configureAsset(asset, deviceRecord));
  }

  private Optional<Class<? extends Asset<?>>> resolveAssetClass(
      String simpleClassName, DeviceRecord deviceRecord) {
    if (simpleClassName == null || deviceRecord == null) {
      return Optional.empty();
    }

    return ValueUtil.getAssetClass(simpleClassName)
        .or(
            () -> {
              LOG.warning(
                  "CSV import skipped a device record "
                      + deviceRecord
                      + " because of an invalid asset type name for: "
                      + getProtocolInstanceUri());
              return Optional.empty();
            });
  }

  private Optional<Asset<?>> instantiateAsset(Class<?> clazz, DeviceRecord deviceRecord) {
    if (clazz == null || deviceRecord == null) {
      return Optional.empty();
    }

    Asset<?> asset = null;
    try {
      Constructor<?> constructor = clazz.getConstructor(String.class);
      asset = (Asset<?>) constructor.newInstance(deviceRecord.getName());
    } catch (ReflectiveOperationException e) {
      LOG.log(
          Level.WARNING,
          "CSV import failed to create asset " + deviceRecord + " for: " + getProtocolInstanceUri(),
          e);
    }
    return Optional.ofNullable(asset);
  }

  protected Optional<Asset<?>> configureAsset(Asset<?> asset, DeviceRecord deviceRecord) {
    if (asset == null || deviceRecord == null) {
      LOG.warning(
          () ->
              "Skipped LoRaWAN asset configuration because the "
                  + (asset == null ? "asset" : "device record")
                  + " is missing for "
                  + getProtocolInstanceUri());
      return Optional.empty();
    }

    Optional<Attribute<Object>> devEuiAttribute = asset.getAttribute(ATTRIBUTE_NAME_DEV_EUI);
    Optional.ofNullable(deviceRecord.getDevEUI())
        .ifPresent(
            devEui -> devEuiAttribute.ifPresent(attr -> attr.setValue(devEui.toUpperCase())));
    Optional.ofNullable(deviceRecord.getVendorId())
        .ifPresent(
            vendorId ->
                asset
                    .getAttribute(ATTRIBUTE_NAME_VENDOR_ID)
                    .ifPresent(attribute -> attribute.setValue(vendorId)));
    Optional.ofNullable(deviceRecord.getModelId())
        .ifPresent(
            modelId ->
                asset
                    .getAttribute(ATTRIBUTE_NAME_MODEL_ID)
                    .ifPresent(attribute -> attribute.setValue(modelId)));
    Optional.ofNullable(deviceRecord.getFirmwareVersion())
        .ifPresent(
            version ->
                asset
                    .getAttribute(ATTRIBUTE_NAME_FIRMWARE_VERSION)
                    .ifPresent(attribute -> attribute.setValue(version)));

    Optional<String> devEui =
        devEuiAttribute
            .flatMap(attr -> attr.getValue(String.class))
            .filter(value -> !isNullOrEmpty(value));
    devEui.ifPresent(
        value -> {
          asset.setId(generateAssetId(value));
          asset.setParentId(getAgent().getId());
        });

    boolean isOk = !isNullOrEmpty(asset.getId());
    if (!isOk) {
      String reason;
      if (devEuiAttribute.isEmpty()) {
        reason = "the mandatory '" + ATTRIBUTE_NAME_DEV_EUI + "' attribute is missing";
      } else if (devEui.isEmpty()) {
        reason = "the mandatory '" + ATTRIBUTE_NAME_DEV_EUI + "' attribute has no usable value";
      } else {
        reason = "an asset ID could not be generated";
      }
      LOG.warning(
          () ->
              "Skipped LoRaWAN asset of type '%s' for device '%s' (devEUI '%s') on %s because %s"
                  .formatted(
                      asset.getType(),
                      deviceRecord.getName(),
                      deviceRecord.getDevEUI(),
                      getProtocolInstanceUri(),
                      reason));
    }

    for (Attribute<?> attribute : asset.getAttributes().values()) {
      MQTTAgentLink agentLink = new MQTTAgentLink(agent.getId());

      Optional<String> jsonPath = getAgentConfigJsonPath(attribute);
      Optional<String> regex = getAgentConfigRegex(attribute);
      if (jsonPath.isPresent() || regex.isPresent()) {
        isOk = isOk && configureMQTTSubscriptionTopic(attribute, agentLink, deviceRecord);
      }
      isOk = isOk && configureMQTTValueFilter(attribute, agentLink, deviceRecord);
      isOk =
          isOk && configureMQTTMessageMatchFilterAndPredicate(attribute, agentLink, deviceRecord);
      isOk = isOk && configureMQTTValueConverter(attribute, agentLink, deviceRecord);
      Optional<Integer> downlinkPort = getAgentConfigDownlinkPort(attribute);
      if (downlinkPort.isPresent()) {
        isOk = isOk && configureMQTTPublishTopic(attribute, agentLink, deviceRecord);
        isOk = isOk && configureMQTTWriteValueConverter(attribute, agentLink, deviceRecord);
        isOk = isOk && configureMQTTWriteValueTemplate(attribute, agentLink, deviceRecord);
      }

      if (getAgentConfig(attribute).isPresent()) {
        attribute.addOrReplaceMeta(new MetaItem<>(AGENT_LINK, agentLink));
        MetaMap metaMap = attribute.getMeta();
        metaMap.remove(AGENT_LINK_CONFIG);
        attribute.setMeta(metaMap);
      } else if (ATTRIBUTE_NAME_DEV_EUI.equals(attribute.getName())) {
        attribute.addOrReplaceMeta(
            new MetaItem<>(AGENT_LINK, agentLink), new MetaItem<>(STORE_DATA_POINTS, false));
      }
    }

    return isOk ? Optional.of(asset) : Optional.empty();
  }
}
