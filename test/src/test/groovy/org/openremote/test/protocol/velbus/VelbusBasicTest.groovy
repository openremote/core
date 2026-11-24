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
package org.openremote.test.protocol.velbus

import org.openremote.agent.protocol.velbus.VelbusNetwork
import org.openremote.agent.protocol.velbus.VelbusPacket
import org.openremote.agent.protocol.velbus.device.*
import org.openremote.container.Container
import org.openremote.container.concurrent.ContainerScheduledExecutor
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.attribute.AttributeExecuteStatus
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import org.openremote.setup.integration.protocol.velbus.MockVelbusClient

import java.util.concurrent.Executors

import static spock.util.matcher.HamcrestMatchers.closeTo

class VelbusBasicTest extends Specification {
  @Shared
  def static VelbusNetwork network

  @Shared
  def static MockVelbusClient messageProcessor = new MockVelbusClient()

  @Shared
  def static PollingConditions conditions = new PollingConditions(timeout: 5, delay: 0.2)

  static loadDevicePackets(MockVelbusClient messageProcessor) {
    messageProcessor.mockPackets = [

      /*
       * INPUT BUTTON PROCESSOR PACKETS
       */

      // Module type request for address 1 - return VMB4AN packets
      "0F FB 02 40 B4 04 00 00 00 00 00 00 00 00": ["0F FB 02 07 FF 32 00 01 01 17 25 7E 04 00"],

      // Module Status for address 2
      "0F FB 02 02 FA FF F9 04 00 00 00 00 00 00": [
        "0F FB 02 06 ED 10 04 00 01 00 EC 04 00 00",
        "0F FB 02 08 B8 0C 00 00 00 00 00 00 28 04",
        "0F FB 02 08 B8 0D 00 00 00 00 00 00 27 04",
        "0F FB 02 08 B8 0E 00 00 00 00 00 00 26 04",
        "0F FB 02 08 B8 0F 00 00 00 00 00 00 25 04"
      ],

      // Read memory 1 address 2
      "0F FB 02 03 C9 00 46 E2 04 00 00 00 00 00": ["0F FB 02 07 CC 00 46 07 00 16 00 BE 04 00"],

      // Read memory 2 address 2
      "0F FB 02 03 C9 00 4A DE 04 00 00 00 00 00": ["0F FB 02 07 CC 00 4A 08 00 17 00 B8 04 00"],

      // VMB4AN Sensor 1 Readout
      "0F FB 02 03 E5 08 00 04 04 00 00 00 00 00": [
        "0F FB 02 06 A9 08 02 00 11 27 03 04 00 00",
        "0F FB 02 08 AC 08 00 32 35 2E 32 B0 C1 04",
        "0F FB 02 05 AC 08 05 43 00 F3 04 00 00 00",
        "0F FB 02 07 EA 08 06 00 00 0A 0A E1 04 00"
      ],

      // VMB4AN Sensor 2 Readout
      "0F FB 02 03 E5 09 00 03 04 00 00 00 00 00": [
        "0F FB 02 06 A9 09 01 00 00 0E 2D 04 00 00",
        "0F FB 02 08 AC 09 00 30 55 6E 69 74 67 04",
        "0F FB 02 04 AC 09 05 00 36 04 00 00 00 00",
        "0F FB 02 07 EA 09 05 00 00 01 01 F3 04 00"
      ],

      // VMB4AN Sensor 3 Readout
      "0F FB 02 03 E5 0A 00 02 04 00 00 00 00 00": [
        "0F FB 02 06 A9 0A 00 00 00 2B 10 04 00 00",
        "0F FB 02 08 AC 0A 00 30 55 6E 69 74 66 04",
        "0F FB 02 04 AC 0A 05 00 35 04 00 00 00 00",
        "0F FB 02 07 EA 0A 04 00 00 01 01 F3 04 00"
      ],

      // VMB4AN Sensor 4 Readout
      "0F FB 02 03 E5 0B 00 01 04 00 00 00 00 00": [
        "0F FB 02 06 A9 0B 00 00 00 2B 0F 04 00 00",
        "0F FB 02 08 AC 0B 00 30 55 6E 69 74 65 04",
        "0F FB 02 04 AC 0B 05 00 34 04 00 00 00 00",
        "0F FB 02 07 EA 0B 04 00 00 01 01 F2 04 00"
      ],

      // VMB4AN Sensor 1 Settings
      "0F FB 02 02 E7 08 03 04 00 00 00 00 00 00": [
        "0F FB 02 08 E8 08 FF FF FF 00 0C 8C 67 04",
        "0F FB 02 08 E9 08 FF 00 00 FF FF FF FF 04"
      ],

      // VMB4AN Sensor 2 Settings
      "0F FB 02 02 E7 09 02 04 00 00 00 00 00 00": [
        "0F FB 02 08 E8 09 FF FF FF 00 00 00 FE 04",
        "0F FB 02 08 E9 09 FF FF FF FF FF FF 00 04"
      ],

      // VMB4AN Sensor 3 Settings
      "0F FB 02 02 E7 0A 01 04 00 00 00 00 00 00": [
        "0F FB 02 08 E8 0A FF FF FF FF FF 00 FF 04",
        "0F FB 02 08 E9 0A FF FF FF FF FF FF FF 04"
      ],

      // VMB4AN Sensor 4 Settings
      "0F FB 02 02 E7 0B 00 04 00 00 00 00 00 00": [
        "0F FB 02 08 E8 0B FF FF FF FF FF 00 FE 04",
        "0F FB 02 08 E9 0B FF FF FF FF FF FF FE 04"
      ],

      // VMB4AN Lock channel for 1s
      "0F F8 02 05 12 07 00 00 01 D8 04 00 00 00": ["0F FB 02 06 ED 10 84 00 01 00 6C 04 00 00"],

      // VMB4AN lock channel indefinitely
      "0F F8 02 05 12 07 FF FF FF DC 04 00 00 00": ["0F FB 02 06 ED 10 84 00 01 00 6C 04 00 00"],



      // Module Type request for address 48 - return VMBGPOD Packets
      "0F FB 30 40 86 04 00 00 00 00 00 00 00 00": [
        "0F FB 30 07 FF 28 00 02 01 16 12 6D 04 00",
        "0F FB 30 08 B0 28 00 02 31 32 FF 40 42 04"
        //"0F FB 30 08 B0 28 00 02 31 32 33 40 0E 04"
      ],

      // Module Status request for address 48
      "0F FB 30 02 FA 00 CA 04 00 00 00 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 8D 47 04 00",
        "0F FB 31 07 ED 00 FF FF 00 10 8D 36 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 8D 45 04 00",
      ],

      // Module Type request for address 1 - return VMBGP1 Packets
      "0F FB 01 40 B5 04 00 00 00 00 00 00 00 00": [
        "0F FB 01 07 FF 1E 00 02 01 16 12 A6 04 00",
        "0F FB 01 08 B0 1E 00 02 02 FF FF FF 1E 04"
      ],

      // Module Status request for address 1 - return
      "0F FB 01 02 FA 00 F9 04 00 00 00 00 00 00": ["0F FB 01 07 ED 00 FF FF 00 00 00 03 04"],

      // CH8 lock for 1s
      "0F F8 30 05 12 08 00 00 01 A9 04 00 00 00": ["0F FB 30 07 ED 00 FF FF 80 00 8D C7 04 00"],

      // CH8 lock indefinitely
      "0F F8 30 05 12 08 FF FF FF AD 04 00 00 00": ["0F FB 30 07 ED 00 FF FF 80 00 8D C7 04 00"],

      // CH8 unlock
      "0F F8 30 02 13 08 AC 04 00 00 00 00 00 00": ["0F FB 30 07 ED 00 FF FF 00 00 8D 47 04 00"],

      // Module Type request for address 10 - return VMB7IN
      "0F FB 0A 40 AC 04 00 00 00 00 00 00 00 00": ["0F FB 0A 07 FF 22 E7 9C 03 15 37 F2 04 00"],

      // Module status request for address 10
      "0F FB 0A 02 FA 00 F0 04 00 00 00 00 00 00": ["0F FB 0A 07 ED 01 7F FF 00 00 C0 B9 04 00"],

      /*
       * THERMOSTAT PROCESSOR PACKETS
       */
      // Module Type request for address 3 - return VMBGP1 Packets with disabled thermostat
      "0F FB 03 40 B3 04 00 00 00 00 00 00 00 00": [
        "0F FB 03 07 FF 1E 00 02 01 16 12 A4 04 00",
        "0F FB 03 08 B0 1E 00 02 FF FF FF FF 1F 04"
      ],

      // Thermostat Status request for address 48
      "0F FB 30 02 E7 00 DD 04 00 00 00 00 00 00": [
        "0F FB 30 08 EA 00 00 10 38 18 00 00 74 04",
        "0F FB 30 08 E8 18 32 2C 20 18 06 01 21 04",
        "0F FB 30 08 E9 2A 2E 34 48 00 3C 05 C0 04"
      ],

      // Set temperature of Heat Night mode to 20.5
      "0F FB 30 03 E4 03 29 B3 04 00 00 00 00 00": [
        "0F FB 30 08 EA 00 00 10 38 18 00 00 74 04",
        "0F FB 30 08 E8 18 32 2C 29 18 06 01 18 04",
        "0F FB 30 08 E9 2A 2E 34 48 00 3C 05 C0 04"
      ],

      // Set thermostat disabled state
      "0F F8 30 05 12 21 FF FF FF 94 04 00 00 00": ["0F FB 30 08 EA 06 00 10 38 18 00 00 6E 04"],

      // Unlock thermostat
      "0F F8 30 02 13 21 93 04 00 00 00 00 00 00": ["0F FB 30 08 EA 00 00 10 38 18 00 00 74 04"],

      // Set thermostat manual state (HEAT_SAFE)
      "0F FB 30 03 DE FF FF E7 04 00 00 00 00 00": ["0F FB 30 08 EA 02 00 10 38 18 00 00 72 04"],

      // Set to normal mode (HEAT_SAFE)
      "0F FB 30 03 DE 00 00 E5 04 00 00 00 00 00": ["0F FB 30 08 EA 00 00 10 38 18 00 00 74 04"],

      // Set to HEAT_COMFORT mode 1 min
      "0F FB 30 03 DB 00 01 E7 04 00 00 00 00 00": ["0F FB 30 08 EA 44 00 10 38 32 00 01 15 04"],

      // Set thermostat manual state (HEAT_COMFORT)
      "0F FB 30 03 DB FF FF EA 04 00 00 00 00 00": ["0F FB 30 08 EA 42 00 10 37 32 00 00 19 04"],

      // Set to HEAT SAFE until next program step
      "0F FB 30 03 DE FF 00 E6 04 00 00 00 00 00": ["0F FB 30 08 EA 00 00 10 38 18 00 00 74 04"],

      // Set to normal mode (HEAT_COMFORT)
      "0F FB 30 03 DB 00 00 E8 04 00 00 00 00 00": ["0F FB 30 08 EA 40 00 10 37 32 00 00 1B 04"],

      // Set to COOL_DAY mode until next program step
      "0F FB 30 03 DC FF 00 E8 04 00 00 00 00 00": ["0F FB 30 08 EA A0 00 10 37 2E 00 00 BF 04"],

      /*
       * PROGRAMS PROCESSOR PACKETS
       */

      // Read memory block (alarm1 times)
      "0F FB 30 03 C9 02 85 73 04 00 00 00 00 00": ["0F FB 30 07 CC 02 85 10 00 16 00 46 04 00"],

      // Read memory block (alarm2 times)
      "0F FB 30 03 C9 02 89 6F 04 00 00 00 00 00": ["0F FB 30 07 CC 02 89 08 00 17 00 49 04 00"],

      // Set alarm1 disabled
      "0F FB 00 07 C3 01 10 00 16 00 00 05 04 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 89 4B 04 00",
        "0F FB 31 07 ED 00 FF FF 00 10 89 3A 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 89 49 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 89 48 04 00"
      ],

