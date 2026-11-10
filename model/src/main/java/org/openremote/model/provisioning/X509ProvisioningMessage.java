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
package org.openremote.model.provisioning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Completes an X.509 provisioning challenge using proof of possession of the private key. */
public class X509ProvisioningMessage extends ProvisioningMessage {
  protected String cert;
  protected String signature;

  public X509ProvisioningMessage(String cert) {
    this(cert, null);
  }

  @JsonCreator
  public X509ProvisioningMessage(
      @JsonProperty("cert") String cert, @JsonProperty("signature") String signature) {
    this.cert = cert;
    this.signature = signature;
  }

  public String getCert() {
    return cert;
  }

  /** Base64-encoded signature over the UTF-8 bytes of the server's challenge. */
  public String getSignature() {
    return signature;
  }
}
