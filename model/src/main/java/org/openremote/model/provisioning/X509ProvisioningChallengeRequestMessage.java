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
 * Requests a challenge on the connection that owns the device's provisioning response topic; the
 * answer is an {@link X509ProvisioningChallengeResponseMessage}. This is a sibling of {@link
 * X509ProvisioningMessage} rather than a subclass so that it carries no unused signature field and
 * so that the two steps of the exchange stay distinguishable.
 */
public class X509ProvisioningChallengeRequestMessage extends ProvisioningMessage {

  protected String cert;

  @JsonCreator
  public X509ProvisioningChallengeRequestMessage(@JsonProperty("cert") String cert) {
    this.cert = cert;
  }

  public String getCert() {
    return cert;
  }
}
