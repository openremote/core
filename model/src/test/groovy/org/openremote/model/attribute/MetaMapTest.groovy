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
package org.openremote.model.attribute

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import org.openremote.model.asset.Asset
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class MetaMapTest extends Specification {

  def setupSpec() {
    ValueUtil.initialise(null)
  }

  static String path(JsonMappingException e) {
    return e.path.collect { it.fieldName != null ? ".${it.fieldName}" : "[${it.index}]" }.join("").replaceFirst(/^\./, "")
  }

  def "a metadata value that cannot be read names its item in the error path: #expectedPath"() {
    when: "metadata holding a value that does not fit its item is read"
    ValueUtil.JSON.readValue(json, MetaMap)

    then: "the error path starts at the item"
    JsonMappingException e = thrown()
    path(e) == expectedPath

    where:
    json | expectedPath
    '{"label": "L", "readOnly": {"not": "a boolean"}}' | "readOnly"
    '{"label": "L", "constraints": [{"type": "noSuchConstraint"}]}' | "constraints[0]"
  }

  def "an asset holding a metadata value that cannot be read names the attribute, meta and item in the error path"() {
    when: "the asset is read"
    ValueUtil.JSON.readValue('{"name": "Thing", "type": "ThingAsset", "attributes": {"temperature": {"name": "temperature", "type": "number", "meta": {"label": "T", "readOnly": "not a boolean"}}}}', Asset)

    then: "the error path leads to the item"
    JsonMappingException e = thrown()
    path(e) == "attributes.temperature.meta.readOnly"
  }

  def "a syntax error within metadata is reported as it is"() {
    when: "metadata that is not valid JSON is read"
    ValueUtil.JSON.readValue('{"label": }', MetaMap)

    then: "the parse error is not wrapped"
    thrown(JsonParseException)
  }
}
