/*
 * Copyright 2021, OpenRemote Inc.
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
package org.openremote.agent.protocol.serial;

import com.fazecast.jSerialComm.SerialPort;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;

/** Configuration interface for jSerialComm device connections. */
public interface JSerialCommChannelConfig extends ChannelConfig {
  enum Stopbits {
    /** 1 stop bit will be sent at the end of every character */
    STOPBITS_1(SerialPort.ONE_STOP_BIT),
    /** 2 stop bits will be sent at the end of every character */
    STOPBITS_2(SerialPort.TWO_STOP_BITS),
    /** 1.5 stop bits will be sent at the end of every character */
    STOPBITS_1_5(SerialPort.ONE_POINT_FIVE_STOP_BITS);

    private final int value;

    Stopbits(int value) {
      this.value = value;
    }

    public int value() {
      return value;
    }

    public static Stopbits valueOf(int value) {
      for (Stopbits stopbit : Stopbits.values()) {
        if (stopbit.value == value) {
          return stopbit;
        }
      }
      throw new IllegalArgumentException(
          "unknown " + Stopbits.class.getSimpleName() + " value: " + value);
    }
  }

  enum Paritybit {
    /** No parity bit will be sent with each data character at all */
    NONE(SerialPort.NO_PARITY),
    /**
     * An odd parity bit will be sent with each data character, ie. will be set to 1 if the data
     * character contains an even number of bits set to 1.
     */
    ODD(SerialPort.ODD_PARITY),
    /**
     * An even parity bit will be sent with each data character, ie. will be set to 1 if the data
     * character contains an odd number of bits set to 1.
     */
    EVEN(SerialPort.EVEN_PARITY),
    /** A mark parity bit (ie. always 1) will be sent with each data character */
    MARK(SerialPort.MARK_PARITY),
    /** A space parity bit (ie. always 0) will be sent with each data character */
    SPACE(SerialPort.SPACE_PARITY);

    private final int value;

    Paritybit(int value) {
      this.value = value;
    }

    public int value() {
      return value;
    }

    public static Paritybit valueOf(int value) {
      for (Paritybit paritybit : Paritybit.values()) {
        if (paritybit.value == value) {
          return paritybit;
        }
      }
      throw new IllegalArgumentException(
          "unknown " + Paritybit.class.getSimpleName() + " value: " + value);
    }
  }

  /**
   * Sets the baud rate (ie. bits per second) for communication with the serial device. The baud
   * rate will include bits for framing (in the form of stop bits and parity), such that the
   * effective data rate will be lower than this value.
   *
   * @param baudrate The baud rate (in bits per second)
   */
  JSerialCommChannelConfig setBaudrate(int baudrate);

  /**
   * Sets the number of stop bits to include at the end of every character to aid the serial device
   * in synchronizing with the data.
   *
   * @param stopbits The number of stop bits to use
   */
  JSerialCommChannelConfig setStopbits(Stopbits stopbits);

  /**
   * Sets the number of data bits to use to make up each character sent to the serial device.
   *
   * @param databits The number of data bits to use
   */
  JSerialCommChannelConfig setDatabits(int databits);

  /**
   * Sets the type of parity bit to be used when communicating with the serial device.
   *
   * @param paritybit The type of parity bit to be used
   */
  JSerialCommChannelConfig setParitybit(Paritybit paritybit);

  /**
   * @return The configured baud rate, defaulting to 115200 if unset
   */
  int getBaudrate();

  /**
   * @return The configured stop bits, defaulting to {@link Stopbits#STOPBITS_1} if unset
   */
  Stopbits getStopbits();

  /**
   * @return The configured data bits, defaulting to 8 if unset
   */
  int getDatabits();

  /**
   * @return The configured parity bit, defaulting to {@link Paritybit#NONE} if unset
   */
  Paritybit getParitybit();

  /**
   * @return The number of milliseconds to wait between opening the serial port and initializing.
   */
  int getWaitTimeMillis();

  /**
   * Sets the time to wait after opening the serial port and before sending it any configuration
   * information or data. A value of 0 indicates that no waiting should occur.
   *
   * @param waitTimeMillis The number of milliseconds to wait, defaulting to 0 (no wait) if unset
   * @throws IllegalArgumentException if the supplied value is &lt; 0
   */
  JSerialCommChannelConfig setWaitTimeMillis(int waitTimeMillis);

  /**
   * Sets the maximal time (in ms) to block while try to read from the serial port. Default is
   * 1000ms
   */
  JSerialCommChannelConfig setReadTimeout(int readTimeout);

  /** Return the maximal time (in ms) to block and wait for something to be ready to read. */
  int getReadTimeout();

  @Override
  JSerialCommChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis);

  @Override
  JSerialCommChannelConfig setWriteSpinCount(int writeSpinCount);

  @Override
  JSerialCommChannelConfig setAllocator(ByteBufAllocator allocator);

  @Override
  JSerialCommChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator);

  @Override
  JSerialCommChannelConfig setAutoRead(boolean autoRead);

  @Override
  JSerialCommChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark);

  @Override
  JSerialCommChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark);

  @Override
  JSerialCommChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator);
}
