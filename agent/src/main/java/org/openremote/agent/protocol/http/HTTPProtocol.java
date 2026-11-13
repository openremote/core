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
package org.openremote.agent.protocol.http;

import static org.openremote.container.web.WebTargetBuilder.addHeaders;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.client.utils.URIBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.specimpl.ResponseBuilderImpl;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.DynamicTimeInjectorFilter;
import org.openremote.container.web.DynamicValueInjectorFilter;
import org.openremote.container.web.WebTargetBuilder;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.AgentLink;
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

/**
 * This is a HTTP client protocol for communicating with HTTP servers; it uses the {@link
 * WebTargetBuilder} factory to generate JAX-RS {@link jakarta.ws.rs.client.WebTarget}s that can be
 * used to make arbitrary calls to endpoints on a HTTP server but it can also be extended and used
 * as a JAX-RS client proxy.
 *
 * <h2>Response filtering</h2>
 *
 * <p>Any {@link Attribute} whose value is to be set by the HTTP server response (i.e. it has an
 * {@link HTTPAgentLink#getPollingMillis()} value can use the standard {@link
 * AgentLink#getValueFilters()} in order to filter the received HTTP response.
 *
 * <p><b>NOTE: if an exception is thrown during the request that means no response is returned then
 * this is treated as if a 500 response has been received</b>
 *
 * <h2>Dynamic placeholder injection</h2>
 *
 * This allows the path, query params, headers and/or {@link AgentLink#getWriteValue()} to contain
 * the linked {@link Attribute} value when sending requests.
 *
 * <h3>Path example</h3>
 *
 * {@link HTTPAgentLink#getPath()} = "volume/set/%VALUE%" and request received to set attribute
 * value to 100. Actual path used for the request = "volume/set/100"
 *
 * <h3>Query parameter example</h3>
 *
 * {@link HTTPAgentLink#getQueryParameters()} =
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 *     param1: ["val1", "val2"],
 *     param2: 12232,
 *     param3: "%TIME%"
 * }
 * }</pre>
 *
 * </blockquote>
 *
 * Request received to set attribute value to true. Actual query parameters injected into the
 * request = "param1=val1&amp;param1=val2&amp;param2=12232&amp;param3=true"
 *
 * <h3>Body examples</h3>
 *
 * {@link AgentLink#getWriteValue()} = &lt;?xml version="1.0"
 * encoding="UTF-8"?&gt;%VALUE%&lt;/xml&gt; and request received to set attribute value to 100.
 * Actual body used for the request = "{volume: 100}"
 *
 * <p>{@link AgentLink#getWriteValue()} = '{myObject: "%VALUE%"}' and request received to set
 * attribute value to:
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 *   prop1: true,
 *   prop2: "test",
 *   prop3: {
 *       prop4: 1234.4223
 *   }
 * }
 * }</pre>
 *
 * </blockquote>
 *
 * Actual body used for the request = "{myObject: {prop1: true, prop2: "test", prop3: {prop4:
 * 1234.4223}}}"
 */
public class HTTPProtocol extends AbstractProtocol<HTTPAgent, HTTPAgentLink> {

  public static class HttpClientRequest {

    public String method;
    public MultivaluedMap<String, ?> headers;
    public MultivaluedMap<String, ?> queryParameters;
    public String path;
    protected String contentType;
    protected WebTarget client;
    protected WebTarget requestTarget;
    protected boolean containsDynamicValue;
    protected boolean pagingEnabled;
    protected boolean containsDynamicTime;
    private final Supplier<Instant> instantSupplier;

