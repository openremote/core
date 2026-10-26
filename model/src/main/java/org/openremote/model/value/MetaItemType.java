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
package org.openremote.model.value;

import java.util.Date;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeLink;
import org.openremote.model.util.TsIgnore;
import org.openremote.model.value.ValueType.ObjectMap;

@TsIgnore
@SuppressWarnings("rawtypes")
public final class MetaItemType {

  /* PROTOCOL / SERVICE META */

  /**
   * Links the attribute to an agent, connecting it to a sensor and/or actuator with required
   * configuration properties encapsulated in the concrete protocol specific {@link
   * org.openremote.model.asset.agent.AgentLink}.
   */
  public static final MetaItemDescriptor<AgentLink> AGENT_LINK =
      new MetaItemDescriptor<>("agentLink", ValueType.VALUE_AGENT_LINK);

  /**
   * Stores {@link #AGENT_LINK} configuration data. This meta item is useful for protocol
   * implementations that require additional information at the time of asset instantiation for
   * configuring agent links.
   */
  public static final MetaItemDescriptor<ObjectMap> AGENT_LINK_CONFIG =
      new MetaItemDescriptor<>("agentLinkConfig", ValueType.JSON_OBJECT);

  /**
   * Links the attribute to another attribute, so an attribute event on the attribute triggers the
   * same attribute event on the linked attribute.
   */
  public static final MetaItemDescriptor<AttributeLink[]> ATTRIBUTE_LINKS =
      new MetaItemDescriptor<>("attributeLinks", ValueType.ATTRIBUTE_LINK.asArray());

  /* ACCESS PERMISSION META */

  /** Marks the attribute as readable by unauthenticated public clients. */
  public static final MetaItemDescriptor<Boolean> ACCESS_PUBLIC_READ =
      new MetaItemDescriptor<>("accessPublicRead", ValueType.BOOLEAN);

  /** Marks the attribute as writable by unauthenticated public clients. */
  public static final MetaItemDescriptor<Boolean> ACCESS_PUBLIC_WRITE =
      new MetaItemDescriptor<>("accessPublicWrite", ValueType.BOOLEAN);

  /**
   * Marks the attribute as readable by restricted clients and therefore users who are linked to the
   * asset, see {@link UserAssetLink}.
   */
  public static final MetaItemDescriptor<Boolean> ACCESS_RESTRICTED_READ =
      new MetaItemDescriptor<>("accessRestrictedRead", ValueType.BOOLEAN);

  /**
   * Marks the attribute as writable by restricted clients and therefore users who are linked to the
   * asset, see {@link UserAssetLink}.
   */
  public static final MetaItemDescriptor<Boolean> ACCESS_RESTRICTED_WRITE =
      new MetaItemDescriptor<>("accessRestrictedWrite", ValueType.BOOLEAN);

  /**
   * Marks the attribute as read-only for UI purposes only; this does not offer any
   * authorisation/security.
   */
  public static final MetaItemDescriptor<Boolean> READ_ONLY =
      new MetaItemDescriptor<>("readOnly", ValueType.BOOLEAN);

  /* DATA POINT META */

  /**
   * Can be set to false to prevent attribute values being stored in time series database; otherwise
   * any attribute which also has an {@link #AGENT_LINK} meta item or {@link #STORE_DATA_POINTS} is
   * set to true, will be stored.
   */
  public static final MetaItemDescriptor<Boolean> STORE_DATA_POINTS =
      new MetaItemDescriptor<>("storeDataPoints", ValueType.BOOLEAN);

  /**
   * Previously set how long to store attribute values in the time series database (data older than
   * this would have been automatically purged).
   *
   * @deprecated Per-attribute retention is no longer supported. Purging now uses TimescaleDB
   *     drop_chunks with a global week-based retention period configured via
   *     OR_DATA_POINTS_MAX_AGE_WEEKS. For backward compatibility, the largest value of this meta
   *     item across all attributes (rounded up to whole weeks) is used as a minimum for the global
   *     retention period, so it can only extend how long data is kept for all attributes; it never
   *     shortens it and has no effect when purging is disabled.
   */
  @Deprecated
  public static final MetaItemDescriptor<Integer> DATA_POINTS_MAX_AGE_DAYS =
      new MetaItemDescriptor<>("dataPointsMaxAgeDays", ValueType.POSITIVE_INTEGER);

  /** Could possibly have predicted data points */
  // TODO: Re-evaluate this can this info be retrieved automatically using prediction service
  public static final MetaItemDescriptor<Boolean> HAS_PREDICTED_DATA_POINTS =
      new MetaItemDescriptor<>("hasPredictedDataPoints", ValueType.BOOLEAN);

