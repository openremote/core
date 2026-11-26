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
package org.openremote.agent.protocol.knx;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.openremote.container.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import tuwien.auto.calimero.*;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.process.ProcessCommunicator;
import tuwien.auto.calimero.process.ProcessCommunicatorImpl;
import tuwien.auto.calimero.process.ProcessEvent;
import tuwien.auto.calimero.process.ProcessListener;

public class KNXConnection implements NetworkLinkListener, ProcessListener {

  protected ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;

  protected static final int INITIAL_RECONNECT_DELAY_MILLIS = 1000;
  protected static final int MAX_RECONNECT_DELAY_MILLIS = 60000;
  protected static final int RECONNECT_BACKOFF_MULTIPLIER = 2;
  protected ScheduledFuture<?> reconnectTask;
  protected int reconnectDelayMilliseconds = INITIAL_RECONNECT_DELAY_MILLIS;
  protected final List<Consumer<ConnectionStatus>> connectionStatusConsumers = new ArrayList<>();
  protected final ScheduledExecutorService scheduledExecutorService;
  protected final String gatewayAddress;
  private final int gatewayPort;
  private final boolean natMode;
  private final String messageSourceAddress;
  private final String bindAddress;
  protected final int port = 3671;
  protected final boolean routingMode;
  protected KNXNetworkLink knxLink;
  protected ProcessCommunicator processCommunicator;
  protected final Map<GroupAddress, byte[]> groupAddressStateMap = new HashMap<>();
  protected final Map<GroupAddress, List<Pair<StateDP, Consumer<Object>>>> groupAddressConsumerMap =
      new HashMap<>();

