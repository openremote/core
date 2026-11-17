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
package org.openremote.agent.protocol.velbus.device;

import static org.openremote.agent.protocol.velbus.AbstractVelbusProtocol.LOG;

import java.util.*;
import org.openremote.agent.protocol.velbus.VelbusPacket;
import org.openremote.model.util.EnumUtil;
import org.openremote.model.util.Pair;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.ValueType;

public class InputProcessor extends ChannelProcessor {

  protected enum PropertyType {
    CHANNEL_STATE(""),
    LED_STATE("_LED"),
    LOCK_STATE("_LOCKED"),
    LOCK_DURATION("_LOCK"),
    ENABLED_STATE("_ENABLED"),
    INVERTED_STATE("_INVERTED");

    private static final PropertyType[] values = values();
    private String propertySuffix;

    PropertyType(String propertySuffix) {
      this.propertySuffix = propertySuffix;
    }

    public String getPropertySuffix() {
      return propertySuffix;
    }

    public static Optional<PropertyType> fromPropertySuffix(String str) {
      for (PropertyType value : values) {
        if (value.getPropertySuffix().equalsIgnoreCase(str)) {
          return Optional.of(value);
        }
      }
      return Optional.empty();
    }
  }

  public enum ChannelState {
    PRESSED(0x01),
    RELEASED(0x02),
    LONG_PRESSED(0x04);

    private static final EnumSet<ChannelState> values = EnumSet.allOf(ChannelState.class);
    private final int code;

    ChannelState(int code) {
      this.code = code;
    }

    public int getCode() {
      return this.code;
    }

    public static Optional<ChannelState> fromValue(Object value) {
      if (value == null) {
        return Optional.empty();
      }

      if (ValueUtil.isBoolean(value.getClass())) {
        return fromBoolean(ValueUtil.getBoolean(value).orElse(null));
      }

      return EnumUtil.enumFromValue(ChannelState.class, value);
    }

    public static Optional<ChannelState> fromCode(int integer) {
      for (ChannelState value : values) {
        if (value.getCode() == integer) {
          return Optional.of(value);
        }
      }
      return Optional.empty();
    }

    public static Optional<ChannelState> fromBoolean(Boolean value) {
      if (value == null) {
        return Optional.of(RELEASED);
      } else if (value) {
        return Optional.of(LONG_PRESSED);
      } else {
        return Optional.of(PRESSED);
      }
    }
  }

  @Override
  public List<PropertyDescriptor> getPropertyDescriptors(VelbusDeviceType deviceType) {
    int maxChannelNumber = getMaxChannelNumber(deviceType);
    List<PropertyDescriptor> properties =
        new ArrayList<>(maxChannelNumber * PropertyType.values.length);

    for (int i = 1; i <= maxChannelNumber; i++) {
      for (PropertyType propertyType : PropertyType.values) {
        switch (propertyType) {
          case CHANNEL_STATE:
            if (deviceType != VelbusDeviceType.VMB4AN) {
              properties.add(
                  new PropertyDescriptor("ch" + i + "State", "CH" + i, "CH" + i, ValueType.TEXT));
            }
            break;
          case LED_STATE:
            if (deviceType != VelbusDeviceType.VMB4AN) {
              properties.add(
                  new PropertyDescriptor(
                      "ch" + i + "LedState",
                      "CH" + i + "LED State",
                      "CH" + i + propertyType.getPropertySuffix(),
                      ValueType.TEXT));
            }
            break;
          case LOCK_STATE:
            properties.add(
                new PropertyDescriptor(
                    "ch" + i + "Locked",
                    "CH" + i + " Locked",
                    "CH" + i + propertyType.getPropertySuffix(),
                    ValueType.BOOLEAN));
            break;
          case LOCK_DURATION:
            properties.add(
                new PropertyDescriptor(
                    "ch" + i + "LockDuration",
                    "CH" + i + " Lock Duration (s)",
                    "CH" + i + propertyType.getPropertySuffix(),
                    ValueType.NUMBER));
            break;
          case ENABLED_STATE:
            properties.add(
                new PropertyDescriptor(
                    "ch" + i + "Enabled",
                    "CH" + i + " Enabled",
                    "CH" + i + propertyType.getPropertySuffix(),
                    ValueType.BOOLEAN,
                    true));
            break;
          case INVERTED_STATE:
            properties.add(
                new PropertyDescriptor(
                    "ch" + i + "Inverted",
                    "CH" + i + " Inverted",
                    "CH" + i + propertyType.getPropertySuffix(),
                    ValueType.BOOLEAN,
                    true));
            break;
        }
      }
    }

    return properties;
  }

