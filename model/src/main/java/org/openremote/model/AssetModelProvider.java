/*
 * Copyright 2020, OpenRemote Inc.
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
package org.openremote.model;

import java.util.Collection;
import java.util.Map;
import java.util.ServiceLoader;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.util.TsIgnore;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemDescriptor;
import org.openremote.model.value.ValueDescriptor;

/**
 * Provides model descriptors that are processed by {@link ValueUtil}; implementations can be
 * discovered using the standard {@link ServiceLoader} mechanism or can be manually registered by
 * adding an instance to the {@link ValueUtil#getModelProviders}.
 *
 * <p>If {@link #useAutoScan} is true then the {@link org.reflections.Reflections} library is used
 * to find all classes that extend {@link Asset} in the same JAR as the {@link AssetModelProvider},
 * these are then searched for all types of descriptors; and also if the {@link AssetModelProvider}
 * contains one or more {@link ModelDescriptor} annotations then those classes will also be scanned
 * for descriptors.
 */
@TsIgnore
public interface AssetModelProvider {

  /**
   * Indicates if the containing JAR of this {@link AssetModelProvider} should be auto scanned for
   * {@link Asset} implementations; descriptors are then extracted using reflection from these
   * classes.
   */
  boolean useAutoScan();

  /**
   * Allows {@link AssetDescriptor}s to be explicitly defined; if {@link #useAutoScan} is true then
   * this option is ignored.
   *
   * <p>If {@link #useAutoScan} is true and this is also defined then the results will be combined
   * with those returned by scanning.
   */
  default AssetDescriptor<?>[] getAssetDescriptors() {
    return null;
  }

  /**
   * Get {@link AttributeDescriptor}s that should be associated with the specified {@link Asset}
   * type; these are combined with any {@link AttributeDescriptor}s already associated with the
   * given {@link Asset} type. Any duplicate conflicts will generate an {@link
   * IllegalStateException} during {@link ValueUtil} initialization.
   */
  default Map<String, Collection<AttributeDescriptor<?>>> getAttributeDescriptors() {
    return null;
  }

  /**
   * Get {@link MetaItemDescriptor}s that should be associated with the specified {@link Asset}
   * type; these are combined with any {@link MetaItemDescriptor}s already associated with the given
   * {@link Asset} type. Any duplicate conflicts will generate an {@link IllegalStateException}
   * during {@link ValueUtil} initialization.
   *
   * <p>If {@link #useAutoScan} is true and this is also defined then the results will be combined
   * with those returned by scanning.
   */
  default Map<String, Collection<MetaItemDescriptor<?>>> getMetaItemDescriptors() {
    return null;
  }

  /**
   * Get {@link ValueDescriptor}s that should be associated with the specified {@link Asset} type;
   * these are combined with any {@link ValueDescriptor}s already associated with the given {@link
   * Asset} type. Any duplicate conflicts will generate an {@link IllegalStateException} during
   * {@link ValueUtil} initialization. Shouldn't contain any {@link ValueDescriptor}s of type array
   * (i.e. ones obtained by calling {@link ValueDescriptor#asArray} or ones where {@link
   * ValueDescriptor#getType} returns a class that {@link Class#isArray} returns true for.
   *
   * <p>If {@link #useAutoScan} is true and this is also defined then the results will be combined
   * with those returned by scanning.
   */
  default Map<String, Collection<ValueDescriptor<?>>> getValueDescriptors() {
    return null;
  }

  /**
   * Called when the full Asset model has been initialized which gives {@link AssetModelProvider}s
   * the chance to do additional work (e.g. add constraints such as allowed values based on
   * available asset types).
   */
  default void onAssetModelFinished() {}

  /** Indicates that this model provider is dynamic and can be refreshed when needed. */
  default boolean isDynamic() {
    return false;
  }
}
