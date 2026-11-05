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
package org.openremote.manager.persistence

import spock.lang.Specification

class ApplicationVersionTest extends Specification {
  def "normalize #version"() {
    expect:
    ApplicationVersion.normalize(version) == "1.2.0"

    where:
    version << [
      "1.2.0",
      "1.2.0-SNAPSHOT",
      "1.2.0-M1",
      "1.2.0-RC1",
      "1.2.0-beta.2",
      "1.2.0-alpha",
      "1.2.0-LOCAL",
      "1.2.0+build.42",
      "1.2.0-RC1+build.42"
    ]
  }

  def "reject unexpected version #version"() {
    when:
    ApplicationVersion.normalize(version)

    then:
    thrown(IllegalArgumentException)

    where:
    version << [null, "", "1.2", "v1.2.0", "01.2.0", "1.2.0-", "1.2.0+", "1.2.0/foo", "1.2.0-beta..2", "1.2.0 "]
  }
}
