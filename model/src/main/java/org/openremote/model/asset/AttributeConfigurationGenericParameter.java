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
package org.openremote.model.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A value that an {@link AttributeConfigurationDocument} leaves out and asks for on import, applied
 * to every path it lists. The parameter name is the document map key, so it is not repeated here.
 */
@Schema(
    description =
        "A value supplied on import and applied to every listed path; the parameter name is the key within the document.")
public class AttributeConfigurationGenericParameter {

  @Schema(
      description =
          "Value descriptor name of the value, one of text, boolean, integer, long or number.",
      example = "text")
  protected String type;

  @Schema(
      description =
          "Dot separated paths within the document that receive the value, each of the form attributes.<attribute>.meta.<meta item>[.<property>...].",
      example = "[\"attributes.error.meta.agentLink.id\"]")
  protected List<String> paths;

  @JsonCreator
  public AttributeConfigurationGenericParameter(
      @JsonProperty("type") String type, @JsonProperty("paths") List<String> paths) {
    this.type = type;
    this.paths = paths;
  }

  public String getType() {
    return type;
  }

  public List<String> getPaths() {
    return paths;
  }
}
