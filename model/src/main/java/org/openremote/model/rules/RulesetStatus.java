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
package org.openremote.model.rules;

public enum RulesetStatus {
  /**
   * Ruleset compiled successfully but is not running, due to failure of other rulesets in same
   * scope.
   */
  READY,

  /** Ruleset has been compiled and can be executed. */
  DEPLOYED,

  /** Ruleset did not compile successfully and can not be executed. */
  COMPILATION_ERROR,

  /** Validity period is not valid */
  VALIDITY_PERIOD_ERROR,

  /** Ruleset was executed but there was a runtime error. */
  EXECUTION_ERROR,

  /** Ruleset caused a loop while being executed. */
  LOOP_ERROR,

  /** Ruleset has been disabled. */
  DISABLED,

  /** Ruleset is outside of validity period but will be valid again in the future. */
  PAUSED,

  /** Ruleset is outside of validity period and will not be valid again. */
  EXPIRED,

  /** Ruleset has been removed. */
  REMOVED,

  /** Contains no rules */
  EMPTY
}
