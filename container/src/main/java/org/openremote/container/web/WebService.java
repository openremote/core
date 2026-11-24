/*
 * Copyright 2025, OpenRemote Inc.
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
package org.openremote.container.web;

import static java.lang.System.Logger.Level.*;
import static org.openremote.container.web.CORSConfig.DEFAULT_CORS_ALLOW_ALL;
import static org.openremote.model.Constants.OR_ADDITIONAL_HOSTNAMES;
import static org.openremote.model.Constants.OR_HOSTNAME;
import static org.openremote.model.util.MapAccess.*;

import com.google.common.collect.Lists;
import com.thetransactioncompany.cors.CORSFilter;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.RequestDumpingHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.*;
import io.undertow.servlet.handlers.DefaultServlet;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.util.Headers;
import io.undertow.websockets.core.WebSocketChannel;
import jakarta.servlet.*;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import java.net.Inet4Address;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.openremote.container.json.JacksonConfig;
import org.openremote.container.security.IdentityService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.http.HTTPMethod;
import org.openremote.model.util.Config;
import org.openremote.model.util.TextUtil;
import org.xnio.Options;

public abstract class WebService implements ContainerService {

  // Change this to 0.0.0.0 to bind on all interfaces, enabling
  // access of the manager service from other devices in your LAN
  public static final String OR_WEBSERVER_LISTEN_HOST = "OR_WEBSERVER_LISTEN_HOST";
  public static final String OR_WEBSERVER_LISTEN_HOST_DEFAULT = "0.0.0.0";
  public static final String OR_WEBSERVER_LISTEN_PORT = "OR_WEBSERVER_LISTEN_PORT";
  public static final int OR_WEBSERVER_LISTEN_PORT_DEFAULT = 8080;
  public static final String OR_WEBSERVER_DUMP_REQUESTS = "OR_WEBSERVER_DUMP_REQUESTS";
  public static final boolean OR_WEBSERVER_DUMP_REQUESTS_DEFAULT = false;
  public static final String OR_WEBSERVER_ALLOWED_ORIGINS = "OR_WEBSERVER_ALLOWED_ORIGINS";
  public static final String OR_WEBSERVER_ALLOWED_METHODS = "OR_WEBSERVER_ALLOWED_METHODS";
  public static final String OR_WEBSERVER_EXPOSED_HEADERS = "OR_WEBSERVER_EXPOSED_HEADERS";
  public static final String OR_WEBSERVER_ALLOWED_HEADERS = "OR_WEBSERVER_ALLOWED_HEADERS";
  public static final String OR_WEBSERVER_IO_THREADS_MAX = "OR_WEBSERVER_IO_THREADS_MAX";
  public static final int OR_WEBSERVER_IO_THREADS_MAX_DEFAULT =
      Math.max(Runtime.getRuntime().availableProcessors(), 2);
  public static final String OR_WEBSERVER_WORKER_THREADS_MAX = "OR_WEBSERVER_WORKER_THREADS_MAX";
  public static final int OR_WEBSERVER_WORKER_THREADS_MAX_DEFAULT =
      Math.max(Runtime.getRuntime().availableProcessors(), 10);
  public static final String OR_WEBSERVER_MAX_ENTITY_SIZE = "OR_WEBSERVER_MAX_ENTITY_SIZE";
  public static final long OR_WEBSERVER_MAX_ENTITY_SIZE_DEFAULT = 30_000_000L;
  public static final String OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE =
      "OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE";
  public static final long OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE_DEFAULT = 30_000_000L;
  private static final System.Logger LOG = System.getLogger(WebService.class.getName());
  protected boolean devMode;
  protected String host;
  protected IdentityService identityService;
  protected ExecutorService executorService;
  protected int port;
  protected Undertow undertow;
  protected long maxEntitySize = OR_WEBSERVER_MAX_ENTITY_SIZE_DEFAULT;
  protected long multipartMaxEntitySize = OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE_DEFAULT;
  protected URI containerHostUri;
  protected PathHandler pathHandler = Handlers.path();

  public static final Map<String, String> MIME_TYPES =
      Map.of(
          "mjs", "text/javascript",
          "pbf", "application/x-protobuf",
          "wsdl", "application/xml",
          "xsl", "text/xsl");

  public static final Set<String> MIME_TYPES_ALREADY_GZIPPED =
      Set.of("application/vnd.mapbox-vector-tile", "application/x-protobuf");

  protected static String getLocalIpAddress() throws Exception {
    return Inet4Address.getLocalHost().getHostAddress();
  }

  @Override
  public int getPriority() {
    return LOW_PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    identityService = container.getService(IdentityService.class);
    executorService = container.getExecutor();
    devMode = container.isDevMode();
    Map<String, String> config = container.getConfig();

    host = getString(config, OR_WEBSERVER_LISTEN_HOST, OR_WEBSERVER_LISTEN_HOST_DEFAULT);
    port = getInteger(config, OR_WEBSERVER_LISTEN_PORT, OR_WEBSERVER_LISTEN_PORT_DEFAULT);
    String containerHost =
        host.equalsIgnoreCase("localhost")
                || host.indexOf("127") == 0
                || host.indexOf("0.0.0.0") == 0
            ? getLocalIpAddress()
            : host;

    containerHostUri =
        UriBuilder.fromPath("/").scheme("http").host(containerHost).port(port).build();

    maxEntitySize =
        getLong(config, OR_WEBSERVER_MAX_ENTITY_SIZE, OR_WEBSERVER_MAX_ENTITY_SIZE_DEFAULT);
    multipartMaxEntitySize =
        getLong(
            config,
            OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE,
            OR_WEBSERVER_MULTIPART_MAX_ENTITY_SIZE_DEFAULT);

    undertow =
        build(
                container,
                Undertow.builder()
                    .addHttpListener(port, host)
                    .setIoThreads(
                        getInteger(
                            config,
                            OR_WEBSERVER_IO_THREADS_MAX,
                            OR_WEBSERVER_IO_THREADS_MAX_DEFAULT))
                    .setWorkerThreads(
                        getInteger(
                            config,
                            OR_WEBSERVER_WORKER_THREADS_MAX,
                            OR_WEBSERVER_WORKER_THREADS_MAX_DEFAULT))
                    .setWorkerOption(Options.WORKER_NAME, "WebService")
                    .setWorkerOption(Options.THREAD_DAEMON, true)
                    .setServerOption(
                        UndertowOptions.MULTIPART_MAX_ENTITY_SIZE, multipartMaxEntitySize)
                    .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, maxEntitySize))
            .build();

    // We have to set system properties for websocket timeouts
    System.setProperty(WebSocketChannel.WEB_SOCKETS_READ_TIMEOUT, "30000");
    System.setProperty(WebSocketChannel.WEB_SOCKETS_WRITE_TIMEOUT, "30000");
  }

  @Override
  public void start(Container container) throws Exception {}

  @Override
  public void startIngress(Container container) throws Exception {
    if (undertow != null) {
      undertow.start();
      LOG.log(INFO, "Webserver ready on http://" + host + ":" + port);
    }
  }

  @Override
  public void stop(Container container) throws Exception {
    // Remove all deployments
    Servlets.defaultContainer().listDeployments().forEach(this::undeploy);

    pathHandler.clearPaths();

    if (undertow != null) {
      undertow.stop();
      undertow = null;
    }
  }

  /** Deploys the undertow specific deployment and wires up the path handler */
  public void deploy(DeploymentInfo deploymentInfo, boolean useCanonicalPathHandler) {
    String pathPrefix = deploymentInfo.getContextPath();

    if (!pathPrefix.startsWith("/")) {
      pathPrefix = "/" + pathPrefix;
    }

    LOG.log(
        INFO,
        "Deploying undertow servlet deployment: name="
            + deploymentInfo.getDeploymentName()
            + ", path="
            + pathPrefix
            + ", secure="
            + !deploymentInfo.isSecurityDisabled());

    // This will catch anything not handled by Resteasy/Servlets, such as IOExceptions "at the wrong
    // time"
    deploymentInfo.setExceptionHandler(new WebServiceExceptions.ServletExceptionHandler(devMode));

    try {
      DeploymentManager manager = Servlets.defaultContainer().addDeployment(deploymentInfo);
      manager.deploy();
      HttpHandler httpHandler = manager.start();

      if (useCanonicalPathHandler) {
        LOG.log(DEBUG, "Using canonical path handler for deployment");
        httpHandler = new CanonicalPathHandler(httpHandler);
      }

      pathHandler.addPrefixPath(deploymentInfo.getContextPath(), httpHandler);
    } catch (ServletException e) {
      LOG.log(ERROR, "Servlet deployment failed: " + e.getMessage());
    }
  }

  public void deploy(String pathPrefix, HttpHandler httpHandler) {
    if (!pathPrefix.startsWith("/")) {
      pathPrefix = "/" + pathPrefix;
    }

    LOG.log(INFO, "Deploying undertow http handler: path=" + pathPrefix);
    pathHandler.addPrefixPath(pathPrefix, httpHandler);
  }

  public void undeploy(String deploymentName) {
    try {
      DeploymentManager manager = Servlets.defaultContainer().getDeployment(deploymentName);
      DeploymentInfo deploymentInfo = manager.getDeployment().getDeploymentInfo();
      LOG.log(
          INFO,
          "Un-deploying undertow servlet deployment: name="
              + deploymentInfo.getDeploymentName()
              + ", path="
              + deploymentInfo.getContextPath());
      pathHandler.removePrefixPath(deploymentInfo.getContextPath());
      manager.stop();
      manager.undeploy();
      Servlets.defaultContainer().removeDeployment(deploymentInfo);
    } catch (Exception ex) {
      LOG.log(
          ERROR,
          "Servlet un-deployment failed: name="
              + deploymentName
              + ", exception="
              + ex.getMessage());
    }
  }

  public void undeployHttpHandler(String pathPrefix) {
    LOG.log(INFO, "Un-deploying undertow handler: path=" + pathPrefix);
    pathHandler.removePrefixPath(pathPrefix);
  }

  public void addPathPrefixHandler(String path, HttpHandler handler) {
    pathHandler.addPrefixPath(path, handler);
  }

  public void removePathPrefixHandler(String path) {
    pathHandler.removePrefixPath(path);
  }

  /**
   * Get standard JAX-RS providers that are used in the deployment with optional realm extraction
   * from the request path
   */
  public static List<Object> getStandardProviders(boolean devMode) {
    return Lists.newArrayList(
        new WebServiceExceptions.JAXRSExceptionMapper(devMode),
        new JacksonConfig(),
        new ClientErrorExceptionHandler());
  }

  /**
   * Provides the LAN IPv4 address the container is bound to so it can be used in the context
   * provider callbacks; if CB is on the other side of some sort of NAT then this won't work also
   * assumes HTTP
   */
  public URI getHostUri() {
    return containerHostUri;
  }

  protected Undertow.Builder build(Container container, Undertow.Builder builder) {
    HttpHandler handler =
        new WebServiceExceptions.RootUndertowExceptionHandler(devMode, pathHandler);

    if (getBoolean(
        container.getConfig(), OR_WEBSERVER_DUMP_REQUESTS, OR_WEBSERVER_DUMP_REQUESTS_DEFAULT)) {
      handler = new RequestDumpingHandler(handler);
    }

    // Add GZIP encoding/decoding support at the undertow level
    handler =
        new EncodingHandler(
                new ContentEncodingRepository()
                    .addEncodingHandler(
                        "gzip",
                        new GzipEncodingProvider(),
                        50,
                        Predicates.and(
                            Predicates.requestLargerThan(5120),
                            Predicates.not(
                                Predicates.contains(
                                    ExchangeAttributes.responseHeader(Headers.CONTENT_TYPE),
                                    MIME_TYPES_ALREADY_GZIPPED.toArray(new String[0]))))))
            .setNext(handler);

    builder.setHandler(handler);

    return builder;
  }

  public void deployServlet(
      Class<? extends ServletContainerInitializer> servletContainerInitializerClass,
      String deploymentPath,
      String deploymentName,
      Integer realmIndex,
      boolean secure,
      CORSConfig corsOverride)
      throws IllegalArgumentException {
    DeploymentInfo deploymentInfo =
        Servlets.deployment()
            .setDeploymentName(deploymentName)
            .setContextPath(deploymentPath)
            .setSecurityDisabled(!secure)
            .addServletContainerInitializer(
                new ServletContainerInitializerInfo(servletContainerInitializerClass, null))
            .setClassLoader(this.getClass().getClassLoader());

    ServletContainerInitializer containerInitializer =
        (c, ctx) -> configureServlet(ctx, secure, realmIndex, corsOverride);

    InstanceFactory<ServletContainerInitializer> factory =
        new ImmediateInstanceFactory<>(containerInitializer);
    deploymentInfo.addServletContainerInitializer(
        new ServletContainerInitializerInfo(
            containerInitializer.getClass(), factory, Collections.emptySet()));

    deploy(deploymentInfo, false);
  }

  public void deployJaxRsApplication(
      Application application,
      String deploymentPath,
      String deploymentName,
      Integer realmIndex,
      boolean secure,
      CORSConfig corsOverride)
      throws IllegalArgumentException {
    ServletContextListener jaxRsListener =
        new ServletContextListener() {
          @Override
          public void contextInitialized(ServletContextEvent sce) {
            ServletContext ctx = sce.getServletContext();
            ResteasyDeployment deployment = new ResteasyDeploymentImpl();
            deployment.setApplication(application);
            ctx.setAttribute(ResteasyDeployment.class.getName(), deployment);

            ServletRegistration.Dynamic servlet =
                ctx.addServlet("ResteasyServlet", HttpServletDispatcher.class);
            servlet.setAsyncSupported(true);
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/*");

            if (secure) {
              deployment.setSecurityEnabled(true);
            }
          }
        };
    Class<? extends EventListener> listenerClass = jaxRsListener.getClass();
    InstanceFactory<? extends EventListener> factory =
        new ImmediateInstanceFactory<>(jaxRsListener);

    DeploymentInfo deploymentInfo =
        Servlets.deployment()
            .setDeploymentName(deploymentName)
            .setContextPath(deploymentPath)
            .addListeners(Servlets.listener(listenerClass, factory))
            .setClassLoader(this.getClass().getClassLoader());

    ServletContainerInitializer containerInitializer =
        (c, ctx) -> configureServlet(ctx, secure, realmIndex, corsOverride);

    InstanceFactory<ServletContainerInitializer> initFactory =
        new ImmediateInstanceFactory<>(containerInitializer);
    deploymentInfo.addServletContainerInitializer(
        new ServletContainerInitializerInfo(
            containerInitializer.getClass(), initFactory, Collections.emptySet()));

    deploy(deploymentInfo, false);
  }

  /**
   * Serve files from disk or classpath; {@link ResourceSource}s provided should be in preference
   * order.
   */
  @SuppressWarnings("resource")
  public void deployFileServlet(
      String deploymentPath,
      String deploymentName,
      ResourceSource[] resourceSources,
      String[] allowedRoles,
      Integer realmIndex,
      CORSConfig corsOverride) {

    if (resourceSources == null || resourceSources.length == 0) {
      throw new IllegalArgumentException("No file paths specified");
    }

    ResourceManager filesResourceManager;
    if (resourceSources.length == 1) {
      filesResourceManager = createResourceManager(resourceSources[0]);
    } else {
      CompositeResourceManager compositeResourceManager = new CompositeResourceManager();
      for (ResourceSource resourceSource : resourceSources) {
        compositeResourceManager.addResourceManager(createResourceManager(resourceSource));
      }
      filesResourceManager = compositeResourceManager;
    }

    DeploymentInfo deploymentInfo =
        Servlets.deployment()
            .setDeploymentName(deploymentName)
            .setResourceManager(filesResourceManager)
            .setContextPath(deploymentPath)
            .addServlet(Servlets.servlet("DefaultServlet", DefaultServlet.class))
            .addWelcomePages("index.html", "index.htm")
            .setClassLoader(getClass().getClassLoader());

    MIME_TYPES.forEach(
        (ext, mimeType) -> deploymentInfo.addMimeMapping(new MimeMapping(ext, mimeType)));

    ServletContainerInitializer containerInitializer =
        (c, ctx) -> {
          configureServlet(
              ctx, allowedRoles != null && allowedRoles.length > 0, realmIndex, corsOverride);

          // Add a security filter to mimic JAX-RS @RolesAllowed annotation for file serving
          if (allowedRoles != null && allowedRoles.length > 0) {
            Filter securityFilter = new SecurityServletFilter(allowedRoles);
            FilterRegistration.Dynamic securityRegistration =
                ctx.addFilter(SecurityServletFilter.class.getSimpleName(), securityFilter);
            securityRegistration.setAsyncSupported(true);
            securityRegistration.addMappingForUrlPatterns(
                EnumSet.of(DispatcherType.REQUEST), true, "/*");
          }

          Filter alreadyGzippedFilter = new AlreadyGZippedFilter(MIME_TYPES_ALREADY_GZIPPED);
          FilterRegistration.Dynamic alreadyGzippedRegistration =
              ctx.addFilter(AlreadyGZippedFilter.class.getSimpleName(), alreadyGzippedFilter);
          alreadyGzippedRegistration.setAsyncSupported(true);
          alreadyGzippedRegistration.addMappingForUrlPatterns(
              EnumSet.of(DispatcherType.REQUEST), true, "/*");
        };

    InstanceFactory<ServletContainerInitializer> factory =
        new ImmediateInstanceFactory<>(containerInitializer);
    deploymentInfo.addServletContainerInitializer(
        new ServletContainerInitializerInfo(
            containerInitializer.getClass(), factory, Collections.emptySet()));

    deploy(deploymentInfo, true);
  }

  protected ResourceManager createResourceManager(ResourceSource resourceSource) {
    if (resourceSource instanceof FileResource(Path path)) {
      return new PathResourceManager(path);
    }
    if (resourceSource instanceof ClassPathResource(ClassLoader classLoader, String prefix)) {
      return new DirectoryAwareClassPathResourceManager(classLoader, prefix);
    }
    throw new UnsupportedOperationException("ResourceSource not currently supported");
  }

  public void configureServlet(
      ServletContext ctx, boolean secure, Integer realmIndex, CORSConfig corsOverride) {

    if (corsOverride == null) {
      // Just use default CORS config
      corsOverride = new CORSConfig();
    }

    if (secure) {
      if (identityService == null)
        throw new IllegalStateException(
            "No identity service found, make sure "
                + IdentityService.class.getName()
                + " is added before this service");
    }

    // Do all filter registration using the ServletContext for sane predictable ordering

    // CORS filter 1st
    // Cannot set config on constructor as init method will overwrite it
    CORSFilter corsFilter = new CORSFilter();
    FilterRegistration.Dynamic corsRegistration =
        ctx.addFilter(CORSFilter.class.getSimpleName(), corsFilter);
    corsRegistration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
    corsRegistration.setAsyncSupported(true);
    getCORSConfiguration(corsOverride)
        .forEach((k, v) -> corsRegistration.setInitParameter(k.toString(), v.toString()));

    if (realmIndex != null) {
      // The realm extraction from path
      RealmPathExtractorFilter realmPathExtractorFilter = new RealmPathExtractorFilter(realmIndex);
      FilterRegistration.Dynamic realmExtractorRegistration =
          ctx.addFilter(RealmPathExtractorFilter.class.getSimpleName(), realmPathExtractorFilter);
      realmExtractorRegistration.addMappingForUrlPatterns(
          EnumSet.of(DispatcherType.REQUEST), true, "/*");
      realmExtractorRegistration.setAsyncSupported(true);
    }

    // Then logging
    FilterRegistration.Dynamic loggingRegistration =
        ctx.addFilter(LoggingFilter.class.getSimpleName(), new LoggingFilter(devMode));
    loggingRegistration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    loggingRegistration.setAsyncSupported(true);

    // Then security
    if (secure) {
      FilterRegistration.Dynamic securityRegistration = identityService.secureDeployment(ctx);
      securityRegistration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
      securityRegistration.setAsyncSupported(true);
    }
  }

  public Undertow getUndertow() {
    return undertow;
  }

  public static List<String> getExternalHostnames() {

    // Get list of external hostnames
    String defaultHostname = Config.getString(OR_HOSTNAME, null);
    String additionalHostnamesStr = Config.getString(OR_ADDITIONAL_HOSTNAMES, null);

    List<String> externalHostnames = new ArrayList<>();

    if (!TextUtil.isNullOrEmpty(additionalHostnamesStr)) {
      externalHostnames.addAll(Arrays.stream(additionalHostnamesStr.split(",")).toList());
    }

    if (!TextUtil.isNullOrEmpty(defaultHostname) && !externalHostnames.contains(defaultHostname)) {
      externalHostnames.add(defaultHostname);
    }

    return externalHostnames;
  }

  public static Properties getCORSConfiguration(CORSConfig corsConfig) {
    Properties props = new Properties();

    if (corsConfig != null) {
      props.put("cors.supportsCredentials", Boolean.toString(corsConfig.isCorsAllowCredentials()));
      props.put("cors.maxAge", corsConfig.getCorsMaxAge());
      if (corsConfig.getCorsExposedHeaders() != null) {
        props.put("cors.exposedHeaders", corsConfig.getCorsExposedHeaders());
      }
      if (corsConfig.getCorsAllowedHeaders() != null) {
        props.put("cors.supportedHeaders", corsConfig.getCorsAllowedHeaders());
      }
      // CORSFilter doesn't support wildcard for methods
      String allowedMethods = corsConfig.getCorsAllowedMethods();
      allowedMethods =
          allowedMethods == null || DEFAULT_CORS_ALLOW_ALL.equals(allowedMethods)
              ? Arrays.stream(HTTPMethod.values())
                  .map(Enum::toString)
                  .collect(Collectors.joining(","))
              : allowedMethods;
      props.put("cors.supportedMethods", allowedMethods);
      props.put("cors.allowOrigin", String.join(",", corsConfig.getCorsAllowedOrigins()));
    }

    return props;
  }

  public CompletableFuture<String> getBearerToken(@NotNull OAuthGrant oAuthGrant) {
    return getBearerToken(executorService, WebTargetBuilder.getClient(), oAuthGrant);
  }

  /** Retrieve bearer tokens for the provided {@link OAuthGrant}. */
  public static CompletableFuture<String> getBearerToken(
      ExecutorService executorService, Client client, @NotNull OAuthGrant oAuthGrant) {
    if (oAuthGrant == null) {
      return CompletableFuture.failedFuture(
          new NullPointerException(OAuthGrant.class.getSimpleName() + " cannot be null"));
    }
    CompletableFuture<String> future = new CompletableFuture<>();

    executorService.submit(
        () -> {
          LOG.log(DEBUG, "Retrieving OAuth access token: " + oAuthGrant);

          try {
            OAuthFilter oAuthFilter = new OAuthFilter(client, oAuthGrant);
            String authHeaderValue = oAuthFilter.getAccessToken();
            if (TextUtil.isNullOrEmpty(authHeaderValue)) {
              throw new RuntimeException("Returned access token is null");
            }
            LOG.log(DEBUG, "Retrieved access token via OAuth: " + oAuthGrant);
            future.complete(authHeaderValue);
          } catch (Exception e) {
            future.completeExceptionally(
                new Exception(
                    "Error retrieving OAuth access token for '"
                        + oAuthGrant
                        + "': "
                        + e.getMessage()));
          }
        });

    return future;
  }
}
