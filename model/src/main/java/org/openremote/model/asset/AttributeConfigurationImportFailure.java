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

/** Reason why an {@link AttributeConfigurationDocument} cannot be imported. */
public enum AttributeConfigurationImportFailure {

  /** The document is not parsable JSON or is not a JSON object. */
  INVALID_JSON,

  /** The document version is not {@link AttributeConfigurationDocument#VERSION}. */
  UNSUPPORTED_VERSION,

  /** The version, asset type or attributes field is absent. */
  MISSING_REQUIRED_FIELD,

  /** The attributes field or one of its entries does not hold a type and a metadata object. */
  MALFORMED_ATTRIBUTE_ENTRY,

  /** No attribute of the document exists on the target asset with the same type. */
  NO_IMPORTABLE_ATTRIBUTES,

  /** The submitted draft no longer identifies the persisted target asset. */
  DRAFT_MISMATCH
}
