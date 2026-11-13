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
package org.openremote.manager.event;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static java.lang.System.Logger.Level.*;
import static org.openremote.manager.asset.AssetProcessingService.ATTRIBUTE_EVENT_PROCESSOR;
import static org.openremote.manager.asset.AssetProcessingService.ATTRIBUTE_EVENT_ROUTE_CONFIG_ID;
import static org.openremote.model.Constants.*;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import jakarta.security.enterprise.AuthenticationException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.undertow.UndertowComponent;
import org.apache.camel.component.undertow.UndertowConstants;
import org.apache.camel.component.undertow.UndertowHostKey;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.security.TokenPrincipal;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.Event;
import org.openremote.model.event.RespondableEvent;
import org.openremote.model.event.TriggeredEventSubscription;
import org.openremote.model.event.shared.*;
import org.openremote.model.http.RequestParams;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogEvent;
import org.openremote.model.util.Pair;
import org.openremote.model.util.TextUtil;

/**
 * Receives and publishes messages, handles the client/server event bus.
 *
 * <p>Messages always start with a message discriminator in all uppercase letters, followed by an
 * optional JSON payload.
 *
 * <p>The following messages can be sent by a client:
 *
 * <dl>
 *   <dt><code>SUBSCRIBE:{...}</code>
 *   <dt>
 *   <dd>
 *       <p>The payload is a serialized representation of {@link EventSubscription} with an optional
 *       {@link org.openremote.model.event.shared.EventFilter}. Clients can subscribe to receive
 *       {@link SharedEvent}s when they are published on the server. Subscriptions are handled by
 *       {@link SharedEvent#getEventType}.
 *   <dt><code>UNSUBSCRIBE:{...}</code>
 *   <dd>
 *       <p>The payload is a serialized representation of {@link CancelEventSubscription}. If a
 *       client does not want to wait for expiration of its subscriptions, it can cancel a
 *       subscription.
 *   <dt><code>EVENT:{...}</code>
 *   <dd>
 *       <p>The payload is a serialized representation of a subtype of {@link SharedEvent}. If the
 *       server does not recognize the event, it is silently ignored.
 * </dl>
 *
 * <p>The following messages can be published/returned by the server:
 *
 * <dl>
 *   <dt><code>UNAUTHORIZED:{...}</code>
 *   <dd>
 *       <p>The payload is a serialized representation of {@link UnauthorizedEventSubscription}.
 *   <dt><code>EVENT:{...}</code>
 *   <dd>
 *       <p>The payload is a serialized representation of a subtype of {@link SharedEvent}.
 *   <dt><code>EVENT:[...]</code>
 *   <dd>
 *       <p>The payload is an array of {@link SharedEvent}s.
 * </dl>
 */
public class ClientEventService extends RouteBuilder implements ContainerService {

  /** Holds state for each websocket session */
  protected static class SessionDispatchState {
    final Deque<Exchange> queue = new ArrayDeque<>();
    boolean draining = false;
    boolean closed = false;
  }

  public static final int PRIORITY = ManagerWebService.PRIORITY - 200;
  protected static final int MAX_QUEUE_SIZE = 100;
  protected static final String WEBSOCKET_URI =
      "undertow://ws://0.0.0.0/websocket/events?fireWebSocketChannelEvents=true&sendTimeout=15000"; // Host is not used as existing undertow instance is utilized
  protected static final System.Logger LOG = System.getLogger(ClientEventService.class.getName());
  protected static final String PUBLISH_QUEUE = "direct://ClientPublishQueue";

  /**
   * Delimiter used when composing the internal subscription key from {@code eventType} and {@code
   * subscriptionId}.
   *
   * <p>The key format is: {@code <eventType>::<subscriptionId>}
   *
   * <p>This allows unsubscribe requests to match subscriptions even when the client only provides
   * one part of the original key, which is required for websocket clients that unsubscribe using
   * only the subscription id or only the event type.
   */
  protected static final String SUBSCRIPTION_KEY_DELIMITER = "::";

  protected final Collection<EventSubscriptionAuthorizer> eventSubscriptionAuthorizers =
      new CopyOnWriteArraySet<>();
  protected final Collection<EventAuthorizer> eventAuthorizers = new CopyOnWriteArraySet<>();
  protected final Set<Pair<EventSubscription<? extends Event>, Consumer<? extends Event>>>
      eventSubscriptions = new CopyOnWriteArraySet<>();
  protected final Map<String, WebSocketChannel> sessionChannels = new ConcurrentHashMap<>();
  protected final Map<String, Map<String, Consumer<? extends Event>>>
      websocketSessionSubscriptionConsumers = new ConcurrentHashMap<>();
  protected final Map<String, SessionDispatchState> sessionStates = new ConcurrentHashMap<>();

