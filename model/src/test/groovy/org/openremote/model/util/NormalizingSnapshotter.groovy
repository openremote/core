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

import spock.lang.Snapshotter

/**
 * A {@link Snapshotter} that applies the same line-ending normalization to the stored snapshot value as it
 * applies to the actual value.
 *
 * <p>Spock's built-in {@code Snapshotter} normalizes only the actual value (in the {@code Snapshot}
 * constructor) and loads the stored snapshot raw, so a snapshot file checked out with CRLF line endings
 * (e.g. Windows with {@code core.autocrlf=true}) never matches a value normalized to {@code \n}. This
 * subclass closes that gap by normalizing the loaded value through the same normalizer, and by
 * normalizing values before they are written, so the stored file and the comparison are both stable
 * regardless of the platform's line endings.</p>
 */
class NormalizingSnapshotter extends Snapshotter {

  NormalizingSnapshotter(Snapshotter.Store store) {
    super(store)
  }

  @Override
  protected String loadSnapshot(String snapshotId) {
    // normalize the loaded value the same way the actual value is normalized
    return normalize(super.loadSnapshot(snapshotId))
  }

  @Override
  protected void saveSnapshot(String snapshotId, String value) {
    // store the normalized value so the file round-trips consistently across platforms
    super.saveSnapshot(snapshotId, normalize(value))
  }

  private String normalize(String value) {
    // Spock's default normalizer is StringGroovyMethods::normalize (line endings -> \n); calling
    // String.normalize() from Groovy resolves to that exact DGM method, so this stays correct even if
    // a spec overrides the normalizer via Snapshotter.normalizedWith(...)
    return value.normalize()
  }
}
