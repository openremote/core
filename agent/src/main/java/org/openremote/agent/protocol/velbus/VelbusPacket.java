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
package org.openremote.agent.protocol.velbus;

import java.util.Objects;

public class VelbusPacket {

  /** Packet priority. */
  public enum PacketPriority {
    /// <summary>
    /// High priority. Usually used by real-time command like
    /// pressing a button etc.
    /// </summary>
    HIGH(0xF8),

    /// <summary>
    /// Low priority.
    /// </summary>
    LOW(0xFB);

    private int value;

    PacketPriority(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  /** Command for inbound packets */
  public enum InboundCommand {
    UNKNOWN(-1),
    PUSH_BUTTON_STATUS(0x00),
    RELAY_STATUS(0xFB),
    MODULE_TYPE(0xFF),
    MODULE_SUBADDRESSES(0xB0),
    MODULE_STATUS(0xED),
    SENSOR_STATUS(0xEA),
    CURRENT_TEMP_STATUS(0xE6),
    TEMP_SETTINGS1(0xE8),
    TEMP_SETTINGS2(0xE9),
    TIME_REQUEST(0xD7),
    MEMORY_DATA(0xFE),
    MEMORY_BLOCK_DUMP(0xCC),
    DIMMER_LEVEL_STATUS(0x0F),
    DIMMER_STATUS(0xEE),
    OUT_LEVEL_STATUS(0xB8),
    BLIND_STATUS(0xEC),
    LED_OFF(0xF5),
    LED_ON(0xF6),
    LED_SLOW(0xF7),
    LED_FAST(0xF8),
    LED_VERYFAST(0xF9),
    LED_STATUS(0xF4),
    COUNTER_STATUS(0xBE),
    RAW_SENSOR_STATUS(0xA9),
    RAW_SENSOR_TEXT_STATUS(0xAC);

    private int code;

    InboundCommand(int code) {
      this.code = code;
    }

    public static InboundCommand fromCode(int code) {
      for (InboundCommand type : InboundCommand.values()) {
        if (type.getCode() == code) {
          return type;
        }
      }

      return UNKNOWN;
    }

    public int getCode() {
      return this.code;
    }
  }

  /** Command for outbound packets */
  public enum OutboundCommand {
    UNKNOWN(-1),
    MODULE_STATUS(0xFA),
    REALTIME_CLOCK_SET(0xD8),
    REALTIME_DATE_SET(0xB7),
    DAYLIGHT_SAVING_SET(0xAF),
    BUTTON_STATUS(0x00),
    RELAY_ON_TIMER(0x03),
    RELAY_BLINK_TIMER(0x0D),
    RELAY_OFF(0x01),
    RELAY_ON(0x02),
    FORCE_ON(0x14),
    FORCE_ON_CANCEL(0x15),
    INHIBIT(0x16),
    INHIBIT_CANCEL(0x17),
    LOCK(0x12),
    LOCK_CANCEL(0x13),
    LED_OFF(0xF5),
    LED_ON(0xF6),
    LED_SLOW(0xF7),
    LED_FAST(0xF8),
    LED_VERYFAST(0xF9),
    SENSOR_SETTINGS(0xE7),
    READ_MEMORY(0xFD),
    READ_MEMORY_BLOCK(0xC9),
    WRITE_MEMORY(0xFC),
    TEMP_SET(0xE4),
    TEMP_MODE1_HEAT(0xE0),
    TEMP_MODE1_COOL(0xDF),
    TEMP_MODE2_COMFORT(0xDB),
    TEMP_MODE2_DAY(0xDC),
    TEMP_MODE2_NIGHT(0xDD),
    TEMP_MODE2_SAFE(0xDE),
    SET_ALARM(0xC3),
    SET_LEVEL(0x07),
    SET_VALUE(0x07),
    SET_LEVEL_LAST(0x11),
    SET_LEVEL_HALT(0x10),
    LEVEL_ON_TIMER(0x08),
    BLIND_POSITION(0x1C),
    BLIND_UP(0x05),
    BLIND_DOWN(0x06),
    BLIND_HALT(0x04),
    BLIND_LOCK(0x1A),
    BLIND_LOCK_CANCEL(0x1B),
    BLIND_FORCE_UP(0x12),
    BLIND_FORCE_UP_CANCEL(0x13),
    BLIND_FORCE_DOWN(0x14),
    BLIND_FORCE_DOWN_CANCEL(0x15),
    BLIND_INHIBIT_UP(0x18),
    BLIND_INHIBIT_DOWN(0x19),
    MEMO_TEXT(0xAC),
    COUNTER_STATUS(0xBD),
    COUNTER_RESET(0xAD),
    PROGRAM_SELECT(0xB3),
    SENSOR_READOUT(0xE5),
    PROGRAM_STEPS_ENABLE(0xB2),
    PROGRAM_STEPS_DISABLE(0xB1),
    SET_SUNRISE_SUNSET(0xAE);

    private int code;

    OutboundCommand(int code) {
      this.code = code;
    }

    public static OutboundCommand fromCode(int code) {
      for (OutboundCommand type : OutboundCommand.values()) {
        if (type.getCode() == code) {
          return type;
        }
      }

      return UNKNOWN;
    }

    public int getCode() {
      return this.code;
    }
  }

  /** Minimum packet size */
  public static final int MIN_PACKET_SIZE = 6;

  /** Maximum packet size used by the Velbus protocol. */
  public static final int MAX_PACKET_SIZE = 14;

  /*
   * Start of packet.
   */
  public static final byte STX = 0x0F;
  /*
   * End of packet.
   */
  public static final byte ETX = 0x04;
  /*
   * Raw representation of this packet.
   */
  private byte[] rawPacket = new byte[MAX_PACKET_SIZE];

  /** Flag that can be set to indicate that this packet has been handled (for inbound packets) */
  private boolean handled;

  public VelbusPacket(byte[] content) {
    rawPacket = content;
  }

  /// <summary>
  /// Initializes a new instance of the Packet class.
  /// </summary>
  public VelbusPacket() {
    this(0x00);
  }

  /*
   * Initializes a new instance of the Packet class using the
   * specified address.
   */
  public VelbusPacket(int address) {
    this(address, PacketPriority.LOW, 0, false);
  }

  public VelbusPacket(int address, int command, byte... dataBytes) {
    this(address, command, PacketPriority.LOW, dataBytes);
  }

  public VelbusPacket(int address, int command, PacketPriority priority, byte... dataBytes) {
    this(address);
    setCommand(command);
    setDataSize(dataBytes.length + 1);
    setPriority(priority);

    for (byte i = 0; i < dataBytes.length; i++) {
      setByte(i + 1, dataBytes[i]);
    }
  }

  /*
   * Initializes a new instance of the Packet class using the
   * specified address, priority, data size and rtr state.
   */
  public VelbusPacket(int address, PacketPriority priority, int dataSize, boolean rtr) {
    setAddress(address);
    setPriority(PacketPriority.LOW);
    setDataSize(dataSize);
    setRtr(rtr);
  }

  /*
   * Calculates checksum byte
   */
  public static byte calculateChecksum(VelbusPacket packet) {
    byte checksum = 0;
    for (int i = 0; i <= packet.getSize() - 3; i++) {
      checksum += packet.rawPacket[i];
    }
    return (byte) (-checksum);
  }

  /*
   * Get Address
   */
  public int getAddress() {
    return rawPacket[2] & 0xFF;
  }

  /*
   * Set address
   */
  public void setAddress(int address) {
    rawPacket[2] = (byte) address;
  }

  public int getTypeCode() {
    return rawPacket[5] & 0xFF;
  }

  public void setTypeCode(int typeCode) {
    rawPacket[5] = (byte) typeCode;
  }

  /*
   * Get data size
   */
  public int getDataSize() {
    return (rawPacket[3] & 0x0F);
  }

  /*
   * Set data size
   */
  public void setDataSize(int dataSize) {
    rawPacket[3] = (byte) ((rawPacket[3] & 0xF0) + dataSize);
  }

  /*
   * Get total packet size
   */
  public int getSize() {
    return getDataSize() + 6;
  }

  /** Get the checksum byte */
  public byte getChecksum() {
    return rawPacket[rawPacket.length - 2];
  }

  /*
   * Get packet priority
   */
  public PacketPriority getPriority() {
    return (rawPacket[1] == 0xF8 ? PacketPriority.HIGH : PacketPriority.LOW);
  }

  /*
   * Set packet priority
   */
  public void setPriority(PacketPriority priority) {
    rawPacket[1] = (byte) (priority.getValue());
  }

  /*
   * Gets the request to reply state of the packet.
   */
  public boolean getRtr() {
    return ((rawPacket[3] & 0x40) == 0x40);
  }

  /*
   *  Sets the request to reply state of the packet.
   */
  public void setRtr(boolean rtr) {
    if (rtr) rawPacket[3] |= 0x40;
    else rawPacket[3] &= 0x0F;
  }

  /*
   * Get packet byte by index
   */
  public byte getByte(int index) {
    return rawPacket[4 + index];
  }

  /*
   * Set packet byte at specified index
   */
  public void setByte(int index, byte value) {
    rawPacket[4 + index] = value;
  }

  public int getInt(int index) {
    return getByte(index) & 0xFF;
  }

  /*
   * Gets the command byte of the packet. Since the command
   * byte is the first databyte, the datasize needs to be greater or
   * equal to one.
   */
  public int getCommand() {
    if (getDataSize() <= 0) {
      return -1;
    }

    return getByte(0) & 0xFF;
  }

  /*
   * Gets or sets the command byte of the packet. Since the command
   * byte is the first databyte, the datasize needs to be greater or
   * equal to one.
   */
  public void setCommand(int command) {
    setByte(0, (byte) command);
  }

  /*
   * Checks if the packet has a command byte (eg. if DataSize >= 1).
   */
  public boolean hasCommand() {
    return (getDataSize() >= 1);
  }

  public boolean isHandled() {
    return handled;
  }

  public void setHandled(boolean handled) {
    this.handled = handled;
  }

  /*
   * Packs the byte so it is ready for sending.
   * Packing involves adding a checksum and the frame delimiters.
   */
  public byte[] pack() {
    rawPacket[0] = VelbusPacket.STX;
    rawPacket[getSize() - 1] = VelbusPacket.ETX;
    rawPacket[getSize() - 2] = calculateChecksum(this);

    return rawPacket;
  }

  /*
   * Clones this packet.
   */
  public VelbusPacket clone() {
    VelbusPacket packet;
    try {
      packet = new VelbusPacket();
      for (int i = 0; i < getSize(); i++) {
        packet.rawPacket[i] = rawPacket[i];
      }
      return packet;
    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return null;
  }

  public boolean isValid() {
    return getSize() <= rawPacket.length && getChecksum() == calculateChecksum(this);
  }

  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    byte[] packed = pack();
    for (int i = 0; i < MAX_PACKET_SIZE; i++) {
      if (i != 0) {
        stringBuilder.append(" ");
      }

      if (packed.length > i) {
        stringBuilder.append(String.format("%02X", (packed[i] & 0xFF)));
      } else {
        stringBuilder.append("00");
      }
    }

    return stringBuilder.toString();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(toString());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null || !(obj instanceof VelbusPacket)) {
      return false;
    }

    return toString().equals(obj.toString());
  }

  public static VelbusPacket fromString(String packetStr) {
    if (packetStr == null || packetStr.isEmpty()) {
      return null;
    }

    String[] byteStrs = packetStr.split("\\s");
    byte[] bytes = new byte[byteStrs.length];
    for (int i = 0; i < byteStrs.length; i++) {
      bytes[i] = (byte) Integer.parseInt(byteStrs[i], 16);
    }
    return new VelbusPacket(bytes);
  }
}
