/*
 * Copyright 2016, OpenRemote Inc.
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
package org.openremote.model.asset.agent;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import java.util.Map;
import java.util.logging.Logger;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.*;
import org.openremote.model.protocol.ProtocolAssetService;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TsIgnoreTypeParams;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueFilter;

/**
 * A protocol instance is responsible for connecting devices and services to the context broker. A
 * protocol instance has a one-to-one mapping with an {@link Agent} and should get its'
 * configuration parameters from the {@link Attribute}s of this {@link Agent}, how this is done is
 * up to the {@link Agent}/{@link Protocol} creator to determine.
 *
 * <h2>Lifecycle</h2>
 *
 * When the system is started or a CRUD operation is performed on an {@link Agent} then the
 * following calls are made:
 *
 * <h3>Destroy existing protocol instance</h3>
 *
 * If a protocol instance already exists for the {@link Agent} then the following calls are made on
 * that instance:
 *
 * <ol>
 *   <li>{@link #unlinkAttribute} - Called for each attribute linked to the {@link Agent}
 *   <li>{@link #stop}
 * </ol>
 *
 * <h3>Create/initialize protocol instance</h3>
 *
 * If the {@link Agent} was deleted or {@link Agent#isDisabled} then nothing happens otherwise:
 *
 * <ol>
 *   <li>{@link #start} - If this call throws an exception then it is assumed that a permanent
 *       failure has occurred (i.e. the {@link Agent} is incorrectly configured) and this Agent's
 *       status will be marked as {@link ConnectionStatus#ERROR} and attribute linking will not
 *       occur.
 *   <li>{@link #linkAttribute} - Called for each attribute linked to the {@link Agent}
 * </ol>
 *
 * <h3>Configuring protocol instances</h3>
 *
 * Each {@link Agent} asset has its' own {@link Protocol} instance and this instance is responsible
 * for managing only the attributes linked to that specific instance, it is up to the specific
 * {@link Agent} type to initialize an instance of its' own {@link Protocol}.
 *
 * <p>
 *
 * <h3>Connecting attributes to actuators and sensors</h3>
 *
 * {@link Attribute}s of {@link Asset}s can be linked to a protocol instance by creating an {@link
 * MetaItemType#AGENT_LINK} {@link MetaItem} on an attribute. Besides the {@link
 * MetaItemType#AGENT_LINK}, other protocol-specific meta items may also be required when an asset
 * attribute is linked to a protocol instance. Attributes linked to a protocol instance will get
 * passed to the protocol via a call to {@link #linkAttribute}.
 *
 * <p>The protocol handles read and write of linked attributes:
 *
 * <p>If the actual state of the device (or service) changes, the linked protocol writes the new
 * state into the attribute by sending an {@link AttributeEvent} using the {@link #}value and
 * notifies the context broker of the change. A protocol updates a linked attributes' value by
 * sending an {@link AttributeEvent} messages on the {@link #SENSOR_QUEUE}, including the source
 * protocol name in header {@link #SENSOR_QUEUE_SOURCE_PROTOCOL}.
 *
 * <p>If the user writes a new value into the linked attribute, the protocol translates this value
 * change into a device (or service) action. Write operations on attributes linked to an {@link
 * Agent} can be consumed by the agent's protocol on the {@link #ACTUATOR_TOPIC} where the message
 * body will be an {@link AttributeEvent}. Each message also contains the target protocol name in
 * header {@link #ACTUATOR_TOPIC_TARGET_PROTOCOL}.
 *
 * <p>To simplify protocol development some common protocol behavior is recommended for generic
 * protocols:
 *
 * <h1>Inbound value conversion (Protocol -> Linked Attribute)</h1>
 *
 * <p>Standard value filtering and/or conversion should be performed in the following order:
 *
 * <ol>
 *   <li>Configurable value filtering which allows the value produced by the protocol to be filtered
 *       through any number of {@link ValueFilter}s before being written to the linked attribute
 *       (see {@link AgentLink#getValueFilters})
 *   <li>Configurable value conversion which allows the value produced by the protocol to be
 *       converted in a configurable way before being written to the linked attribute (see {@link
 *       AgentLink#getValueConverter})
 *   <li>Automatic basic value conversion should be performed when the type of the value produced by
 *       the protocol and any configured value conversion does not match the linked attributes
 *       underlying value type; this basic conversion should use the {@link ValueUtil#convert}
 *       method
 * </ol>
 *
 * <h1>Outbound value conversion (Linked Attribute -> Protocol)</h1>
 *
 * Standard value conversion should be performed in the following order:
 *
 * <ol>
 *   <li>Configurable value conversion which allows the value sent from the linked attribute to be
 *       converted in a configurable way before being sent to the protocol for processing (see
 *       {@link AgentLink#getWriteValueConverter})
 *   <li>Configurable dynamic data insertion into attribute write value before processing by the
 *       protocol; it also allows the written value to be fixed or statically converted (see {@link
 *       AgentLink#getWriteValue}).
 * </ol>
 *
 * When sending the converted value onto the actual protocol implementation for processing the
 * original {@link AttributeEvent} as well as the converted value should be made available.
 *
 * <p>NOTE: That {@link #start} will always be called before {@link #linkAttribute} and {@link
 * #unlinkAttribute} will always be called before {@link #stop}.
 *
 * <p>The following summarizes the method calls protocols should expect:
 *
 * <p>{@link Agent} asset is created/loaded:
 *
 * <ol>
 *   <li>{@link #start}
 *   <li>{@link #linkAttribute}
 * </ol>
 *
 * <p>{@link Agent} is modified:
 *
 * <ol>
 *   <li>{@link #unlinkAttribute}
 *   <li>{@link #stop}
 *   <li>{@link #start}
 *   <li>{@link #linkAttribute}
 * </ol>
 *
 * <p>{@link Agent} is removed:
 *
 * <ol>
 *   <li>{@link #unlinkAttribute}
 *   <li>{@link #stop}
 * </ol>
 *
 * <p>Attribute linked to {@link Agent} is created/loaded:
 *
 * <ol>
 *   <li>{@link #linkAttribute}
 * </ol>
 *
 * <p>Attribute linked to {@link Agent} is modified:
 *
 * <ol>
 *   <li>{@link #unlinkAttribute}
 *   <li>{@link #linkAttribute}
 * </ol>
 *
 * <p>Attribute link to {@link Agent} is removed:
 *
 * <ol>
 *   <li>{@link #unlinkAttribute}
 * </ol>
 */
