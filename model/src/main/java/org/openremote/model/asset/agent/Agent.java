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
package org.openremote.model.asset.agent;

import static org.openremote.model.value.MetaItemType.AGENT_LINK;

import java.util.Optional;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.util.TsIgnoreTypeParams;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaHolder;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

/**
 * An agent is a special sub type of {@link Asset} that is associated with a {@link Protocol} and is
 * responsible for providing an instance of the associated {@link Protocol} when requested via
 * {@link #getProtocolInstance}.
 */
@SuppressWarnings("unchecked")
@TsIgnoreTypeParams
public abstract class Agent<T extends Agent<T, U, V>, U extends Protocol<T>, V extends AgentLink<?>>
    extends Asset<T> {

  public static final AttributeDescriptor<Boolean> DISABLED =
      new AttributeDescriptor<>("agentDisabled", ValueType.BOOLEAN);

  public static final AttributeDescriptor<ConnectionStatus> STATUS =
      new AttributeDescriptor<>(
          "agentStatus", ValueType.CONNECTION_STATUS, new MetaItem<>(MetaItemType.READ_ONLY));

  /**
   * Can be used by protocols that support it to indicate that string values should be converted
   * to/from bytes from/to HEX string representation (e.g. 34FD87)
   */
  public static final AttributeDescriptor<Boolean> MESSAGE_CONVERT_HEX =
      new AttributeDescriptor<>("messageConvertHex", ValueType.BOOLEAN).withOptional(true);

  /**
   * Can be used by protocols that support it to indicate that string values should be converted
   * to/from bytes from/to binary string representation (e.g. 1001010111)
   */
  public static final AttributeDescriptor<Boolean> MESSAGE_CONVERT_BINARY =
      new AttributeDescriptor<>("messageConvertBinary", ValueType.BOOLEAN).withOptional(true);

  /**
   * Charset to use when converting byte[] to a string (should default to UTF8 if not specified);
   * values must be string that matches a charset as defined in {@link java.nio.charset.Charset}
   */
  public static final AttributeDescriptor<String> MESSAGE_CHARSET =
      new AttributeDescriptor<>("messageCharset", ValueType.TEXT).withOptional(true);

  /**
   * Max length of messages received by a {@link Protocol}; what this actually means will be
   * protocol specific i.e. for {@link String} protocols it could be the number of characters but
   * for {@link Byte} protocols it could be the number of bytes. This is typically used for I/O
   * based {@link Protocol}s.
   */
  public static final AttributeDescriptor<Integer> MESSAGE_MAX_LENGTH =
      new AttributeDescriptor<>("messageMaxLength", ValueType.POSITIVE_INTEGER).withOptional(true);

  /**
   * Defines a set of delimiters for messages received by a {@link Protocol}; the first matched
   * delimiter should be used to generate the shortest possible match(This is typically used for I/O
   * based {@link Protocol}s.
   */
  public static final AttributeDescriptor<String[]> MESSAGE_DELIMITERS =
      new AttributeDescriptor<>("messageDelimiters", ValueType.TEXT.asArray()).withOptional(true);

  /**
   * For protocols that use {@link #MESSAGE_DELIMITERS}, this indicates whether or not the matched
   * delimiter should be stripped from the message.
   */
  public static final AttributeDescriptor<Boolean> MESSAGE_STRIP_DELIMITER =
      new AttributeDescriptor<>("messageStripDelimiter", ValueType.BOOLEAN).withOptional(true);

  /** {@link OAuthGrant} for connecting to services that require OAuth authentication */
  public static final AttributeDescriptor<OAuthGrant> OAUTH_GRANT =
      new AttributeDescriptor<>("oAuthGrant", ValueType.OAUTH_GRANT).withOptional(true);

  /** Basic authentication username and password */
  public static final AttributeDescriptor<UsernamePassword> USERNAME_AND_PASSWORD =
      new AttributeDescriptor<>("usernamePassword", ValueType.USERNAME_AND_PASSWORD)
          .withOptional(true);

  /** TCP/IP network host name/IP address */
  public static final AttributeDescriptor<String> HOST =
      new AttributeDescriptor<>("host", ValueType.HOSTNAME_OR_IP_ADDRESS).withOptional(true);

  /** TCP/IP network port number */
  public static final AttributeDescriptor<Integer> PORT =
      new AttributeDescriptor<>("port", ValueType.PORT).withOptional(true);

  /** Local TCP/IP network port number to bind to */
  public static final AttributeDescriptor<Integer> BIND_PORT =
      new AttributeDescriptor<>("bindPort", ValueType.PORT).withOptional(true);

  /** Local TCP/IP network host name/IP address to bind to */
  public static final AttributeDescriptor<String> BIND_HOST =
      new AttributeDescriptor<>("bindHost", ValueType.HOSTNAME_OR_IP_ADDRESS).withOptional(true);

  /** Serial port name/address */
  public static final AttributeDescriptor<String> SERIAL_PORT =
      new AttributeDescriptor<>("serialPort", ValueType.TEXT).withOptional(true);

  /** Serial baudrate to use for connection */
  public static final AttributeDescriptor<Integer> SERIAL_BAUDRATE =
      new AttributeDescriptor<>("serialBaudrate", ValueType.POSITIVE_INTEGER).withOptional(true);

  /** Default polling frequency (milliseconds) */
  public static final AttributeDescriptor<Integer> POLLING_MILLIS =
      new AttributeDescriptor<>("pollingMillis", ValueType.POSITIVE_INTEGER).withOptional(true);

  /** Don't expect a response from the protocol just update the attribute immediately on write */
  public static final AttributeDescriptor<Boolean> UPDATE_ON_WRITE =
      new AttributeDescriptor<>("updateOnWrite", ValueType.BOOLEAN).withOptional(true);

  protected Agent() {}

  protected Agent(String name) {
    super(name);
  }

  /** Get the protocol instance for this Agent. */
  public abstract U getProtocolInstance();

  /** Get the {@link AgentLink} for the specified linked agent */
  @SuppressWarnings("unchecked")
  public <T extends MetaHolder> V getAgentLink(T attribute) {
    AgentLink<?> agentLink =
        attribute
            .getMetaValue(AGENT_LINK)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Failed to getAgentLink<?>despite attribute being linked to an agent"));
    return (V) agentLink;
  }

  public Optional<Boolean> isDisabled() {
    return getAttributes().getValue(DISABLED);
  }

  @SuppressWarnings("unchecked")
  public T setDisabled(boolean disabled) {
    getAttributes().getOrCreate(DISABLED).setValue(disabled);
    return (T) this;
  }

  public Optional<ConnectionStatus> getAgentStatus() {
    return getAttributes().getValue(STATUS);
  }

  public Optional<OAuthGrant> getOAuthGrant() {
    return getAttributes().getValue(OAUTH_GRANT);
  }

  @SuppressWarnings("unchecked")
  public T setOAuthGrant(OAuthGrant value) {
    getAttributes().getOrCreate(OAUTH_GRANT).setValue(value);
    return (T) this;
  }

  public Optional<UsernamePassword> getUsernamePassword() {
    return getAttributes().getValue(USERNAME_AND_PASSWORD);
  }

  @SuppressWarnings("unchecked")
  public T setUsernamePassword(UsernamePassword value) {
    getAttributes().getOrCreate(USERNAME_AND_PASSWORD).setValue(value);
    return (T) this;
  }

  public Optional<String> getHost() {
    return getAttributes().getValue(HOST);
  }

  @SuppressWarnings("unchecked")
  public T setHost(String value) {
    getAttributes().getOrCreate(HOST).setValue(value);
    return (T) this;
  }

  public Optional<Integer> getPort() {
    return getAttributes().getValue(PORT);
  }

  @SuppressWarnings("unchecked")
  public T setPort(Integer value) {
    getAttributes().getOrCreate(PORT).setValue(value);
    return (T) this;
  }

  public Optional<Integer> getBindPort() {
    return getAttributes().getValue(BIND_PORT);
  }

  @SuppressWarnings("unchecked")
  public T setBindPort(Integer value) {
    getAttributes().getOrCreate(BIND_PORT).setValue(value);
    return (T) this;
  }

  public Optional<String> getBindHost() {
    return getAttributes().getValue(BIND_HOST);
  }

  @SuppressWarnings("unchecked")
  public T setBindHost(String value) {
    getAttributes().getOrCreate(BIND_HOST).setValue(value);
    return (T) this;
  }

  public Optional<String> getSerialPort() {
    return getAttributes().getValue(SERIAL_PORT);
  }

  @SuppressWarnings("unchecked")
  public T setSerialPort(String value) {
    getAttributes().getOrCreate(SERIAL_PORT).setValue(value);
    return (T) this;
  }

  public Optional<Integer> getSerialBaudrate() {
    return getAttributes().getValue(SERIAL_BAUDRATE);
  }

  @SuppressWarnings("unchecked")
  public T setSerialBaudrate(Integer value) {
    getAttributes().getOrCreate(SERIAL_BAUDRATE).setValue(value);
    return (T) this;
  }

  public Optional<Integer> getPollingMillis() {
    return getAttributes().getValue(POLLING_MILLIS);
  }

  public Optional<Boolean> isUpdateOnWrite() {
    return getAttributes().getValue(UPDATE_ON_WRITE);
  }

  @SuppressWarnings("unchecked")
  public T setUpdateOnWrite(boolean updateOnWrite) {
    getAttributes().getOrCreate(UPDATE_ON_WRITE).setValue(updateOnWrite);
    return (T) this;
  }

  /**
   * Indicates if the specified attribute name is a configuration attribute and therefore if an
   * {@link org.openremote.model.attribute.AttributeEvent} is detected for this attribute the agent
   * should be stopped and restarted. The default behavior is any attribute that has an {@link
   * AttributeDescriptor} defined on the {@link Agent} class or one of its' subclasses is considered
   * to be a configuration attribute for the agent; excluding {@link Agent#STATUS}. Agent's can
   * override this behavior as required.
   */
  public boolean isConfigurationAttribute(String attributeName) {
    // This is an event for an agent so is it for an attribute that has a descriptor which is
    // defined in an agent class
    // and it's not the status attribute (or we'll end up in a loop)
    return !attributeName.equals(Agent.STATUS.getName())
        && ValueUtil.getAssetInfo(getType())
            .map(info -> info.getAttributeDescriptors().containsKey(attributeName))
            .orElse(false)
        // Exclude attributes that have a descriptor from the base ThingAsset class
        && ValueUtil.getAssetInfo(ThingAsset.class)
            .map(typeInfo -> !typeInfo.getAttributeDescriptors().containsKey(attributeName))
            .orElse(false);
  }
}