  protected TimerService timerService;
  protected ExecutorService executorService;
  protected MessageBrokerService messageBrokerService;
  protected ManagerIdentityService identityService;
  protected GatewayService gatewayService;
  protected boolean started;
  protected Consumer<Exchange> gatewayInterceptor;

  public static String getSessionKey(Exchange exchange) {
    return exchange.getIn().getHeader(UndertowConstants.CONNECTION_KEY, String.class);
  }

  public static String getClientId(Exchange exchange) {
    AuthContext authContext = exchange.getIn().getHeader(AUTH_CONTEXT, AuthContext.class);
    if (authContext != null) {
      return authContext.getClientId();
    }
    return null;
  }

  public static String getRealm(Exchange exchange) {
    return exchange.getIn().getHeader(REALM_PARAM_NAME, String.class);
  }

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    timerService = container.getService(TimerService.class);
    messageBrokerService = container.getService(MessageBrokerService.class);
    identityService = container.getService(ManagerIdentityService.class);
    gatewayService = container.getService(GatewayService.class);
    executorService = container.getExecutor();

    UndertowComponent undertowWebsocketComponent =
        new UndertowComponent(messageBrokerService.getContext()) {
          @Override
          protected org.apache.camel.component.undertow.UndertowHost createUndertowHost(
              UndertowHostKey key) {
            return new UndertowHost(container, key, getHostOptions());
          }
        };
    messageBrokerService.getContext().addComponent("undertow", undertowWebsocketComponent);

    messageBrokerService
        .getContext()
        .getTypeConverterRegistry()
        .addTypeConverters(new EventTypeConverters());