  @Override
  public List<VelbusPacket> getStatusRequestPackets(VelbusDevice device) {

    // Initialize basic state as disabled addresses don't broadcast this info
    for (int i = 1; i <= getMaxChannelNumber(device.getDeviceType()); i++) {
      device.setProperty("CH" + i, ChannelState.RELEASED);
      device.setProperty("CH" + i + "_ENABLED", false);
      device.setProperty("CH" + i + "_LOCKED", false);
      device.setProperty("CH" + i + "_INVERTED", false);
      device.setProperty("CH" + i + "_LED", LedState.OFF);
    }

    if (device.getDeviceType() == VelbusDeviceType.VMB4AN) {
      return Collections.singletonList(
          new VelbusPacket(
              device.getBaseAddress(),
              VelbusPacket.OutboundCommand.MODULE_STATUS.getCode(),
              (byte) 0xFF));
    }

    return Collections.singletonList(
        new VelbusPacket(
            device.getBaseAddress(),
            VelbusPacket.OutboundCommand.MODULE_STATUS.getCode(),
            (byte) 0x00));
  }

  @Override
  public List<VelbusPacket> getPropertyWritePackets(
      VelbusDevice device, String property, Object value) {
    return getChannelNumberAndPropertyType(device, property)
        .map(
            channelNumberAndPropertyType -> {
              List<VelbusPacket> packets = new ArrayList<>();
              int channelNumber = channelNumberAndPropertyType.key;

              if (!isChannelEnabled(device, channelNumber)) {
                LOG.info(
                    "Write request to disabled channel so ignoring on device: "
                        + device.getBaseAddress());
                return packets;
              }

              switch (channelNumberAndPropertyType.value) {
                case CHANNEL_STATE:
                  if (device.getDeviceType() == VelbusDeviceType.VMB4AN) {
                    return null;
                  }
                  ChannelState.fromValue(value)
                      .ifPresent(
                          channelState -> {
                            packets.addAll(
                                getChannelStatePackets(
                                    device,
                                    ChannelProcessor.getAddressForChannel(device, channelNumber),
                                    channelNumber,
                                    channelState));
                            // Push value into device cache as well
                            device.setProperty(property, channelState);
                          });
                  break;
                case LED_STATE:
                  if (device.getDeviceType() == VelbusDeviceType.VMB4AN) {
                    return null;
                  }
                  EnumUtil.enumFromValue(LedState.class, value)
                      .ifPresent(
                          ledState -> {
                            packets.addAll(
                                getLedStatePackets(
                                    device,
                                    ChannelProcessor.getAddressForChannel(device, channelNumber),
                                    channelNumber,
                                    ledState));
                            // Push value into device cache as well
                            device.setProperty(property, ledState);
                          });
                  break;
                case LOCK_STATE:
                  ValueUtil.getBoolean(value)
                      .ifPresent(
                          locked ->
                              packets.addAll(
                                  getLockStatePackets(device, channelNumber, locked ? -1 : 0)));
                  break;
                case LOCK_DURATION:
                  ValueUtil.getIntegerCoerced(value)
                      .ifPresent(
                          duration -> {
                            packets.addAll(getLockStatePackets(device, channelNumber, duration));
                            // Push value into device cache as well
                            device.setProperty(property, duration);
                          });
                  break;
              }

              return packets;
            })
        .orElse(null);
  }

