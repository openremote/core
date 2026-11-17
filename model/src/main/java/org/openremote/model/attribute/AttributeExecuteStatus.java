/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.model.attribute;

import java.util.Optional;

public enum AttributeExecuteStatus {

  /** Request execution */
  REQUEST_START(true),

  /** Request repeating execution */
  REQUEST_REPEATING(true),

  /** Request that running execution be canceled */
  REQUEST_CANCEL(true),

  /** Ready to be executed */
  READY(false),

  /** Execution completed */
  COMPLETED(false),

  /** Execution is currently running */
  RUNNING(false),

  /** Execution has been canceled */
  CANCELLED(false);

  private final boolean write;
  // Prevents cloning of values each time fromString is called
  private static final AttributeExecuteStatus[] copyOfValues = values();

  AttributeExecuteStatus(boolean write) {
    this.write = write;
  }

  public boolean isWrite() {
    return write;
  }

  public static Optional<AttributeExecuteStatus> fromString(String value) {
    for (AttributeExecuteStatus status : copyOfValues) {
      if (status.name().equalsIgnoreCase(value)) return Optional.of(status);
    }
    return Optional.empty();
  }
}
