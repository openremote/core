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
package org.openremote.test.protocol;

import java.util.logging.Logger;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.AbstractLink;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;

public class KNXTestingNetworkLink extends AbstractLink {

  private static final Logger LOG = Logger.getLogger(KNXTestingNetworkLink.class.getName());

  private static volatile KNXTestingNetworkLink instance;
  private volatile String lastDataReceived;

  public KNXTestingNetworkLink(Object... args) throws KNXFormatException {
    super(
        "KNXTestingLink",
        KNXMediumSettings.create(KNXMediumSettings.MEDIUM_TP1, new IndividualAddress("1.1.1")));
    KNXTestingNetworkLink.instance = this;
  }

  @Override
  protected void onSend(CEMILData msg, boolean waitForCon)
      throws KNXTimeoutException, KNXLinkClosedException {
    if (!msg.getDestination().toString().equals("0/4/14")) {
      LOG.info("KNX bus received message: " + msg);
      this.lastDataReceived = byteArrayToHex(msg.getPayload());
    }
  }

  @Override
  protected CEMI onReceive(FrameEvent e) throws KNXFormatException {
    return super.onReceive(e);
  }

  public static KNXTestingNetworkLink getInstance() {
    return KNXTestingNetworkLink.instance;
  }

  public static String byteArrayToHex(byte[] a) {
    StringBuilder sb = new StringBuilder(a.length * 2);
    for (byte b : a) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  public String getLastDataReceived() {
    return this.lastDataReceived;
  }

  @Override
  protected void onSend(KNXAddress dst, byte[] msg, boolean waitForCon)
      throws KNXTimeoutException, KNXLinkClosedException {
    // TODO Auto-generated method stub

  }
}