  @Override
  public boolean processReceivedPacket(VelbusDevice device, VelbusPacket packet) {
    VelbusPacket.InboundCommand packetCommand =
        VelbusPacket.InboundCommand.fromCode(packet.getCommand());

    switch (packetCommand) {
      case MODULE_STATUS:
        // Get start channel
        int startChannel = ChannelProcessor.getStartChannelNumber(device, packet.getAddress());

        if (startChannel < 0 || startChannel > getMaxChannelNumber(device.getDeviceType())) {
          return false;
        }

        // Extract channel info
        // Update each of the 8 channels for this address
        int statusByte;
        int lockByte;
        int enabledByte = 0xFF;
        int invertedByte = 0;

        // VMBMETEO and VMB4AN handle this differently to other devices
        if (device.getDeviceType() == VelbusDeviceType.VMBMETEO
            || device.getDeviceType() == VelbusDeviceType.VMB4AN) {
          statusByte = packet.getByte(1) & 0xFF;
          lockByte = packet.getByte(2) & 0xFF;
        } else {
          statusByte = packet.getByte(1) & 0xFF;
          enabledByte = packet.getByte(2) & 0xFF;
          invertedByte = packet.getByte(3) & 0xFF;
          lockByte = packet.getByte(4) & 0xFF;
        }

        for (int i = startChannel; i < startChannel + 8; i++) {
          ChannelState state =
              (statusByte & 0x01) == 1 ? ChannelState.PRESSED : ChannelState.RELEASED;
          boolean enabled = (enabledByte & 0x01) == 1;
          boolean inverted = (invertedByte & 0x01) == 0;
          boolean locked = (lockByte & 0x01) == 1;
          statusByte = statusByte >>> 1;
          enabledByte = enabledByte >>> 1;
          invertedByte = invertedByte >>> 1;
          lockByte = lockByte >>> 1;

          // Push to device cache
          device.setProperty("CH" + i, state);
          device.setProperty("CH" + i + "_ENABLED", enabled);
          device.setProperty("CH" + i + "_LOCKED", locked);
          device.setProperty("CH" + i + "_INVERTED", inverted);
        }

        // Note this packet command is used by several processors so don't return true
        packet.setHandled(true);
        return false;
      case PUSH_BUTTON_STATUS:
        startChannel = ChannelProcessor.getStartChannelNumber(device, packet.getAddress());

        if (startChannel < 0
            || startChannel
                > Math.max(1, ChannelProcessor.getMaxChannelNumber(device.getDeviceType()) - 7)) {
          return false;
        }

        // Update each of the 8 input channels for this address
        int pressedByte = packet.getByte(1) & 0xFF;
        int releasedByte = packet.getByte(2) & 0xFF;
        int longPressedByte = packet.getByte(3) & 0xFF;

        for (int i = startChannel; i < startChannel + 8; i++) {
          if ((pressedByte & 0x01) == 1) {
            device.setProperty("CH" + i, ChannelState.PRESSED);
          } else if ((releasedByte & 0x01) == 1) {
            device.setProperty("CH" + i, ChannelState.RELEASED);
          } else if ((longPressedByte & 0x01) == 1) {
            device.setProperty("CH" + i, ChannelState.LONG_PRESSED);
          }

          pressedByte = pressedByte >>> 1;
          releasedByte = releasedByte >>> 1;
          longPressedByte = longPressedByte >>> 1;
        }
        return true;
      case LED_STATUS:
        {
          startChannel = ChannelProcessor.getStartChannelNumber(device, packet.getAddress());

          if (startChannel < 0 | startChannel < getMaxChannelNumber(device.getDeviceType()) - 8) {
            return false;
          }

          byte onByte = packet.getByte(1);
          byte slowByte = packet.getByte(2);
          byte fastByte = packet.getByte(3);

          for (int i = 0; i < 8; i++) {
            boolean on = ((onByte >> i) & 0x01) == 1;
            boolean slow = ((slowByte >> i) & 0x01) == 1;
            boolean fast = ((fastByte >> i) & 0x01) == 1;
            String propertyName = "CH" + startChannel + i + "_LED";

            if (on) {
              device.setProperty(propertyName, LedState.ON);
            } else if (slow && fast) {
              device.setProperty(propertyName, LedState.VERYFAST);
            } else if (slow) {
              device.setProperty(propertyName, LedState.SLOW);
            } else if (fast) {
              device.setProperty(propertyName, LedState.FAST);
            }
          }

          return true;
        }
      case LED_OFF:
      case LED_ON:
      case LED_FAST:
      case LED_SLOW:
      case LED_VERYFAST:
        {
          startChannel = ChannelProcessor.getStartChannelNumber(device, packet.getAddress());

          if (startChannel < 0 | startChannel < getMaxChannelNumber(device.getDeviceType()) - 8) {
            return false;
          }

          LedState ledState = LedState.valueOf(packetCommand.name().substring(4));
          byte ledByte = packet.getByte(1);

          for (int i = 0; i < 8; i++) {

            if (((ledByte >> i) & 0x01) == 1) {
              String propertyName = "CH" + startChannel + i + "_LED";
              device.setProperty(propertyName, ledState);
            }
          }
          return true;
        }
      default:
        return false;
    }
  }

