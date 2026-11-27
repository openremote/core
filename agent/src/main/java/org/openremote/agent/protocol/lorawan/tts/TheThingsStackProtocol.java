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
package org.openremote.agent.protocol.lorawan.tts;

import static org.openremote.agent.protocol.lorawan.LoRaWANConstants.ASSET_TYPE_TAG;
import static org.openremote.agent.protocol.lorawan.tts.TheThingsStackAgent.*;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;
import static org.openremote.model.util.TextUtil.isNullOrEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.FieldMask;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.openremote.agent.protocol.lorawan.AbstractLoRaWANProtocol;
import org.openremote.agent.protocol.lorawan.DeviceRecord;
import org.openremote.agent.protocol.lorawan.GrpcRetryingRunner;
import org.openremote.agent.protocol.lorawan.GrpcRetryingRunner.StreamBreakSignalException;
import org.openremote.agent.protocol.lorawan.LoRaWANMQTTProtocol;
import org.openremote.agent.protocol.lorawan.UniqueBlockingQueue;
import org.openremote.agent.protocol.mqtt.MQTTAgent;
import org.openremote.agent.protocol.mqtt.MQTTAgentLink;
import org.openremote.agent.protocol.mqtt.MQTTProtocol;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.query.filter.NumberPredicate;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.UniqueIdentifierGenerator;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.JsonPathFilter;
import org.openremote.model.value.ValueFilter;
import ttn.lorawan.v3.EndDeviceOuterClass;
import ttn.lorawan.v3.EndDeviceRegistryGrpc;
import ttn.lorawan.v3.EventsGrpc;
import ttn.lorawan.v3.EventsOuterClass;
import ttn.lorawan.v3.Identifiers;

