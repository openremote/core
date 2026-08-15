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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(name = "error", value = ErrorResponseMessage.class),
  @JsonSubTypes.Type(name = "success", value = SuccessResponseMessage.class),
  @JsonSubTypes.Type(
      name = "x509-challenge-request",
      value = X509ProvisioningChallengeRequestMessage.class),
  @JsonSubTypes.Type(
      name = "x509-challenge",
      value = X509ProvisioningChallengeResponseMessage.class),
  @JsonSubTypes.Type(name = "x509", value = X509ProvisioningMessage.class),
  @JsonSubTypes.Type(name = "mtls", value = MTLSProvisioningMessage.class)
})
public abstract class ProvisioningMessage {}