  protected Optional<Pair<Integer, PropertyType>> getChannelNumberAndPropertyType(
      VelbusDevice device, String propertyName) {

    return getChannelNumberAndPropertySuffix(device, CHANNEL_REGEX, propertyName)
        .map(
            pair ->
                PropertyType.fromPropertySuffix(pair.value)
                    .map(propertyType -> new Pair<>(pair.key, propertyType))
                    .orElse(null));
  }

  protected static List<VelbusPacket> getChannelStatePackets(
      VelbusDevice velbusDevice, int address, int channelNumber, ChannelState channelState) {
    byte[] packetBytes = new byte[3];
    channelNumber = channelNumber % 8;
    int byteIndex =
        channelState == ChannelState.PRESSED ? 0 : channelState == ChannelState.RELEASED ? 1 : 2;
    packetBytes[byteIndex] = (byte) Math.pow(2, (channelNumber - 1));

    return Collections.singletonList(
        new VelbusPacket(
            address,
            VelbusPacket.OutboundCommand.BUTTON_STATUS.getCode(),
            VelbusPacket.PacketPriority.HIGH,
            packetBytes));
  }

  protected static List<VelbusPacket> getLedStatePackets(
      VelbusDevice velbusDevice, int address, int channelNumber, LedState ledState) {
    byte ledStateByte = (byte) (Math.pow(2, channelNumber) - 1);
    VelbusPacket.OutboundCommand ledCommand =
        VelbusPacket.OutboundCommand.valueOf("LED_" + ledState.toString());

    return Collections.singletonList(
        new VelbusPacket(
            address, ledCommand.getCode(), VelbusPacket.PacketPriority.LOW, ledStateByte));
  }

  static List<VelbusPacket> getLockStatePackets(
      VelbusDevice velbusDevice, int channelNumber, int lockDurationSeconds) {
    boolean locked = lockDurationSeconds != 0;
    byte[] packetBytes = locked ? new byte[4] : new byte[1];
    packetBytes[0] =
        velbusDevice.getDeviceType() == VelbusDeviceType.VMB4AN
            ? (byte) (channelNumber - 1)
            : (byte) channelNumber;

    if (locked) {
      lockDurationSeconds = Math.min(lockDurationSeconds, 0xFFFFFE);
      int duration =
          lockDurationSeconds > 0 ? lockDurationSeconds : 0xFFFFFF; // FFFFFF locks it permanently
      packetBytes[1] = (byte) (duration >> 16);
      packetBytes[2] = (byte) (duration >> 8);
      packetBytes[3] = (byte) (duration);
    }

    VelbusPacket.OutboundCommand command =
        locked ? VelbusPacket.OutboundCommand.LOCK : VelbusPacket.OutboundCommand.LOCK_CANCEL;

    return Collections.singletonList(
        new VelbusPacket(
            velbusDevice.getBaseAddress(),
            command.getCode(),
            VelbusPacket.PacketPriority.HIGH,
            packetBytes));
  }
}