  /** Apply predicted data points as current attribute values when timestamps match. */
  public static final MetaItemDescriptor<Boolean> APPLY_PREDICTED_DATA_POINTS =
      new MetaItemDescriptor<>("applyPredictedDataPoints", ValueType.BOOLEAN);

  /** The forecast service calculates predicted data points based on the forecast configuration. */
  public static final MetaItemDescriptor<ForecastConfiguration> FORECAST =
      new MetaItemDescriptor<>("forecast", ValueType.FORECAST_CONFIGURATION);

  /* RULE META */

  /**
   * Can be set to false to exclude an attribute update from being processed by the rules engines as
   * {@link org.openremote.model.attribute.AttributeInfo} facts, otherwise any attribute that also
   * has an {@link #AGENT_LINK} meta item or {@link #RULE_STATE} is true, will be processed with a
   * lifecycle that reflects the state of the asset attribute. Each attribute will have one fact at
   * all times in rules memory. These state facts are kept in sync with asset changes: When the
   * attribute is updated, the fact will be updated (replaced).
   */
  public static final MetaItemDescriptor<Boolean> RULE_STATE =
      new MetaItemDescriptor<>("ruleState", ValueType.BOOLEAN);

  /**
   * Used by when-then rules to indicate that the rule should be allowed to re-trigger immediately;
   * this can be useful for attributes that contain event based data rather than state data.
   */
  public static final MetaItemDescriptor<Boolean> RULE_RESET_IMMEDIATE =
      new MetaItemDescriptor<>("ruleResetImmediate", ValueType.BOOLEAN);

  /* FORMATTING / DISPLAY META */

  /** A human-friendly string that can be displayed in UI instead of the raw attribute name. */
  public static final MetaItemDescriptor<String> LABEL =
      new MetaItemDescriptor<>("label", ValueType.TEXT);

  /**
   * {@link ValueFormat} to be applied when converting the associated {@link Attribute} to string
   * representation.
   */
  public static final MetaItemDescriptor<ValueFormat> FORMAT =
      new MetaItemDescriptor<>("format", ValueType.VALUE_FORMAT);

  /**
   * Indicates the units associated with the value, there's some special handling for {@link
   * Boolean} and {@link Date} values but otherwise the value type should be numeric. Units are
   * intended for UI usage and should support internationalisation, custom unit types can be
   * composed e.g. ["kilo", "metre", "per", "hour"] => "km/h" see {@link
   * org.openremote.model.Constants} for well known units that UIs should support as a minimum.
   * Currencies get special handling and should be represented using the upper case 3 letter
   * currency code as defined in ISO 4217
   */
  public static final MetaItemDescriptor<String[]> UNITS =
      new MetaItemDescriptor<>("units", ValueType.TEXT.asArray());

  /**
   * {@link ValueConstraint}s to be applied to the {@link Attribute} value; these are added to any
   * constraints defined on any of the descriptors associated with the attribute.
   */
  public static final MetaItemDescriptor<ValueConstraint[]> CONSTRAINTS =
      new MetaItemDescriptor<>("constraints", ValueType.VALUE_CONSTRAINT.asArray());

  /**
   * Marks the value as secret and indicates that clients should display this in a concealed manner
   * (e.g. password input with optional show)
   */
  public static final MetaItemDescriptor<Boolean> SECRET =
      new MetaItemDescriptor<>("secret", ValueType.BOOLEAN);

  /** Indicates that any input should support multiline text entry */
  public static final MetaItemDescriptor<Boolean> MULTILINE =
      new MetaItemDescriptor<>("multiline", ValueType.BOOLEAN);

  /** If there is a dashboard, some kind of attribute overview, should this attribute be shown. */
  public static final MetaItemDescriptor<Boolean> SHOW_ON_DASHBOARD =
      new MetaItemDescriptor<>("showOnDashboard", ValueType.BOOLEAN);

  /**
   * Indicates that the button input should send the true/on/pressed/closed value when pressed; and
   * then send the false/off/released/open value when released (for use by UIs)
   */
  public static final MetaItemDescriptor<Boolean> MOMENTARY =
      new MetaItemDescriptor<>("momentary", ValueType.BOOLEAN);

  /**
   * Can be used on a {@link ValueType#BOOLEAN} attribute to indicate if a service user is connected
   * to the MQTT broker; the value of this meta item should be the username of the user to monitor
   */
  public static final MetaItemDescriptor<String> USER_CONNECTED =
      new MetaItemDescriptor<>("userConnected", ValueType.TEXT);

  protected MetaItemType() {}
}
