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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * A {@link ConfigurationTemplate} to import, together with the asset draft it is imported into. The
 * document is submitted as text so that unparsable files are reported like any other invalid
 * document.
 */
@Schema(description = "A configuration template to validate against an asset draft, as file text.")
public class ConfigurationTemplateImportRequest {

  @Schema(description = "The asset draft currently being edited, including unsaved changes.")
  @NotNull protected Asset<?> draft;

  @Schema(description = "Contents of the selected configuration template file.")
  @NotNull protected String document;

  @JsonCreator
  public ConfigurationTemplateImportRequest(Asset<?> draft, String document) {
    this.draft = draft;
    this.document = document;
  }

  public Asset<?> getDraft() {
    return draft;
  }

  public String getDocument() {
    return document;
  }
}