    public HttpClientRequest(
        WebTarget client,
        String path,
        String method,
        MultivaluedMap<String, ?> headers,
        MultivaluedMap<String, ?> queryParameters,
        boolean pagingEnabled,
        String contentType,
        Supplier<Instant> instantSupplier) {

      if (!TextUtil.isNullOrEmpty(path)) {
        if (path.startsWith("/")) {
          path = path.substring(1);
        }
      }

      this.client = client;
      this.path = path;
      this.method = method != null ? method : HttpMethod.GET;
      this.headers = headers;
      this.queryParameters = queryParameters;
      this.pagingEnabled = pagingEnabled;
      this.contentType = contentType != null ? contentType : DEFAULT_CONTENT_TYPE;
      this.instantSupplier = instantSupplier;

      // Check if this request contains any dynamic value references in headers, query params or
      // path
      Predicate<Object> containsValue =
          e -> e instanceof String str && Constants.containsDynamicValuePlaceholder(str);
      containsDynamicValue =
          (queryParameters != null
                  && queryParameters.values().stream()
                      .anyMatch(values -> values.stream().anyMatch(containsValue)))
              || (headers != null
                  && headers.values().stream()
                      .anyMatch(values -> values.stream().anyMatch(containsValue)))
              || (path != null && containsValue.test(path));

      // Check if this HTTP request contains any references of dynamic time. We do so by checking
      // query parameters
      // and headers, using the predicate below.
      Predicate<Object> containsTime =
          e -> e instanceof String str && Constants.containsDynamicTimePlaceholder(str);
      containsDynamicTime =
          (queryParameters != null
                  && queryParameters.values().stream()
                      .anyMatch(values -> values.stream().anyMatch(containsTime)))
              || (headers != null
                  && headers.values().stream()
                      .anyMatch(values -> values.stream().anyMatch(containsTime)))
              || (path != null && containsTime.test(path));

      requestTarget = createRequestTarget(path);
    }

    protected WebTarget createRequestTarget(String path) {
      WebTarget requestTarget = client.path(path == null ? "" : path);

      if (queryParameters != null) {
        requestTarget = WebTargetBuilder.addQueryParams(requestTarget, queryParameters);
      }

      return requestTarget;
    }

    protected Invocation.Builder getRequestBuilder() {
      Invocation.Builder requestBuilder = requestTarget.request();

      if (headers != null) {
        requestBuilder = addHeaders(requestBuilder, headers);
      }

      return requestBuilder;
    }

    protected Invocation buildInvocation(Invocation.Builder requestBuilder, String value) {
      Invocation invocation;

      if (containsDynamicTime) {
        requestBuilder.property(
            DynamicTimeInjectorFilter.INSTANT_SUPPLIER_PROPERTY, instantSupplier);
      }

      if (containsDynamicValue) {
        requestBuilder.property(DynamicValueInjectorFilter.DYNAMIC_VALUE_PROPERTY, value);
      }

      if (method != null && !HttpMethod.GET.equals(method) && value != null) {
        invocation = requestBuilder.build(method, Entity.entity(value, contentType));
      } else {
        invocation = requestBuilder.build(method);
      }

      return invocation;
    }

    public Response invoke(String value) {
      Invocation.Builder requestBuilder = getRequestBuilder();
      Invocation invocation = buildInvocation(requestBuilder, value);
      return invocation.invoke();
    }

    @Override
    public String toString() {
      return client.getUri() + (path != null ? "/" + path : "");
    }
  }

  protected static class PagingResponse extends BuiltResponse {

    private PagingResponse(
        int status, Headers<Object> metadata, Object entity, Annotation[] entityAnnotations) {
      super(status, metadata, entity, entityAnnotations);
    }

    public static ResponseBuilder fromResponse(Response response) {
      ResponseBuilder b = new PagingResponseBuilder().status(response.getStatus());

      for (String headerName : response.getHeaders().keySet()) {
        List<Object> headerValues = response.getHeaders().get(headerName);
        for (Object headerValue : headerValues) {
          b.header(headerName, headerValue);
        }
      }
      return b;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T readEntity(Class<T> type) {
      return (T) entity;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T readEntity(Class<T> type, Type genericType, Annotation[] anns) {
      return (T) entity;
    }
  }

  protected static class PagingResponseBuilder extends ResponseBuilderImpl {
    @Override
    public Response build() {
      if (status == -1 && entity == null) status = 204;
      else if (status == -1) status = 200;
      return new PagingResponse(status, metadata, entity, entityAnnotations);
    }
  }

  public static final String PROTOCOL_DISPLAY_NAME = "HTTP Client";
  public static final String DEFAULT_HTTP_METHOD = HttpMethod.GET;
  public static final String DEFAULT_CONTENT_TYPE = MediaType.TEXT_PLAIN;
  protected static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, HTTPProtocol.class);
  // Cannot be final due to adjustment needed in tests to speed up tests
  public static int MIN_POLLING_MILLIS = 5000;
  public static final int DEFAULT_READ_TIMEOUT_MILLIS = 10000;

  protected final Map<AttributeRef, HttpClientRequest> requestMap = new HashMap<>();
  protected final Map<AttributeRef, ScheduledFuture<?>> pollingMap = new HashMap<>();
  protected final Map<AttributeRef, Set<AttributeRef>> pollingLinkedAttributeMap = new HashMap<>();
  protected ResteasyClient client;
  protected WebTarget webTarget;