  private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, KNXConnection.class);

  public KNXConnection(
      String gatewayAddress,
      String bindAddress,
      Integer gatewayPort,
      String messageSourceAddress,
      boolean routingMode,
      boolean natMode) {
    this.gatewayAddress = gatewayAddress;
    this.scheduledExecutorService = Container.SCHEDULED_EXECUTOR;
    this.routingMode = routingMode;
    this.bindAddress = bindAddress;
    this.gatewayPort = gatewayPort;
    this.natMode = natMode;
    this.messageSourceAddress = messageSourceAddress;
  }

  public synchronized void connect() {
    if (connectionStatus == ConnectionStatus.CONNECTED
        || connectionStatus == ConnectionStatus.CONNECTING) {
      LOG.finest("Already connected or connection in progress");
      return;
    }

    onConnectionStatusChanged(ConnectionStatus.CONNECTING);

    InetSocketAddress localEndPoint;
    InetSocketAddress remoteEndPoint = new InetSocketAddress(this.gatewayAddress, this.gatewayPort);
    try {
      TPSettings tpSettings = new TPSettings(new IndividualAddress(this.messageSourceAddress));
      if (StringUtils.isNotBlank(this.bindAddress)) {
        localEndPoint = new InetSocketAddress(this.bindAddress, 0);
      } else {
        InetAddress localHost = InetAddress.getLoopbackAddress();
        localEndPoint = new InetSocketAddress(localHost, 0);
      }
      if (!routingMode) {
        knxLink =
            KNXNetworkLinkIP.newTunnelingLink(
                localEndPoint, remoteEndPoint, this.natMode, tpSettings);
      } else {
        knxLink =
            KNXNetworkLinkIP.newRoutingLink(
                localEndPoint.getAddress(), remoteEndPoint.getAddress(), tpSettings);
      }

      if (knxLink.isOpen()) {
        LOG.fine("Successfully connected to: " + gatewayAddress + ":" + port);
        processCommunicator = new ProcessCommunicatorImpl(knxLink);
        processCommunicator.addProcessListener(this);
        knxLink.addLinkListener(this);

        reconnectTask = null;
        reconnectDelayMilliseconds = INITIAL_RECONNECT_DELAY_MILLIS;
        onConnectionStatusChanged(ConnectionStatus.CONNECTED);

        // Get the values of all registered group addresses
        LOG.finest("Initializing group address values");
        synchronized (groupAddressConsumerMap) {
          groupAddressConsumerMap.forEach(
              (groupAddress, datapointConsumerList) -> {
                if (!datapointConsumerList.isEmpty()) {
                  // Take first data point for the group address and request the value
                  Pair<StateDP, Consumer<Object>> datapointConsumer =
                      datapointConsumerList.getFirst();
                  getGroupAddressValue(
                      datapointConsumer.key.getMainAddress(), datapointConsumer.key.getPriority());
                }
              });
        }

      } else {
        LOG.log(Level.INFO, "Connection error");
        // Failed to connect so schedule reconnection attempt
        scheduleReconnect();
      }
    } catch (final KNXException | InterruptedException e) {
      LOG.log(Level.INFO, "Connection error", e.getMessage());
      scheduleReconnect();
    }
  }

  public synchronized void disconnect() {
    if (connectionStatus == ConnectionStatus.DISCONNECTING
        || connectionStatus == ConnectionStatus.DISCONNECTED) {
      LOG.finest("Already disconnecting or disconnected");
      return;
    }

    LOG.finest("Disconnecting");
    onConnectionStatusChanged(ConnectionStatus.DISCONNECTING);
    if (processCommunicator != null) {
      processCommunicator.detach();
    }
    if (knxLink != null) {
      knxLink.removeLinkListener(this);
      knxLink.close();
      knxLink = null;
    }
    onConnectionStatusChanged(ConnectionStatus.DISCONNECTED);
  }

  public synchronized void addConnectionStatusConsumer(
      Consumer<ConnectionStatus> connectionStatusConsumer) {
    if (!connectionStatusConsumers.contains(connectionStatusConsumer)) {
      connectionStatusConsumers.add(connectionStatusConsumer);
    }
  }

  public synchronized void removeConnectionStatusConsumer(
      Consumer<ConnectionStatus> connectionStatusConsumer) {
    connectionStatusConsumers.remove(connectionStatusConsumer);
  }

  protected synchronized void onConnectionStatusChanged(ConnectionStatus connectionStatus) {
    this.connectionStatus = connectionStatus;

    connectionStatusConsumers.forEach(consumer -> consumer.accept(connectionStatus));
  }

  public void sendCommand(Datapoint datapoint, Optional<Object> value) {
    try {
      if (this.connectionStatus == ConnectionStatus.CONNECTED && value.isPresent()) {
        LOG.finest("Sending to KNX action datapoint '" + datapoint + "': " + value);
        Object val = value.get();
        DPTXlator translator = TypeMapper.toDPTXlator(datapoint, val);
        processCommunicator.write(datapoint.getMainAddress(), translator);
      }
    } catch (KNXAckTimeoutException e) {
      LOG.log(Level.INFO, "Failed to send KNX value: " + datapoint + " : " + value, e);
      onConnectionError();
    } catch (Exception e) {
      LOG.severe(e.getMessage());
    }
  }

  /** A group address has changed on the KNX network so notify consumers */
  @Override
  public void groupWrite(final ProcessEvent e) {
    onGroupAddressUpdated(e.getDestination(), e.getASDU());
  }

  protected void onGroupAddressUpdated(GroupAddress groupAddress, byte[] value) {
    synchronized (groupAddressStateMap) {
      // Update the state map and notify consumers
      groupAddressStateMap.compute(groupAddress, (ga, oldValue) -> value);
    }

    synchronized (groupAddressConsumerMap) {
      groupAddressConsumerMap.computeIfPresent(
          groupAddress,
          (ga, datapointAndConsumerList) -> {
            datapointAndConsumerList.forEach(
                datapointAndConsumer -> {
                  StateDP datapoint = datapointAndConsumer.key;
                  Consumer<Object> consumer = datapointAndConsumer.value;
                  updateConsumer(value, datapoint, consumer);
                });

            return datapointAndConsumerList;
          });
    }
  }

  @Override
  public void groupReadRequest(final ProcessEvent e) {
    // RT: From description of super method I don't think we should update internal state in
    // response to this but could be wrong
    // groupWrite(e);
  }

  @Override
  public void groupReadResponse(final ProcessEvent e) {
    groupWrite(e);
  }

  @Override
  public void detached(final DetachEvent e) {
    LOG.log(Level.INFO, "KNX link detached", e.getSource());
  }

  @Override
  public void indication(FrameEvent e) {}

  @Override
  public void linkClosed(CloseEvent e) {
    LOG.log(Level.INFO, "KNX link closed", e.getReason());
    // RT: Is this called when deliberately disconnecting or just when an error occurs?
    onConnectionError();
  }

  @Override
  public void confirmation(FrameEvent e) {}

  protected void onConnectionError() {
    onConnectionStatusChanged(ConnectionStatus.ERROR);
    processCommunicator.detach();
    if (knxLink != null) {
      knxLink.removeLinkListener(this);
      knxLink.close();
    }
    knxLink = null;

    // Clear out the group address states
    List<GroupAddress> groupAddresses =
        Arrays.asList(groupAddressStateMap.keySet().toArray(new GroupAddress[0]));
    groupAddresses.forEach(groupAddress -> onGroupAddressUpdated(groupAddress, null));

    scheduleReconnect();
  }

  /** Add a consumer for the specified {@link StateDP}. */
  public void addDatapointValueConsumer(StateDP datapoint, Consumer<Object> consumer) {
    synchronized (groupAddressConsumerMap) {
      List<Pair<StateDP, Consumer<Object>>> groupAddressConsumers =
          groupAddressConsumerMap.computeIfAbsent(
              datapoint.getMainAddress(), groupAddress -> new ArrayList<>());

      groupAddressConsumers.add(new Pair<>(datapoint, consumer));

      // Look for existing value for this GA
      synchronized (groupAddressStateMap) {
        groupAddressStateMap.compute(
            datapoint.getMainAddress(),
            (groupAddress, groupValue) -> {
              if (groupValue == null) {
                // State not available for this group address so request it
                getGroupAddressValue(datapoint.getMainAddress(), datapoint.getPriority());
              } else {
                updateConsumer(groupValue, datapoint, consumer);
              }

              return groupValue;
            });
      }
    }
  }

  /**
   * Remove a consumer for the specified {@link StateDP}.
   *
   * <p><b>NOTE: The {@link StateDP} must be the same instance as supplied at registration.</b>
   */
  public void removeDatapointValueConsumer(StateDP datapoint) {
    synchronized (groupAddressConsumerMap) {
      groupAddressConsumerMap.computeIfPresent(
          datapoint.getMainAddress(),
          (groupAddress, datapointConsumerList) -> {
            if (datapointConsumerList.removeIf(
                datapointConsumer -> datapointConsumer.key == datapoint)) {
              if (datapointConsumerList.isEmpty()) {
                datapointConsumerList = null;
              }
            }
            return datapointConsumerList;
          });
    }
  }

  protected void getGroupAddressValue(GroupAddress groupAddress, Priority priority) {
    if (knxLink == null || !knxLink.isOpen()) {
      LOG.finest("Cannot send read request not currently connected: " + groupAddress);
      return;
    }

    try {
      LOG.finest("Sending read request to KNX group address: " + groupAddress);
      this.knxLink.sendRequest(
          groupAddress, priority, DataUnitBuilder.createLengthOptimizedAPDU(0x00, null));
    } catch (Exception e) {
      LOG.log(Level.INFO, "Error sending KNX read request for group address: " + groupAddress, e);
    }
  }

  protected void updateConsumer(byte[] data, StateDP datapoint, Consumer<Object> consumer) {
    Object value = null;

    if (data != null) {
      try {
        value = TypeMapper.toValue(datapoint, data);
      } catch (Exception ex) {
        LOG.log(
            Level.WARNING, "Couldn't translate Group address value to DPT type: " + datapoint, ex);
      }
    }

    consumer.accept(value);
  }

  protected synchronized void scheduleReconnect() {
    if (reconnectTask != null) {
      return;
    }

    onConnectionStatusChanged(ConnectionStatus.WAITING);

    if (reconnectDelayMilliseconds < MAX_RECONNECT_DELAY_MILLIS) {
      reconnectDelayMilliseconds *= RECONNECT_BACKOFF_MULTIPLIER;
      reconnectDelayMilliseconds = Math.min(MAX_RECONNECT_DELAY_MILLIS, reconnectDelayMilliseconds);
    }

    LOG.finest("Scheduling reconnection in '" + reconnectDelayMilliseconds + "' milliseconds");

    reconnectTask =
        scheduledExecutorService.schedule(
            () -> {
              synchronized (KNXConnection.this) {
                reconnectTask = null;
                // Attempt to reconnect if not disconnecting
                if (connectionStatus != ConnectionStatus.DISCONNECTING
                    && connectionStatus != ConnectionStatus.DISCONNECTED) {
                  connect();
                }
              }
            },
            reconnectDelayMilliseconds,
            TimeUnit.MILLISECONDS);
  }

  @Override
  public String toString() {
    return KNXConnection.class.getSimpleName()
        + "{"
        + "gatewayAddress='"
        + gatewayAddress
        + '\''
        + ", gatewayPort="
        + gatewayPort
        + '}';
  }
}
