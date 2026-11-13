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
package org.openremote.agent.protocol.velbus.device;

import static org.openremote.agent.protocol.velbus.AbstractVelbusProtocol.LOG;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openremote.agent.protocol.velbus.VelbusNetwork;
import org.openremote.agent.protocol.velbus.VelbusPacket;
import org.openremote.model.util.TextUtil;

public class VelbusDevice {

  public static final int MAX_INITIALISATION_ATTEMPTS = 5;
  public static int INITIALISATION_TIMEOUT_MILLISECONDS = 10000;
  protected int baseAddress;
  protected int[] subAddresses = new int[4]; // Max of 4 sub-addresses
  protected final Map<String, Object> devicePropertyCache = new HashMap<>();
  protected final Map<String, List<Consumer<Object>>> propertyValueConsumers =
      new ConcurrentHashMap<>();
  protected VelbusNetwork velbusNetwork;
  protected FeatureProcessor[] featureProcessors;
  protected boolean initialised;
  protected boolean initialisationFailed;
  protected int initialisationAttempts;
  protected VelbusDeviceType deviceType;
  protected Future<?> initialisationTask;

  public VelbusDevice(int baseAddress, VelbusNetwork velbusNetwork) {
    this.baseAddress = baseAddress;
    this.velbusNetwork = velbusNetwork;
  }

  public int getBaseAddress() {
    return baseAddress;
  }

  public int[] getSubAddresses() {
    return subAddresses;
  }

  public VelbusDeviceType getDeviceType() {
    return deviceType;
  }

  public int getAddress(int index) {
    index = Math.max(0, Math.min(5, index));
    return index == 0 ? getBaseAddress() : subAddresses[index - 1];
  }

  public int getAddressIndex(int address) {
    if (baseAddress == address) {
      return 0;
    }
    for (int i = 0; i < subAddresses.length; i++) {
      if (subAddresses[i] == address) {
        return i + 1;
      }
    }

    return -1;
  }

  private void setDeviceType(VelbusDeviceType deviceType) {
    this.deviceType = deviceType;
    featureProcessors = deviceType.getFeatureProcessors();
  }

  private void cancelInitialisationTask(boolean interrupt) {
    if (initialisationTask != null) {
      initialisationTask.cancel(interrupt);
      initialisationTask = null;
    }
  }

  public void reset() {
    cancelInitialisationTask(true);
    devicePropertyCache.clear();
    initialised = false;
    initialisationAttempts = 0;
    deviceType = null;
    Arrays.fill(subAddresses, 0);
  }

  /** Attempt initialization of this device */
  public void initialise() {
    synchronized (this) {
      if (isInitialised()) {
        return;
      }

      if (initialisationTask != null) {
        LOG.finest("Initialization already in progress");
        return;
      }

      if (initialisationFailed) {
        initialisationAttempts = 0;
        initialisationFailed = false;
        LOG.finest("Re-attempting device initialization");
      }

      LOG.info("Initialization starting: " + getBaseAddress());
      initialisationTask =
          velbusNetwork
              .getExecutorService()
              .scheduleWithFixedDelay(
                  this::doInitialisation,
                  0,
                  INITIALISATION_TIMEOUT_MILLISECONDS,
                  TimeUnit.MILLISECONDS);
    }
  }

  private void doInitialisation() {
    synchronized (this) {
      if (initialisationAttempts >= MAX_INITIALISATION_ATTEMPTS) {
        LOG.fine(
            "Initialization failed - Device has reached maximum initialization attempts: "
                + getBaseAddress());
        initialisationFailed = true;
        cancelInitialisationTask(false);
        return;
      }

      initialisationAttempts++;

      // Send/Resend the packets needed to initialize the device
      velbusNetwork.sendPackets(createModuleTypePacket(baseAddress));
    }
  }

  public boolean isInitialised() {
    return initialised;
  }

  public boolean isInitialisedAndValid() {
    return isInitialised() && deviceType != VelbusDeviceType.UNKNOWN;
  }

  /**
   * Indicates that the device is now initialized and if so it updates the flag and performs post
   * initialization tasks
   */
  private void onInitialised() {
    synchronized (this) {
      if (isInitialised()) {
        return;
      }

      cancelInitialisationTask(true);
      LOG.info("Device initialized: " + getBaseAddress());
      this.initialised = true;
    }

    // Send the status request packets for this device
    if (isInitialisedAndValid() && featureProcessors != null) {

      LOG.finest("Sending module status request packets");
      velbusNetwork.sendPackets(
          Arrays.stream(featureProcessors)
              .flatMap(processor -> processor.getStatusRequestPackets(this).stream())
              .distinct()
              .toArray(VelbusPacket[]::new));
    }
  }

  public void addPropertyValueConsumer(String property, Consumer<Object> propertyValueConsumer) {
    if (property.isEmpty()) {
      return;
    }

    List<Consumer<Object>> consumers =
        propertyValueConsumers.computeIfAbsent(property, p -> new ArrayList<>());

    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (consumers) {
      consumers.add(propertyValueConsumer);
    }

    // Push the current value of the property to the consumer
    propertyValueConsumer.accept(getPropertyValue(property));
  }

