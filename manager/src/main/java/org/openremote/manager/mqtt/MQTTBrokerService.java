/*
 * Copyright 2022, OpenRemote Inc.
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

import static java.lang.System.Logger.Level.*;
import static java.util.stream.StreamSupport.stream;
import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.model.Constants.*;
import static org.openremote.model.syslog.SyslogCategory.API;
import static org.openremote.model.util.MapAccess.getBoolean;
import static org.openremote.model.util.MapAccess.getInteger;
import static org.openremote.model.util.MapAccess.getString;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.api.core.management.CoreNotificationType;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.core.client.impl.ClientSessionInternal;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.MetricsConfiguration;
import org.apache.activemq.artemis.core.config.WildcardConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTStateManager;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil;
import org.apache.activemq.artemis.core.remoting.FailureListener;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnection;
import org.apache.activemq.artemis.core.security.impl.SecurityStoreImpl;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerConnectionPlugin;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerSessionPlugin;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.settings.impl.PageFullMessagePolicy;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.Acceptor;
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextFactoryProvider;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager5;
import org.apache.activemq.artemis.spi.core.security.jaas.NoCacheLoginException;
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal;
import org.apache.camel.builder.RouteBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.IdentityProvider;
import org.openremote.container.security.TokenPrincipal;
import org.openremote.container.security.keycloak.KeycloakIdentityProvider;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.AuthorisationService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.security.User;
import org.openremote.model.util.Debouncer;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.UniqueIdentifierGenerator;

// TODO: Add queue size limiting in canPublish of MQTTHandlers (needs to be done at auth time to
// allow pub to be rejected)
public class MQTTBrokerService extends RouteBuilder
    implements ContainerService, ActiveMQServerConnectionPlugin, ActiveMQServerSessionPlugin {

  public static final String MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS =
      "MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS";
  public static int MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS_DEFAULT = 5000;
  public static final int PRIORITY = MED_PRIORITY;
  public static final String MQTT_SERVER_LISTEN_HOST = "MQTT_SERVER_LISTEN_HOST";
  public static final String MQTT_SERVER_LISTEN_PORT = "MQTT_SERVER_LISTEN_PORT";
  // Allow 5 min durable session but this will not enable retained topics etc. as we delete queues
  // aggressively for now
  public static final int DEFAULT_SESSION_EXPIRY_MILLIS = 300000;
  public static final String MTLS_ACCEPTOR_NAME = "mqtt-mtls";
  protected static final long CERT_RELOAD_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
  protected final WildcardConfiguration wildcardConfiguration = new WildcardConfiguration();
  protected static final System.Logger LOG =
      System.getLogger(MQTTBrokerService.class.getName() + "." + API.name());

  protected AssetStorageService assetStorageService;
  protected AuthorisationService authorisationService;
  protected ManagerIdentityService identityService;
  protected ManagerKeycloakIdentityProvider identityProvider;
  protected ClientEventService clientEventService;
  protected MessageBrokerService messageBrokerService;
  protected ExecutorService executorService;
  protected TimerService timerService;
  protected AssetProcessingService assetProcessingService;
  protected PersistenceService persistenceService;
  protected List<MQTTHandler> customHandlers = new ArrayList<>();
  protected ConcurrentMap<String, RemotingConnection> clientIDConnectionMap =
      new ConcurrentHashMap<>();
  protected ConcurrentMap<String, RemotingConnection> connectionIDConnectionMap =
      new ConcurrentHashMap<>();
  protected ConcurrentMap<String, List<PersistenceEvent<UserAssetLink>>> userAssetLinkChangeMap =
      new ConcurrentHashMap<>();
  protected Debouncer<String> userAssetDisconnectDebouncer;
  // Stores disconnected connections for a short period to allow last will publishes to be processed
  protected Cache<String, RemotingConnection> disconnectedConnectionCache;
  protected boolean active;
  protected String host;
  protected int port;
  protected Configuration serverConfiguration;
  protected EmbeddedActiveMQ server;
  protected ActiveMQSecurityManager5 securityManager;
  protected ServerLocator serverLocator;
  protected ClientSessionFactory sessionFactory;

  protected boolean mtlsDisabled;
  protected boolean mtlsAcceptorEnabled;
  protected String mtlsHost;
  protected int mtlsPort;
  protected String keystorePath;
  protected String keystorePassword;
  protected String truststorePath;
  protected String truststorePassword;
  protected ScheduledExecutorService scheduledExecutorService;
  protected ScheduledFuture<?> certificateReloadFuture;

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    host = getString(container.getConfig(), MQTT_SERVER_LISTEN_HOST, "0.0.0.0");
    port = getInteger(container.getConfig(), MQTT_SERVER_LISTEN_PORT, 1883);
    int debounceMillis =
        getInteger(
            container.getConfig(),
            MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS,
            MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS_DEFAULT);
    assetStorageService = container.getService(AssetStorageService.class);
    authorisationService = container.getService(AuthorisationService.class);
    clientEventService = container.getService(ClientEventService.class);
    identityService = container.getService(ManagerIdentityService.class);
    messageBrokerService = container.getService(MessageBrokerService.class);
    executorService = container.getExecutor();
    timerService = container.getService(TimerService.class);
    assetProcessingService = container.getService(AssetProcessingService.class);
    persistenceService = container.getService(PersistenceService.class);

    scheduledExecutorService = container.getScheduledExecutor();

    // mTLS values
    final Path keystoreDirPath = Paths.get(OR_MQTT_MTLS_KEYSTORE_DIR_DEFAULT);
    this.mtlsDisabled =
        getBoolean(container.getConfig(), OR_MQTT_MTLS_DISABLED, OR_MQTT_MTLS_DISABLED_DEFAULT);
    this.mtlsHost = getString(container.getConfig(), OR_MQTT_MTLS_SERVER_LISTEN_HOST, host);
    this.mtlsPort =
        getInteger(
            container.getConfig(), OR_MQTT_MTLS_SERVER_LISTEN_PORT, OR_MQTT_MTLS_PORT_DEFAULT);
    this.keystorePath =
        getString(
            container.getConfig(),
            OR_MQTT_MTLS_KEYSTORE_PATH,
            persistenceService
                .resolvePath(keystoreDirPath.resolve(OR_MQTT_MTLS_KEYSTORE_FILE_DEFAULT))
                .toString());
    this.keystorePassword =
        getString(
            container.getConfig(),
            OR_MQTT_MTLS_KEYSTORE_PASSWORD,
            OR_MQTT_MTLS_KEYSTORE_PASSWORD_DEFAULT);
    this.truststorePath =
        getString(
            container.getConfig(),
            OR_MQTT_MTLS_TRUSTSTORE_PATH,
            persistenceService
                .resolvePath(keystoreDirPath.resolve(OR_MQTT_MTLS_TRUSTSTORE_FILE_DEFAULT))
                .toString());
    this.truststorePassword =
        getString(
            container.getConfig(),
            OR_MQTT_MTLS_TRUSTSTORE_PASSWORD,
            OR_MQTT_MTLS_KEYSTORE_PASSWORD_DEFAULT);

    userAssetDisconnectDebouncer =
        new Debouncer<>(
            container.getScheduledExecutor(),
            id -> processUserAssetLinkChange(id, userAssetLinkChangeMap.remove(id)),
            debounceMillis);
    // This allows last will messages to be processed
    disconnectedConnectionCache =
        CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofMillis(3000))
            .build();

    if (!identityService.isKeycloakEnabled()) {
      LOG.log(
          WARNING, "MQTT connections are not supported when not using Keycloak identity provider");
      active = false;
    } else {
      active = true;
      identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
      container.getService(MessageBrokerService.class).getContext().addRoutes(this);
    }

    // Create server config
    serverConfiguration = new ConfigurationImpl();
    serverConfiguration.setMqttSessionScanInterval(10000);
    serverConfiguration.addAcceptorConfiguration("in-vm", "vm://0?protocols=core");
    String serverURI =
        new URIBuilder()
            .setScheme("tcp")
            .setHost(host)
            .setPort(port)
            .setParameter("protocols", "MQTT")
            .setParameter(
                "allowLinkStealing",
                "false") // Preventing this ensures previous connection/session is properly cleaned
            // up before a reconnect
            .setParameter(
                "defaultMqttSessionExpiryInterval", Integer.toString(DEFAULT_SESSION_EXPIRY_MILLIS))
            .build()
            .toString();
    serverConfiguration.addAcceptorConfiguration("tcp", serverURI);

    if (!mtlsDisabled) {
      addMTLSAcceptor(container);
    }

    serverConfiguration.registerBrokerPlugin(this);
    if (container.getMeterRegistry() != null) {
      serverConfiguration.setMetricsConfiguration(
          new MetricsConfiguration()
              .setJvmMemory(false)
              .setPlugin(
                  new org.apache.activemq.artemis.core.server.metrics.plugins
                      .SimpleMetricsPlugin() {
                    @Override
                    public MeterRegistry getRegistry() {
                      return container.getMeterRegistry();
                    }
                  }));
    }
    serverConfiguration.setWildCardConfiguration(wildcardConfiguration);
    serverConfiguration.setLiteralMatchMarkers("()");

    // Configure global address settings - aggressively cleanup queues (don't support retained
    // messages)
    serverConfiguration.addQueueConfiguration(
        QueueConfiguration.of(wildcardConfiguration.getAnyWordsString()).setDurable(false));
    serverConfiguration.addAddressSetting(
        wildcardConfiguration.getAnyWordsString(),
        new AddressSettings()
            .setDeadLetterAddress(SimpleString.of("ActiveMQ.DLQ"))
            .setExpiryAddress(SimpleString.of("ActiveMQ.expired"))
            .setAutoDeleteCreatedQueues(true)
            .setAutoDeleteAddresses(true)
            // Auto delete MQTT addresses after 1 day as they never get flagged as used so will
            // linger otherwise
            .setAutoDeleteAddressesSkipUsageCheck(true)
            .setAutoDeleteAddressesDelay(86400000)
            .setAutoDeleteQueuesMessageCount(-1L)
            .setAutoDeleteQueuesDelay(0)
            // This has a negative impact on performance if set to 0
            .setDefaultConsumerWindowSize(-1)
            .setPageLimitMessages(0L)
            .setAddressFullMessagePolicy(AddressFullMessagePolicy.FAIL)
            .setPageFullMessagePolicy(PageFullMessagePolicy.FAIL)
            // We don't want excessive metrics so only enable metrics for each custom handler
            // address
            .setEnableMetrics(false));

    // The below is an example of rate limiting at the address level the FAIL policy will cause an
    // exception
    // that is handled by the MQTTProtocolHandler which will disconnect the client (MQTT doesn't
    // have a nice
    // way of handling rejected publishes)
    //        serverConfiguration.addAddressSetting("*.*.writeattributevalue.#",
    //            new AddressSettings()
    //                .setMaxSizeMessages(3)
    //                .setAddressFullMessagePolicy(AddressFullMessagePolicy.FAIL)
    //                .setMaxSizeBytes(1L)
    //        );

    serverConfiguration.setPersistenceEnabled(false);

    serverConfiguration.setAuthenticationCacheSize(0);
    serverConfiguration.setAuthorizationCacheSize(0);

    // Load custom handlers
    this.customHandlers =
        stream(ServiceLoader.load(MQTTHandler.class).spliterator(), false)
            .sorted(Comparator.comparingInt(MQTTHandler::getPriority))
            .collect(Collectors.toList());

    // Init each custom handler
    for (MQTTHandler handler : customHandlers) {
      try {
        handler.init(container, serverConfiguration);
      } catch (Exception e) {
        LOG.log(
            WARNING,
            "MQTT custom handler threw an exception whilst initialising: handler="
                + handler.getName(),
            e);
        throw e;
      }
    }
  }

  @Override
  public void start(Container container) throws Exception {

    if (!active) {
      return;
    }

    // Start the broker
    server = new EmbeddedActiveMQ();
    server.setConfiguration(serverConfiguration);

    securityManager =
        new ActiveMQORSecurityManager(this, executorService, identityService, identityProvider);

    // The context factory is instantiated by Artemis via ServiceLoader during server start, so the
    // container has to be registered before that happens
    OpenRemoteSSLContextFactory.setContainer(container);

    server.setSecurityManager(securityManager);
    server.start();
    LOG.log(DEBUG, "Started MQTT broker");

    if (mtlsAcceptorEnabled) {
      certificateReloadFuture =
          scheduledExecutorService.scheduleWithFixedDelay(
              this::reloadMTLSCertificatesIfChanged,
              CERT_RELOAD_INTERVAL_MILLIS,
              CERT_RELOAD_INTERVAL_MILLIS,
              TimeUnit.MILLISECONDS);
    }

    // Add a notification handler for subscribe/unsubscribe and publish events
    server
        .getActiveMQServer()
        .getManagementService()
        .addNotificationListener(
            notification -> {
              if (notification.getType() == CoreNotificationType.CONSUMER_CREATED
                  || notification.getType() == CoreNotificationType.CONSUMER_CLOSED) {
                boolean isSubscribe =
                    notification.getType() == CoreNotificationType.CONSUMER_CREATED;
                String sessionId =
                    notification
                        .getProperties()
                        .getSimpleStringProperty(ManagementHelper.HDR_SESSION_NAME)
                        .toString();
                String topic =
                    notification
                        .getProperties()
                        .getSimpleStringProperty(ManagementHelper.HDR_ADDRESS)
                        .toString();
                ServerSession session = server.getActiveMQServer().getSessionByID(sessionId);

                if (session == null) {
                  return;
                }

                // Ignore internal subscriptions
                boolean isInternal =
                    session.getRemotingConnection().getTransportConnection()
                        instanceof InVMConnection;
                if (isInternal) {
                  return;
                }

                if (isSubscribe) {
                  onSubscribe(
                      session.getRemotingConnection(),
                      MQTTUtil.getMqttTopicFromCoreAddress(topic, wildcardConfiguration));
                } else {
                  onUnsubscribe(
                      session.getRemotingConnection(),
                      MQTTUtil.getMqttTopicFromCoreAddress(topic, wildcardConfiguration));
                }
              }
            });

    // Don't use producer flow control
    serverLocator = ActiveMQClient.createServerLocator("vm://0").setProducerWindowSize(-1);
    sessionFactory = serverLocator.createSessionFactory();

    // Start each custom handler
    for (MQTTHandler handler : customHandlers) {
      try {
        handler.start(container);
      } catch (Exception e) {
        LOG.log(
            WARNING,
            "MQTT custom handler threw an exception whilst starting: handler=" + handler.getName(),
            e);
        throw e;
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void configure() throws Exception {
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-UserAndAssetLink")
        .filter(body().isInstanceOf(PersistenceEvent.class))
        .process(
            exchange -> {
              PersistenceEvent<?> persistenceEvent =
                  (PersistenceEvent<?>) exchange.getIn().getBody(PersistenceEvent.class);

              if (persistenceEvent.getEntity() instanceof User user) {

                if (!user.isServiceAccount()) {
                  return;
                }

                boolean forceDisconnect =
                    persistenceEvent.getCause() == PersistenceEvent.Cause.DELETE;

                if (persistenceEvent.getCause() == PersistenceEvent.Cause.UPDATE) {
                  // Force disconnect if certain properties have changed
                  forceDisconnect =
                      persistenceEvent.hasPropertyChanged("enabled")
                          || persistenceEvent.hasPropertyChanged("username")
                          || persistenceEvent.hasPropertyChanged("secret");
                }

                if (forceDisconnect) {
                  LOG.log(
                      TRACE,
                      "User modified or deleted so force closing any sessions for this user: "
                          + user);
                  // Find existing connection for this user
                  getUserConnections(user.getId()).forEach(this::doForceDisconnect);
                }

              } else if (persistenceEvent.getEntity() instanceof UserAssetLink userAssetLink) {
                String userID = userAssetLink.getId().getUserId();
                // Debounce force disconnect check of this user's sessions as there could be many
                // asset links changing
                List<PersistenceEvent<UserAssetLink>> changedUserAssetLinks =
                    userAssetLinkChangeMap.computeIfAbsent(
                        userID, id -> Collections.synchronizedList(new ArrayList<>()));
                changedUserAssetLinks.add((PersistenceEvent<UserAssetLink>) persistenceEvent);
                userAssetDisconnectDebouncer.call(userID);
              }
            });
  }

  @Override
  public void stop(Container container) throws Exception {

    userAssetDisconnectDebouncer.cancelAll(true);

    if (certificateReloadFuture != null) {
      certificateReloadFuture.cancel(true);
      certificateReloadFuture = null;
    }

    server.stop();
    LOG.log(DEBUG, "Stopped MQTT broker");

    OpenRemoteSSLContextFactory.clearContainer();

    stream(ServiceLoader.load(MQTTHandler.class).spliterator(), false)
        .sorted(Comparator.comparingInt(MQTTHandler::getPriority).reversed())
        .forEach(
            handler -> {
              try {
                handler.stop();
              } catch (Exception e) {
                LOG.log(
                    WARNING,
                    "MQTT custom handler threw an exception whilst stopping: handler="
                        + handler.getName(),
                    e);
              }
            });
  }

  @Override
  public void afterCreateConnection(RemotingConnection connection) throws ActiveMQException {

    // MQTT seems to only use failure callback even if closed gracefully (need to look at the
    // exception for details)
    connection.addFailureListener(
        new FailureListener() {
          @Override
          public void connectionFailed(ActiveMQException exception, boolean failedOver) {
            connectionFailed(exception, failedOver, null);
          }

          @Override
          public void connectionFailed(
              ActiveMQException exception, boolean failedOver, String scaleDownTargetNodeID) {
            connectionIDConnectionMap.remove(getConnectionIDString(connection));

            if (connection.getClientID() != null) {
              // Check and remove atomically so a reconnect cannot replace this entry mid-cleanup.
              // If it is already absent, leave any newer disconnected connection in the cache.
              clientIDConnectionMap.computeIfPresent(
                  connection.getClientID(),
                  (clientID, currentConnection) -> {
                    // Only clean up the exact connection that triggered this callback.
                    if (currentConnection == connection) {
                      // Last wills are processed asynchronously and still need this connection.
                      // Cache it before removal so lookups never see a gap between the two stores.
                      disconnectedConnectionCache.put(clientID, connection);
                      return null; // Returning null removes the active entry.
                    }
                    // A reconnect has replaced this connection; preserve the replacement and cache.
                    return currentConnection;
                  });
            }

            if (exception.getType()
                == ActiveMQExceptionType
                    .REMOTE_DISCONNECT) { // Seems to be the type for graceful close of connection
              // Notify handlers of connection close
              LOG.log(DEBUG, () -> "Client disconnected: " + connectionToString(connection));
              for (MQTTHandler handler : getCustomHandlers()) {
                handler.onDisconnect(connection);
              }
            } else {
              // Notify handlers of connection failure
              LOG.log(
                  DEBUG,
                  () ->
                      "Client disconnected (failure="
                          + exception.getMessage()
                          + "): "
                          + connectionToString(connection));
              for (MQTTHandler handler : getCustomHandlers()) {
                handler.onConnectionLost(connection);
              }
            }
          }
        });
  }

  // We do this here as connection plugin afterCreateConnection callback fires before client ID and
  // subject are populated
  // For each connection multiple sessions are created but we only want to call custom handlers
  // onConnect once
  @Override
  public void afterCreateSession(ServerSession session) throws ActiveMQException {
    RemotingConnection remotingConnection = session.getRemotingConnection();

    // Ignore internal connections or ones without a client ID
    if (remotingConnection == null
        || remotingConnection.getClientID() == null
        || remotingConnection.getTransportConnection() instanceof InVMConnection) {
      return;
    }

    String connectionID = getConnectionIDString(remotingConnection);
    clientIDConnectionMap.put(remotingConnection.getClientID(), remotingConnection);

    if (!connectionIDConnectionMap.containsKey(connectionID)) {
      LOG.log(DEBUG, () -> "Client connected: " + connectionToString(remotingConnection));
      connectionIDConnectionMap.put(connectionID, remotingConnection);
      for (MQTTHandler handler : getCustomHandlers()) {
        handler.onConnect(remotingConnection);
      }
    }
  }

  @Override
  public void afterDestroyConnection(RemotingConnection connection) throws ActiveMQException {}

  public void onSubscribe(RemotingConnection connection, String topicStr) {
    Topic topic = Topic.parse(topicStr);
    LOG.log(TRACE, () -> "onSubscribe '" + topicStr + "': " + connectionToString(connection));
    for (MQTTHandler handler : getCustomHandlers()) {
      if (handler.handlesTopic(topic)) {
        LOG.log(
            DEBUG, () -> "Client subscribed '" + topicStr + "': " + connectionToString(connection));
        handler.onSubscribe(connection, topic);
        break;
      }
    }
  }

  public void onUnsubscribe(RemotingConnection connection, String topicStr) {
    Topic topic = Topic.parse(topicStr);
    LOG.log(TRACE, () -> "onUnsubscribe '" + topicStr + "': " + connectionToString(connection));
    for (MQTTHandler handler : getCustomHandlers()) {
      if (handler.handlesTopic(topic)) {
        LOG.log(
            DEBUG,
            () -> "Client unsubscribed '" + topicStr + "': " + connectionToString(connection));
        handler.onUnsubscribe(connection, topic);
        break;
      }
    }
  }

  public Iterable<MQTTHandler> getCustomHandlers() {
    return customHandlers;
  }

  public void processUserAssetLinkChange(
      String userID, List<PersistenceEvent<UserAssetLink>> changes) {
    if (TextUtil.isNullOrEmpty(userID)) {
      return;
    }

    // Check if user has any active connections
    Set<RemotingConnection> userConnections = getUserConnections(userID);
    Subject subject =
        userConnections.stream()
            .filter(connection -> connection.getSubject() != null)
            .findFirst()
            .map(RemotingConnection::getSubject)
            .orElse(null);

    // Only notify handlers if subject is a restricted user
    if (Optional.ofNullable(IdentityProvider.getTokenPrincipal(subject))
        .map(TokenPrincipal::isRestrictedUser)
        .orElse(false)) {
      LOG.log(
          TRACE,
          "User asset links modified for connected restricted user so passing to handlers to decide what to do: user="
              + subject);
      // Pass to handlers to decide what to do
      userConnections.forEach(
          connection -> {
            for (MQTTHandler handler : customHandlers) {
              connection.setSubject(subject);
              handler.onUserAssetLinksChanged(connection, changes);
            }
          });
    }
  }

  /** Get active connections for the specified user ID */
  public Set<RemotingConnection> getUserConnections(String userID) {
    if (TextUtil.isNullOrEmpty(userID)) {
      return Collections.emptySet();
    }

    return server.getActiveMQServer().getRemotingService().getConnections().stream()
        .filter(
            connection -> {
              if (!connection.getTransportConnection().isOpen()) {
                return false;
              }
              Subject subject = connection.getSubject();
              String subjectID = IdentityProvider.getSubjectId(subject);
              return userID.equals(subjectID);
            })
        .collect(Collectors.toSet());
  }

  public String getConnectionUserId(String connectionID) {
    RemotingConnection connection = connectionIDConnectionMap.get(connectionID);
    return connection == null ? null : IdentityProvider.getSubjectId(connection.getSubject());
  }

  protected void doForceDisconnect(RemotingConnection connection) {
    LOG.log(
        DEBUG, () -> "Force disconnecting client connection: " + connectionToString(connection));
    connection.disconnect(false);
    // Destroy session for force disconnects
    try {
      if (MQTTStateManager.getInstance(server.getActiveMQServer())
              .removeSessionState(connection.getClientID())
          != null) {
        LOG.log(
            TRACE,
            () -> "Removed session state for client connection: " + connectionToString(connection));
      }
    } catch (Exception e) {
      LOG.log(
          INFO,
          () ->
              "Failed to get server instance to clear session for client connection: "
                  + connectionToString(connection));
    }
    ((SecurityStoreImpl) server.getActiveMQServer().getSecurityStore())
        .invalidateAuthorizationCache();
  }

  public boolean disconnectSession(String connectionID) {
    RemotingConnection connection = connectionIDConnectionMap.get(connectionID);
    if (connection != null) {
      LOG.log(DEBUG, "Force disconnecting client connection: " + connectionToString(connection));
      doForceDisconnect(connection);
      return true;
    }

    return false;
  }

  public WildcardConfiguration getWildcardConfiguration() {
    return wildcardConfiguration;
  }

  public static String getConnectionIDString(RemotingConnection connection) {
    if (connection == null) {
      return null;
    }

    Object ID = connection.getID();
    return ID instanceof ChannelId ? ((ChannelId) ID).asLongText() : ID.toString();
  }

  public static String connectionToString(RemotingConnection connection) {
    if (connection == null) {
      return "";
    }

    String username = null;
    Subject subject = connection.getSubject();

    if (subject != null) {
      username = getSubjectName(subject);
    }

    return "connection="
        + connection.getRemoteAddress()
        + ", clientID="
        + connection.getClientID()
        + ", subject="
        + username;
  }

  public static String getSubjectName(Subject subject) {
    return subject.getPrincipals().stream()
        .filter(principal -> principal instanceof UserPrincipal)
        .findFirst()
        .map(Principal::getName)
        .orElse(KeycloakIdentityProvider.getSubjectNameAndRealm(subject));
  }

  public RemotingConnection getConnectionFromClientID(String clientID) {
    if (TextUtil.isNullOrEmpty(clientID)) {
      return null;
    }

    // This logic is needed because the connection clientID isn't populated when
    // afterCreateConnection is called
    RemotingConnection connection = clientIDConnectionMap.get(clientID);

    if (connection == null) {
      // Try and find the client ID from the sessions
      for (RemotingConnection remotingConnection :
          server.getActiveMQServer().getRemotingService().getConnections()) {
        if (Objects.equals(clientID, remotingConnection.getClientID())) {
          connection = remotingConnection;
          clientIDConnectionMap.put(clientID, connection);
          break;
        }
      }
    }

    if (connection == null) {
      // Look in the recently disconnected cache
      connection = disconnectedConnectionCache.getIfPresent(clientID);
    }

    return connection;
  }

  protected void notifyConnectionAuthenticated(RemotingConnection connection) {
    if (connection.getSubject() != null) {
      // Notify handlers that connection authenticated
      LOG.log(DEBUG, "Client connection authenticated: " + connectionToString(connection));
      for (MQTTHandler handler : getCustomHandlers()) {
        handler.onConnectionAuthenticated(connection);
      }
    }
  }

  /** Create a client session for communicating with the broker */
  protected ClientSession createSession() throws Exception {

    ClientSessionInternal session = null;

    try {
      String internalClientID = UniqueIdentifierGenerator.generateId("Internal client");
      session =
          (ClientSessionInternal)
              sessionFactory.createSession(
                  null,
                  null,
                  false,
                  true,
                  true,
                  true,
                  serverLocator.getAckBatchSize(),
                  internalClientID);
      session.addMetaData(ClientSession.JMS_SESSION_IDENTIFIER_PROPERTY, "Internal session");
      ServerSession serverSession = server.getActiveMQServer().getSessionByID(session.getName());
      serverSession.disableSecurity();
      session.start();
    } catch (Exception e) {
      LOG.log(WARNING, "Failed to create MQTT client session", e);
    }

    return session;
  }

  protected WildcardConfiguration getServerWildcardConfiguration() {
    return server.getConfiguration().getWildcardConfiguration();
  }

  public void authenticateConnection(
      RemotingConnection connection, String realm, String username, String password) {
    if (connection != null) {
      connection.setSubject(null); // Clear existing subject
      try {
        securityManager.authenticate(realm + ":" + username, password, connection, null);
        notifyConnectionAuthenticated(connection);
      } catch (NoCacheLoginException e) {
        LOG.log(INFO, "Failed to authenticate MQTT connection: " + connectionToString(connection));
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Adds the mTLS acceptor, provided the deployment can actually serve it. A server certificate
   * comes from either the proxy certificate directory or a configured keystore, and a truststore is
   * mandatory: without one there is nothing to verify client certificates against, which is the
   * only reason this acceptor exists. Anything missing leaves the acceptor out rather than failing
   * the broker, so a deployment that has not been set up for mTLS still starts.
   */
  protected void addMTLSAcceptor(Container container) throws Exception {
    Path proxyCertsDir = OpenRemoteSSLContextFactory.resolveProxyCertsDir(container);
    boolean hasProxyCert = proxyCertsDir != null && Files.isDirectory(proxyCertsDir);
    boolean hasKeystore = isReadableFile(keystorePath);

    if (!hasProxyCert && !hasKeystore) {
      LOG.log(
          INFO,
          "MQTT mTLS acceptor not started: no server certificate available (no proxy certificates"
              + " in "
              + proxyCertsDir
              + " and no readable keystore at "
              + keystorePath
              + ")");
      return;
    }

    if (!isReadableFile(truststorePath)) {
      LOG.log(
          WARNING,
          "MQTT mTLS acceptor not started: client certificates cannot be verified without a"
              + " truststore, set "
              + OR_MQTT_MTLS_TRUSTSTORE_PATH
              + " (no readable truststore at "
              + truststorePath
              + ")");
      return;
    }

    String mtlsServerURI =
        new URIBuilder()
            .setScheme("tcp")
            .setHost(this.mtlsHost)
            .setPort(this.mtlsPort)
            .setParameter("protocols", "MQTT")
            .setParameter("allowLinkStealing", "false")
            .setParameter(
                "defaultMqttSessionExpiryInterval", Integer.toString(DEFAULT_SESSION_EXPIRY_MILLIS))
            .setParameter("sslEnabled", "true")
            .setParameter("needClientAuth", "true") // Require client certificates (mTLS)
            .setParameter("keyStorePath", this.keystorePath)
            .setParameter("keyStorePassword", this.keystorePassword)
            .setParameter("trustStorePath", this.truststorePath)
            .setParameter("trustStorePassword", this.truststorePassword)
            .build()
            .toString();

    serverConfiguration.addAcceptorConfiguration(MTLS_ACCEPTOR_NAME, mtlsServerURI);
    mtlsAcceptorEnabled = true;
    LOG.log(
        INFO,
        "Added mTLS MQTT acceptor listening on "
            + mtlsHost
            + ":"
            + mtlsPort
            + (hasProxyCert
                ? " (using the proxy certificate)"
                : " (using the configured keystore)"));
  }

  protected static boolean isReadableFile(String path) {
    return !TextUtil.isNullOrEmpty(path) && Files.isReadable(Paths.get(path));
  }

  /**
   * Artemis builds an acceptor's {@link javax.net.ssl.SSLContext} once and holds it, so clearing
   * the context factory's cache is not enough on its own; the acceptor has to be reloaded for a
   * renewed certificate to be served. Reloading drops the connections on that acceptor, which is
   * why it only happens when the certificate has actually changed.
   */
  protected void reloadMTLSCertificatesIfChanged() {
    try {
      if (!(SSLContextFactoryProvider.getSSLContextFactory()
          instanceof OpenRemoteSSLContextFactory sslContextFactory)) {
        return;
      }
      if (!sslContextFactory.certificatesHaveChanged()) {
        return;
      }

      Acceptor acceptor =
          server.getActiveMQServer().getRemotingService().getAcceptor(MTLS_ACCEPTOR_NAME);

      if (acceptor == null) {
        return;
      }

      sslContextFactory.clearSSLContexts();
      acceptor.reload();
      LOG.log(INFO, "Reloaded the mTLS acceptor following a certificate change");
    } catch (Exception e) {
      LOG.log(WARNING, "Failed to reload the mTLS acceptor after a certificate change", e);
    }
  }
}
