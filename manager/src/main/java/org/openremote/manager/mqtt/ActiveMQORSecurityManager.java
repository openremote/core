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
package org.openremote.manager.mqtt;

import static org.openremote.manager.mqtt.MQTTBrokerService.connectionToString;

import jakarta.security.enterprise.AuthenticationException;
import java.security.Principal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.Subject;
import org.apache.activemq.artemis.core.config.WildcardConfiguration;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil;
import org.apache.activemq.artemis.core.security.CheckType;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager5;
import org.apache.activemq.artemis.spi.core.security.jaas.NoCacheLoginException;
import org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal;
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal;
import org.openremote.container.security.IdentityProvider;
import org.openremote.container.security.IdentityService;
import org.openremote.container.security.OIDCTokenResponse;
import org.openremote.container.security.TokenPrincipal;
import org.openremote.manager.security.RemotingConnectionPrincipal;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TextUtil;

/**
 * An {@link ActiveMQSecurityManager5} implementation that authenticates the user by either
 * retrieving an access token on behalf of the service user or validating the supplied access token
 */
public class ActiveMQORSecurityManager implements ActiveMQSecurityManager5 {
  protected static final Logger LOG =
      SyslogCategory.getLogger(SyslogCategory.API, ActiveMQORSecurityManager.class.getName());
  public static final String ANONYMOUS_USERNAME = "anonymous";
  protected static final long TOKEN_TIMEOUT_MILLIS = 10000;
  protected final MQTTBrokerService brokerService;
  protected final ExecutorService executorService;
  protected final IdentityService identityService;

  public ActiveMQORSecurityManager(
      MQTTBrokerService brokerService,
      ExecutorService executorService,
      IdentityService identityService) {
    this.brokerService = brokerService;
    this.executorService = executorService;
    this.identityService = identityService;
  }

  protected static Topic fromAddress(String address, WildcardConfiguration wildcardConfiguration)
      throws IllegalArgumentException {
    return Topic.parse(MQTTUtil.getMqttTopicFromCoreAddress(address, wildcardConfiguration));
  }