  public void removePropertyValueConsumer(String property, Consumer<Object> propertyValueConsumer) {
    if (property.isEmpty()) {
      return;
    }

    propertyValueConsumers.computeIfPresent(
        property,
        (prop, consumers) -> {
          //noinspection SynchronizationOnLocalVariableOrMethodParameter
          synchronized (consumers) {
            consumers.remove(propertyValueConsumer);
          }
          return consumers;
        });
  }

  public void removeAllPropertyValueConsumers() {
    propertyValueConsumers.forEach((prop, consumers) -> consumers.clear());
    propertyValueConsumers.clear();
  }

  public void writeProperty(String property, Object value) {
    if (!isInitialisedAndValid()) {
      LOG.finest("Ignoring property write as device is not initialized and/or it is invalid");
      return;
    }

    if (TextUtil.isNullOrEmpty(property)) {
      return;
    }

    if (featureProcessors != null) {
      for (FeatureProcessor processor : featureProcessors) {
        List<VelbusPacket> packets = processor.getPropertyWritePackets(this, property, value);
        if (packets != null) {
          velbusNetwork.sendPackets(packets.toArray(new VelbusPacket[0]));
          break;
        }
      }
    }
  }

  public void processReceivedPacket(VelbusPacket velbusPacket) {
    VelbusPacket.InboundCommand packetCommand =
        VelbusPacket.InboundCommand.fromCode(velbusPacket.getCommand());

    switch (packetCommand) {
      case UNKNOWN:
        LOG.info(
            "Packet received and ignored for device '"
                + baseAddress
                + "': "
                + Integer.toHexString(velbusPacket.getCommand())
                + " ("
                + packetCommand
                + ")");
        break;
      case MODULE_TYPE:
        // This is the basic initialization response packet for all devices
        int typeCode = velbusPacket.getTypeCode();
        setDeviceType(VelbusDeviceType.fromCode(typeCode));

        if (!deviceType.hasSubAddresses()) {
          onInitialised();
        }
        LOG.finest("Packet received and handled by device '" + baseAddress + "': " + packetCommand);
        break;
      case MODULE_SUBADDRESSES:
        if (velbusPacket.getDataSize() != 8) {
          LOG.warning("MODULE_SUBADDRESSES packet doesn't contain exactly 8 data bytes");
          break;
        }

        for (int i = 4; i < velbusPacket.getDataSize(); i++) {
          int subAddress = velbusPacket.getInt(i);
          subAddresses[i - 4] = subAddress;
          if (subAddress != 255) {
            velbusNetwork.registerSubAddress(this, subAddress);
          }
        }

        LOG.finest("Packet received and handled by device '" + baseAddress + "': " + packetCommand);
        onInitialised();
        break;
      default:
        if (!isInitialisedAndValid()) {
          return;
        }

        // Ask the feature processors to handle this packet
        boolean handled = false;

        if (featureProcessors != null) {
          handled =
              Arrays.stream(featureProcessors)
                  .anyMatch(processor -> processor.processReceivedPacket(this, velbusPacket));
        }

        handled = handled || velbusPacket.isHandled();

        if (!handled) {
          LOG.fine(
              "Packet received was not handled by device '" + baseAddress + "': " + packetCommand);
        } else {
          LOG.finest(
              "Packet received and handled by device '" + baseAddress + "': " + packetCommand);
        }
        break;
    }
  }

  void setProperty(String property, Object value) {
    property = property.toUpperCase();
    synchronized (devicePropertyCache) {
      devicePropertyCache.put(property, value);
    }

    // Notify linked consumers
    propertyValueConsumers.computeIfPresent(
        property,
        (prop, consumers) -> {
          consumers.forEach(consumer -> consumer.accept(value));
          return consumers;
        });
  }

  protected Object getPropertyValue(String propertyName) {
    return devicePropertyCache.get(propertyName);
  }

  protected boolean hasPropertyValue(String propertyName) {
    return devicePropertyCache.containsKey(propertyName);
  }

  public static VelbusPacket[] createTimeInjectionPackets() {
    Calendar c = Calendar.getInstance();
    int dst = c.get(Calendar.DST_OFFSET) > 0 ? 1 : 0;
    int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    int dom = c.get(Calendar.DAY_OF_MONTH);
    int month = c.get(Calendar.MONTH) + 1;
    int hours = c.get(Calendar.HOUR_OF_DAY);
    int mins = c.get(Calendar.MINUTE);
    int year = c.get(Calendar.YEAR);

    VelbusPacket[] packets = new VelbusPacket[3];
    // Time packet
    packets[0] =
        new VelbusPacket(
            0x00,
            VelbusPacket.OutboundCommand.REALTIME_CLOCK_SET.getCode(),
            (byte) dow,
            (byte) hours,
            (byte) mins);
    // Date packet
    packets[1] =
        new VelbusPacket(
            0x00,
            VelbusPacket.OutboundCommand.REALTIME_DATE_SET.getCode(),
            (byte) dom,
            (byte) month,
            (byte) (year >>> 8),
            (byte) year);
    // DST packet
    packets[2] =
        new VelbusPacket(
            0x00, VelbusPacket.OutboundCommand.DAYLIGHT_SAVING_SET.getCode(), (byte) dst);
    return packets;
  }

  public static VelbusPacket createModuleTypePacket(int baseAddress) {
    return new VelbusPacket(baseAddress, VelbusPacket.PacketPriority.LOW, 0, true);
  }
}
