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
package org.openremote.manager.persistence;

import java.util.regex.Pattern;

/** Normalized application versions used as activation-history keys. */
public final class ApplicationVersion {
  /**
   * Accepts {@code major.minor.patch[-qualifier][+build.metadata]} as a complete version string.
   * Each numeric version component is zero or a positive decimal integer without leading zeros.
   * Optional qualifier and build suffixes contain nonempty, dot-separated identifiers made of ASCII
   * letters, digits, or hyphens (for example, {@code SNAPSHOT}, {@code M1}, or {@code beta.2}).
   * Numeric suffix identifiers may have leading zeros; this is not strict SemVer validation. Both
   * suffixes are discarded during normalization, leaving only {@code major.minor.patch}.
   */
  private static final Pattern VERSION =
      Pattern.compile(
          "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

  private ApplicationVersion() {}

  public static String normalize(String version) {
    if (version == null || !VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Invalid OpenRemote application version: " + version);
    }
    return version.split("[-+]", 2)[0];
  }
}