  @Override
  public Subject authenticate(
      String user, String password, RemotingConnection remotingConnection, String securityDomain)
      throws NoCacheLoginException {

    if (remotingConnection.getSubject() != null) {
      return remotingConnection.getSubject();
    }

    Set<Principal> principals = new HashSet<>();
    // Create a connection principal to track the remoting connection associated with this subject
    principals.add(new RemotingConnectionPrincipal(remotingConnection));

    if (TextUtil.isNullOrEmpty(password)) {
      // Replicate anonymous guest user
      principals.add(new UserPrincipal(ANONYMOUS_USERNAME));
      principals.add(new RolePrincipal(ANONYMOUS_USERNAME));

      LOG.finer(
          "Anonymous user authenticated: "
              + MQTTBrokerService.connectionToString(remotingConnection));
    } else {
      String realm = null;

      try {
        // TODO: Add support for bearer token authentication
        // https://github.com/openremote/core/issues/2534
        // Login service user
        if (user != null) {
          int delimIndex = user.indexOf(':');
          if (delimIndex > 0) {
            realm = user.substring(0, delimIndex);
            user = user.substring(delimIndex + 1);
          }
        }

        if (realm == null) {
          LOG.info("Invalid user format: " + user);
          return null;
        }

        OIDCTokenResponse oidcTokenResponse =
            identityService
                .authenticate(realm, user, password)
                .get(TOKEN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        TokenPrincipal tokenPrincipal = identityService.verify(realm, oidcTokenResponse.getToken());
        UserPrincipal userPrincipal = new UserPrincipal(tokenPrincipal.getName());
        principals.add(tokenPrincipal);
        principals.add(userPrincipal);
      } catch (InterruptedException
          | TimeoutException
          | ExecutionException
          | AuthenticationException e) {
        Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;
        LOG.info(
            "Failed to authenticate user: realm="
                + realm
                + ", username="
                + user
                + ", exception="
                + cause);
        return null;
      }
    }

    Subject subject = new Subject(true, principals, Set.of(), Set.of());
    // Set subject here so any code that calls this method behaves like a normal ActiveMQ
    // SecurityStoreImpl::authenticate call
    remotingConnection.setSubject(subject);

    return subject;
  }

  @Override
  public boolean authorize(Subject subject, Set<Role> roles, CheckType checkType, String address) {

    return switch (checkType) {
      case SEND -> verifyRights(subject, address, true);
      case CONSUME -> {
        int index = address.indexOf("::");
        address = address.substring(0, index);
        yield verifyRights(subject, address, false);
      }
      case CREATE_ADDRESS,
          DELETE_ADDRESS,
          CREATE_DURABLE_QUEUE,
          DELETE_DURABLE_QUEUE,
          CREATE_NON_DURABLE_QUEUE,
          DELETE_NON_DURABLE_QUEUE ->
          // All MQTT clients must be able to create addresses and queues (every session and
          // subscription will create a queue within the topic address)
          true;
      case MANAGE, BROWSE, VIEW, EDIT -> false;
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  protected boolean verifyRights(Subject subject, String address, boolean isWrite) {
    Topic topic;

    try {
      // Get MQTT topic from address
      topic = fromAddress(address, brokerService.getWildcardConfiguration());
    } catch (IllegalArgumentException e) {
      LOG.log(Level.FINE, "Invalid topic provided by client '" + address, e);
      return false;
    }

    TokenPrincipal tokenPrincipal = IdentityProvider.getTokenPrincipal(subject);
    String topicClientID = MQTTHandler.topicClientID(topic);

    if (topicClientID == null) {
      LOG.fine(
          "Client ID not found but it must be included as the second token in the topic: topic="
              + topic);
      return false;
    }

    RemotingConnection connection =
        RemotingConnectionPrincipal.getRemotingConnectionFromSubject(subject);

    if (connection == null) {
      LOG.info("Failed to find connection for the specified client ID: clientID=" + topicClientID);
      return false;
    }

    if (isWrite && topic.hasWildcard()) {
      return false;
    }

    // See if a custom handler wants to handle authorization for this topic pub/sub
    for (MQTTHandler handler : brokerService.getCustomHandlers()) {
      if (handler.handlesTopic(topic)) {
        LOG.finest(
            "Passing topic to handler for "
                + (isWrite ? "pub" : "sub")
                + ": handler="
                + handler.getName()
                + ", topic="
                + topic
                + ", "
                + connectionToString(connection));
        boolean result;

        if (isWrite) {
          result = handler.checkCanPublish(connection, tokenPrincipal, topic);
        } else {
          result = handler.checkCanSubscribe(connection, tokenPrincipal, topic);
        }
        if (result) {
          LOG.finest(
              "Handler '"
                  + handler.getName()
                  + "' has authorized "
                  + (isWrite ? "pub" : "sub")
                  + ": topic="
                  + topic
                  + ", "
                  + connectionToString(connection));
        } else {
          LOG.finest(
              "Handler '"
                  + handler.getName()
                  + "' has not authorized "
                  + (isWrite ? "pub" : "sub")
                  + ": topic="
                  + topic
                  + ", "
                  + connectionToString(connection));
        }
        return result;
      }
    }

    LOG.info(
        "Un-supported request "
            + (isWrite ? "pub" : "sub")
            + ": topic="
            + topic
            + ", "
            + connectionToString(connection));
    return false;
  }

  @Override
  public boolean validateUser(String user, String password) {
    throw new UnsupportedOperationException(
        "Invoke validateUser(String, String, RemotingConnection, String) instead");
  }

  @Override
  public boolean validateUserAndRole(
      String user, String password, Set<Role> roles, CheckType checkType) {
    throw new UnsupportedOperationException(
        "Invoke validateUserAndRole(String, String, Set<Role>, CheckType, String, RemotingConnection, String) instead");
  }
}