@TsIgnoreTypeParams
public interface Protocol<T extends Agent<T, ?, ?>> {

  Logger LOG = SyslogCategory.getLogger(PROTOCOL, Protocol.class);

  /** Prefixes the log message with {@link #getProtocolName} and {@link #getProtocolInstanceUri}. */
  default String prefixLogMessage(String msg) {
    return getProtocolName() + " [" + getProtocolInstanceUri() + "]: " + msg;
  }

  /** Get the name for this protocol */
  String getProtocolName();

  /** Get a URI that describes this specific protocol instance */
  String getProtocolInstanceUri();

  /**
   * Get list of {@link Attribute}s currently linked to this protocol instance grouped by {@link
   * Asset} ID
   */
  Map<AttributeRef, Attribute<?>> getLinkedAttributes();

  /**
   * Links an {@link Attribute} to its' agent; the agent would have been connected before this call.
   * This is called when the agent is connected or when the attribute has been modified and
   * re-linked.
   *
   * <p>If the attribute is not valid for this protocol then it is up to the protocol to log the
   * issue and return false.
   *
   * <p>Attributes are linked to an agent via an {@link MetaItemType#AGENT_LINK} meta item.
   */
  void linkAttribute(String assetId, Attribute<?> attribute) throws Exception;

  /**
   * Un-links an {@link Attribute} from its' agent; the agent will still be connected during this
   * call. This is called whenever the attribute is modified or removed or when the agent is
   * modified or removed.
   */
  void unlinkAttribute(String assetId, Attribute<?> attribute) throws Exception;

  /**
   * Called before any calls to {@link #linkAttribute} to allow the protocol to perform required
   * tasks with {@link ContainerService}s (e.g. register Camel routes). The protocol instance should
   * validate the settings defined in the associated {@link Agent}; but whether or not the protocol
   * establishes a connection/ fully initializes at this time is up to the protocol implementation,
   * it could be desirable to wait until {@link #linkAttribute} is called for the first time, or it
   * could be a connectionless protocol. If there is a configuration issue etc. then an appropriate
   * exception should be thrown and suitable logs made.
   */
  void start(Container container) throws Exception;

  /**
   * Called once all linked attributes have been unlinked via {@link #unlinkAttribute} to allow the
   * protocol to perform required tasks with {@link ContainerService}s e.g. remove Camel routes,
   * destroy/cleanup resources etc.
   */
  void stop(Container container) throws Exception;

  /** Get the {@link Agent} associated with this protocol instance. */
  T getAgent();

  /**
   * Update the value of a linked {@link Attribute} linked to this {@link Protocol}; call this to
   * publish new sensor values. Implementors are responsible for ensuring the requested {@link
   * Attribute} is actually linked to this {@link Protocol}.
   */
  void updateLinkedAttribute(final AttributeRef attributeRef, final Object value, long timestamp);

  /**
   * Update the value of a linked {@link Attribute} linked to this {@link Protocol}; call this to
   * publish new sensor values. Implementors are responsible for ensuring the requested {@link
   * Attribute} is actually linked to this {@link Protocol}.
   */
  void updateLinkedAttribute(final AttributeRef attributeRef, final Object value);

  /**
   * Called by instantiator during instantiation to allow the {@link Protocol} to interact with
   * {@link Asset}s and {@link Attribute}s.
   */
  void setAssetService(ProtocolAssetService assetService);

  /**
   * An Attribute event (write) has been requested for an attribute linked to this protocol;
   * implementations should handle multithreaded requests or use some sort of synchronization for
   * thread safety. The {@link AttributeEvent} will be enriched with data about the relating asset
   * and attribute.
   */
  void processLinkedAttributeWrite(AttributeEvent event);

  /**
   * Called when one of the associated {@link Agent}'s {@link Attribute}s are updated; the
   * implementation can decide if it wants to request the protocol instance to be re-created thus
   * simplifying handling of config changes.
   *
   * @return true if the agent should be redeployed (this will stop current protocol instance and
   *     create a new one).
   */
  boolean onAgentAttributeChanged(AttributeEvent event);
}