  public HTTPProtocol(HTTPAgent agent) {
    super(agent);
    client = createClient();
  }

  @Override
  protected void doStop(Container container) {
    pollingMap.forEach((attributeRef, scheduledFuture) -> scheduledFuture.cancel(true));
    pollingMap.clear();
    requestMap.clear();

    if (client != null) {
      client.close();
    }
  }

  @Override
  protected void doStart(Container container) throws Exception {

    String baseUri =
        agent
            .getBaseURI()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("Missing or invalid base URI attribute: " + this));

    if (baseUri.endsWith("/")) {
      baseUri = baseUri.substring(0, baseUri.length() - 1);
    }

    URI uri;

    try {
      uri = new URIBuilder(baseUri).build();
    } catch (URISyntaxException e) {
      LOG.log(Level.SEVERE, "Invalid URI", e);
      throw e;
    }

    /* We're going to fail hard and fast if optional meta items are incorrectly configured */

    Optional<OAuthGrant> oAuthGrant = agent.getOAuthGrant();
    Optional<UsernamePassword> usernameAndPassword = agent.getUsernamePassword();
    boolean followRedirects = agent.getFollowRedirects().orElse(false);
    WebTargetBuilder webTargetBuilder = new WebTargetBuilder(client, uri);

    if (oAuthGrant.isPresent()) {
      LOG.info("Adding OAuth");
      webTargetBuilder.setOAuthAuthentication(oAuthGrant.get());
    } else {
      usernameAndPassword.ifPresent(
          userPass -> {
            LOG.info("Adding Basic Authentication");
            webTargetBuilder.setBasicAuthentication(userPass.getUsername(), userPass.getPassword());
          });
    }

    webTargetBuilder.followRedirects(followRedirects);

    LOG.fine("Creating web target client for agent '" + getAgent().getId() + "': " + baseUri);
    webTarget = webTargetBuilder.build();

    setConnectionStatus(ConnectionStatus.CONNECTED);
  }

  @Override
  protected void doLinkAttribute(String assetId, Attribute<?> attribute, HTTPAgentLink agentLink) {
    AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

    String method = agentLink.getMethod().map(Enum::name).orElse(DEFAULT_HTTP_METHOD);
    String path = agentLink.getPath().orElse(null);
    String contentType = agentLink.getContentType().orElse(null);

    Map<String, List<String>> headers = agentLink.getHeaders().orElse(null);
    Map<String, List<String>> queryParams = agentLink.getQueryParameters().orElse(null);
    Integer pollingMillis =
        agentLink
            .getPollingMillis()
            .map(millis -> Math.max(millis, MIN_POLLING_MILLIS))
            .orElse(null);
    boolean pagingEnabled = agentLink.getPagingMode().orElse(false);
    String pollingAttribute = agentLink.getPollingAttribute().orElse(null);

    if (!TextUtil.isNullOrEmpty(pollingAttribute)) {
      synchronized (pollingLinkedAttributeMap) {
        AttributeRef pollingSourceRef = new AttributeRef(attributeRef.getId(), pollingAttribute);
        pollingLinkedAttributeMap.compute(
            pollingSourceRef,
            (ref, links) -> {
              if (links == null) {
                links = new HashSet<>();
              }
              links.add(attributeRef);

              return links;
            });
      }
    }

    // We pass in the combined headers as they can only be set on the invocation builder
    MultivaluedMap<String, String> combinedHeaders;
    Optional<MultivaluedMap<String, String>> agentHeaders =
        agent.getRequestHeaders().map(WebTargetBuilder::mapToMultivaluedMap);
    if (headers != null) {
      combinedHeaders =
          agentHeaders.map(MultivaluedHashMap::new).orElse(new MultivaluedHashMap<>());
      headers.forEach(combinedHeaders::addAll);
    } else combinedHeaders = agentHeaders.orElse(null);

    // We pass in the combined query params so we can determine if the request contains dynamic
    // placeholders
    MultivaluedMap<String, String> combinedQueryParams;
    Optional<MultivaluedMap<String, String>> agentParams =
        agent.getRequestQueryParameters().map(WebTargetBuilder::mapToMultivaluedMap);
    if (queryParams != null) {
      combinedQueryParams =
          agentParams.map(MultivaluedHashMap::new).orElse(new MultivaluedHashMap<>());
      queryParams.forEach(combinedQueryParams::addAll);
    } else combinedQueryParams = agentParams.orElse(null);

    HttpClientRequest clientRequest =
        buildClientRequest(
            path,
            method,
            combinedHeaders,
            combinedQueryParams,
            pagingEnabled,
            contentType,
            timerService);

    LOG.finer("Creating HTTP client request '" + clientRequest + "': " + attributeRef);

    requestMap.put(attributeRef, clientRequest);

    Optional.ofNullable(pollingMillis)
        .ifPresent(
            seconds ->
                pollingMap.put(
                    attributeRef,
                    schedulePollingRequest(
                        attributeRef, attribute, agentLink, clientRequest, seconds)));
  }

