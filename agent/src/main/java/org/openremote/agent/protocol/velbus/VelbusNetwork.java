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
package org.openremote.agent.protocol.velbus;

import static org.openremote.agent.protocol.velbus.AbstractVelbusProtocol.LOG;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.openremote.agent.protocol.io.IOClient;
import org.openremote.agent.protocol.velbus.device.VelbusDevice;
import org.openremote.model.asset.agent.ConnectionStatus;

public class VelbusNetwork {

  protected static int DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS =
      100; // Need to throttle bus writes
  protected final Integer timeInjectionIntervalSeconds;
  protected IOClient<VelbusPacket> client;
  protected final Queue<VelbusPacket> messageQueue = new ArrayDeque<>();
  protected List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();
  protected ScheduledFuture<?> timeInjector;
  protected VelbusDevice[] devices = new VelbusDevice[254];
  protected VelbusDevice[] subAddressDevices = new VelbusDevice[254];
  protected ScheduledFuture<?> queueProcessingTask;
  protected ScheduledExecutorService executorService;
  protected final List<Consumer<ConnectionStatus>> connectionStatusConsumers = new ArrayList<>();

  public VelbusNetwork(
      IOClient<VelbusPacket> client,
      ScheduledExecutorService executorService,
      Integer timeInjectionIntervalSeconds) {
    this.client = client;
    this.executorService = executorService;
    this.timeInjectionIntervalSeconds = timeInjectionIntervalSeconds;
    client.addConnectionStatusConsumer(this::onConnectionStatusChanged);
    client.addMessageConsumer(this::onPacketReceived);
    onConnectionStatusChanged(getConnectionStatus());
    if (timeInjectionIntervalSeconds != null) {
      timeInjector =
          getExecutorService()
              .scheduleWithFixedDelay(
                  this::doTimeInjection,
                  timeInjectionIntervalSeconds,
                  timeInjectionIntervalSeconds,
                  TimeUnit.SECONDS);
    }
  }

