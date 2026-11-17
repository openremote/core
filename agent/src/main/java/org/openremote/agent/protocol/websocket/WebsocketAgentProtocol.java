/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.agent.protocol.websocket;

import static org.openremote.agent.protocol.http.HTTPProtocol.DEFAULT_CONTENT_TYPE;
import static org.openremote.agent.protocol.http.HTTPProtocol.DEFAULT_HTTP_METHOD;
import static org.openremote.container.web.WebTargetBuilder.getClient;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import io.netty.channel.ChannelHandler;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.apache.http.HttpHeaders;
import org.jboss.resteasy.util.BasicAuthHelper;
import org.openremote.agent.protocol.io.AbstractNettyIOClientProtocol;
import org.openremote.container.web.WebService;
import org.openremote.container.web.WebTargetBuilder;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.protocol.ProtocolUtil;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.ValueType;

/**
 * This is a generic {@link org.openremote.model.asset.agent.Protocol} for communicating with a
 * Websocket server using {@link String} based messages.
 *
 * <h2>Protocol Specifics</h2>
 *
 * When the websocket connection is established it is possible to subscribe to events by specifying
 * the {@link WebsocketAgent#CONNECT_SUBSCRIPTIONS} on the {@link WebsocketAgent} or {@link
 * WebsocketAgentLink#getWebsocketSubscriptions()} on linked {@link Attribute}s; a subscription can
 * be a message sent over the websocket or a HTTP REST API call.
 */