  @Override
  protected void doUnlinkAttribute(
      String assetId, Attribute<?> attribute, HTTPAgentLink agentLink) {
    AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
    requestMap.remove(attributeRef);
    cancelPolling(attributeRef);

    agentLink
        .getPollingMillis()
        .ifPresent(
            pollingAttribute -> {
              synchronized (pollingLinkedAttributeMap) {
                pollingLinkedAttributeMap.remove(attributeRef);
                pollingLinkedAttributeMap.values().forEach(links -> links.remove(attributeRef));
              }
            });
  }

  @Override
  protected void doLinkedAttributeWrite(
      HTTPAgentLink agentLink, AttributeEvent event, Object processedValue) {

    HttpClientRequest request = requestMap.get(event.getRef());

    if (request != null) {

      executeAttributeWriteRequest(
          request, processedValue, response -> onAttributeWriteResponse(request, response));
    } else {
      LOG.finest(
          "Ignoring attribute write request as either attribute or agent is not linked: " + event);
    }
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  public String getProtocolInstanceUri() {
    return webTarget != null ? webTarget.getUri().toString() : agent.getBaseURI().orElse("");
  }

  protected HttpClientRequest buildClientRequest(
      String path,
      String method,
      MultivaluedMap<String, ?> headers,
      MultivaluedMap<String, ?> queryParams,
      boolean pagingEnabled,
      String contentType,
      TimerService timerService) {
    return new HttpClientRequest(
        webTarget,
        path,
        method,
        headers,
        queryParams,
        pagingEnabled,
        contentType,
        timerService::getNow);
  }

  protected ScheduledFuture<?> schedulePollingRequest(
      AttributeRef attributeRef,
      Attribute<?> attribute,
      HTTPAgentLink agentLink,
      HttpClientRequest clientRequest,
      int pollingMillis) {

    LOG.fine(
        "Scheduling polling request '"
            + clientRequest
            + "' to execute every "
            + pollingMillis
            + " ms for attribute: "
            + attribute);

    return scheduledExecutorService.scheduleWithFixedDelay(
        () -> {
          try {
            Pair<Boolean, Object> ignoreAndConverted =
                ProtocolUtil.doOutboundValueProcessing(
                    attributeRef,
                    agentLink,
                    agentLink.getWriteValue().orElse(null),
                    dynamicAttributes.contains(attributeRef),
                    timerService.getNow());

            if (ignoreAndConverted.key) {
              LOG.log(
                  Level.FINER,
                  "Value conversion returned ignore so attribute will not write to protocol: "
                      + attributeRef);
              return;
            }

            String valueStr =
                ignoreAndConverted.value == null
                    ? null
                    : ValueUtil.convert(ignoreAndConverted.value, String.class);

            executePollingRequest(
                clientRequest,
                valueStr,
                response -> {
                  try {
                    onPollingResponse(clientRequest, response, attributeRef, agentLink);
                  } catch (Exception e) {
                    LOG.log(
                        Level.WARNING,
                        prefixLogMessage(
                            "Exception thrown while processing polling response ["
                                + (e.getCause() != null
                                    ? e.getCause().getMessage()
                                    : e.getMessage())
                                + "]: "
                                + clientRequest.requestTarget.getUriBuilder().build().toString()));
                  }
                });
          } catch (Exception e) {
            LOG.log(
                Level.WARNING,
                prefixLogMessage(
                    "Exception thrown while processing polling response ["
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                        + "]: "
                        + clientRequest.requestTarget.getUriBuilder().build().toString()));
          }
        },
        0,
        pollingMillis,
        TimeUnit.MILLISECONDS);
  }

  protected void executePollingRequest(
      HttpClientRequest clientRequest, String body, Consumer<Response> responseConsumer) {
    Response originalResponse = null, lastResponse = null;
    List<String> entities = new ArrayList<>();

    try {
      originalResponse = clientRequest.invoke(body);
      if (clientRequest.pagingEnabled) {
        lastResponse = originalResponse;
        entities.add(lastResponse.readEntity(String.class));
        while ((lastResponse = executePagingRequest(clientRequest, lastResponse)) != null) {
          entities.add(lastResponse.readEntity(String.class));
          lastResponse.close();
        }
        originalResponse = PagingResponse.fromResponse(originalResponse).entity(entities).build();
      }

      responseConsumer.accept(originalResponse);
    } catch (Exception e) {
      LOG.log(
          Level.WARNING,
          prefixLogMessage(
              "Exception thrown while doing polling request ["
                  + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                  + "]: "
                  + clientRequest.requestTarget.getUriBuilder().build().toString()));
    } finally {
      if (originalResponse != null) {
        originalResponse.close();
      }
      if (lastResponse != null) {
        lastResponse.close();
      }
    }
  }

  protected Response executePagingRequest(HttpClientRequest clientRequest, Response response) {
    if (response.hasLink("next")) {
      URI nextUrl = response.getLink("next").getUri();
      return clientRequest
          .client
          .register(new PaginationFilter(nextUrl))
          .request()
          .build(clientRequest.method)
          .invoke();
    }
    return null;
  }

  protected void executeAttributeWriteRequest(
      HttpClientRequest clientRequest, Object attributeValue, Consumer<Response> responseConsumer) {
    String valueStr =
        attributeValue == null ? null : ValueUtil.convert(attributeValue, String.class);

    try (Response response = clientRequest.invoke(valueStr)) {
      responseConsumer.accept(response);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Exception thrown while doing attribute write request", e);
    }
  }

  protected void onPollingResponse(
      HttpClientRequest request,
      Response response,
      AttributeRef attributeRef,
      HTTPAgentLink agentLink) {

    int responseCode = response != null ? response.getStatus() : 500;
    Object value = null;

    if (response != null
        && response.hasEntity()
        && response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
      try {
        boolean binaryMode =
            agent.getMessageConvertBinary().orElse(agentLink.isMessageConvertBinary());
        boolean hexMode = agent.getMessageConvertHex().orElse(agentLink.isMessageConvertHex());

        if (hexMode || binaryMode) {
          byte[] bytes = response.readEntity(byte[].class);
          value =
              hexMode ? ValueUtil.bytesToHexString(bytes) : ValueUtil.bytesToBinaryString(bytes);
        } else {
          value = response.readEntity(String.class);
        }
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Error occurred while trying to read response body", e);
        response.close();
      }
    } else {
      LOG.fine(
          prefixLogMessage(
              "Request returned an un-successful response code ("
                  + responseCode
                  + "):"
                  + request.requestTarget.getUriBuilder().build().toString()));
      return;
    }

    if (attributeRef != null) {
      updateLinkedAttribute(attributeRef, value);

      // Look for any attributes that also want to use this polling response
      synchronized (pollingLinkedAttributeMap) {
        Set<AttributeRef> linkedRefs = pollingLinkedAttributeMap.get(attributeRef);
        if (linkedRefs != null) {
          Object finalValue = value;
          linkedRefs.forEach(ref -> updateLinkedAttribute(ref, finalValue));
        }
      }
    }
  }

  protected void onAttributeWriteResponse(HttpClientRequest request, Response response) {

    if (response != null
        && response.hasEntity()
        && response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
      LOG.fine(
          prefixLogMessage(
              "Attribute write request returned an unsuccessful response code ("
                  + response.getStatus()
                  + "): "
                  + request.requestTarget.getUriBuilder().build().toString()));
    }
  }

  protected void cancelPolling(AttributeRef attributeRef) {
    ScheduledFuture<?> pollTask = pollingMap.remove(attributeRef);
    if (pollTask != null) {
      pollTask.cancel(false);
    }
  }

  protected ResteasyClient createClient() {
    Integer readTimeout = agent.getRequestTimeoutMillis().orElse(null);
    return WebTargetBuilder.createClient(
        executorService,
        1,
        Optional.ofNullable(readTimeout).orElse(DEFAULT_READ_TIMEOUT_MILLIS).longValue(),
        null);
  }
}
