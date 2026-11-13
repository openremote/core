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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.openremote.agent.protocol.velbus.VelbusPacket;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.ValueType;

/**
 * Covers OLED specific features:
 *
 * <ul>
 *   <li>Memo Text
 * </ul>
 */
public class OLEDProcessor extends FeatureProcessor {

  @Override
  public List<PropertyDescriptor> getPropertyDescriptors(VelbusDeviceType deviceType) {
    return Collections.singletonList(
        new PropertyDescriptor("memoText", "Memo Text", "MEMO_TEXT", ValueType.TEXT));
  }

  @Override
  public List<VelbusPacket> getPropertyWritePackets(
      VelbusDevice device, String property, Object value) {
    if (property.equals("MEMO_TEXT")) {
      return ValueUtil.getString(value)
          .map(
              text -> {
                if (text.length() == 0) {
                  // Cancel the memo text
                  device.setProperty("MEMO_TEXT", "");
                  return Collections.singletonList(getCancelMemoTextPacket(device));
                }

                String[] vals = text.trim().split(":");
                int timeout = 5; // Default timeout

                if (vals.length > 1) {
                  try {
                    timeout = Integer.parseInt(vals[vals.length - 1]);
                  } catch (NumberFormatException e) {
                    LOG.fine(
                        "Last section of command value '"
                            + vals[vals.length - 1]
                            + "' is not a number so using default value");
                  }
                }

                int endPos = text.lastIndexOf(":");
                endPos = endPos > 0 ? endPos : text.length();
                endPos = Math.min(62, endPos);
                text = text.substring(0, endPos);
                List<VelbusPacket> packets = new ArrayList<>();

                byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
                int counter = 0;
                byte[] packetBytes = new byte[7];
                packetBytes[0] = (byte) 0x00; // Doesn't matter what this byte is
                packetBytes[1] = (byte) 0x00; // startPos

                for (int i = 0; i <= text.length(); i++) {
                  packetBytes[counter + 2] = i == text.length() ? 0x00 : bytes[i];
                  counter++;

                  if (counter > 4 || i == text.length()) {
                    // Can only send 5 characters at a time
                    byte[] pBytes = Arrays.copyOf(packetBytes, counter + 2);
                    packets.add(
                        new VelbusPacket(
                            device.getBaseAddress(),
                            VelbusPacket.OutboundCommand.MEMO_TEXT.getCode(),
                            VelbusPacket.PacketPriority.LOW,
                            pBytes));
                    packetBytes[1] = (byte) ((packetBytes[1] & 0xFF) + 5);
                    counter = 0;
                  }
                }

                // Create timer task to clear memo text
                int finalTimeout = timeout;
                device.velbusNetwork.scheduleTask(
                    () -> {
                      device.velbusNetwork.sendPackets(getCancelMemoTextPacket(device));
                      device.setProperty("MEMO_TEXT", "");
                    },
                    finalTimeout * 1000);

                device.setProperty("MEMO_TEXT", text);
                return packets;
              })
          .orElse(null);
    }

    return null;
  }

  @Override
  public boolean processReceivedPacket(VelbusDevice device, VelbusPacket packet) {

    VelbusPacket.InboundCommand packetCommand =
        VelbusPacket.InboundCommand.fromCode(packet.getCommand());

    switch (packetCommand) {
      case MODULE_STATUS:
        // TODO: try and extract memo text from the device at initialization
        device.setProperty("MEMO_TEXT", "");

        packet.setHandled(true);
        return false;
    }

    return false;
  }

  protected VelbusPacket getCancelMemoTextPacket(VelbusDevice device) {
    return new VelbusPacket(
        device.getBaseAddress(),
        VelbusPacket.OutboundCommand.MEMO_TEXT.getCode(),
        VelbusPacket.PacketPriority.LOW,
        (byte) 0x00,
        (byte) 0x00,
        (byte) 0x00,
        (byte) 0x00,
        (byte) 0x00,
        (byte) 0x00,
        (byte) 0x00);
  }
}