      // Set alarm2 enabled
      "0F FB 30 07 C3 02 08 00 17 00 01 DA 04 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 99 3B 04 00",
        "0F FB 31 07 ED 00 FF FF 00 10 99 2A 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 99 39 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 99 38 04 00"
      ],

      // Set sunrise enabled
      "0F FB 30 03 AE FF 03 13 04 00 00 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 D9 FB 04 00",
        "0F FB 31 07 ED 00 FF FF 00 10 D9 EA 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 D9 F9 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 D9 F8 04 00"
      ],

      // Set sunset disabled
      "0F FB 30 03 AE FF 01 15 04 00 00 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 59 7B 04 00",
        "0F FB 31 07 ED 00 FF FF 00 10 59 6A 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 59 79 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 59 78 04 00"
      ],

      // Enable program steps CH13
      "0F FB 30 02 B2 0D 05 04 00 00 00 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 99 3B 04 00",
        "0F FB 31 07 ED 00 FF FF 00 00 99 3A 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 99 39 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 99 38 04 00"
      ],

      // Disable program steps all channels for 1s
      "0F FB 30 05 B1 FF 00 00 01 10 04 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 FF 99 3C 04 00",
        "0F FB 31 07 ED 00 FF FF 00 FF 99 3B 04 00",
        "0F FB 32 07 ED 00 FF FF 00 FF 99 3A 04 00",
        "0F FB 33 07 ED 00 FF FF 00 FF 99 39 04 00"
      ],

      // Disable CH20 program steps for 1s
      "0F FB 30 05 B1 14 00 00 01 FB 04 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 99 3B 04 00",
        "0F FB 31 07 ED 00 FF FF 00 00 99 3A 04 00",
        "0F FB 32 07 ED 00 FF FF 00 08 99 31 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 99 38 04 00"
      ],

      // Select winter program
      "0F FB 30 02 B3 02 0F 04 00 00 00 00 00 00": [
        "0F FB 30 07 ED 00 FF FF 00 00 5A 7A 04 00",
        "0F FB 31 07 ED 00 FF FF 00 00 5A 79 04 00",
        "0F FB 32 07 ED 00 FF FF 00 00 5A 78 04 00",
        "0F FB 33 07 ED 00 FF FF 00 00 5A 77 04 00"
      ],

      /*
       * RELAY PROCESSOR PACKETS
       */

      // Module Type request for address 81 - return VMB1RYNO Packets
      "0F FB 51 40 65 04 00 00 00 00 00 00 00 00": ["0F FB 51 07 FF 1B E1 74 00 13 25 F7 04 00"],

      // Relay Status request for address 81
      "0F FB 51 02 FA 1F 8A 04 00 00 00 00 00 00": [
        "0F FB 51 08 FB 01 00 00 00 00 00 00 A1 04",
        "0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04",
        "0F FB 51 08 FB 04 00 00 00 00 00 00 9E 04",
        "0F FB 51 08 FB 08 00 00 00 00 00 00 9A 04",
        "0F FB 51 08 FB 10 00 00 00 00 00 00 92 04"
      ],

      // Switch on CH4 relay
      "0F F8 51 02 02 08 9C 04 00 00 00 00 00 00": ["0F FB 51 08 FB 08 00 01 80 00 00 00 19 04"],

      // Switch off CH4 relay
      "0F F8 51 02 01 08 9D 04 00 00 00 00 00 00": ["0F FB 51 08 FB 08 00 00 00 00 00 00 9A 04"],

      // Switch CH1 to intermittent
      "0F F8 51 05 0D 01 FF FF FF 98 04 00 00 00": [
        "0F FB 51 08 FB 01 00 03 20 FF FF FE 82 04",
        "0F FB 40 02 F8 01 BB 04 00 00 00 00 00 00",
        "0F F8 51 04 00 01 00 00 A3 04 00 00 00"
      ],

      // Switch CH1 off
      "0F F8 51 02 01 01 A4 04 00 00 00 00 00 00": [
        "0F FB 40 02 F5 01 BE 04 00 00 00 00 00 00",
        "0F FB 51 08 FB 01 00 00 00 00 00 00 A1 04",
        "0F F8 51 04 00 00 01 00 A3 04 00 00 00 00"
      ],

      // CH2 to intermittent for 1s
      "0F F8 51 05 0D 02 00 00 01 93 04 00 00 00": [
        "0F FB 51 08 FB 02 00 03 20 00 00 01 7C 04",
        "0F F8 51 04 00 02 00 00 A2 04 00 00 00"
      ],

      // CH2 to intermittent indefinitely
      "0F F8 51 05 0D 02 FF FF FF 97 04 00 00 00": [
        "0F FB 51 08 FB 02 00 03 20 00 00 00 7D 04",
        "0F F8 51 04 00 02 00 00 A2 04 00 00 00",
      ],

      // CH2 off
      "0F F8 51 02 01 02 A3 04 00 00 00 00 00 00": ["0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"],

      // Inhibit channel 2 for 1s
      "0F F8 51 05 16 02 00 00 01 8A 04 00 00 00": ["0F FB 51 08 FB 02 01 00 00 00 00 00 9F 04"],

      // Inhibit channel 2 indefinitely
      "0F F8 51 05 16 02 FF FF FF 8E 04 00 00 00": ["0F FB 51 08 FB 02 01 00 00 00 00 00 9F 04"],

      // Cancel channel 2 inhibit
      "0F F8 51 02 17 02 8D 04 00 00 00 00 ": ["0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"],

      // Force channel 3 on for 1s
      "0F F8 51 05 14 04 00 00 01 8A 04 00 00 00": [
        "0F FB 30 02 F6 02 CC 04 00 00 00 00 00 00",
        "0F FB 51 08 FB 04 02 01 80 00 00 00 1B 04",
        "0F F8 51 04 00 04 00 00 A0 04 00 00 00 00"
      ],

      // Force channel 1 off for 1s
      "0F F8 51 05 12 01 00 00 01 8F 04 00 00 00": [
        "0F FB 51 08 FB 01 03 00 00 00 00 00 9E 04",
        "0F FB 40 02 F5 01 BE 04 00 00 00 00 00 00"
      ],

      // Channel 1 on for 1s
      "0F F8 51 05 03 01 00 00 01 9E 04 00 00 00": [
        "0F FB 40 02 F8 01 BB 04 00 00 00 00 00 00",
        "0F FB 51 08 FB 01 00 01 20 00 00 01 7F 04",
        "0F F8 51 04 00 01 00 00 A3 04 00 00 00 00"
      ],

      // Channel 1 on indefinitely
      "0F F8 51 05 03 01 FF FF FF A2 04 00 00 00": [
        "0F FB 40 02 F6 01 BD 04 00 00 00 00 00 00",
        "0F FB 51 08 FB 01 00 01 80 00 00 00 20 04",
        "0F F8 51 04 00 01 00 00 A3 04 00 00 00 00"
      ],

      // CH2 lock for 1s
      "0F F8 51 05 12 02 00 00 01 8E 04 00 00 00": ["0F FB 51 08 FB 02 03 00 00 00 00 00 9D 04"],

      // CH2 lock indefinitely
      "0F F8 51 05 12 02 FF FF FF 92 04 00 00 00": ["0F FB 51 08 FB 02 03 00 00 00 00 00 9D 04"],

      // CH2 lock cancel
      "0F F8 51 02 13 02 91 04 00 00 00 00 00 00": ["0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"],

      /*
       * COUNTER PROCESSOR PACKETS
       */

      // Read memory from address 10 (counter data)
      "0F FB 0A 03 FD 03 FE EB 04 00 00 00 00 00": ["0F FB 0A 04 FE 03 FE FD EC 04 00 00 00 00"],

      // Counter status
      "0F FB 0A 03 BD 0F 00 1D 04 00 00 00 00 00": [
        "0F FB 0A 08 BE 68 00 00 00 00 FF FF C0 04",
        "0F FB 0A 08 BE 11 00 00 00 00 FF FF 17 04"
      ],

      // Counter 1 reset
      "0F FB 0A 02 AD 00 3D 04 00 00 00 00 00 00": ["0F FB 0A 08 BE 68 00 00 00 00 FF FF C0 04"],

      /*
       * DIMMER PROCESSOR PACKETS
       */

      // Module Type request for address 187 - return VMB4DC Packets
      "0F FB BB 40 FB 04 00 00 00 00 00 00 00 00": ["0F FB BB 07 FF 12 AB 31 03 15 09 26 04 00"],

      // Dimmer Status request for address 187
      "0F FB BB 02 FA 0F 30 04 00 00 00 00 00 00": [
        "0F FB BB 08 B8 01 00 00 00 00 00 00 7A 04",
        "0F FB BB 08 B8 02 00 00 00 00 00 00 79 04",
        "0F FB BB 08 B8 04 00 00 00 00 00 00 77 04",
        "0F FB BB 08 B8 08 00 00 00 00 00 00 73 04"
      ],

      // Switch dimmer channel on
      "0F F8 BB 05 07 08 64 00 00 C6 04 00 00 00": [
        "0F FB BB 08 B8 08 00 00 20 00 00 00 53 04",
        "0F F8 BB 04 00 08 00 00 32 04 00 00 00 00",
        "0F FB BB 08 B8 08 00 64 80 00 00 00 8F 04"
      ],

      // Switch dimmer channel off
      "0F F8 BB 05 07 08 00 00 00 2A 04 00 00 00": [
        "0F FB BB 08 B8 08 00 63 80 00 00 00 90 04",
        "0F F8 BB 04 00 00 08 00 32 04 00 00 00 00",
        "0F FB BB 08 B8 08 00 00 00 00 00 00 73 04"
      ],

      // Set dimmer to 50%
      "0F F8 BB 05 07 08 32 00 00 F8 04 00 00 00": [
        "0F FB BB 08 B8 08 00 00 20 00 00 00 53 04",
        "0F F8 BB 04 00 08 00 00 32 04 00 00 00 00",
        "0F FB BB 08 B8 08 00 32 80 00 00 00 C1 04"
      ],

      // Set dimmer to last level (50%)
      "0F F8 BB 05 11 08 00 00 00 20 04 00 00 00": [
        "0F FB BB 08 B8 08 00 01 00 00 00 00 72 04",
        "0F F8 BB 04 00 08 00 00 32 04 00 00 00 00",
        "0F FB BB 08 B8 08 00 32 80 00 00 00 C1 04"
      ],

      // Set dimmer to 75% over 7s
      "0F F8 BB 05 07 02 4B 00 07 DE 04 00 00 00": [
        "0F FB BB 08 B8 02 00 00 00 00 00 00 79 04",
        "0F F8 BB 04 00 02 00 00 38 04 00 00 00 00",
        "0F FB BB 08 B8 02 00 1A 20 00 00 00 3F 04",
        "0F FB BB 08 B8 02 00 33 20 00 00 00 26 04",
        "0F FB BB 08 B8 02 00 4B 80 00 00 00 AE 04"
      ],

      // CH2 off
      "0F F8 BB 05 07 02 00 00 00 30 04 00 00 00": [
        "0F FB BB 08 B8 02 00 4B 80 00 00 00 AE 04",
        "0F F8 BB 04 00 00 02 00 38 04 00 00 00 00",
        "0F FB BB 08 B8 02 00 00 00 00 00 00 79 04"
      ],

      // CH2 on
      "0F F8 BB 05 07 02 64 00 00 CC 04 00 00 00": [
        "0F FB BB 08 B8 02 00 01 20 00 00 00 58 04",
        "0F F8 BB 04 00 02 00 00 38 04 00 00 00 00",
        "0F FB BB 08 B8 02 00 08 20 00 00 03 4E 04"
      ],

      // CH2 on for 3s
      "0F F8 BB 05 08 02 00 00 03 2C 04 00 00 00": ["0F FB BB 08 B8 02 00 08 20 00 00 03 4E 04"],

      // CH2 to 50% over 60s
      "0F F8 BB 05 07 02 32 00 3C C2 04 00 00 00": [
        "0F FB BB 08 B8 02 00 00 20 00 00 00 59 04",
        "0F F8 BB 04 00 02 00 00 38 04 00 00 00 00",
        "0F FB BB 08 B8 02 00 15 20 00 00 00 44 04"
      ],

      // CH2 halt
      "0F F8 BB 02 10 02 2A 04 00 00 00 00 00 00": ["0F FB BB 08 B8 02 00 02 80 00 00 00 F7 04"],

      // CH3 inhibit for 1s
      "0F F8 BB 05 16 04 00 00 01 1E 04 00 00 00": ["0F FB BB 08 B8 04 01 00 00 00 00 00 76 04"],

      // CH3 inhibit indefinitely
      "0F F8 BB 05 16 04 FF FF FF 22 04 00 00 00": ["0F FB BB 08 B8 04 01 00 00 00 00 00 76 04"],

      // CH3 inhibit cancel
      "0F F8 BB 02 17 04 21 04 00 00 00 00 00 00": ["0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"],

      // CH3 force on 5s
      "0F F8 BB 05 14 04 00 00 05 1C 04 00 00 00": [
        "0F F8 BB 04 00 04 00 00 36 04 00 00 00 00",
        "0F FB BB 08 B8 04 02 64 80 00 00 00 91 04"
      ],

      // CH3 on
      "0F F8 BB 05 07 04 64 00 00 CA 04 00 00 00": [
        "0F FB BB 08 B8 04 00 00 00 00 00 00 77 04",
        "0F FB BB 08 B8 04 00 00 20 00 00 00 57 04",
        "0F F8 BB 04 00 04 00 00 36 04 00 00 00 00"
      ],

      // CH3 on indefinitely
      "0F F8 BB 05 08 04 FF FF FF 30 04 00 00 00": ["0F FB BB 08 B8 04 00 64 80 00 00 00 93 04"],

      // CH3 off
      "0F F8 BB 05 07 04 00 00 00 2E 04 00 00 00": [
        "0F FB BB 08 B8 04 00 63 80 00 00 00 94 04",
        "0F F8 BB 04 00 00 04 00 36 04 00 00 00 00",
        "0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"
      ],

      // CH3 lock for 1s
      "0F F8 BB 05 12 04 00 00 01 22 04 00 00 00": [
        "0F FB BB 08 B8 04 00 00 00 00 00 00 77 04",
        "0F FB BB 08 B8 04 03 00 00 00 00 00 74 04"
      ],

      // CH3 lock indefinitely
      "0F F8 BB 05 12 04 FF FF FF 26 04 00 00 00": ["0F FB BB 08 B8 04 03 00 00 00 00 00 74 04"],

      // CH3 lock cancel
      "0F F8 BB 02 13 04 25 04 00 00 00 00 00 00": ["0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"],

      /*
       * BLIND PROCESSOR PACKETS
       */

      // Module Type request for address 97 - return VMB2BLE Packets
      "0F FB 61 40 55 04 00 00 00 00 00 00 00 00": ["0F FB 61 07 FF 1D C4 56 00 13 25 20 04 00"],

      // CH1 status request
      "0F FB 61 02 FA 01 98 04 00 00 00 00 00 00": ["0F FB 61 08 EC 01 08 00 00 00 00 00 98 04"],

      // CH2 status request
      "0F FB 61 02 FA 02 97 04 00 00 00 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"],

      // CH2 down
      "0F F8 61 05 06 02 00 00 00 8B 04 00 00 00": [
        "0F FB 61 08 EC 02 09 02 20 00 00 00 74 04",
        "0F F8 61 04 00 08 00 00 8C 04 00 00 00 00"
      ],

      // CH2 up
      "0F F8 61 05 05 02 00 00 00 8C 04 00 00 00": [
        "0F FB 61 08 EC 02 09 01 02 64 00 00 2F 04",
        "0F F8 61 04 00 04 00 00 90 04 00 00 00 00"
      ],

      // CH2 50%
      "0F F8 61 03 1C 02 32 45 04 00 00 00 00 00": [
        "0F FB 61 08 EC 02 09 02 20 00 00 00 74 04",
        "0F F8 61 04 00 08 00 00 8C 04 00 00 00 00"
      ],

      // CH2 up for 10s
      "0F F8 61 05 05 02 00 00 0A 82 04 00 00 00": [
        "0F FB 61 08 EC 02 09 00 02 32 00 00 62 04",
        "0F FB 61 08 EC 02 09 01 02 32 00 00 61 04",
        "0F F8 61 04 00 04 00 00 90 04 00 00 00 00"
      ],

      // CH2 down for 15s
      "0F F8 61 05 06 02 00 00 0F 7C 04 00 00 00": [
        "0F FB 61 08 EC 02 09 00 20 00 00 00 76 04",
        "0F FB 61 08 EC 02 09 02 20 00 00 00 74 04",
        "0F F8 61 04 00 08 00 00 8C 04 00 00 00 00"
      ],

      // CH2 halt
      "0F F8 61 02 04 02 90 04 00 00 00 00 00 00": [
        "0F FB 61 08 EC 02 09 00 00 63 00 00 33 04",
        "0F F8 61 04 00 00 04 00 90 04 00 00 00 00",
      ],

      // CH2 inhibit 1s
      "0F F8 61 05 16 02 00 00 01 7A 04 00 00 00": [
        "0F FB 61 08 EC 02 09 00 00 63 01 00 32 04",
        "0F F8 61 04 00 04 00 00 90 04 00 00 00 00"
      ],

      // CH2 inhibit indefinitely
      "0F F8 61 05 16 02 FF FF FF 7E 04 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 01 00 95 04"],

      // CH2 inhibit cancel
      "0F F8 61 02 17 02 7D 04 00 00 00 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"],

      // CH2 inhibit down indefinitely
      "0F F8 61 05 19 02 FF FF FF 7B 04 00 00 00": [
        "0F FB 61 08 EC 02 09 00 20 00 02 00 74 04",
        "0F FB 61 08 EC 02 09 02 20 00 02 00 72 04",
        "0F F8 61 04 00 08 00 00 8C 04 00 00 00 00",
        "0F FB 61 08 EC 02 09 00 00 64 02 00 30 04",
        "0F F8 61 04 00 00 08 00 8C 04 00 00 00 00"
      ],

      // CH2 force up for 5s
      "0F F8 61 05 12 02 00 00 05 7A 04 00 00 00": [
        "0F FB 61 08 EC 02 09 00 02 64 05 00 2B 04",
        "0F FB 61 08 EC 02 09 01 02 64 05 00 2A 04",
        "0F F8 61 04 00 04 00 00 90 04 00 00 00 00"
      ],

      // CH2 locked for 1s
      "0F F8 61 05 1A 02 00 00 01 76 04 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 06 00 90 04"],

      // CH2 locked indefinitely
      "0F F8 61 05 1A 02 FF FF FF 7A 04 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 06 00 90 04"],

      // CH2 lock cancel
      "0F F8 61 02 1B 02 79 04 00 00 00 00 00 00": ["0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"],

      /*
       * METEO PROCESSOR PACKETS
       */

      // Module type request address 254 (VMBMETEO)
      "0F FB FE 40 B8 04 00 00 00 00 00 00 00 00": ["0F FB FE 07 FF 31 E7 6C 01 16 26 31 04 00"],

      // Input status
      "0F FB FE 02 FA 00 FC 04 00 00 00 00 00 00": ["0F FB FE 07 ED 02 00 00 FD 0A 00 FB 04 00"],

      // Meteo status
      "0F FB FE 03 E5 0F 00 01 04 00 00 00 00 00": [
        "0F FB FE 07 A9 00 00 36 E5 00 00 2D 04 00",
        "0F FB FE 07 E6 2C 6F FE E3 57 E5 53 04 00"
      ],

      // Programs memory read
      "0F FB FE 03 C9 00 84 A8 04 00 00 00 00 00": [
        "0F FB FE 07 CC 00 84 06 0F 16 00 76 04 00",
        "0F FB FE 07 CC 00 88 08 1E 16 1E 43 04 00"
      ],

      /*
       * VMBPIRO PACKETS
       */

      // Module type request address 8 (VMBPIRO)
      "0F FB 08 40 AE 04 00 00 00 00 00 00 00 00": ["0F FB 08 07 FF 2C 14 05 01 15 19 74 04 00"],

      // Input status
      "0F FB 08 02 FA 00 F2 04 00 00 00 00 00 00": [
        "0F FB 08 08 ED 02 02 40 00 00 C0 01 F4 04",
        "0F FB 08 07 E6 3B 40 28 00 3B 60 C3 04 00"
      ],

      // Programs memory read alarm 1
      "0F FB 08 03 C9 00 32 F0 04 00 00 00 00 00": ["0F FB 08 07 CC 00 32 07 00 16 00 CC 04 00"],

      // Programs memory read alarm 2
      "0F FB 08 03 C9 00 36 EC 04 00 00 00 00 00": ["0F FB 08 07 CC 00 36 08 00 17 00 C6 04 00"],

      /*
       * VMB1TS PACKETS
       */

      // Module type request address 5 (VMB1TS)
      "0F FB 05 40 B1 04 00 00 00 00 00 00 00 00": ["0F FB 05 08 FF 0C 00 38 DD 01 12 47 6F 04"],

      // Input status
      "0F FB 05 02 FA 00 F5 04 00 00 00 00 00 00": ["0F FB 05 08 EA 00 00 40 3B 0A 00 00 7A 04"],

      // Current Temp status
      "0F FB 05 02 E5 00 0A 04 00 00 00 00 00 00": ["0F FB 05 07 E6 3B C0 26 A0 3C 80 87 04 00"],

      // Temp status
      "0F FB 05 02 E7 00 08 04 00 00 00 00 00 00": [
        "0F FB 05 08 E8 0A 2C 28 1E 0A 06 01 74 04",
        "0F FB 05 08 E9 2A 2E 34 48 00 3C 00 F0 04"
      ]
    ]
  }

  static writeDelayMillis = VelbusNetwork.DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS
  static timeoutMillis = VelbusDevice.INITIALISATION_TIMEOUT_MILLISECONDS

  def setupSpec() {
    // Minimal delays for simulated network
    VelbusNetwork.DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS = 10
    VelbusDevice.INITIALISATION_TIMEOUT_MILLISECONDS = 10
    // Uncomment and configure the below lines to communicate with an actual VELBUS network
    //        def client = new VelbusSocketMessageProcessor("192.168.0.65", 6000);
    //        VelbusNetwork.DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS = 100;
    //        def client = new VelbusSerialMessageProcessor("COM6", 38400);
    //        VelbusNetwork.DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS = 100;

    def scheduledTasksExecutor = Executors.newScheduledThreadPool(1)
    network = new VelbusNetwork(messageProcessor, scheduledTasksExecutor, null)

    loadDevicePackets(VelbusBasicTest.messageProcessor)
  }

  def cleanupSpec() {
    VelbusNetwork.DELAY_BETWEEN_PACKET_WRITES_MILLISECONDS = writeDelayMillis
    VelbusDevice.INITIALISATION_TIMEOUT_MILLISECONDS = timeoutMillis
    if (network != null) {
      network.close()
    }
  }

  def setup() {
    network.connect()

    conditions.eventually {
      assert network.getConnectionStatus() == ConnectionStatus.CONNECTED
    }
  }

  def cleanup() {
    def counter = 0
    while(network.messageQueue.size() > 0 && counter < 100) {
      Thread.sleep(20)
      counter++
    }
    network.disconnect()
    network.removeAllDevices()
  }

  def "Input Button Processor Test"() {

    given: "the input button processor"
    def inputButtonProcessor = (InputProcessor)VelbusDeviceType.VMBGPOD.getFeatureProcessors().find {
      it.getClass() == InputProcessor.class
    }
    VelbusDevice device = null

    when: "A device property value consumer is registered for device address 2 (VMB4AN)"
    Object ioAlarm1
    network.addPropertyValueConsumer(2, "CH1", { newValue ->
      ioAlarm1 = newValue
    })
    device = network.getDevice(2)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB4AN
      assert device.getBaseAddress() == 2

      1.upto(8, {
        def state = (it == 5 ? InputProcessor.ChannelState.PRESSED : InputProcessor.ChannelState.RELEASED)
        def locked = it == 3
        assert device.getPropertyValue("CH" + it) == state
        assert device.getPropertyValue("CH" + it + "_LOCKED") == locked
        assert device.getPropertyValue("CH" + it + "_LED") == FeatureProcessor.LedState.OFF
      })
    }

    when: "an alarm channel is locked for 1s"
    device.writeProperty("CH8_LOCK", 1d)

    then: "the channel should become locked"
    conditions.eventually {
      assert device.getPropertyValue("CH8_LOCKED")
    }

    and: "the channel should become unlocked again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 02 06 ED 10 04 00 01 00 EC 04 00 00"))
    conditions.eventually {
      assert !device.getPropertyValue("CH8_LOCKED")
    }

    when: "an input channel is locked indefinitely"
    device.writeProperty("CH8_LOCK", -1d)

    then: "the channel should become locked"
    conditions.eventually {
      assert device.getPropertyValue("CH8_LOCKED")
    }

    when: "the channel lock is canceled"
    device.writeProperty("CH8_LOCK", 0d)

    then: "the channel should become unlocked again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 02 06 ED 10 04 00 01 00 EC 04 00 00"))
    conditions.eventually {
      assert !device.getPropertyValue("CH8_LOCKED")
    }

    when: "A device property value consumer is registered for device address 1 (VMBGP1)"
    Object gp1Channel1
    network.addPropertyValueConsumer(1, "CH1", { newValue ->
      gp1Channel1 = newValue
    })
    device = network.getDevice(1)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGP1
      assert device.getBaseAddress() == 1
      assert device.getSubAddresses()[0] == 2
      assert device.getSubAddresses()[1] == 255
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 255

      // Check the processor shows this device as supporting 8 channels
      assert inputButtonProcessor.getMaxChannelNumber(device.getDeviceType()) == 8

      // Check channels are enabled
      1.upto(8, {
        assert inputButtonProcessor.isChannelEnabled(device, (int)it)
      })
    }

    and: "the input button property values should be initialized"
    conditions.eventually {
      1.upto(8, {
        assert device.getPropertyValue("CH" + it) == InputProcessor.ChannelState.RELEASED
      })
    }

    when: "A device property value consumer is registered for device address 48 (VMBGPOD)"
    Object gpodChannel22
    network.addPropertyValueConsumer(48, "CH22", { newValue ->
      gpodChannel22 = newValue
    })
    device = network.getDevice(48)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGPOD
      assert device.getBaseAddress() == 48
      assert device.getSubAddresses()[0] == 49
      assert device.getSubAddresses()[1] == 50
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 64

      // Check the processor shows this device as supporting 32 channels
      assert inputButtonProcessor.getMaxChannelNumber(device.getDeviceType()) == 32

      // Check channels in disabled sub address show as disabled
      1.upto(32, {
        if (it >= 25 && it <= 32) {
          assert !inputButtonProcessor.isChannelEnabled(device, it)
        } else {
          assert inputButtonProcessor.isChannelEnabled(device, it)
        }
      })
    }

    and: "the input button property values should be initialized"
    conditions.eventually {
      1.upto(32, {
        if (it >= 25 && it <= 32) {
          assert device.getPropertyValue("CH" + it) == InputProcessor.ChannelState.RELEASED
          assert !device.getPropertyValue("CH" + it + "_ENABLED")
          assert device.getPropertyValue("CH" + it + "_LED") == FeatureProcessor.LedState.OFF
          assert !device.getPropertyValue("CH" + it + "_LOCKED")
        } else {
          assert device.getPropertyValue("CH" + it) == InputProcessor.ChannelState.RELEASED
          assert device.getPropertyValue("CH" + it + "_LED") == FeatureProcessor.LedState.OFF
          assert !device.getPropertyValue("CH" + it + "_LOCKED")
          assert device.getPropertyValue("CH" + it + "_ENABLED")
        }
      })
    }

    when: "a button is long pressed on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 32 04 00 00 00 20 A3 04 00 00 00 00"))

    then: "the property value should change to LONG_PRESSED"
    conditions.eventually {
      assert gpodChannel22 == InputProcessor.ChannelState.LONG_PRESSED
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.LONG_PRESSED
    }

    when: "the button is released on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 32 04 00 00 20 00 A3 04 00 00 00 00"))

    then: "the property value should return to RELEASED"
    conditions.eventually {
      assert gpodChannel22 == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.RELEASED
    }

    when: "a button is pressed on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 32 04 00 20 00 00 A3 04 00 00 00 00"))

    then: "the property value should change to PRESSED"
    conditions.eventually {
      assert gpodChannel22 == InputProcessor.ChannelState.PRESSED
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.PRESSED
    }

    when: "the button is released on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 32 04 00 00 20 00 A3 04 00 00 00 00"))

    then: "the property value should return to RELEASED"
    conditions.eventually {
      assert gpodChannel22 == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.RELEASED
    }

    and: "eventually the message queue should become empty"
    conditions.eventually {
      assert network.messageQueue.size() == 0
    }

    when: "a button press is written to the device"
    messageProcessor.sentMessages.clear()
    network.writeProperty(48, "CH22", InputProcessor.ChannelState.PRESSED.name())

    then: "the button press packet should have been sent to the message processor"
    conditions.eventually {
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.PRESSED
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F F8 32 04 00 20 00 00 A3 04 00 00 00 00")
      }
    }

    when: "a button release is written to the device"
    network.writeProperty(48, "CH22", InputProcessor.ChannelState.RELEASED.name())

    then: "the button release packet should have been sent to the message processor"
    conditions.eventually {
      assert device.getPropertyValue("CH22") == InputProcessor.ChannelState.RELEASED
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F F8 32 04 00 00 20 00 A3 04 00 00 00 00")
      }
    }

    when: "an input channel is locked for 1s"
    device.writeProperty("CH8_LOCK", 1d)

    then: "the channel should become locked"
    conditions.eventually {
      assert device.getPropertyValue("CH8_LOCKED")
    }

    and: "the channel should become unlocked again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 30 07 ED 00 FF FF 00 00 00 D4 04"))
    conditions.eventually {
      assert !device.getPropertyValue("CH8_LOCKED")
    }

    when: "an input channel is locked indefinitely"
    device.writeProperty("CH8_LOCK", -1d)

    then: "the channel should become locked"
    conditions.eventually {
      assert device.getPropertyValue("CH8_LOCKED")
    }

    when: "the channel lock is canceled"
    device.writeProperty("CH8_LOCK", 0d)

    then: "the channel should become unlocked again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 30 07 ED 00 FF FF 00 00 00 D4 04"))
    conditions.eventually {
      assert !device.getPropertyValue("CH8_LOCKED")
    }

    and: "eventually the message queue should become empty"
    conditions.eventually {
      assert network.messageQueue.size() == 0
    }

    when: "a channel LED is set to on"
    messageProcessor.sentMessages.clear()
    device.writeProperty("CH1_LED", FeatureProcessor.LedState.ON.name())

    then: "the LED should be on"
    conditions.eventually {
      device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.ON
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F FB 30 02 F6 01 CD 04 00 00 00 00 00 00")
      }
    }

    when: "a channel LED is set to slow"
    messageProcessor.sentMessages.clear()
    device.writeProperty("CH1_LED", FeatureProcessor.LedState.SLOW.name())

    then: "the LED should be on slow"
    conditions.eventually {
      device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.SLOW
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F FB 30 02 F7 01 CC 04 00 00 00 00 00 00")
      }
    }

    when: "a channel LED is set to fast"
    messageProcessor.sentMessages.clear()
    device.writeProperty("CH1_LED", FeatureProcessor.LedState.FAST.name())

    then: "the LED should be on fast"
    conditions.eventually {
      device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.FAST
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F FB 30 02 F8 01 CB 04 00 00 00 00 00 00")
      }
    }

    when: "a channel LED is set to very fast"
    messageProcessor.sentMessages.clear()
    device.writeProperty("CH1_LED", FeatureProcessor.LedState.VERYFAST.name())

    then: "the LED should be on very fast"
    conditions.eventually {
      device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.VERYFAST
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F FB 30 02 F9 01 CA 04 00 00 00 00 00 00")
      }
    }

    when: "a channel LED is set to off"
    messageProcessor.sentMessages.clear()
    device.writeProperty("CH1_LED", FeatureProcessor.LedState.OFF.name())

    then: "the LED should be off"
    conditions.eventually {
      device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F FB 30 02 F5 01 CE 04 00 00 00 00 00 00")
      }
    }

    when: "A device property value consumer is registered for device address 10 (VMB7IN)"
    Object vmb7InChannel1
    network.addPropertyValueConsumer(10, "CH1", { newValue ->
      vmb7InChannel1 = newValue
    })
    device = network.getDevice(10)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB7IN
      assert device.getBaseAddress() == 10

      // Check the processor shows this device as supporting 7 channels
      assert inputButtonProcessor.getMaxChannelNumber(device.getDeviceType()) == 7

      // Check channels are enabled
      1.upto(7, {
        assert inputButtonProcessor.isChannelEnabled(device, it)
      })
    }

    and: "the input button property values should be initialized"
    conditions.eventually {
      1.upto(7, {
        if (it == 1) {
          // Channels with enabled counter shows as pressed
          assert device.getPropertyValue("CH" + it) == InputProcessor.ChannelState.PRESSED
        } else {
          assert device.getPropertyValue("CH" + it) == InputProcessor.ChannelState.RELEASED
        }
        assert device.getPropertyValue("CH" + it + "_LED") == FeatureProcessor.LedState.OFF
        assert !device.getPropertyValue("CH" + it + "_LOCKED")
        assert device.getPropertyValue("CH" + it + "_ENABLED")
      })
    }

    when: "a button is long pressed on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 0A 04 00 00 00 02 E9 04"))

    then: "the property value should change to LONG_PRESSED"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.LONG_PRESSED
    }

    when: "the button is released on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 0A 04 00 00 02 00 E9 04"))

    then: "the property value should return to RELEASED"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.RELEASED
    }

    when: "a button is pressed on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 0A 04 00 02 00 00 E9 04"))

    then: "the property value should change to PRESSED"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.PRESSED
    }

    when: "the button is released on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F F8 0A 04 00 00 02 00 E9 04"))

    then: "the property value should return to RELEASED"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.RELEASED
    }

    and: "eventually the message queue should become empty"
    conditions.eventually {
      assert network.messageQueue.size() == 0
    }

    when: "a button press is written to the device"
    messageProcessor.sentMessages.clear()
    network.writeProperty(10, "CH3", InputProcessor.ChannelState.PRESSED.name())

    then: "the button press packet should have been sent to the message processor"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == InputProcessor.ChannelState.PRESSED
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F F8 0A 04 00 04 00 00 E7 04 00 00 00 00")
      }
    }

    when: "a button release is written to the device"
    network.writeProperty(10, "CH3", InputProcessor.ChannelState.RELEASED.name())

    then: "the button release packet should have been sent to the message processor"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == InputProcessor.ChannelState.RELEASED
      if (network.client == messageProcessor) {
        assert messageProcessor.sentMessages.contains("0F F8 0A 04 00 00 04 00 E7 04 00 00 00 00")
      }
    }
  }

  def "Thermostat Processor Test"() {

    given: "the thermostat processor"
    def thermostatProcessor = (ThermostatProcessor)VelbusDeviceType.VMBGPOD.getFeatureProcessors().find {
      it.getClass() == ThermostatProcessor.class
    }
    VelbusDevice device = null

    when: "A device property value consumer is registered for a VMBGP1 device with the thermostat enabled"
    Object gp1CurrentTemp
    network.addPropertyValueConsumer(1, "TEMP_CURRENT", { newValue ->
      gp1CurrentTemp = newValue
    })
    device = network.getDevice(1)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGP1
      assert device.getBaseAddress() == 1
      assert device.getSubAddresses()[0] == 2
      assert device.getSubAddresses()[1] == 255
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 255

      // Check the processor shows the thermostat as enabled
      assert thermostatProcessor.isThermostatEnabled(device)
    }

    when: "A device property value consumer is registered for a VMBGP1 device with the thermostat disabled"
    network.addPropertyValueConsumer(3, "TEMP_CURRENT", { newValue ->
      gp1CurrentTemp = newValue
    })
    device = network.getDevice(3)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGP1
      assert device.getBaseAddress() == 3
      assert device.getSubAddresses()[0] == 255
      assert device.getSubAddresses()[1] == 255
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 255

      // Check the processor shows the thermostat as disabled
      assert !thermostatProcessor.isThermostatEnabled(device)
    }

    when: "A device property value consumer is registered for a VMBGPOD device with the thermostat enabled"
    Object gpodHeatNight
    network.addPropertyValueConsumer(48, "TEMP_TARGET_HEAT_NIGHT", { newValue ->
      gpodHeatNight = newValue
    })
    device = network.getDevice(48)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGPOD
      assert device.getBaseAddress() == 48
      assert device.getSubAddresses()[0] == 49
      assert device.getSubAddresses()[1] == 50
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 64

      // Check the processor shows the thermostat as enabled
      assert thermostatProcessor.isThermostatEnabled(device)
    }

    and: "the thermostat values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("HEATER") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("BOOST") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("PUMP") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("COOLER") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_ALARM1") == InputProcessor.ChannelState.PRESSED
      assert device.getPropertyValue("TEMP_ALARM2") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_ALARM3") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_ALARM4") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_SAFE
      assert device.getPropertyValue("TEMP_CURRENT") == 28.0d
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 12d
      assert device.getPropertyValue("TEMP_TARGET_COOL_COMFORT") == 21d
      assert device.getPropertyValue("TEMP_TARGET_COOL_DAY") == 23d
      assert device.getPropertyValue("TEMP_TARGET_COOL_NIGHT") == 26d
      assert device.getPropertyValue("TEMP_TARGET_COOL_SAFE") == 36d
      assert device.getPropertyValue("TEMP_TARGET_HEAT_COMFORT") == 25d
      assert device.getPropertyValue("TEMP_TARGET_HEAT_DAY") == 22d
      assert device.getPropertyValue("TEMP_TARGET_HEAT_NIGHT") == 16d
      assert device.getPropertyValue("TEMP_TARGET_HEAT_SAFE") == 12d
    }

    when: "we update the heat night temperature property"
    device.writeProperty("TEMP_TARGET_HEAT_NIGHT", 20.5)

    then: "the heat night temp should be updated"
    conditions.eventually {
      assert gpodHeatNight == 20.5d
    }

    when: "we receive a temperature sensor value"
    network.client.onMessageReceived(VelbusPacket.fromString("0F FB 30 07 E6 3F 60 30 A0 3F 80 AB 04"))

    then: "the temperature value should be resolved to 0.1 degrees"
    assert device.getPropertyValue("TEMP_CURRENT") == 31.7d

    when: "we set thermostat state to disabled"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.DISABLED.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.DISABLED
    }

    when: "we set the thermostat state to manual"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.MANUAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.MANUAL
    }

    when: "we set the thermostat state to normal"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.NORMAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
    }

    when: "we set the thermostat state to manual"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.MANUAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.MANUAL
    }

    when: "we set the thermostat state to disabled"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.DISABLED.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.DISABLED
    }

    when: "we set the thermostat state to normal"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.NORMAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
    }

    when: "we set the thermostat mode to heat comfort with timer"
    device.writeProperty("TEMP_MODE_HEAT_COMFORT_MINS", 1d)

    then: "the state and mode should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.TIMER
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_COMFORT
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 25d
    }

    when: "we set the thermostat state to disabled"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.DISABLED.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.DISABLED
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_SAFE
    }

    when: "we set the thermostat mode to heat comfort with timer"
    device.writeProperty("TEMP_MODE_HEAT_COMFORT_MINS", 1d)

    then: "the state and mode should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.TIMER
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_COMFORT
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 25d
    }

    when: "we set the thermostat state to manual"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.MANUAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.MANUAL
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_COMFORT
    }

    when: "we set the thermostat mode to heat comfort with timer"
    device.writeProperty("TEMP_MODE_HEAT_COMFORT_MINS", 1d)

    then: "the state and mode should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.TIMER
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_COMFORT
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 25d
    }

    when: "we set the thermostat state to normal"
    device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.NORMAL.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_COMFORT
    }

    when: "we switch back to HEAT SAFE"
    device.writeProperty("TEMP_MODE", ThermostatProcessor.TemperatureMode.HEAT_SAFE.name())

    then: "the state should update"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_SAFE
    }

    when: "we set temp mode to COOL DAY"
    device.writeProperty("TEMP_MODE", ThermostatProcessor.TemperatureMode.COOL_DAY.name())

    then: "the current temp mode should change"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.COOL_DAY
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 23d
    }

    when: "we set temp mode to COOL COMFORT permanently"
    // Set to COOL_COMFORT mode permanently
    messageProcessor.mockPackets["0F FB 30 03 DB FF FF EA 04 00 00 00 00 00"] = ["0F FB 30 08 EA C2 00 1A 37 2A 00 00 97 04"]

    device.writeProperty("TEMP_MODE_COOL_COMFORT_MINS", -1d)

    then: "the current temp mode should change"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.MANUAL
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.COOL_COMFORT
    }

    cleanup: "Reset the heat night target temperature"
    if (device != null) {
      device.writeProperty("TEMP_TARGET_HEAT_NIGHT", 16d)
      device.writeProperty("TEMP_STATE", ThermostatProcessor.TemperatureState.NORMAL.name())
      device.writeProperty("TEMP_MODE", ThermostatProcessor.TemperatureMode.HEAT_SAFE.name())
    }
  }

  def "Programs Processor Test"() {

    when: "A device property value consumer is registered for device address 48 (VMBGPOD)"
    Object gpodAlarm1WakeTime
    network.addPropertyValueConsumer(48, "ALARM1_WAKE_TIME", { newValue ->
      gpodAlarm1WakeTime = newValue
    })
    VelbusDevice device = network.getDevice(48)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGPOD
      assert device.getBaseAddress() == 48
      assert device.getSubAddresses()[0] == 49
      assert device.getSubAddresses()[1] == 50
      assert device.getSubAddresses()[2] == 255
      assert device.getSubAddresses()[3] == 64
    }

    and: "the programs property values should be initialized"
    conditions.eventually {
      1.upto(24, {
        def programStepsEnabled = it != 13
        assert device.getPropertyValue("CH" + it + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX) == programStepsEnabled
      })

      assert !device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      assert !device.getPropertyValue("SUNRISE_ENABLED")
      assert device.getPropertyValue("SUNSET_ENABLED")
      assert device.getPropertyValue("ALARM1_ENABLED")
      assert !device.getPropertyValue("ALARM2_ENABLED")
      assert device.getPropertyValue("ALARM1_MASTER")
      assert !device.getPropertyValue("ALARM2_MASTER")
      assert device.getPropertyValue("ALARM1_WAKE_TIME").equals(gpodAlarm1WakeTime)
      assert device.getPropertyValue("ALARM1_WAKE_TIME") == "16:00"
      assert device.getPropertyValue("ALARM1_BED_TIME") == "22:00"
      assert device.getPropertyValue("ALARM2_WAKE_TIME") == "08:00"
      assert device.getPropertyValue("ALARM2_BED_TIME") == "23:00"
      assert device.getPropertyValue("PROGRAM") == ProgramsProcessor.Program.SUMMER
    }

    when: "alarm 1 is disabled and alarm 2 is enabled"
    device.writeProperty("ALARM1_ENABLED", false)
    device.writeProperty("ALARM2_ENABLED", true)

    then: "the alarms should update to match"
    conditions.eventually {
      assert !device.getPropertyValue("ALARM1_ENABLED")
      assert device.getPropertyValue("ALARM2_ENABLED")
    }

    when: "sunrise is enabled and sunset disabled"
    device.writeProperty("SUNRISE_ENABLED", true)
    device.writeProperty("SUNSET_ENABLED", false)

    then: "the alarms should update to match"
    conditions.eventually {
      assert device.getPropertyValue("SUNRISE_ENABLED")
      assert !device.getPropertyValue("SUNSET_ENABLED")
    }

    when: "we enable program steps on channel 13"
    device.writeProperty("CH13" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX, true)

    then: "channel 13 program steps should be enabled and all channel program steps should now show as enabled"
    conditions.eventually {
      assert device.getPropertyValue("CH13" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      assert device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
    }

    when: "all channel program steps are disabled for 1s"
    device.writeProperty("ALL" + ProgramsProcessor.PROGRAM_STEPS_DISABLED_SECONDS_SUFFIX, 1d)

    then: "all channels should show as disabled"
    conditions.eventually {
      assert !device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      1.upto(24, {
        assert !device.getPropertyValue("CH" + it + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      })
    }

    when: "the module re-enables the channels"
    // Note below packets only used when mock message procoessor is used by the VelbusNetwork
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 30 07 ED 00 FF FF 00 00 99 3B 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 31 07 ED 00 FF FF 00 00 99 3A 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 32 07 ED 00 FF FF 00 00 99 39 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 33 07 ED 00 FF FF 00 00 99 38 04 00"))

    then: "all channels should show as enabled again"
    conditions.eventually {
      assert device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      1.upto(24, {
        assert device.getPropertyValue("CH" + it + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      })
    }

    when: "all channel 20 program steps are disabled for 1s"
    device.writeProperty("CH20" + ProgramsProcessor.PROGRAM_STEPS_DISABLED_SECONDS_SUFFIX, 1d)

    then: "only channel 20 should show as disabled"
    conditions.eventually {
      assert !device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      1.upto(24, {
        def programStepsEnabled = it != 20
        assert device.getPropertyValue("CH" + it + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX) == programStepsEnabled
      })
    }

    when: "the module re-enables the channel"
    // Note below packets only used when mock message procoessor is used by the VelbusNetwork
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 30 07 ED 00 FF FF 00 00 99 3B 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 31 07 ED 00 FF FF 00 00 99 3A 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 32 07 ED 00 FF FF 00 00 99 39 04 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 33 07 ED 00 FF FF 00 00 99 38 04 00"))

    then: "all channels should show as enabled again"
    conditions.eventually {
      assert device.getPropertyValue("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      1.upto(24, {
        assert device.getPropertyValue("CH" + it + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX)
      })
    }

    when: "the current program is changed to winter"
    device.writeProperty("PROGRAM", ProgramsProcessor.Program.WINTER.name())

    then: "the program should update"
    conditions.eventually {
      assert device.getPropertyValue("PROGRAM") == ProgramsProcessor.Program.WINTER
    }

    when: "the master alarm 1 wake and sleep times are updated"
    device.writeProperty("ALARM1_WAKE_TIME", "04:00")
    device.writeProperty("ALARM1_BED_TIME", "20:00")

    then: "the properties should update"
    conditions.eventually {
      assert device.getPropertyValue("ALARM1_WAKE_TIME") == "04:00"
      assert device.getPropertyValue("ALARM1_BED_TIME") == "20:00"
    }

    cleanup: "return initial state"
    if (device != null) {
      device.writeProperty("ALARM1_ENABLED", true)
      device.writeProperty("ALARM2_ENABLED", false)
      device.writeProperty("SUNRISE_ENABLED", false)
      device.writeProperty("SUNSET_ENABLED", true)
      device.writeProperty("ALL" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX, true)
      device.writeProperty("CH13" + ProgramsProcessor.PROGRAM_STEPS_ENABLED_SUFFIX, false)
      device.writeProperty("PROGRAM", ProgramsProcessor.Program.SUMMER.name())
      device.writeProperty("ALARM1_WAKE_TIME", "16:00")
      device.writeProperty("ALARM1_BED_TIME", "22:00")
    }
  }

  def "OLED Processor Test"() {

    when: "A device property value consumer is registered for device address 48 (VMBGPOD)"
    Object gpodMemoText
    network.addPropertyValueConsumer(48, "MEMO_TEXT", { newValue ->
      gpodMemoText = newValue
    })
    VelbusDevice device = network.getDevice(48)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBGPOD
      assert device.getBaseAddress() == 48
    }

    and: "the OLED property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("MEMO_TEXT") == ""
    }

    and: "eventually the message queue should become empty"
    conditions.eventually {
      assert network.messageQueue.size() == 0
    }

    when: "some memo text is sent to the device"
    def msg = "Hello world. This is a test message for the OLED panels to make sure they work:30"
    messageProcessor.sentMessages.clear()
    device.writeProperty("MEMO_TEXT", msg)

    then: "the memo text property should contain the text and the correct packets should have been sent to the device"
    if (network.client == messageProcessor) {
      conditions.eventually {
        assert device.getPropertyValue("MEMO_TEXT") == msg.substring(0, 62)
        assert messageProcessor.sentMessages.size() >= 13
        def firstPacketIndex = messageProcessor.sentMessages.indexOf("0F FB 30 08 AC 00 00 48 65 6C 6C 6F 1E 04")
        assert firstPacketIndex >= 0
        assert messageProcessor.sentMessages[firstPacketIndex + 1].toString() == "0F FB 30 08 AC 00 05 20 77 6F 72 6C 29 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 2].toString() == "0F FB 30 08 AC 00 0A 64 2E 20 54 68 9A 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 3].toString() == "0F FB 30 08 AC 00 0F 69 73 20 69 73 2B 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 4].toString() == "0F FB 30 08 AC 00 14 20 61 20 74 65 84 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 5].toString() == "0F FB 30 08 AC 00 19 73 74 20 6D 65 20 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 6].toString() == "0F FB 30 08 AC 00 1E 73 73 61 67 65 E1 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 7].toString() == "0F FB 30 08 AC 00 23 20 66 6F 72 20 68 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 8].toString() == "0F FB 30 08 AC 00 28 74 68 65 20 4F 3A 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 9].toString() == "0F FB 30 08 AC 00 2D 4C 45 44 20 70 80 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 10].toString() == "0F FB 30 08 AC 00 32 61 6E 65 6C 73 CD 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 11].toString() == "0F FB 30 08 AC 00 37 20 74 6F 20 6D 4B 04"
        assert messageProcessor.sentMessages[firstPacketIndex + 12].toString() == "0F FB 30 06 AC 00 3C 61 6B 00 0C 04 00 00"
      }
    }

    cleanup: "put the device state back"
    if (device != null) {
      device.writeProperty("MEMO_TEXT", "")
    }
  }

  def "Relay Processor Test"() {

    when: "A device property value consumer is registered for device address 81 (VMB1RYNO)"
    Object relayChannel1State
    network.addPropertyValueConsumer(81, "CH1", { newValue ->
      relayChannel1State = newValue
    })
    VelbusDevice device = network.getDevice(81)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB1RYNO
      assert device.getBaseAddress() == 81
    }

    and: "the relay property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH5") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH5_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("CH1_LOCKED")
      assert !device.getPropertyValue("CH2_LOCKED")
      assert !device.getPropertyValue("CH3_LOCKED")
      assert !device.getPropertyValue("CH4_LOCKED")
      assert !device.getPropertyValue("CH5_LOCKED")
      assert !device.getPropertyValue("CH1_INHIBITED")
      assert !device.getPropertyValue("CH2_INHIBITED")
      assert !device.getPropertyValue("CH3_INHIBITED")
      assert !device.getPropertyValue("CH4_INHIBITED")
      assert !device.getPropertyValue("CH5_INHIBITED")
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH5_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is switched on"
    device.writeProperty("CH4", true)

    then: "a relay should be on"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == RelayProcessor.ChannelState.ON
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is switched off"
    device.writeProperty("CH4", false)

    then: "a relay should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is set to intermittent"
    device.writeProperty("CH1", RelayProcessor.ChannelState.INTERMITTENT.name())

    then: "the relay should be in intermittent mode"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.INTERMITTENT
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.FAST
    }

    when: "the relay is switched off again"
    device.writeProperty("CH1", false)

    then: "the relay should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is set to intermittent for 1s"
    device.writeProperty("CH2_INTERMITTENT", 1d)

    then: "the relay should be in intermittent state"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.INTERMITTENT
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.FAST
    }

    and: "the relay should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is set to intermittent indefinitely"
    device.writeProperty("CH2_INTERMITTENT", -1d)

    then: "the relay should be in intermittent state"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.INTERMITTENT
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.FAST
    }

    when: "the relay intermittent state is canceled"
    device.writeProperty("CH2_INTERMITTENT", 0d)

    then: "the relay should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is inhibited for 1s"
    device.writeProperty("CH2_INHIBIT", 1d)

    then: "the relay should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.INHIBITED
    }

    and: "the relay should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is inhibited indefinitely"
    device.writeProperty("CH2_INHIBIT", -1d)

    then: "the relay should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.INHIBITED
    }

    when: "the relay inhibit is canceled"
    device.writeProperty("CH2_INHIBIT", 0d)

    then: "the relay should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is forced on for 1s"
    device.writeProperty("CH3_FORCE_ON", 1d)

    then: "a relay should be on"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == RelayProcessor.ChannelState.ON
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.FORCED
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.ON
    }

    and: "the relay should switch off again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 04 00 00 00 00 00 00 9E 04"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 30 02 F5 02 CD 04 00 00 00 00 00 00"))
    conditions.eventually {
      assert device.getPropertyValue("CH3") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is locked for 1s"
    device.writeProperty("CH1_LOCK", 1d)

    then: "the relay should be off and disabled (forced off)"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
      assert device.getPropertyValue("CH1_LOCKED")
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
    }

    and: "the relay should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 01 00 00 00 00 00 00 A1 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is switched on for 1s"
    device.writeProperty("CH1_ON", 1d)

    then: "the relay should be on and in normal state"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.ON
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.FAST
    }

    and: "the relay should return to off"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 01 00 00 00 00 00 00 A1 04"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 40 02 F5 01 BE 04 00 00 00 00 00 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 51 04 00 00 01 00 A3 04 00 00 00 00"))
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is switched on indefinitely"
    device.writeProperty("CH1_ON", -1d)

    then: "the relay should be on and in normal state"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.ON
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.ON
    }

    when: "we cancel the on timer"
    device.writeProperty("CH1_ON", 0d)

    then: "the relay should return to off"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a relay is locked for 1s"
    device.writeProperty("CH2_LOCK", 1d)

    then: "the relay should be disabled"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
    }

    and: "the relay should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a relay is locked indefinitely"
    device.writeProperty("CH2_LOCK", -1d)

    then: "the relay should be locked"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
    }

    when: "the relay lock is canceled"
    device.writeProperty("CH2_LOCK", 0d)

    then: "the relay should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 51 08 FB 02 00 00 00 00 00 00 A0 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == RelayProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    cleanup: "put the device state back"
    if (device != null) {
      device.writeProperty("CH1", false)
      device.writeProperty("CH2", false)
      device.writeProperty("CH3", false)
      device.writeProperty("CH4", false)
      device.writeProperty("CH5", false)
    }
  }

  def "Counter Processor Test"() {

    when: "A device property value consumer is registered for device address 10 (VMB7IN)"
    Object vmb7InCounter1
    network.addPropertyValueConsumer(10, "COUNTER1", { newValue ->
      vmb7InCounter1 = newValue
    })
    VelbusDevice device = network.getDevice(10)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB7IN
      assert device.getBaseAddress() == 10
    }

    and: "the counter property values should be initialized"
    conditions.eventually {
      1.upto(4, {
        if (it == 1) {
          assert device.getPropertyValue("COUNTER" + it + "_ENABLED")
          assert device.getPropertyValue("COUNTER" + it) == 0d
          assert device.getPropertyValue("COUNTER" + it + "_INSTANT") == 0.02
          assert device.getPropertyValue("COUNTER" + it + "_UNITS") == CounterProcessor.CounterUnits.LITRES
        } else if (it == 2) {
          assert device.getPropertyValue("COUNTER" + it + "_ENABLED")
          assert device.getPropertyValue("COUNTER" + it) == 0d
          assert device.getPropertyValue("COUNTER" + it + "_INSTANT") == 140d
          assert device.getPropertyValue("COUNTER" + it + "_UNITS") == CounterProcessor.CounterUnits.KILOWATTS
        } else {
          assert !device.getPropertyValue("COUNTER" + it + "_ENABLED")
          assert device.getPropertyValue("COUNTER" + it) == 0d
          assert device.getPropertyValue("COUNTER" + it + "_INSTANT") == 0d
          assert device.getPropertyValue("COUNTER" + it + "_UNITS") == null
        }
      })
    }

    when: "the counter updates on the device"
    network.client.onMessageReceived(VelbusPacket.fromString("0F FB 0A 08 BE 68 00 00 00 71 23 46 E4 04"))

    then: "the counter should update to match"
    conditions.eventually {
      assert device.getPropertyValue("COUNTER1") == 0.04
      assert device.getPropertyValue("COUNTER1_INSTANT") == 0.15
    }

    when: "the counter is reset"
    device.writeProperty("COUNTER1_RESET", AttributeExecuteStatus.REQUEST_START)

    then: "the counter should update to match"
    conditions.eventually {
      assert device.getPropertyValue("COUNTER1") == 0d
      assert device.getPropertyValue("COUNTER1_INSTANT") == 0.02
    }
  }

  // TODO: Add tests for VMB4AN once protocol doc is finished
  def "Analog Output Processor Test"() {

    when: "A device property value consumer is registered for device address 187 (VMB4DC)"
    Object output1State
    network.addPropertyValueConsumer(2, "OUTPUT1", { newValue ->
      output1State = newValue
    })
    VelbusDevice device = network.getDevice(2)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB4AN
      assert device.getBaseAddress() == 2
    }

    and: "the IO module property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("OUTPUT1") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("OUTPUT2") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("OUTPUT3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("OUTPUT1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("OUTPUT2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("OUTPUT3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("OUTPUT1_LOCKED")
      assert !device.getPropertyValue("OUTPUT2_LOCKED")
      assert !device.getPropertyValue("OUTPUT3_LOCKED")
      assert !device.getPropertyValue("OUTPUT4_LOCKED")
      assert !device.getPropertyValue("OUTPUT1_INHIBITED")
      assert !device.getPropertyValue("OUTPUT2_INHIBITED")
      assert !device.getPropertyValue("OUTPUT3_INHIBITED")
      assert !device.getPropertyValue("OUTPUT4_INHIBITED")
      assert device.getPropertyValue("OUTPUT1_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("OUTPUT2_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("OUTPUT3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("OUTPUT4_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("OUTPUT1_VALUE") == 0d
      assert device.getPropertyValue("OUTPUT2_VALUE") == 0d
      assert device.getPropertyValue("OUTPUT3_VALUE") == 0d
      assert device.getPropertyValue("OUTPUT4_VALUE") == 0d
    }

    //        when: "an output channel is switched on"
    //        device.writeProperty("OUTPUT4", true)
    //
    //        then: "the output should be on 100%"
    //        conditions.eventually {
    //            assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.ON
    //            assert device.getPropertyValue("OUTPUT4_VALUE") == 3839
    //            assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    //            assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.ON
    //        }
    //
    //        when: "the output is switched off"
    //        device.writeProperty("OUTPUT4", false)
    //
    //        then: "the output should be off"
    //        conditions.eventually {
    //            assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.OFF
    //            assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    //            assert device.getPropertyValue("OUTPUT4_LED") == FeatureProcessor.LedState.OFF
    //        }
    //
    //        when: "a output channel is set to 50%"
    //        device.writeProperty("OUTPUT4_LEVEL", 50d)
    //
    //        then: "the output should be on 5V"
    //        conditions.eventually {
    //            assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.ON
    //            assert device.getPropertyValue("OUTPUT4_VALUE") == 50000
    //            assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    //            assert device.getPropertyValue("OUTPUT4_LED") == FeatureProcessor.LedState.ON
    //        }
    //
    //        when: "the output is switched off"
    //        device.writeProperty("OUTPUT4", false)
    //
    //        then: "the output should be off"
    //        conditions.eventually {
    //            assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.OFF
    //            assert device.getPropertyValue("OUTPUT4_VALUE") == 0
    //            assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    //            assert device.getPropertyValue("OUTPUT4_LED") == FeatureProcessor.LedState.OFF
    //        }
    //
    //        when: "a output channel is set to last"
    //        device.writeProperty("OUTPUT4", AnalogOutputProcessor.ChannelState.LAST.objectToValue(STRING))
    //
    //        then: "the output should go to 5V"
    //        conditions.eventually {
    //            assert device.getPropertyValue("OUTPUT4") == AnalogOutputProcessor.ChannelState.ON
    //            assert device.getPropertyValue("OUTPUT4_VALUE") == 50000
    //            assert device.getPropertyValue("OUTPUT4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    //            assert device.getPropertyValue("OUTPUT4_LED") == FeatureProcessor.LedState.ON
    //        }


    when: "A device property value consumer is registered for device address 187 (VMB4DC)"
    Object dimmerChannel1State
    network.addPropertyValueConsumer(187, "CH1", { newValue ->
      dimmerChannel1State = newValue
    })
    device = network.getDevice(187)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB4DC
      assert device.getBaseAddress() == 187
    }

    and: "the dimmer property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH1_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("CH1_LOCKED")
      assert !device.getPropertyValue("CH2_LOCKED")
      assert !device.getPropertyValue("CH3_LOCKED")
      assert !device.getPropertyValue("CH4_LOCKED")
      assert !device.getPropertyValue("CH1_INHIBITED")
      assert !device.getPropertyValue("CH2_INHIBITED")
      assert !device.getPropertyValue("CH3_INHIBITED")
      assert !device.getPropertyValue("CH4_INHIBITED")
      assert device.getPropertyValue("CH1_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH1_LEVEL") == 0d
      assert device.getPropertyValue("CH2_LEVEL") == 0d
      assert device.getPropertyValue("CH3_LEVEL") == 0d
      assert device.getPropertyValue("CH4_LEVEL") == 0d
    }

    when: "a dimmer channel is switched on"
    device.writeProperty("CH4", true)

    then: "the dimmer should be on 100%"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH4_LEVEL") == 100d
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.ON
    }

    when: "the dimmer is switched off"
    device.writeProperty("CH4", false)

    then: "the dimmer should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer channel is set to 50%"
    device.writeProperty("CH4_LEVEL", 50d)

    then: "the dimmer should be on 50%"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH4_LEVEL") == 50d
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.ON
    }

    when: "the dimmer is switched off"
    device.writeProperty("CH4", false)

    then: "the dimmer should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4_LEVEL") == 0d
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer channel is set to last"
    device.writeProperty("CH4", AnalogOutputProcessor.ChannelState.LAST.name())

    then: "the dimer should go to 50%"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH4_LEVEL") == 50d
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.ON
    }

    when: "the dimmer is switched off again"
    device.writeProperty("CH4", false)

    then: "the dimmer should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH4") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH4_LEVEL") == 0d
      assert device.getPropertyValue("CH4_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH4_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer is set to 75% over 7s"
    device.writeProperty("CH2_LEVEL_AND_SPEED", "75:7")

    then: "the dimmer should reach 75% after 7s"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.ON
      assert device.getPropertyValue("CH2_LEVEL") == 75d
    }

    when: "the dimmer is switched off again"
    device.writeProperty("CH2", false)

    then: "the dimmer should be off"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_LEVEL") == 0d
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer is switched on for 3s"
    device.writeProperty("CH2_ON", 3)

    then: "the dimmer should switch on"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.FAST
    }

    and: "eventually the message queue should become empty"
    conditions.eventually {
      assert network.messageQueue.size() == 0
    }

    then: "the dimmer should switch off again"
    if (network.client == messageProcessor) {
      messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 BB 04 00 00 02 00 38 04 00 00 00 00"))
      messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 02 00 00 00 00 00 00 79 04"))
    }
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH2_LEVEL") == 0d
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer is set to 50% over 60s"
    device.writeProperty("CH2_LEVEL_AND_SPEED", "50:60")

    then: "the dimmer should start ramping up"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.FAST
    }

    when: "we cancel the current dimming operation"
    device.writeProperty("CH2_LEVEL", -1d)

    then: "the dimmer level should be somewhere between 0-50% and be on"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH2_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED") == FeatureProcessor.LedState.ON
      def dimLevel = device.getPropertyValue("CH2_LEVEL")
      assert dimLevel> 0 && dimLevel < 50
    }

    when: "a dimmer is inhibited for 1s"
    device.writeProperty("CH3_INHIBIT", 1d)

    then: "the dimmer should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.INHIBITED
    }

    and: "the dimmer should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a dimmer is inhibited indefinitely"
    device.writeProperty("CH3_INHIBIT", -1d)

    then: "the dimmer should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.INHIBITED
    }

    when: "the dimmer inhibit is canceled"
    device.writeProperty("CH3_INHIBIT", 0d)

    then: "the dimmer should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a dimmer is forced on for 5s"
    device.writeProperty("CH3_FORCE_ON", 5d)

    then: "the dimmer should be on"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.FORCED
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.ON
      assert device.getPropertyValue("CH3_LEVEL") == 100
    }

    and: "the dimmer should switch off again"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 BB 04 00 00 04 00 36 04 00 00 00 00"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"))

    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH3_LEVEL") == 0
    }

    when: "a dimmer is locked for 1s"
    device.writeProperty("CH3_LOCK", 1d)

    then: "the dimmer should be off and locked"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
      assert device.getPropertyValue("CH3_LOCKED")
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH3_LEVEL") == 0
    }

    and: "the dimmer should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH3_LEVEL") == 0
    }

    when: "a dimmer is switched on indefinitely"
    device.writeProperty("CH3_ON", -1d)

    then: "the dimmer should be on and in normal state"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.ON
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.ON
    }

    when: "we cancel the on timer"
    device.writeProperty("CH3_ON", 0d)

    then: "the dimmer should return to off"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH3_LED") == FeatureProcessor.LedState.OFF
    }

    when: "a dimmer is locked for 1s"
    device.writeProperty("CH3_LOCK", 1d)

    then: "the dimmer should be disabled"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
    }

    and: "the dimmer should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB BB 08 B8 04 00 00 00 00 00 00 77 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    when: "a dimmer is locked indefinitely"
    device.writeProperty("CH3_LOCK", -1d)

    then: "the dimmer should be locked"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.LOCKED
    }

    when: "the dimmer lock is canceled"
    device.writeProperty("CH3_LOCK", 0d)

    then: "the dimmer should return to normal"
    conditions.eventually {
      assert device.getPropertyValue("CH3") == AnalogOutputProcessor.ChannelState.OFF
      assert device.getPropertyValue("CH3_SETTING") == OutputChannelProcessor.ChannelSetting.NORMAL
    }

    cleanup: "put the device state back"
    if (device != null) {
      device.writeProperty("CH1", false)
      device.writeProperty("CH2", false)
      device.writeProperty("CH3", false)
      device.writeProperty("CH4", false)
    }
  }

  def "Blind Processor Test"() {

    when: "A device property value consumer is registered for device address 97 (VMB2BLE)"
    Object blindChannel1State
    network.addPropertyValueConsumer(97, "CH1", { newValue ->
      blindChannel1State = newValue
    })
    VelbusDevice device = network.getDevice(97)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB2BLE
      assert device.getBaseAddress() == 97
    }

    and: "the blind property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("CH1") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH1_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("CH1_LOCKED")
      assert !device.getPropertyValue("CH2_LOCKED")
      assert !device.getPropertyValue("CH1_INHIBITED")
      assert !device.getPropertyValue("CH2_INHIBITED")
      assert device.getPropertyValue("CH1_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH1_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH1_POSITION") == 0d
      assert device.getPropertyValue("CH2_POSITION") == 0d
    }

    when: "a blind channel set to down"
    device.writeProperty("CH2", true)

    then: "the blind should reach 100%"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.DOWN
    }
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 64 00 00 32 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_POSITION") == 100d
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "the blind is set to up"
    device.writeProperty("CH2", false)

    then: "the dimmer should return to 0%"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.UP
    }
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_POSITION") == 0d
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "a blind channel is set to 50%"
    device.writeProperty("CH2_POSITION", 50d)

    then: "the blind should be at 50%"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.DOWN
    }
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 32 00 00 64 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_POSITION") == 50d
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "the blind is set to up for 10s"
    device.writeProperty("CH2_UP", 10d)

    then: "the blind should be up"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.UP
    }
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 61 04 00 00 04 00 90 04 00 00 00 00"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_POSITION") == 0d
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "a blind channel is set to down for 15s"
    device.writeProperty("CH2_DOWN", 15d)

    then: "the blind should be down"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.DOWN
    }
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 64 00 00 32 04"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 61 04 00 00 08 00 8C 04 00 00 00 00"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_POSITION") == 100d
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "a blind is set to up"
    device.writeProperty("CH2", false)

    then: "the blind should start moving up"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.UP
    }

    when: "we cancel the current up operation"
    device.writeProperty("CH2", null)

    then: "the blind should stop and the position should be somewhere between 100%-0%"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      def position = device.getPropertyValue("CH2_POSITION")
      assert position < 100 && position> 0
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
    }

    when: "a blind is inhibited for 1s"
    device.writeProperty("CH2_INHIBIT", 1d)

    then: "the blind should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.INHIBITED
    }

    and: "the blind should return to normal after 1s"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
    }

    when: "a blind is inhibited indefinitely"
    device.writeProperty("CH2_INHIBIT", -1d)

    then: "the blind should be inhibited"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.INHIBITED
    }

    when: "the blind inhibit is canceled"
    device.writeProperty("CH2_INHIBIT", 0d)

    then: "the blind should return to normal"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
    }

    when: "a blind is inhibited down indefinitely"
    device.writeProperty("CH2_INHIBIT_DOWN", -1d)

    then: "the blind should be inhibited from going down"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_LED_DOWN") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.INHIBITED_DOWN
    }

    when: "the blind inhibit is canceled"
    device.writeProperty("CH2_INHIBIT", 0d)

    then: "the blind should return to normal"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
    }

    when: "a blind is forced up for 5s"
    device.writeProperty("CH2_FORCE_UP", 5d)

    then: "the blind should be up and forced"
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.FAST
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.FORCED_UP
    }

    then: "the blind should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"))
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F F8 61 04 00 00 04 00 90 04 00 00 00 00"))
    conditions.eventually {
      assert device.getPropertyValue("CH2_LED_UP") == FeatureProcessor.LedState.OFF
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
    }

    when: "a blind is locked for 1s"
    device.writeProperty("CH2_LOCK", 1d)

    then: "the blind should be locked"
    conditions.eventually {
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.LOCKED
      assert device.getPropertyValue("CH2_LOCKED")
    }

    and: "the blind should return to normal"
    messageProcessor.onMessageReceived(VelbusPacket.fromString("0F FB 61 08 EC 02 09 00 00 00 00 00 96 04"))
    conditions.eventually {
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("CH2_LOCKED")
    }

    when: "a blind is locked indefinitely"
    device.writeProperty("CH2_LOCK", -1d)

    then: "the blind should be locked"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.LOCKED
      assert device.getPropertyValue("CH2_LOCKED")
    }

    when: "the blind lock is canceled"
    device.writeProperty("CH2_LOCK", 0d)

    then: "the blind should return to normal"
    conditions.eventually {
      assert device.getPropertyValue("CH2") == BlindProcessor.ChannelState.HALT
      assert device.getPropertyValue("CH2_SETTING") == BlindProcessor.ChannelSetting.NORMAL
      assert !device.getPropertyValue("CH2_LOCKED")
    }

    cleanup: "put the device state back"
    if (device != null) {
      //device.writeProperty("CH1", false)
      device.writeProperty("CH2", false)
      device.writeProperty("CH2_LOCK", 0d)
      device.writeProperty("CH2_INHIBIT_DOWN", 0d)
      device.writeProperty("CH2_INHIBIT_UP", 0d)
      device.writeProperty("CH2_FORCE_UP", 0d)
      device.writeProperty("CH2_FORCE_DOWN", 0d)
    }
  }

  def "Analog Input Processor Test"() {

    when: "A device property value consumer is registered for device address 2 (VMB4AN)"
    Object ioSensor1
    network.addPropertyValueConsumer(2, "SENSOR1", { newValue ->
      ioSensor1 = newValue
    })
    def device = network.getDevice(2)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB4AN
      assert device.getBaseAddress() == 2
      assert device.getPropertyValue("SENSOR1") == 1097.75
      assert device.getPropertyValue("SENSOR1_TEXT") == "25.2°C         "
      assert device.getPropertyValue("SENSOR1_TYPE") == AnalogInputProcessor.SensorType.RESISTANCE
      assert device.getPropertyValue("SENSOR1_MODE") == AnalogInputProcessor.SensorMode.SAFE
      assert device.getPropertyValue("SENSOR2"), closeTo(0, 0.0001)
      assert device.getPropertyValue("SENSOR2_TEXT") == "0Unit          "
      assert device.getPropertyValue("SENSOR2_TYPE") == AnalogInputProcessor.SensorType.CURRENT
      assert device.getPropertyValue("SENSOR2_MODE") == AnalogInputProcessor.SensorMode.SAFE
      assert device.getPropertyValue("SENSOR3"), closeTo(0, 0.0001)
      assert device.getPropertyValue("SENSOR3_TEXT") == "0Unit          "
      assert device.getPropertyValue("SENSOR3_TYPE") == AnalogInputProcessor.SensorType.VOLTAGE
      assert device.getPropertyValue("SENSOR3_MODE") == AnalogInputProcessor.SensorMode.SAFE
      assert device.getPropertyValue("SENSOR4"), closeTo(0, 0.0001)
      assert device.getPropertyValue("SENSOR4_TEXT") == "0Unit          "
      assert device.getPropertyValue("SENSOR4_TYPE") == AnalogInputProcessor.SensorType.VOLTAGE
      assert device.getPropertyValue("SENSOR4_MODE") == AnalogInputProcessor.SensorMode.SAFE
    }

    when: "A device property value consumer is registered for device address 254 (VMBMETEO)"
    Object tempCurrent
    network.addPropertyValueConsumer(254, "TEMP_CURRENT", { newValue ->
      tempCurrent = newValue
    })
    device = network.getDevice(254)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBMETEO
      assert device.getBaseAddress() == 254
    }

    and: "the device property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_CURRENT") == 22.2
      assert device.getPropertyValue("TEMP_MIN") == -0.6
      assert device.getPropertyValue("TEMP_MAX") == 43.9
      assert device.getPropertyValue("RAINFALL") == 0d
      assert device.getPropertyValue("LIGHT") == 13797d
      assert device.getPropertyValue("WIND") == 0d
      assert device.getPropertyValue("CH1") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.PRESSED
      assert device.getPropertyValue("CH3") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH4") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH5") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH6") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH7") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH8") == InputProcessor.ChannelState.RELEASED
    }
  }

  def "VMBPIRO Test"() {

    when: "A device property value consumer is registered for device address 8 (VMBPIRO)"
    Object tempCurrent
    network.addPropertyValueConsumer(8, "TEMP_CURRENT", { newValue ->
      tempCurrent = newValue
    })
    VelbusDevice device = network.getDevice(8)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMBPIRO
      assert device.getBaseAddress() == 8
    }

    and: "the device property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_CURRENT") == 29.6
      assert device.getPropertyValue("TEMP_MIN") == 20.0
      assert device.getPropertyValue("TEMP_MAX") == 29.7
      assert device.getPropertyValue("CH1") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH2") == InputProcessor.ChannelState.PRESSED
      assert device.getPropertyValue("CH3") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH4") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH5") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH6") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH7") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("CH8") == InputProcessor.ChannelState.RELEASED
    }
  }

  def "VMB1TS Test"() {

    when: "A device property value consumer is registered for device address 5 (VMB1TS)"
    Object tempCurrent
    network.addPropertyValueConsumer(5, "TEMP_CURRENT", { newValue ->
      tempCurrent = newValue
    })
    VelbusDevice device = network.getDevice(5)

    then: "The device should be created and become initialized"
    conditions.eventually {
      assert device != null
      assert device.getDeviceType() == VelbusDeviceType.VMB1TS
      assert device.getBaseAddress() == 5
    }

    and: "the device property values should be initialized"
    conditions.eventually {
      assert device.getPropertyValue("TEMP_CURRENT") == 29.9
      assert device.getPropertyValue("TEMP_MIN") == 19.3
      assert device.getPropertyValue("TEMP_MAX") == 30.3
      assert device.getPropertyValue("HEATER") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("BOOST") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("PUMP") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("COOLER") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_ALARM1") == InputProcessor.ChannelState.RELEASED
      assert device.getPropertyValue("TEMP_ALARM2") == InputProcessor.ChannelState.PRESSED
      assert device.getPropertyValue("TEMP_TARGET_CURRENT") == 5.0
      assert device.getPropertyValue("TEMP_MODE") == ThermostatProcessor.TemperatureMode.HEAT_SAFE
      assert device.getPropertyValue("TEMP_STATE") == ThermostatProcessor.TemperatureState.NORMAL
    }
  }
}