    messageBrokerService.getContext().addRoutes(this);
  }

  // TODO: Remove prefix and just use event type then use a subscription wrapper to pass
  // subscription ID around
  @Override
  public void configure() throws Exception {

    // Route for handling inbound websocket messages
    from(WEBSOCKET_URI)
        .routeId("ClientInbound-Websocket")
        .routeConfigurationId(ATTRIBUTE_EVENT_ROUTE_CONFIG_ID)
        .process(
            exchange -> {
              String sessionKey = getSessionKey(exchange);
              SessionDispatchState state =
                  sessionStates.computeIfAbsent(sessionKey, k -> new SessionDispatchState());
              UndertowConstants.EventType eventType =
                  exchange
                      .getIn()
                      .getHeader(
                          UndertowConstants.EVENT_TYPE_ENUM, UndertowConstants.EventType.class);
              boolean isTerminalEvent =
                  (eventType == UndertowConstants.EventType.ONCLOSE
                      || eventType == UndertowConstants.EventType.ONERROR);
              WebSocketChannel channel =
                  exchange.getIn().getHeader(UndertowConstants.CHANNEL, WebSocketChannel.class);
              boolean shouldSubmitDrainer = false;

              synchronized (state) {
                // 1. Drop normal frames if closed, but ALWAYS let terminal events through
                if (isTerminalEvent) {
                  // Mark as closed to prevent queuing additional events
                  state.closed = true;
                } else if (state.closed) {
                  exchange.setRouteStop(true);
                  return;
                }

                // 2. Close the session if the queue limit is exceeded (only for non-terminal
                // events)
                if (!isTerminalEvent && state.queue.size() >= MAX_QUEUE_SIZE) {
                  LOG.log(
                      WARNING,
                      "Session " + sessionKey + " exceeded queue limit. Closing connection.");
                  closeSession(sessionKey, channel, state);
                  exchange.setRouteStop(true);
                  return;
                }

                // 3. Queue the frame and trigger the drainer if dormant
                // Create a safe copy of the exchange for async processing
                state.queue.add(exchange.copy());
                if (!state.draining) {
                  state.draining = true;
                  shouldSubmitDrainer = true;
                }
              }

              if (shouldSubmitDrainer) {
                // Capture the Undertow I/O thread
                final Thread submitterThread = Thread.currentThread();

                try {
                  executorService.submit(
                      () -> {
                        // Defeat CallerRunsPolicy: If the executor forces this thread to run the
                        // task,
                        // process only terminal frames so session close/error cleanup still
                        // executes.
                        if (Thread.currentThread() == submitterThread) {
                          LOG.log(
                              WARNING,
                              "Executor saturated (CallerRuns). Shedding load for session: "
                                  + sessionKey);
                          closeSession(sessionKey, channel, state);
                          drainSession(sessionKey, state, true);
                          return;
                        }

                        drainSession(sessionKey, state, false);
                      });
                } catch (RejectedExecutionException e) {
                  // Defeat AbortPolicy: If the executor throws an exception instead.
                  LOG.log(
                      WARNING,
                      "Executor saturated (Rejected). Shedding load for session: " + sessionKey);
                  closeSession(sessionKey, channel, state);
                  drainSession(sessionKey, state, true);
                }
              }
              exchange.setRouteStop(true);
            });

    // Send event to each interested subscriber
    from(PUBLISH_QUEUE)
        .routeId("ClientPublishToSubscribers")
        .routeConfigurationId(ATTRIBUTE_EVENT_ROUTE_CONFIG_ID)
        .threads()
        .executorService(executorService)
        .filter(body().isInstanceOf(Event.class))
        .process(
            exchange -> {
              Event event = exchange.getIn().getBody(Event.class);
              sendToSubscribers(event);
            });
  }

  /**
   * Drains the session queue and processes incoming frames, optionally only processing terminal
   * events in the event the session is forcefully closed
   */
  protected void drainSession(String sessionKey, SessionDispatchState state, boolean terminalOnly) {
    while (true) {
      Exchange exchange;

      // Safely pull the next frame or exit if empty
      synchronized (state) {
        exchange = state.queue.poll();
        if (exchange == null) {
          state.draining = false;

          if (state.closed) {
            sessionStates.remove(sessionKey, state);
          }
          return;
        }
      }

      try {
        UndertowConstants.EventType eventType =
            exchange
                .getIn()
                .getHeader(UndertowConstants.EVENT_TYPE_ENUM, UndertowConstants.EventType.class);
        boolean isTerminalEvent =
            (eventType == UndertowConstants.EventType.ONCLOSE
                || eventType == UndertowConstants.EventType.ONERROR);

        if (terminalOnly && !isTerminalEvent) {
          continue;
        }

        if (isTerminalEvent) {
          synchronized (state) {
            state.closed = true;
            state.queue.clear(); // Drop any pending data frames that arrived later
          }
        }

        processInbound(exchange);

      } catch (Exception e) {
        LOG.log(WARNING, "Error processing websocket frame for session " + sessionKey, e);
      }
    }
  }

  /** Force close the session but don't clear the queue (that will be done during processing) */
  protected void closeSession(
      String sessionKey, WebSocketChannel channel, SessionDispatchState state) {
    synchronized (state) {
      state.closed = true;
    }
    closeWebsocketChannel(sessionKey, channel);
  }

  /** Handles all inbound websocket exchanges */
  protected void processInbound(Exchange exchange) {
    UndertowConstants.EventType eventType =
        exchange
            .getIn()
            .getHeader(UndertowConstants.EVENT_TYPE_ENUM, UndertowConstants.EventType.class);

    if (eventType != null) {
      processWebsocketConnectionEvent(exchange, eventType);
    } else {
      Object body = null;

      // Do basic formatting of exchange
      if (exchange.getIn().getBody() instanceof String bodyStr) {
        if (bodyStr.startsWith(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX)) {
          body = exchange.getIn().getBody(EventSubscription.class);
        } else if (bodyStr.startsWith(CancelEventSubscription.MESSAGE_PREFIX)) {
          body = exchange.getIn().getBody(CancelEventSubscription.class);
        } else if (bodyStr.startsWith(SharedEvent.MESSAGE_PREFIX)) {
          body = exchange.getIn().getBody(SharedEvent.class);

          if (body instanceof RespondableEvent respondableEvent) {
            // Inject a response consumer
            respondableEvent.setResponseConsumer(
                ev -> sendToWebsocketSession(getSessionKey(exchange), ev));
          }
        }
        exchange.getIn().setBody(body);
      }

      procesInboundEvent(exchange);
    }
  }

  /** Handles the lifecycle of a websocket connection */
  void processWebsocketConnectionEvent(Exchange exchange, UndertowConstants.EventType eventType) {
    WebSocketChannel webSocketChannel =
        exchange.getIn().getHeader(UndertowConstants.CHANNEL, WebSocketChannel.class);
    switch (eventType) {
      case ONOPEN -> {
        WebSocketHttpExchange httpExchange =
            exchange.getIn().getHeader(UndertowConstants.EXCHANGE, WebSocketHttpExchange.class);
        String realm = getHeaderThenQueryParam(httpExchange, REALM_PARAM_NAME, REALM_PARAM_NAME);
        Principal principal = null;
        AuthContext authContext = null;
        String authorization = getHeaderThenQueryParam(httpExchange, AUTHORIZATION, AUTHORIZATION);

        if (!TextUtil.isNullOrEmpty(authorization)) {
          authorization = RequestParams.getBearerAuth(authorization);
          try {
            principal = identityService.verify(realm, authorization);
          } catch (AuthenticationException e) {
            LOG.log(
                INFO,
                "Failed to verify client identity '"
                    + e.getMessage()
                    + "': "
                    + webSocketChannel.getSourceAddress());
          }

          if (principal instanceof AuthContext ac) {
            LOG.log(
                DEBUG,
                "Client identity verified '"
                    + ((TokenPrincipal) principal).getUsername()
                    + "': "
                    + webSocketChannel.getSourceAddress());
            authContext = ac;
          }
        }

        // Set an idle timeout value for service account connections; browsers don't reliably
        // support
        // ping/pong frames so we can't be too aggressive with those connections but other clients
        // should implement ping/pong
        if (authContext != null
            && authContext.getUsername().startsWith(User.SERVICE_ACCOUNT_PREFIX)) {
          webSocketChannel.setIdleTimeout(30000);
        }

        // Push auth and realm into channel for future use
        webSocketChannel.setAttribute(AUTH_CONTEXT, authContext);
        webSocketChannel.setAttribute(REALM_PARAM_NAME, realm);

        exchange.getIn().setHeader(AUTH_CONTEXT, authContext);
        exchange.getIn().setHeader(REALM_PARAM_NAME, realm);
        exchange.getIn().setHeader(SESSION_OPEN, true);
        sessionChannels.put(getSessionKey(exchange), webSocketChannel);
        LOG.log(DEBUG, "Client connection created: " + webSocketChannel.getSourceAddress());
      }
      case ONCLOSE, ONERROR -> {
        AuthContext authContext = (AuthContext) webSocketChannel.getAttribute(AUTH_CONTEXT);
        String realm = (String) webSocketChannel.getAttribute(REALM_PARAM_NAME);
        String sessionKey = getSessionKey(exchange);

        exchange.getIn().setHeader(AUTH_CONTEXT, authContext);
        exchange.getIn().setHeader(REALM_PARAM_NAME, realm);

        if (eventType == UndertowConstants.EventType.ONCLOSE) {
          LOG.log(DEBUG, "Client connection closed: " + webSocketChannel.getSourceAddress());
          exchange.getIn().setHeader(SESSION_CLOSE, true);
        } else {
          LOG.log(DEBUG, "Client connection error: " + webSocketChannel.getSourceAddress());
          closeWebsocketChannel(sessionKey, webSocketChannel);
          exchange.getIn().setHeader(SESSION_CLOSE_ERROR, true);
        }

        LOG.log(TRACE, "Removing subscriptions for session: " + sessionKey);
        sessionChannels.remove(getSessionKey(exchange));
        websocketSessionSubscriptionConsumers.computeIfPresent(
            sessionKey,
            (s, subscriptionConsumers) -> {
              subscriptionConsumers.forEach(
                  (subscriptionKey, consumer) -> removeSubscription(consumer));
              return null;
            });
      }
    }

    // Pass to gateway interceptor
    if (gatewayInterceptor != null) {
      gatewayInterceptor.accept(exchange);
    }
  }

  /** Handles all inbound events from the websocket clients */
  @SuppressWarnings({"unchecked", "rawtypes"})
  void procesInboundEvent(Exchange exchange) {
    WebSocketChannel webSocketChannel =
        exchange.getIn().getHeader(UndertowConstants.CHANNEL, WebSocketChannel.class);
    AuthContext authContext = exchange.getIn().getHeader(AUTH_CONTEXT, AuthContext.class);
    String realm = getRealm(exchange);

    if (webSocketChannel != null) {
      if (authContext == null) {
        authContext = (AuthContext) webSocketChannel.getAttribute(AUTH_CONTEXT);
      }
      if (realm == null) {
        realm = (String) webSocketChannel.getAttribute(REALM_PARAM_NAME);
      }
    }

    exchange.getIn().setHeader(AUTH_CONTEXT, authContext);
    exchange.getIn().setHeader(REALM_PARAM_NAME, realm);

    // Pass to gateway interceptor and abort if it stops the exchange
    if (gatewayInterceptor != null) {
      gatewayInterceptor.accept(exchange);
      if (exchange.isRouteStop()) {
        return;
      }
    }

    if (exchange.getIn().getBody() instanceof EventSubscription<?> subscription) {
      String sessionKey = getSessionKey(exchange);

      if (!sessionChannels.containsKey(sessionKey)) {
        LOG.log(
            TRACE,
            () -> "Ignoring subscription for closed session '" + sessionKey + "': " + subscription);
        exchange.setRouteStop(true);
        return;
      }

      LOG.log(TRACE, () -> "Adding subscription for session '" + sessionKey + "': " + subscription);

      if (!authorizeEventSubscription(realm, authContext, subscription)) {
        sendToWebsocketSession(sessionKey, new UnauthorizedEventSubscription<>(subscription));
        exchange.setRouteStop(true);
        return;
      }

      if (subscription.getEventType().contains(SUBSCRIPTION_KEY_DELIMITER)
          || subscription.getSubscriptionId().contains(SUBSCRIPTION_KEY_DELIMITER)) {
        sendToWebsocketSession(sessionKey, new UnauthorizedEventSubscription<>(subscription));
        exchange.setRouteStop(true);
        return;
      }

      // Force subscription to filter only value changed attribute events
      if (subscription.getFilter() instanceof AssetFilter assetFilter) {
        subscription.setFilter(assetFilter.setValueChanged(true));
      }

      // Notify the client that the subscription has been created
      subscription.setSubscribed(true);
      sendToWebsocketSession(sessionKey, subscription);

      // Create subscription consumer and track it for future removal requests
      Consumer<? extends SharedEvent> consumer =
          ev -> onWebsocketSubscriptionTriggered(sessionKey, subscription, ev);
      websocketSessionSubscriptionConsumers.compute(
          sessionKey,
          (s, consumers) -> {
            if (consumers == null) {
              consumers = new HashMap<>();
            }

            String subscriptionKey =
                subscription.getEventType()
                    + SUBSCRIPTION_KEY_DELIMITER
                    + subscription.getSubscriptionId();
            consumers.put(subscriptionKey, consumer);
            addSubscription(subscription, consumer);
            return consumers;
          });

    } else if (exchange.getIn().getBody()
        instanceof CancelEventSubscription cancelEventSubscription) {
      String sessionKey = getSessionKey(exchange);
      LOG.log(
          TRACE,
          () ->
              "Canceling subscription for session '"
                  + sessionKey
                  + "': "
                  + cancelEventSubscription);
      websocketSessionSubscriptionConsumers.computeIfPresent(
          sessionKey,
          (s, subscriptionConsumers) -> {
            Predicate<String> predicate = null;
            if (!cancelEventSubscription.getEventType().isEmpty()
                && !cancelEventSubscription.getSubscriptionId().isEmpty()) {
              predicate =
                  key ->
                      key.equals(
                          cancelEventSubscription.getEventType()
                              + SUBSCRIPTION_KEY_DELIMITER
                              + cancelEventSubscription.getSubscriptionId());
            } else {
              if (!cancelEventSubscription.getSubscriptionId().isEmpty()) {
                predicate =
                    key ->
                        key.endsWith(
                            SUBSCRIPTION_KEY_DELIMITER
                                + cancelEventSubscription.getSubscriptionId());
              } else if (!cancelEventSubscription.getEventType().isEmpty()) {
                predicate =
                    key ->
                        key.startsWith(
                            cancelEventSubscription.getEventType() + SUBSCRIPTION_KEY_DELIMITER);
              }
            }
            if (predicate != null) {
              final Predicate<String> finalPredicate = predicate;
              subscriptionConsumers
                  .keySet()
                  .removeIf(
                      key -> {
                        if (finalPredicate.test(key)) {
                          removeSubscription(subscriptionConsumers.get(key));
                          return true;
                        }
                        return false;
                      });
            }
            if (subscriptionConsumers.isEmpty()) {
              return null;
            }
            return subscriptionConsumers;
          });
    } else if (exchange.getIn().getBody() instanceof SharedEvent event) {
      if (!authorizeEventWrite(realm, authContext, event)) {
        return;
      }

      // Special handling for incoming attribute events
      if (event instanceof AttributeEvent attributeEvent) {
        // Set timestamp as early as possible if not set
        if (attributeEvent.getTimestamp() <= 0) {
          attributeEvent.setTimestamp(timerService.getCurrentTimeMillis());
        }
        attributeEvent.setSource("WebsocketClient");

        messageBrokerService
            .getFluentProducerTemplate()
            .withBody(attributeEvent)
            .to(ATTRIBUTE_EVENT_PROCESSOR)
            .asyncSend();
      } else {
        // Asynchronously hand off just the event payload to the queue
        messageBrokerService
            .getFluentProducerTemplate()
            .withBody(event)
            .to(PUBLISH_QUEUE)
            .asyncSend();
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected <T extends Event> void sendToSubscribers(T event) {
    eventSubscriptions.forEach(
        eventSubscriptionConsumerPair -> {
          EventSubscription<?> subscription = eventSubscriptionConsumerPair.getKey();

          if (!subscription.getEventType().equals(event.getEventType())) {
            return;
          }

          T filteredEvent =
              subscription.getFilter() == null
                  ? event
                  : ((EventSubscription<T>) subscription).getFilter().apply(event);

          if (filteredEvent == null) {
            return;
          }

          Consumer<T> consumer = (Consumer<T>) eventSubscriptionConsumerPair.getValue();
          try {
            consumer.accept(filteredEvent);
          } catch (Exception e) {
            LOG.log(WARNING, "Event subscriber has thrown an exception: " + consumer, e);
          }
        });
  }

  /**
   * Authorization must be done before adding the subscription and is the responsibility of
   * subscription creators.
   */
  public void addSubscription(
      EventSubscription<? extends Event> eventSubscription, Consumer<? extends Event> consumer)
      throws IllegalStateException {
    eventSubscriptions.add(new Pair<>(eventSubscription, consumer));
  }

  public <T extends Event> void addSubscription(Class<T> eventClass, Consumer<T> consumer)
      throws IllegalStateException {
    addSubscription(new EventSubscription<>(eventClass, null), consumer);
  }

  public <T extends Event> void addSubscription(
      Class<T> eventClass, EventFilter<T> filter, Consumer<T> consumer)
      throws IllegalStateException {
    addSubscription(new EventSubscription<>(eventClass, filter), consumer);
  }

  public void removeSubscription(Consumer<? extends Event> consumer) {
    eventSubscriptions.removeIf(
        subscriptionConsumerPair -> subscriptionConsumerPair.value == consumer);
  }

  @Override
  public void start(Container container) {
    started = true;
  }

  @Override
  public void stop(Container container) {
    started = false;
  }

  public void addSubscriptionAuthorizer(EventSubscriptionAuthorizer authorizer) {
    this.eventSubscriptionAuthorizers.add(authorizer);
  }

  public void addEventAuthorizer(EventAuthorizer authorizer) {
    this.eventAuthorizers.add(authorizer);
  }

  /**
   * This handles basic authorization checks for clients that want to subscribe to events in the
   * system
   */
  public boolean authorizeEventSubscription(
      String realm, AuthContext authContext, EventSubscription<?> subscription) {
    boolean authorized =
        eventSubscriptionAuthorizers.stream()
            .anyMatch(authorizer -> authorizer.authorise(realm, authContext, subscription));

    if (!authorized) {
      if (authContext != null) {
        LOG.log(
            DEBUG,
            "Client not authorized to subscribe: subscription="
                + subscription
                + ", requestRealm="
                + realm
                + ", username="
                + authContext.getUsername()
                + ", userRealm="
                + authContext.getAuthenticatedRealmName());
      } else {
        LOG.log(
            DEBUG,
            "Client not authorized to subscribe: subscription="
                + subscription
                + ", requestRealm="
                + realm
                + ", user=null");
      }
    }

    return authorized;
  }

  /**
   * This handles basic authorization checks for clients that want to write an event to the system;
   * this gets hit a lot so should be as performant as possible
   */
  // TODO: Implement auth cache in OR that covers HTTP, WS and MQTT (ActiveMQ Authorization cache
  // currently covers MQTT which is the main use case)
  public <T extends SharedEvent> boolean authorizeEventWrite(
      String realm, AuthContext authContext, T event) {
    boolean authorized =
        eventAuthorizers.stream()
            .anyMatch(authorizer -> authorizer.authorise(realm, authContext, event));

    if (!authorized) {
      if (authContext != null) {
        LOG.log(
            DEBUG,
            () ->
                "Client not authorized to send event: type="
                    + event.getEventType()
                    + ", requestRealm="
                    + realm
                    + ", user="
                    + authContext.getUsername()
                    + ", userRealm="
                    + authContext.getAuthenticatedRealmName());
      } else {
        LOG.log(
            DEBUG,
            () ->
                "Client not authorized to send event: type="
                    + event.getEventType()
                    + ", requestRealm="
                    + realm
                    + ", user=null");
      }
    }

    return authorized;
  }

  /** Publish an event to interested subscribers */
  public <T extends Event> void publishEvent(T event) {
    // Only publish if service is started and MessageBrokerService is started (route needs to be
    // started)
    if (!started || !messageBrokerService.isStarted()) {
      return;
    }

    if (!(event instanceof SyslogEvent)) {
      LOG.log(System.Logger.Level.TRACE, () -> "Publishing to subscribers: " + event);
    }

    messageBrokerService.getFluentProducerTemplate().withBody(event).to(PUBLISH_QUEUE).asyncSend();
  }

  /**
   * This allows gateway connectors to intercept exchanges from gateway clients; it by-passes the
   * standard processing including authorization so the interceptor provides its own authorization
   * checks.
   */
  public void setGatewayInterceptor(Consumer<Exchange> consumer) {
    this.gatewayInterceptor = consumer;
  }

  protected void onWebsocketSubscriptionTriggered(
      String sessionKey, EventSubscription<?> subscription, SharedEvent event) {
    // Wrap subscription event in triggered wrapper for client to easily route it
    TriggeredEventSubscription<?> triggeredEventSubscription =
        new TriggeredEventSubscription<>(
            Collections.singletonList(event), subscription.getSubscriptionId());

    messageBrokerService
        .getFluentProducerTemplate()
        .withBody(triggeredEventSubscription)
        .withHeader(UndertowConstants.CONNECTION_KEY, sessionKey)
        .to(WEBSOCKET_URI)
        .asyncSend();
  }

  public void sendToWebsocketSession(String sessionKey, Object data) {
    messageBrokerService
        .getFluentProducerTemplate()
        .withBody(data)
        .withHeader(UndertowConstants.CONNECTION_KEY, sessionKey)
        .to(WEBSOCKET_URI)
        .asyncSend();
  }

  public void closeWebsocketSession(String sessionKey) {
    closeWebsocketChannel(sessionKey, sessionChannels.get(sessionKey));
  }

  protected void closeWebsocketChannel(String sessionKey, WebSocketChannel webSocketChannel) {
    if (webSocketChannel != null) {
      LOG.log(INFO, () -> "Force closing websocket session: " + sessionKey);
      try {
        webSocketChannel.close();
      } catch (IOException e) {
        LOG.log(DEBUG, () -> "Failed to close websocket session: " + sessionKey);
      }
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" + '}';
  }

  protected static String getHeaderThenQueryParam(
      WebSocketHttpExchange exchange, String headerName, String queryParamName) {
    if (exchange == null) {
      return null;
    }

    String headerValue = exchange.getRequestHeader(headerName);
    if (headerValue != null && !headerValue.isBlank()) {
      return headerValue;
    }

    return getFirstQueryParam(exchange.getQueryString(), queryParamName);
  }

  protected static String getFirstQueryParam(String queryString, String name) {
    if (queryString == null || queryString.isBlank() || name == null || name.isBlank()) {
      return null;
    }

    // Undertow's queryString is typically the raw part after '?', e.g. "a=1&b=2"
    String qs = queryString.startsWith("?") ? queryString.substring(1) : queryString;

    for (String part : qs.split("&")) {
      if (part.isEmpty()) continue;

      int idx = part.indexOf('=');
      String rawKey = idx >= 0 ? part.substring(0, idx) : part;
      String rawVal = idx >= 0 ? part.substring(idx + 1) : "";

      String key = urlDecode(rawKey);
      if (!name.equals(key)) {
        continue;
      }

      String val = urlDecode(rawVal);
      return (val == null || val.isBlank()) ? null : val;
    }

    return null;
  }

  protected static String urlDecode(String s) {
    if (s == null) return null;
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }
}