public class TheThingsStackProtocol
    extends AbstractLoRaWANProtocol<TheThingsStackProtocol, TheThingsStackAgent> {

  public static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, TheThingsStackProtocol.class);

  public static final String THE_THINGS_STACK_ASSET_TYPE_TAG = ASSET_TYPE_TAG;
  public static final String PROTOCOL_DISPLAY_NAME = "The Things Stack";
  public static final int GRPC_LIST_DEVICE_LIMIT = 1000;
  public static final long GRPC_TIMEOUT_MILLIS = 10000L;
  public static final long GRPC_STREAM_RUNNER_INITIAL_BACKOFF_MILLIS = 5000L;
  public static final long GRPC_STREAM_RUNNER_MAX_BACKOFF_MILLIS = 5 * 60000L;
  /*
      The bulkSyncRunner can be activated to perform periodic full discovery
      of LoRaWAN devices.

      public static final long GRPC_BULK_SYNC_RUNNER_INITIAL_BACKOFF_MILLIS = 5000L;
      public static final long GRPC_BULK_SYNC_RUNNER_MAX_BACKOFF_MILLIS = 5*60000L;
      public static final long GRPC_BULK_SYNC_RUNNER_CONTINUATION_SCHEDULE_MILLIS = 12*60*60000L;
  */
  public static final long GRPC_BULK_SYNC_RUNNER_KEEP_ALIVE_MILLIS = 10000L;
  public static final long GRPC_BULK_SYNC_RUNNER_KEEP_ALIVE_TIMEOUT_MILLIS = 4000L;
  public static final long GRPC_SINGLE_DEVICE_SYNC_RUNNER_INITIAL_BACKOFF_MILLIS = 100L;
  public static final long GRPC_SINGLE_DEVICE_SYNC_RUNNER_MAX_BACKOFF_MILLIS = 5 * 60000L;
  public static final String BULK_SYNC_RUNNER_NAME = "Bulk Sync Runner";
  public static final String SINGLE_DEVICE_SYNC_RUNNER_NAME = "Single Device Sync Runner";
  public static final String DEVICE_EVENT_STREAMER_NAME = "Device Event Streamer";

  private final ConnectionStateManager connectionStateManager = new ConnectionStateManager(this);
  private final AtomicReference<Map<String, EndDeviceOuterClass.EndDevice>> ttsDeviceMap =
      new AtomicReference<>(new ConcurrentHashMap<>());
  private final UniqueBlockingQueue<String> deviceIdQueue = new UniqueBlockingQueue<>();
  private final Set<String> ignoreDevEuiSet = new CopyOnWriteArraySet<>();

  private final GrpcRetryingRunner bulkSyncRunner =
      new GrpcRetryingRunner(BULK_SYNC_RUNNER_NAME, this::getGRPCClientUri);
  private final GrpcRetryingRunner deviceEventStreamer =
      new GrpcRetryingRunner(DEVICE_EVENT_STREAMER_NAME, this::getGRPCClientUri);
  private volatile GrpcRetryingRunner singleDeviceSyncRunner;
  private final Lock singleDeviceSyncLock = new ReentrantLock();

  public TheThingsStackProtocol(TheThingsStackAgent agent) {
    super(agent);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  public String getProtocolInstanceUri() {
    return "tts-mqtt://"
        + getAgent().getMqttHost().orElse("-")
        + ":"
        + getAgent().getMqttPort().map(Object::toString).orElse("-")
        + "/?clientId="
        + getAgent().getClientId().orElse("-");
  }

  @Override
  public void start(org.openremote.model.Container container) throws Exception {
    super.start(container);

    connectionStateManager.init();
    connectionStateManager.start(container);
  }

  @Override
  public void stop(org.openremote.model.Container container) throws Exception {
    connectionStateManager.stop(container);
  }

  @Override
  protected void doUnlinkDevice(String devEui) {
    ignoreDevEuiSet.remove(devEui);
  }

  @Override
  public boolean onAgentAttributeChanged(AttributeEvent event) {
    if (TENANT_ID.getName().equals(event.getName())
        || API_KEY.getName().equals(event.getName())
        || SECURE_GRPC.getName().equals(event.getName())) {
      return true;
    }

    return super.onAgentAttributeChanged(event);
  }

  @Override
  protected MQTTProtocol createMqttClientProtocol(MQTTAgent agent) {
    return new LoRaWANMQTTProtocol(agent) {
      @Override
      public String getProtocolName() {
        return "The Things Stack MQTT Client";
      }

      @Override
      protected void onConnectionStatusChanged(ConnectionStatus connectionStatus) {
        onMqttConnectionStatusChanged(connectionStatus);
      }
    };
  }

  protected void onMqttConnectionStatusChanged(ConnectionStatus connectionStatus) {
    connectionStateManager.onMqttConnectionStatusChanged(connectionStatus);
  }

  protected void onGrpcConnectionStatusChanged(ConnectionStatus connectionStatus) {
    connectionStateManager.onGrpcConnectionStatusChanged(connectionStatus);
  }

  protected void setConnectionStatus(ConnectionStatus connectionStatus) {
    LoRaWANMQTTProtocol mqttProtocol = (LoRaWANMQTTProtocol) getMqttProtocol();
    if (mqttProtocol != null) {
      mqttProtocol.setStatus(connectionStatus);
    }
  }

  @Override
  protected boolean checkCSVImportPrerequisites() {
    boolean isOk = super.checkCSVImportPrerequisites();

    List<AbstractMap.SimpleEntry<AttributeDescriptor<String>, Optional<String>>> list =
        new ArrayList<>(3);
    list.add(new AbstractMap.SimpleEntry<>(HOST, getAgent().getHost()));
    list.add(new AbstractMap.SimpleEntry<>(TENANT_ID, getAgent().getTenantId()));
    list.add(new AbstractMap.SimpleEntry<>(API_KEY, getAgent().getApiKey()));

    for (AbstractMap.SimpleEntry<AttributeDescriptor<String>, Optional<String>> item : list) {
      if (!item.getValue().map(attrValue -> !attrValue.trim().isEmpty()).orElse(false)) {
        isOk = false;
        LOG.log(
            Level.WARNING,
            "CSV import failed because agent attribute '"
                + item.getKey().getName()
                + "'  is missing for: "
                + getProtocolInstanceUri());
      }
    }

    initDeviceMap();

    return isOk;
  }

  @Override
  protected List<String> createWildcardSubscriptionTopicList() {
    Optional<String> tenantId = getAgent().getTenantId().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);
    return applicationId
        .map(
            id ->
                Collections.singletonList(
                    "v3/"
                        + (tenantId.isPresent()
                            ? (applicationId.get() + "@" + tenantId.get())
                            : applicationId.get())
                        + "/devices/+/up"))
        .orElse(new ArrayList<>());
  }

  @Override
  protected boolean configureMQTTSubscriptionTopic(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }

    boolean isOk = true;
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> tenantId = getAgent().getTenantId().map(String::trim);
    Optional<String> devEUI =
        Optional.ofNullable(deviceRecord.getDevEUI()).map(String::trim).map(String::toUpperCase);

    if (applicationId.isPresent() && devEUI.isPresent() && apiKey.isPresent()) {
      EndDeviceOuterClass.EndDevice device = ttsDeviceMap.get().get(devEUI.get());
      if (device != null) {
        String deviceId = device.getIds().getDeviceId();
        agentLink.setSubscriptionTopic(
            "v3/"
                + (tenantId.isPresent()
                    ? (applicationId.get() + "@" + tenantId.get())
                    : applicationId.get())
                + "/devices/"
                + deviceId
                + "/up");
      } else {
        LOG.warning(
            "Skipped LoRaWAN asset because the device was not found on the network server"
                + " while configuring the MQTT subscription topic for "
                + deviceRecord
                + " on "
                + getProtocolInstanceUri());
        isOk = false;
      }
    }

    return isOk;
  }

  @Override
  protected boolean configureMQTTPublishTopic(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }

    boolean isOk = true;
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> tenantId = getAgent().getTenantId().map(String::trim);
    Optional<String> devEUI =
        Optional.ofNullable(deviceRecord.getDevEUI()).map(String::trim).map(String::toUpperCase);

    if (applicationId.isPresent() && devEUI.isPresent() && apiKey.isPresent()) {
      EndDeviceOuterClass.EndDevice device = ttsDeviceMap.get().get(devEUI.get());
      if (device != null) {
        String deviceId = device.getIds().getDeviceId();
        agentLink.setPublishTopic(
            "v3/"
                + (tenantId.isPresent()
                    ? (applicationId.get() + "@" + tenantId.get())
                    : applicationId.get())
                + "/devices/"
                + deviceId
                + "/down/push");
      } else {
        LOG.warning(
            "Skipped LoRaWAN asset because the device was not found on the network server"
                + " while configuring the MQTT publish topic for "
                + deviceRecord
                + " on "
                + getProtocolInstanceUri());
        isOk = false;
      }
    }

    return isOk;
  }

  @Override
  protected boolean configureMQTTMessageMatchFilterAndPredicate(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }
    getAgentConfigUplinkPort(attribute)
        .ifPresent(
            port ->
                agentLink
                    .setMessageMatchFilters(
                        new ValueFilter[] {
                          new JsonPathFilter("$.uplink_message.f_port", true, false)
                        })
                    .setMessageMatchPredicate(new NumberPredicate(port)));
    return true;
  }

  @Override
  protected boolean configureMQTTWriteValueTemplate(
      Attribute<?> attribute, MQTTAgentLink agentLink, DeviceRecord deviceRecord) {
    if (attribute == null || agentLink == null || deviceRecord == null) {
      return false;
    }

    boolean isOk = true;
    Optional<Integer> downlinkPort = getAgentConfigDownlinkPort(attribute);

    if (downlinkPort.isPresent()) {
      ObjectMapper mapper = new ObjectMapper();

      Map<String, Object> downlink = new HashMap<>();
      downlink.put("f_port", downlinkPort.get());
      downlink.put("priority", "NORMAL");
      downlink.put("frm_payload", "%VALUE%");

      Map<String, Object> root = new HashMap<>();
      root.put("downlinks", Collections.singletonList(downlink));

      try {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        agentLink.setWriteValue(json);
      } catch (JsonProcessingException e) {
        isOk = false;
        LOG.log(
            Level.WARNING,
            "Skipped LoRaWAN asset because the MQTT downlink template could not be created for "
                + deviceRecord
                + " on "
                + getProtocolInstanceUri(),
            e);
      }
    }
    return isOk;
  }

  @Override
  protected String generateAssetId(String devEui) {
    return UniqueIdentifierGenerator.generateId("TTS_" + getAgent().getId() + devEui);
  }

  protected String getGRPCClientUri() {
    return "tts-grpc://"
        + getAgent().getHost().orElse("-")
        + ":"
        + getPort()
        + "/?clientId="
        + getAgent().getClientId().orElse("-");
  }

  protected void startMqtt(Container container) throws Exception {
    startMqttProtocol(container);
  }

  protected void stopMqtt(Container container) {
    stopMqttProtocol(container);
  }

  protected void startSync() {
    Optional<String> host = getAgent().getHost().map(String::trim);
    int port = getPort();
    boolean isSecureGRPC = isSecureGRPC();
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    List<String> missingFields = new ArrayList<>();
    if (host.isEmpty()) {
      missingFields.add(HOST.getName());
    }
    if (applicationId.isEmpty()) {
      missingFields.add(APPLICATION_ID.getName());
    }
    if (apiKey.isEmpty()) {
      missingFields.add(API_KEY.getName());
    }

    if (!missingFields.isEmpty()) {
      LOG.warning(
          "Auto-discovery skipped due to missing agent attributes {"
              + String.join(", ", missingFields)
              + "} for: "
              + getProtocolInstanceUri());
      onGrpcConnectionStatusChanged(ConnectionStatus.ERROR);
      return;
    }

    /*
       The bulkSyncRunner can be activated to perform periodic full discovery
       of LoRaWAN devices.

       bulkSyncRunner.start(
           () -> createChannel(host.get(), port, isSecureGRPC, false),
           this::performBulkDeviceSync,
           true,
           Duration.ofMillis(GRPC_BULK_SYNC_RUNNER_INITIAL_BACKOFF_MILLIS),
           Duration.ofMillis(GRPC_BULK_SYNC_RUNNER_MAX_BACKOFF_MILLIS),
           Duration.ofMillis(GRPC_BULK_SYNC_RUNNER_CONTINUATION_SCHEDULE_MILLIS)
       );
    */

    deviceEventStreamer.startStream(
        () -> createChannel(host.get(), port, isSecureGRPC, true),
        this::performDevicesUpdate,
        Duration.ofMillis(GRPC_STREAM_RUNNER_INITIAL_BACKOFF_MILLIS),
        Duration.ofMillis(GRPC_STREAM_RUNNER_MAX_BACKOFF_MILLIS),
        this::onGrpcConnectionStatusChanged);
  }

  protected void stopSync() {
    bulkSyncRunner.shutdown();
    deviceEventStreamer.shutdown();

    singleDeviceSyncLock.lock();
    try {
      if (singleDeviceSyncRunner != null) {
        singleDeviceSyncRunner.shutdown();
        singleDeviceSyncRunner = null;
        deviceIdQueue.clear();
      }
    } finally {
      singleDeviceSyncLock.unlock();
    }
  }

  private void startSingleDeviceSync() {
    Optional<String> host = getAgent().getHost().map(String::trim);
    int port = getPort();
    boolean isSecureGRPC = isSecureGRPC();
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    if (host.isEmpty() || apiKey.isEmpty() || applicationId.isEmpty()) {
      return;
    }

    singleDeviceSyncLock.lock();
    try {
      if (deviceIdQueue.size() > 0 && singleDeviceSyncRunner == null) {
        singleDeviceSyncRunner =
            new GrpcRetryingRunner(SINGLE_DEVICE_SYNC_RUNNER_NAME, this::getGRPCClientUri);
        singleDeviceSyncRunner.start(
            () -> createChannel(host.get(), port, isSecureGRPC, false),
            this::performSingleDeviceSync,
            false,
            Duration.ofMillis(GRPC_SINGLE_DEVICE_SYNC_RUNNER_INITIAL_BACKOFF_MILLIS),
            Duration.ofMillis(GRPC_SINGLE_DEVICE_SYNC_RUNNER_MAX_BACKOFF_MILLIS));
      }
    } finally {
      singleDeviceSyncLock.unlock();
    }
  }

  private int getPort() {
    return getAgent().getPort().orElse(isSecureGRPC() ? 443 : 80);
  }

  private boolean isSecureGRPC() {
    return getAgent().getSecureGRPC().orElse(true);
  }

  private ManagedChannel createChannel(
      String host, int port, boolean isSecure, boolean withKeepAlive) {
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
    if (isSecure) {
      builder.useTransportSecurity();
    } else {
      builder.usePlaintext();
    }

    if (withKeepAlive) {
      builder
          .keepAliveTime(GRPC_BULK_SYNC_RUNNER_KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS)
          .keepAliveTimeout(GRPC_BULK_SYNC_RUNNER_KEEP_ALIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
          .keepAliveWithoutCalls(true);
    }

    return builder.build();
  }

  private void performDevicesUpdate(ManagedChannel channel) throws Exception {
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    if (apiKey.isEmpty() || applicationId.isEmpty()) {
      throw new IllegalStateException("Missing API key or application ID");
    }

    Metadata headers = new Metadata();
    Metadata.Key<String> AUTHORIZATION_HEADER =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    headers.put(AUTHORIZATION_HEADER, "Bearer " + apiKey.get());

    EventsGrpc.EventsBlockingStub stub =
        EventsGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    EventsOuterClass.StreamEventsRequest request =
        EventsOuterClass.StreamEventsRequest.newBuilder()
            .addIdentifiers(
                Identifiers.EntityIdentifiers.newBuilder()
                    .setApplicationIds(
                        Identifiers.ApplicationIdentifiers.newBuilder()
                            .setApplicationId(applicationId.get())
                            .build()))
            .build();

    boolean isStreamEstablished = false;
    boolean isStatusUpdate = true;

    Iterator<EventsOuterClass.Event> events = stub.stream(request);

    try {
      while (events.hasNext()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("[" + DEVICE_EVENT_STREAMER_NAME + "] interrupted");
        }

        EventsOuterClass.Event event = events.next();

        if (isStatusUpdate) {
          isStatusUpdate = false;
          onGrpcConnectionStatusChanged(ConnectionStatus.CONNECTED);
        }
        isStreamEstablished = true;

        String eventName = event.getName();
        String typeUrl = event.getData().getTypeUrl();

        LOG.finest(
            () ->
                "["
                    + DEVICE_EVENT_STREAMER_NAME
                    + "] Received event (name="
                    + eventName
                    + ", typeUrl="
                    + typeUrl
                    + ") for: "
                    + getProtocolInstanceUri());

        if (eventName.equals("end_device.batch.delete")) {
          try {
            Identifiers.EndDeviceIdentifiersList list;

            if (event.getData().is(Identifiers.EndDeviceIdentifiersList.class)) {
              list = event.getData().unpack(Identifiers.EndDeviceIdentifiersList.class);
            } else {
              LOG.warning(
                  () ->
                      "["
                          + DEVICE_EVENT_STREAMER_NAME
                          + "] Bad batch delete event. Unexpected payload type (name="
                          + eventName
                          + ", typeUrl="
                          + typeUrl
                          + ") for: "
                          + getProtocolInstanceUri());
              continue;
            }

            for (Identifiers.EndDeviceIdentifiers devIds : list.getEndDeviceIdsList()) {
              if (isNullOrEmpty(devIds.getDeviceId())) {
                continue;
              }

              List<String> devEuiList = new ArrayList<>();
              ttsDeviceMap
                  .get()
                  .entrySet()
                  .removeIf(
                      entry -> {
                        boolean isRemove =
                            devIds.getDeviceId().equals(entry.getValue().getIds().getDeviceId());
                        if (isRemove) {
                          String devEui =
                              bytesToHex(devIds.getDevEui().toByteArray()).toUpperCase();
                          if (!isNullOrEmpty(devEui)) {
                            devEuiList.add(devEui);
                          }
                        }
                        return isRemove;
                      });

              for (String devEui : devEuiList) {
                ignoreDevEuiSet.remove(devEui);
              }

              LOG.finest(
                  () ->
                      "["
                          + DEVICE_EVENT_STREAMER_NAME
                          + "] Deleted device (deviceId="
                          + devIds.getDeviceId()
                          + ") from batch event (eventName="
                          + eventName
                          + ") for: "
                          + getProtocolInstanceUri());
            }
          } catch (Exception ex) {
            LOG.log(
                Level.WARNING,
                "["
                    + DEVICE_EVENT_STREAMER_NAME
                    + "] Failed to unpack event (eventName="
                    + eventName
                    + ", typeUrl="
                    + typeUrl
                    + ") for: "
                    + getProtocolInstanceUri(),
                ex);
          }

          continue;
        }

        Identifiers.EndDeviceIdentifiers devIds = extractDeviceIds(event);
        String deviceId = (devIds != null ? devIds.getDeviceId() : null);
        String devEui =
            (devIds != null ? bytesToHex(devIds.getDevEui().toByteArray()).toUpperCase() : null);
        if (isNullOrEmpty(deviceId)) {
          continue;
        }

        switch (eventName) {
          case "as.up.data.forward":
          case "js.join.accept":
            {
              if (!isNullOrEmpty(devEui)) {
                if (!ttsDeviceMap.get().containsKey(devEui)
                    || (!devEuiSet.contains(devEui) && !ignoreDevEuiSet.contains(devEui))) {
                  LOG.finest(
                      () ->
                          "["
                              + DEVICE_EVENT_STREAMER_NAME
                              + "] Scheduling sync for device (eventName="
                              + eventName
                              + ", devEui="
                              + devEui
                              + ", deviceId="
                              + deviceId
                              + ") for: "
                              + getProtocolInstanceUri());
                  queueDeviceIdForSync(deviceId);
                }
              }
              break;
            }

          case "end_device.create":
          case "end_device.update":
            {
              LOG.finest(
                  () ->
                      "["
                          + DEVICE_EVENT_STREAMER_NAME
                          + "] Scheduling sync for device (eventName="
                          + eventName
                          + ", deviceId="
                          + deviceId
                          + ") for: "
                          + getProtocolInstanceUri());
              queueDeviceIdForSync(deviceId);
              break;
            }

          case "end_device.delete":
            {
              String devEuiToRemove =
                  ttsDeviceMap.get().entrySet().stream()
                      .filter(entry -> deviceId.equals(entry.getValue().getIds().getDeviceId()))
                      .map(Map.Entry::getKey)
                      .findFirst()
                      .orElse(null);
              if (devEuiToRemove != null) {
                LOG.finest(
                    () ->
                        "["
                            + DEVICE_EVENT_STREAMER_NAME
                            + "] Deleting device (eventName="
                            + eventName
                            + ", devEui="
                            + devEuiToRemove
                            + ", deviceId="
                            + deviceId
                            + ") for: "
                            + getProtocolInstanceUri());
                ignoreDevEuiSet.remove(devEuiToRemove);
              }
              ttsDeviceMap
                  .get()
                  .entrySet()
                  .removeIf(entry -> deviceId.equals(entry.getValue().getIds().getDeviceId()));
              break;
            }
        }
      }
    } catch (StatusRuntimeException ex) {
      if (!GrpcRetryingRunner.isNonRetryableCode(ex) && isStreamEstablished) {
        throw new StreamBreakSignalException(
            "[" + DEVICE_EVENT_STREAMER_NAME + "] ended unexpectedly.", ex);
      } else {
        throw ex;
      }
    }

    throw new StreamBreakSignalException(
        "[" + DEVICE_EVENT_STREAMER_NAME + "] ended normally.", null);
  }

  private void performBulkDeviceSync(ManagedChannel channel) throws Exception {
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    if (apiKey.isEmpty() || applicationId.isEmpty()) {
      throw new IllegalStateException("Missing API key or application ID");
    }

    Metadata headers = new Metadata();
    Metadata.Key<String> AUTHORIZATION_HEADER =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    headers.put(AUTHORIZATION_HEADER, "Bearer " + apiKey.get());

    EndDeviceRegistryGrpc.EndDeviceRegistryBlockingStub stub =
        EndDeviceRegistryGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(GRPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    Map<String, EndDeviceOuterClass.EndDevice> map = new HashMap<>();
    int page = 1;
    boolean hasMore = true;

    while (hasMore) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("[" + BULK_SYNC_RUNNER_NAME + "] interrupted");
      }

      EndDeviceOuterClass.ListEndDevicesRequest request =
          EndDeviceOuterClass.ListEndDevicesRequest.newBuilder()
              .setApplicationIds(
                  Identifiers.ApplicationIdentifiers.newBuilder()
                      .setApplicationId(applicationId.get())
                      .build())
              .setFieldMask(
                  FieldMask.newBuilder()
                      .addPaths("name")
                      .addPaths("ids")
                      .addPaths("attributes")
                      .build())
              .setLimit(GRPC_LIST_DEVICE_LIMIT)
              .setPage(page)
              .build();

      EndDeviceOuterClass.EndDevices response = stub.list(request);

      response
          .getEndDevicesList()
          .forEach(
              device -> {
                if (!isNullOrEmpty(device.getIds().getDeviceId())) {
                  String devEui = bytesToHex(device.getIds().getDevEui().toByteArray());
                  if (!isNullOrEmpty(devEui)) {
                    map.put(devEui.toUpperCase(), device);
                  }
                }
              });

      hasMore = response.getEndDevicesCount() == GRPC_LIST_DEVICE_LIMIT;
      page++;
    }

    ignoreDevEuiSet.clear();
    ttsDeviceMap.set(new ConcurrentHashMap<>(map));

    LOG.fine(
        () ->
            "["
                + BULK_SYNC_RUNNER_NAME
                + "] Synced TTS device map {size="
                + map.size()
                + "} for URI: "
                + getProtocolInstanceUri());

    mergeDiscoveredDevices();
  }

  private void performSingleDeviceSync(ManagedChannel channel) throws Exception {

    String deviceId = deviceIdQueue.peek();

    if (isNullOrEmpty(deviceId)) {
      return;
    }

    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    if (apiKey.isEmpty() || applicationId.isEmpty()) {
      throw new IllegalStateException("Missing API key or application ID");
    }

    Metadata headers = new Metadata();
    Metadata.Key<String> AUTHORIZATION_HEADER =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    headers.put(AUTHORIZATION_HEADER, "Bearer " + apiKey.get());

    EndDeviceRegistryGrpc.EndDeviceRegistryBlockingStub stub =
        EndDeviceRegistryGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(GRPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    Identifiers.EndDeviceIdentifiers deviceIds =
        Identifiers.EndDeviceIdentifiers.newBuilder()
            .setDeviceId(deviceId)
            .setApplicationIds(
                Identifiers.ApplicationIdentifiers.newBuilder()
                    .setApplicationId(applicationId.get())
                    .build())
            .build();

    EndDeviceOuterClass.GetEndDeviceRequest request =
        EndDeviceOuterClass.GetEndDeviceRequest.newBuilder()
            .setEndDeviceIds(deviceIds)
            .setFieldMask(
                FieldMask.newBuilder()
                    .addPaths("name")
                    .addPaths("ids")
                    .addPaths("attributes")
                    .build())
            .build();

    try {
      EndDeviceOuterClass.EndDevice device = stub.get(request);

      if (device != null && !isNullOrEmpty(device.getIds().getDeviceId())) {
        String devEui = bytesToHex(device.getIds().getDevEui().toByteArray()).toUpperCase();
        if (!isNullOrEmpty(devEui)) {
          ttsDeviceMap.get().put(devEui, device);

          LOG.finest(
              () ->
                  "["
                      + SINGLE_DEVICE_SYNC_RUNNER_NAME
                      + "] Synced device (devEui="
                      + devEui
                      + ", deviceId="
                      + device.getIds().getDeviceId()
                      + ") for: "
                      + getProtocolInstanceUri());

          mergeDiscoveredDevice(devEui, device);
        }
      }
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() != Status.Code.NOT_FOUND) {
        Status status = ex.getStatus();
        String errorDetail =
            String.format(
                "{code=%s, description=%s}",
                status.getCode(), status.getDescription() != null ? status.getDescription() : "-");
        LOG.log(
            Level.WARNING,
            String.format(
                "["
                    + SINGLE_DEVICE_SYNC_RUNNER_NAME
                    + "] Device sync (deviceId=%s) failed because of a gRPC connection error %s for: %s",
                deviceId,
                errorDetail,
                getGRPCClientUri()),
            ex);
        throw ex;
      }
    }

    boolean acquired = false;
    try {
      singleDeviceSyncLock.lockInterruptibly();
      acquired = true;

      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("[" + SINGLE_DEVICE_SYNC_RUNNER_NAME + "] interrupted");
      }

      deviceIdQueue.take();
      singleDeviceSyncRunner = null;
      startSingleDeviceSync();
    } finally {
      if (acquired) {
        singleDeviceSyncLock.unlock();
      }
    }
  }

  private void queueDeviceIdForSync(String deviceId) throws InterruptedException {
    if (!isNullOrEmpty(deviceId)) {
      boolean acquired = false;
      try {
        singleDeviceSyncLock.lockInterruptibly();
        acquired = true;

        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("[" + DEVICE_EVENT_STREAMER_NAME + "] interrupted");
        }

        deviceIdQueue.put(deviceId);
        startSingleDeviceSync();

      } finally {
        if (acquired) {
          singleDeviceSyncLock.unlock();
        }
      }
    }
  }

  private Optional<Asset<?>> createAsset(
      String devEUI, String assetTypeName, EndDeviceOuterClass.EndDevice device) {
    if (isNullOrEmpty(devEUI) || isNullOrEmpty(assetTypeName) || device == null) {
      return Optional.empty();
    }

    DeviceRecord record = new DeviceRecord();
    record.setDevEUI(devEUI);
    record.setAssetTypeName(assetTypeName);
    Optional.of(device.getName()).filter(name -> !name.isEmpty()).ifPresent(record::setName);

    return Optional.of(assetTypeName)
        .flatMap(name -> resolveAssetClass(name, device))
        .flatMap(clazz -> instantiateAsset(clazz, device))
        .flatMap(asset -> configureAsset(asset, record));
  }

  private Optional<Class<? extends Asset<?>>> resolveAssetClass(
      String simpleClassName, EndDeviceOuterClass.EndDevice device) {
    if (isNullOrEmpty(simpleClassName) || device == null) {
      return Optional.empty();
    }

    return ValueUtil.getAssetClass(simpleClassName)
        .or(
            () -> {
              LOG.warning(
                  "Auto-discovery skipped device because of unknown asset type: '"
                      + simpleClassName
                      + "', "
                      + ttsDeviceToString(device)
                      + " for: "
                      + getProtocolInstanceUri());
              return Optional.empty();
            });
  }

  private Optional<Asset<?>> instantiateAsset(
      Class<?> clazz, EndDeviceOuterClass.EndDevice device) {
    if (clazz == null || device == null) {
      return Optional.empty();
    }

    Asset<?> asset = null;
    try {
      Constructor<?> constructor = clazz.getConstructor(String.class);
      asset = (Asset<?>) constructor.newInstance(createDeviceName(device));
    } catch (ReflectiveOperationException e) {
      LOG.log(
          Level.WARNING,
          "Auto-discovery failed to create asset '"
              + ttsDeviceToString(device)
              + "' for: "
              + getProtocolInstanceUri(),
          e);
    }
    return Optional.ofNullable(asset);
  }

  private Map<String, Asset<?>> createAssetsFromDiscoveredDevices(
      Map<String, EndDeviceOuterClass.EndDevice> discoveredDeviceMap) {
    return discoveredDeviceMap.entrySet().stream()
        .filter(entry -> duplicateAssetCheck(entry.getKey()))
        .filter(
            entry ->
                !isNullOrEmpty(
                    entry.getValue().getAttributesMap().get(THE_THINGS_STACK_ASSET_TYPE_TAG)))
        .map(
            entry -> {
              String devEui = entry.getKey();
              Optional<Asset<?>> assetOptional =
                  createAsset(
                      devEui,
                      entry.getValue().getAttributesMap().get(THE_THINGS_STACK_ASSET_TYPE_TAG),
                      entry.getValue());
              return assetOptional.map(asset -> new AbstractMap.SimpleEntry<>(devEui, asset));
            })
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private void mergeDiscoveredDevices() throws InterruptedException {
    Map<String, EndDeviceOuterClass.EndDevice> deviceMap = ttsDeviceMap.get();
    Map<String, Asset<?>> assetMap = createAssetsFromDiscoveredDevices(deviceMap);

    for (Map.Entry<String, EndDeviceOuterClass.EndDevice> entry : deviceMap.entrySet()) {

      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }

      if (assetMap.containsKey(entry.getKey())) {
        ignoreDevEuiSet.remove(entry.getKey());

        try {
          assetService.mergeAsset(assetMap.get(entry.getKey()));
        } catch (Exception e) {
          LOG.log(
              Level.WARNING,
              "Asset merge failed '"
                  + assetMap.get(entry.getKey())
                  + "' for: "
                  + getProtocolInstanceUri(),
              e);
        }
      } else {
        ignoreDevEuiSet.add(entry.getKey());
      }
    }
  }

  private void mergeDiscoveredDevice(String devEui, EndDeviceOuterClass.EndDevice device) {
    Map<String, EndDeviceOuterClass.EndDevice> deviceMap = Collections.singletonMap(devEui, device);
    Map<String, Asset<?>> assetMap = createAssetsFromDiscoveredDevices(deviceMap);

    if (assetMap.size() == 1) {
      ignoreDevEuiSet.remove(devEui);

      try {
        assetService.mergeAsset(assetMap.get(devEui));
      } catch (Exception e) {
        LOG.log(
            Level.WARNING,
            "Asset merge failed '" + assetMap.get(devEui) + "' for: " + getProtocolInstanceUri(),
            e);
      }
    } else {
      ignoreDevEuiSet.add(devEui);
    }
  }

  private String createDeviceName(EndDeviceOuterClass.EndDevice device) {
    return Optional.ofNullable(device)
        .map(
            d ->
                Optional.of(d.getName())
                    .filter(name -> !isNullOrEmpty(name))
                    .orElseGet(
                        () ->
                            Optional.of(d.getIds())
                                .map(ids -> bytesToHex(ids.getDevEui().toByteArray()))
                                .filter(devEui -> !isNullOrEmpty(devEui))
                                .orElse("")))
        .orElse("");
  }

  private Identifiers.EndDeviceIdentifiers extractDeviceIds(EventsOuterClass.Event event) {
    for (Identifiers.EntityIdentifiers id : event.getIdentifiersList()) {
      if (id.hasDeviceIds()) {
        return id.getDeviceIds();
      }
    }
    return null;
  }

  private static String bytesToHex(byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString();
  }

  private String ttsDeviceToString(EndDeviceOuterClass.EndDevice device) {
    return Optional.ofNullable(device)
        .map(
            d ->
                "TTS device{"
                    + "devEUI='"
                    + bytesToHex(d.getIds().getDevEui().toByteArray())
                    + '\''
                    + ", deviceId='"
                    + d.getIds().getDeviceId()
                    + '\''
                    + ", name='"
                    + d.getName()
                    + '\''
                    + "}")
        .orElse("");
  }

  private void initDeviceMap() {
    Optional<String> host = getAgent().getHost().map(String::trim);
    int port = getPort();
    boolean isSecureGRPC = isSecureGRPC();
    Optional<String> apiKey = getAgent().getApiKey().map(String::trim);
    Optional<String> applicationId = getAgent().getApplicationId().map(String::trim);

    if (host.isEmpty() || apiKey.isEmpty() || applicationId.isEmpty()) {
      return;
    }

    ManagedChannel channel = createChannel(host.get(), port, isSecureGRPC, false);

    Metadata headers = new Metadata();
    Metadata.Key<String> AUTHORIZATION_HEADER =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    headers.put(AUTHORIZATION_HEADER, "Bearer " + apiKey.get());

    try {
      EndDeviceRegistryGrpc.EndDeviceRegistryBlockingStub stub =
          EndDeviceRegistryGrpc.newBlockingStub(channel)
              .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
              .withDeadlineAfter(GRPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

      Map<String, EndDeviceOuterClass.EndDevice> map = new HashMap<>();
      int page = 1;
      boolean hasMore = true;

      while (hasMore) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }

        EndDeviceOuterClass.ListEndDevicesRequest request =
            EndDeviceOuterClass.ListEndDevicesRequest.newBuilder()
                .setApplicationIds(
                    Identifiers.ApplicationIdentifiers.newBuilder()
                        .setApplicationId(applicationId.get())
                        .build())
                .setFieldMask(
                    FieldMask.newBuilder()
                        .addPaths("name")
                        .addPaths("ids")
                        .addPaths("attributes")
                        .build())
                .setLimit(GRPC_LIST_DEVICE_LIMIT)
                .setPage(page)
                .build();

        EndDeviceOuterClass.EndDevices response = stub.list(request);

        response
            .getEndDevicesList()
            .forEach(
                device -> {
                  if (!isNullOrEmpty(device.getIds().getDeviceId())) {
                    String devEui = bytesToHex(device.getIds().getDevEui().toByteArray());
                    if (!isNullOrEmpty(devEui)) {
                      map.put(devEui.toUpperCase(), device);
                    }
                  }
                });

        hasMore = response.getEndDevicesCount() == GRPC_LIST_DEVICE_LIMIT;
        page++;
      }

      ignoreDevEuiSet.clear();
      ttsDeviceMap.set(new ConcurrentHashMap<>(map));

    } catch (StatusRuntimeException e) {
      Status status = e.getStatus();
      String errorDetail =
          String.format(
              "{code=%s, description=%s}",
              status.getCode(), status.getDescription() != null ? status.getDescription() : "-");
      LOG.log(
          Level.WARNING,
          String.format(
              "CSV import failed because of a gRPC connection error %s for: %s",
              errorDetail, getProtocolInstanceUri()),
          e);
      throw e;
    } finally {
      if (channel != null) {
        channel.shutdown();
        try {
          if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
          }
        } catch (InterruptedException ex) {
          channel.shutdownNow();
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
