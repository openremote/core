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
package org.openremote.model.util

import spock.lang.Specification

class VersionUtilTest extends Specification {

  def "compareVersions('#v1', '#v2') should return #expected"() {
    expect:
    Integer.signum(VersionUtil.compareVersions(v1, v2)) == Integer.signum(expected)

    where:
    v1               | v2 || expected
    "1.0.0"          | "1.0.0" || 0
    "1.0.0"          | "1.0.1" || -1
    "1.0.1"          | "1.0.0" || 1
    "1.0"            | "1.0.0" || 0
    "1.0.0"          | "1.0" || 0
    "2.0.0"          | "1.9.9" || 1
    "1.9.9"          | "2.0.0" || -1
    "1.2.3"          | "1.2.3.4" || -1
    "1.2.3.4"        | "1.2.3" || 1
    "10.0.0"         | "2.0.0" || 1
    "2.0.0"          | "10.0.0" || -1
    "1.0"            | "1.0.0.0" || 0
    "01.002.0003"    | "1.2.3" || 0
    "1.0.0+build"    | "1.0.0" || 0
    "1.0.2147483647" | "1.0.2" || 1
    "1.0.2147483648" | "1.0.0" || 0
    ""               | "0.0.0" || 0
    "0.0.0"          | "" || 0
    ""               | "1.0.0" || -1
    "1.0.0"          | "" || 1
  }

  def "compareVersions with qualifiers: '#v1' vs '#v2'"() {
    expect:
    Integer.signum(VersionUtil.compareVersions(v1, v2)) == Integer.signum(expected)

    where:
    v1               | v2 || expected
    "1.0.0"          | "1.0.0-SNAPSHOT" || 0 // numeric parts are equal
    "1.1.0-SNAPSHOT" | "1.0.0" || 1 // 1.1 > 1.0
    "2.0-beta"       | "1.9" || 1 // 2 > 1
    "1.0-alpha"      | "1.0-beta" || 0 // both parse to 1.0
  }

  def "compareVersions should handle empty segments"() {
    expect:
    VersionUtil.compareVersions("1..0", "1.0.0") == 0
    VersionUtil.compareVersions("1.0.", "1.0.0") == 0
    VersionUtil.compareVersions("1.0.0", "1..0") == 0
    VersionUtil.compareVersions("1.0.0", "1.0.") == 0
    VersionUtil.compareVersions("1..2", "1.0.1") > 0
    VersionUtil.compareVersions("1.0.1", "1..2") < 0
  }

  def "compareVersions should throw NullPointerException for null inputs"() {
    when:
    VersionUtil.compareVersions(null, "1.0.0")

    then:
    thrown(NullPointerException)

    when:
    VersionUtil.compareVersions("1.0.0", null)

    then:
    thrown(NullPointerException)
  }

  def "isVersionGreater should work correctly"() {
    expect:
    VersionUtil.isVersionGreater("2.0.0", "1.0.0")
    !VersionUtil.isVersionGreater("1.0.0", "2.0.0")
    !VersionUtil.isVersionGreater("1.0.0", "1.0.0")
  }

  def "isVersionGreaterOrEqual should work correctly"() {
    expect:
    VersionUtil.isVersionGreaterOrEqual("2.0.0", "1.0.0")
    VersionUtil.isVersionGreaterOrEqual("1.0.0", "1.0.0")
    !VersionUtil.isVersionGreaterOrEqual("1.0.0", "2.0.0")
  }

  def "isVersionEqual should work correctly"() {
    expect:
    VersionUtil.isVersionEqual("1.0.0", "1.0.0")
    VersionUtil.isVersionEqual("1.0", "1.0.0")
    !VersionUtil.isVersionEqual("1.0.0", "1.0.1")
    !VersionUtil.isVersionEqual("1.0.1", "1.0.0")
  }

  def "isVersionLess should work correctly"() {
    expect:
    VersionUtil.isVersionLess("1.0.0", "2.0.0")
    !VersionUtil.isVersionLess("2.0.0", "1.0.0")
    !VersionUtil.isVersionLess("1.0.0", "1.0.0")
  }

  def "isVersionLessOrEqual should work correctly"() {
    expect:
    VersionUtil.isVersionLessOrEqual("1.0.0", "2.0.0")
    VersionUtil.isVersionLessOrEqual("1.0.0", "1.0.0")
    !VersionUtil.isVersionLessOrEqual("2.0.0", "1.0.0")
  }

  def "parsePart('#part') returns #expected"() {
    expect:
    VersionUtil.parsePart(part) == expected

    where:
    part                    | expected
    "123"                   | 123
    "1-SNAPSHOT"            | 1
    "42-alpha"              | 42
    "3+build.42"            | 3
    "0"                     | 0
    "0012"                  | 12
    ""                      | 0
    "beta"                  | 0
    "-1"                    | 0
    "2147483647"            | Integer.MAX_VALUE
    "2147483647-SNAPSHOT"   | Integer.MAX_VALUE
    "2147483648"            | 0
    "999999999999999999999" | 0
  }

  def "normalize #version"() {
    expect:
    VersionUtil.normalizeVersion(version) == "1.2.0"

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

  def "normalize numeric components in #version to #normalized"() {
    expect:
    VersionUtil.normalizeVersion(version) == normalized

    where:
    version            | normalized
    "0.0.0"            | "0.0.0"
    "12.34.56"         | "12.34.56"
    "01.02.003"        | "1.2.3"
    "01.02.003-beta.2" | "1.2.3"
    "1..3"             | "1.0.3"
    "1.2."             | "1.2.0"
    "1.beta.3"         | "1.0.3"
    "1.2147483648.3"   | "1.0.3"
  }

  def "reject unexpected version #version"() {
    when:
    VersionUtil.normalizeVersion(version)

    then:
    thrown(IllegalArgumentException)

    where:
    version << [null, "", "1.2", "1.2.3.4"]
  }
}
