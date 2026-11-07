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
package org.openremote.model.provisioning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A one-time challenge, valid for 60 seconds on the requesting MQTT connection only. Sign the exact
 * UTF-8 bytes of {@code challenge} with the certificate's private key using {@code algorithm}, then
 * send the Base64-encoded signature in an {@link X509ProvisioningMessage} on the same connection.
 *
 * <p>Clients must verify that {@code challenge} starts with {@link #CHALLENGE_PREFIX} and that the
 * topic line that follows is their own request topic before signing anything; otherwise a device
 * acts as a signing oracle for arbitrary data sent by whatever is on the other end of the
 * connection, which matters particularly when the same key is also used for TLS client
 * authentication.
 */
public class X509ProvisioningChallengeResponseMessage extends ProvisioningMessage {

  /** Domain separator that every challenge issued by the manager starts with. */
  public static final String CHALLENGE_PREFIX = "OpenRemote MQTT X509 provisioning v1\n";

  protected String challenge;
  protected String algorithm;

  @JsonCreator
  public X509ProvisioningChallengeResponseMessage(
      @JsonProperty("challenge") String challenge, @JsonProperty("algorithm") String algorithm) {
    this.challenge = challenge;
    this.algorithm = algorithm;
  }

  public String getChallenge() {
    return challenge;
  }

  public String getAlgorithm() {
    return algorithm;
  }
}