  protected void addConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.add(connectionStatusConsumer);
    }
  }

  protected void removeConnectionStatusConsumer(
      Consumer<ConnectionStatus> connectionStatusConsumer) {
    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.remove(connectionStatusConsumer);
    }
  }

  public ScheduledExecutorService getExecutorService() {
    return this.executorService;
  }

  public void sendPackets(VelbusPacket... packets) {
    synchronized (this) {
      if (getConnectionStatus() != ConnectionStatus.CONNECTED) {
        return;
      }
    }

    synchronized (messageQueue) {
      messageQueue.addAll(Arrays.asList(packets));

      if (queueProcessingTask == null) {
        startSendingPackets();
      }
    }
  }

  public void connect() {
    if (client == null) {
      return;
    }

    client.connect();
  }

  public void disconnect() {
    scheduledTasks.forEach(task -> task.cancel(true));
    scheduledTasks.clear();

    if (client != null) {
      client.disconnect();
    }
  }

  public void close() {
    if (timeInjector != null) {
      timeInjector.cancel(true);
      timeInjector = null;
    }

    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.clear();
    }

    disconnect();

    if (client != null) {
      client.removeConnectionStatusConsumer(this::onConnectionStatusChanged);
      client.removeMessageConsumer(this::onPacketReceived);
      client = null;
    }
  }

  public ConnectionStatus getConnectionStatus() {
    return client.getConnectionStatus();
  }

  protected void onConnectionStatusChanged(ConnectionStatus status) {
    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.forEach(consumer -> consumer.accept(status));
    }

    if (status == ConnectionStatus.CONNECTED) {
      // Don't process stale messages
      synchronized (messageQueue) {
        messageQueue.clear();
      }

      // Initialize the devices
      for (VelbusDevice device : devices) {
        if (device != null) {
          device.initialise();
        }
      }
    } else {
      // Reset the devices
      for (VelbusDevice device : devices) {
        if (device != null) {
          device.reset();
        }
      }
      // Clear out sub device registrations
      Arrays.fill(subAddressDevices, null);

      synchronized (messageQueue) {
        messageQueue.clear();
        if (queueProcessingTask != null) {
          queueProcessingTask.cancel(false);
          queueProcessingTask = null;
        }
      }
    }
  }

  protected void onPacketReceived(VelbusPacket packet) {
    // Forward the packet to the device if it exists
    int address = packet.getAddress();

    if (address > 254 || address < 1) {
      return;
    }

    VelbusDevice matchingDevice = devices[address - 1];
    VelbusPacket.InboundCommand command = VelbusPacket.InboundCommand.fromCode(packet.getCommand());
    LOG.finest("Received packet " + command + " : " + packet);

    if (matchingDevice != null) {
      matchingDevice.processReceivedPacket(packet);
    } else {
      // Look for sub address device
      matchingDevice = subAddressDevices[address - 1];

      if (matchingDevice != null) {
        matchingDevice.processReceivedPacket(packet);
      }
    }
  }

  public void addPropertyValueConsumer(
      int deviceAddress, String property, Consumer<Object> propertyValueConsumer) {
    if (deviceAddress < 1 || deviceAddress > 254) {
      LOG.warning("Invalid device address: " + deviceAddress);
      return;
    }

    VelbusDevice device = getDevice(deviceAddress);
    boolean deviceExists = device != null;

    if (!deviceExists) {
      // Device hasn't been created yet so create it
      device = new VelbusDevice(deviceAddress, this);
      devices[deviceAddress - 1] = device;
    }

    device.addPropertyValueConsumer(property, propertyValueConsumer);

    if (!deviceExists && getConnectionStatus() == ConnectionStatus.CONNECTED) {
      device.initialise();
    }
  }

  public void removePropertyValueConsumer(
      int deviceAddress, String property, Consumer<Object> propertyValueConsumer) {
    if (deviceAddress < 1 || deviceAddress > 254) {
      LOG.warning("Invalid device address: " + deviceAddress);
      return;
    }

    VelbusDevice device = getDevice(deviceAddress);

    if (device != null) {
      device.removePropertyValueConsumer(property, propertyValueConsumer);
    }
  }

  protected void removeAllDevices() {
    for (VelbusDevice device : devices) {
      if (device != null) {
        device.removeAllPropertyValueConsumers();
      }
    }
  }

  public void writeProperty(int deviceAddress, String property, Object value) {
    if (getConnectionStatus() != ConnectionStatus.CONNECTED) {
      return;
    }

    if (deviceAddress < 1 || deviceAddress > 254) {
      LOG.warning("Invalid device address: " + deviceAddress);
      return;
    }

    VelbusDevice device = getDevice(deviceAddress);

    if (device != null) {
      device.writeProperty(property, value);
    }
  }

  /**
   * Used by devices that have sub addresses so they can be associated with another address as well
   * as their base address
   */
  public void registerSubAddress(VelbusDevice velbusDevice, int subAddress) {
    if (subAddress < 1 || subAddress > 254) {
      LOG.warning(
          "Invalid device subaddress '"
              + subAddress
              + "' for device: "
              + velbusDevice.getBaseAddress());
      return;
    }

    subAddressDevices[subAddress - 1] = velbusDevice;
  }

  protected VelbusDevice getDevice(int address) {
    return devices[address - 1];
  }

  protected void startSendingPackets() {
    queueProcessingTask =
        getExecutorService()
            .scheduleWithFixedDelay(
                this::doSendPacket,
                0,
                DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS,
                TimeUnit.MILLISECONDS);
  }

  protected void doSendPacket() {
    if (getConnectionStatus() != ConnectionStatus.CONNECTED) {
      return;
    }

    VelbusPacket packet;

    synchronized (messageQueue) {
      packet = messageQueue.poll();
    }

    if (packet == null) {
      queueProcessingTask.cancel(false);
      queueProcessingTask = null;
      return;
    }

    VelbusPacket.OutboundCommand command =
        VelbusPacket.OutboundCommand.fromCode(packet.getCommand());
    LOG.finest("Sending packet " + command + " : " + packet);
    client.sendMessage(packet);
  }

  public ScheduledFuture<?> scheduleTask(Runnable runnable, int delayMillis) {
    // Remove old completed tasks
    scheduledTasks.removeIf(Future::isDone);

    if (getConnectionStatus() == ConnectionStatus.CONNECTED) {
      ScheduledFuture<?> future =
          getExecutorService().schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
      scheduledTasks.add(future);
      return future;
    }

    return null;
  }

  protected void doTimeInjection() {
    sendPackets(VelbusDevice.createTimeInjectionPackets());
  }
}