public class WebsocketAgentProtocol
    extends AbstractNettyIOClientProtocol<
        WebsocketAgentProtocol,
        WebsocketAgent,
        String,
        WebsocketIOClient<String>,
        WebsocketAgentLink> {

  public static final String PROTOCOL_DISPLAY_NAME = "Websocket Client";
  private static final Logger LOG =
      SyslogCategory.getLogger(PROTOCOL, WebsocketAgentProtocol.class);
  public static final int CONNECTED_SEND_DELAY_MILLIS = 2000;
  protected List<Runnable> protocolConnectedTasks;
  protected Map<AttributeRef, Runnable> attributeConnectedTasks;
  protected Map<String, List<String>> clientHeaders;
  protected final List<Pair<AttributeRef, Consumer<String>>> protocolMessageConsumers =
      new ArrayList<>();
  protected WebService webService;

  public WebsocketAgentProtocol(WebsocketAgent agent) {
    super(agent);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  protected void doStart(Container container) throws Exception {
    super.doStart(container);
    this.webService = container.getService(WebService.class);
  }

  @Override
  protected void doStop(Container container) throws Exception {
    super.doStop(container);
    clientHeaders = null;
    protocolConnectedTasks = null;
    attributeConnectedTasks = null;
    protocolMessageConsumers.clear();
  }

  @Override
  protected Supplier<ChannelHandler[]> getEncoderDecoderProvider() {
    return getGenericStringEncodersAndDecoders(client, agent);
  }

  @Override
  protected void onMessageReceived(String message) {
    protocolMessageConsumers.forEach(
        c -> {
          if (c.value != null) {
            c.value.accept(message);
          }
        });
  }

  @Override
  protected String createWriteMessage(
      WebsocketAgentLink agentLink, AttributeEvent event, Object processedValue) {
    return ValueUtil.convert(processedValue, String.class);
  }

  @Override
  protected WebsocketIOClient<String> doCreateIoClient() throws Exception {

    String uriStr =
        agent
            .getConnectUri()
            .orElseThrow(
                () -> new IllegalArgumentException("Missing or invalid connectUri: " + agent));

    URI uri = new URI(uriStr);

    /* We're going to fail hard and fast if optional meta items are incorrectly configured */

    Optional<OAuthGrant> oAuthGrant = agent.getOAuthGrant();
    Optional<UsernamePassword> usernameAndPassword = agent.getUsernamePassword();
    Optional<ValueType.MultivaluedStringMap> headers = agent.getConnectHeaders();
    Optional<WebsocketSubscription[]> subscriptions = agent.getConnectSubscriptions();

    if (oAuthGrant.isEmpty() && usernameAndPassword.isPresent()) {
      String authValue =
          BasicAuthHelper.createHeader(
              usernameAndPassword.get().getUsername(), usernameAndPassword.get().getPassword());
      headers =
          Optional.of(
              headers
                  .map(
                      h -> {
                        h.remove(HttpHeaders.AUTHORIZATION);
                        h.replace(HttpHeaders.AUTHORIZATION, Collections.singletonList(authValue));
                        return h;
                      })
                  .orElseGet(
                      () -> {
                        ValueType.MultivaluedStringMap h = new ValueType.MultivaluedStringMap();
                        h.put(HttpHeaders.AUTHORIZATION, Collections.singletonList(authValue));
                        return h;
                      }));
    }

    clientHeaders = headers.orElse(null);
    WebsocketIOClient<String> websocketClient =
        new WebsocketIOClient<>(uri, headers.orElse(null), oAuthGrant.orElse(null));
    Map<String, List<String>> finalHeaders = headers.orElse(null);

    subscriptions.ifPresent(
        websocketSubscriptions ->
            addProtocolConnectedTask(() -> doSubscriptions(finalHeaders, websocketSubscriptions)));

    return websocketClient;
  }

  @Override
  protected void setConnectionStatus(ConnectionStatus connectionStatus) {
    super.setConnectionStatus(connectionStatus);
    if (connectionStatus == ConnectionStatus.CONNECTED) {
      onConnected();
    }
  }

  @Override
  protected void doLinkAttribute(
      String assetId, Attribute<?> attribute, WebsocketAgentLink agentLink) {
    Optional<WebsocketSubscription[]> subscriptions = agentLink.getWebsocketSubscriptions();
    AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

    subscriptions.ifPresent(
        websocketSubscriptions -> {
          Runnable task = () -> doSubscriptions(clientHeaders, websocketSubscriptions);
          addAttributeConnectedTask(attributeRef, task);
          if (client.getConnectionStatus() == ConnectionStatus.CONNECTED) {
            scheduledExecutorService.schedule(task, 1000, TimeUnit.MILLISECONDS);
          }
        });

    Consumer<String> messageConsumer =
        ProtocolUtil.createGenericAttributeMessageConsumer(
            assetId,
            attribute,
            agent.getAgentLink(attribute),
            timerService::getCurrentTimeMillis,
            this::updateLinkedAttribute);

    if (messageConsumer != null) {
      protocolMessageConsumers.add(new Pair<>(attributeRef, messageConsumer));
    }
  }

  @Override
  protected void doUnlinkAttribute(
      String assetId, Attribute<?> attribute, WebsocketAgentLink agentLink) {
    AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
    protocolMessageConsumers.removeIf(
        (attrRefConsumer) -> attrRefConsumer.key.equals(attributeRef));
    attributeConnectedTasks.remove(attributeRef);
  }

  protected void onConnected() {
    // Look for any subscriptions that need to be processed
    if (protocolConnectedTasks != null) {
      // Execute after a delay to ensure connection is properly initialized
      scheduledExecutorService.schedule(
          () -> protocolConnectedTasks.forEach(Runnable::run),
          CONNECTED_SEND_DELAY_MILLIS,
          TimeUnit.MILLISECONDS);
    }

    if (attributeConnectedTasks != null) {
      // Execute after a delay to ensure connection is properly initialized
      scheduledExecutorService.schedule(
          () -> attributeConnectedTasks.forEach((ref, task) -> task.run()),
          CONNECTED_SEND_DELAY_MILLIS,
          TimeUnit.MILLISECONDS);
    }
  }

  protected void addProtocolConnectedTask(Runnable task) {
    if (protocolConnectedTasks == null) {
      protocolConnectedTasks = new ArrayList<>();
    }
    protocolConnectedTasks.add(task);
  }

  protected void addAttributeConnectedTask(AttributeRef attributeRef, Runnable task) {
    if (attributeConnectedTasks == null) {
      attributeConnectedTasks = new HashMap<>();
    }

    attributeConnectedTasks.put(attributeRef, task);
  }

  protected void doSubscriptions(
      Map<String, List<String>> headers, WebsocketSubscription[] subscriptions) {
    LOG.info("Executing subscriptions for websocket: " + client.getClientUri());

    try {
      // Inject OAuth header
      String authHeaderValue = client.getAuthHeader().get(10000, TimeUnit.MILLISECONDS);
      if (authHeaderValue != null) {
        if (headers == null) {
          headers = new MultivaluedHashMap<>();
        }
        headers.put(HttpHeaders.AUTHORIZATION, Collections.singletonList(authHeaderValue));
      }

      Map<String, List<String>> finalHeaders = headers;
      Arrays.stream(subscriptions)
          .forEach(subscription -> doSubscription(finalHeaders, subscription));
    } catch (Exception e) {
      LOG.info("An exception occurred executing subscriptions: " + e.getMessage());
    }
  }

  protected void doSubscription(
      Map<String, List<String>> headers, WebsocketSubscription subscription) {
    if (subscription instanceof WebsocketHTTPSubscription httpSubscription) {

      if (TextUtil.isNullOrEmpty(httpSubscription.uri)) {
        LOG.warning("Websocket subscription missing or empty URI so skipping: " + subscription);
        return;
      }

      URI uri;

      try {
        uri = new URI(httpSubscription.uri);
      } catch (URISyntaxException e) {
        LOG.warning("Websocket subscription invalid URI so skipping: " + subscription);
        return;
      }

      if (httpSubscription.method == null) {
        httpSubscription.method = WebsocketHTTPSubscription.Method.valueOf(DEFAULT_HTTP_METHOD);
      }

      if (TextUtil.isNullOrEmpty(httpSubscription.contentType)) {
        httpSubscription.contentType = DEFAULT_CONTENT_TYPE;
      }

      if (httpSubscription.headers != null) {
        headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        Map<String, List<String>> finalHeaders = headers;
        httpSubscription.headers.forEach(
            (header, values) -> {
              if (values == null || values.isEmpty()) {
                finalHeaders.remove(header);
              } else {
                List<String> vals =
                    new ArrayList<>(
                        finalHeaders.compute(
                            header, (h, l) -> l != null ? l : Collections.emptyList()));
                vals.addAll(values);
                finalHeaders.put(header, vals);
              }
            });
      }

      WebTargetBuilder webTargetBuilder = new WebTargetBuilder(getClient(), uri);

      LOG.fine("Creating web target client for subscription '" + uri + "'");
      Invocation.Builder request = webTargetBuilder.build().request();
      Invocation invocation;

      if (headers != null) {
        request = WebTargetBuilder.addHeaders(request, headers);
      }

      if (httpSubscription.body == null) {
        invocation = request.build(httpSubscription.method.toString());
      } else {
        invocation =
            request.build(
                httpSubscription.method.toString(),
                Entity.entity(httpSubscription.body, httpSubscription.contentType));
      }
      Response response = invocation.invoke();
      response.close();
      if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        LOG.warning(
            "WebsocketHttpSubscription returned an un-successful response code: "
                + response.getStatus());
      }
    } else {
      client.sendMessage(ValueUtil.convert(subscription.body, String.class));
    }
  }
}
