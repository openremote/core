/*
 * Copyright 2019, OpenRemote Inc.
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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Collectors;
import org.openremote.model.event.shared.SharedEvent;

/** This event is used in response to a {@link ReadAssetsEvent}. */
public class AssetsEvent extends SharedEvent {

  // Preserve an explicit empty list on the wire while accepting older responses that omitted it.
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  protected List<Asset<?>> assets;

  /**
   * Creates an asset response. An explicit empty list is preserved when serialized.
   *
   * @param assets the returned assets, an empty list when none match, or {@code null} if the list
   *     was omitted by the sender
   */
  @JsonCreator
  public AssetsEvent(@JsonProperty("assets") List<Asset<?>> assets) {
    this.assets = assets;
  }

  /**
   * Gets the assets returned in the response.
   *
   * @return the returned assets, or {@code null} if the list was omitted
   */
  public List<Asset<?>> getAssets() {
    return assets;
  }

  @Override
  public String toString() {
    return AssetsEvent.class.getSimpleName()
        + "{"
        + ", assets="
        + (assets == null
            ? "null"
            : assets.stream().map(Asset::getId).collect(Collectors.joining()))
        + '}';
  }
}
