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
package org.openremote.manager.web;

import static org.openremote.model.util.MapAccess.getString;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.*;
import io.undertow.server.handlers.RedirectHandler;
import jakarta.ws.rs.core.Application;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openremote.container.web.*;
import org.openremote.model.Container;
import org.openremote.model.util.Config;
import org.openremote.model.util.TextUtil;

public class ManagerWebService extends WebService {

  private abstract static class ServerVariableMixin {
    @JsonProperty("default")
    List<String> _default;
  }

  private abstract static class StringSchemaMixin {
    @JsonProperty("enum")
    protected List<String> _enum;
  }

  public static final int PRIORITY = LOW_PRIORITY + 100;
  public static final String OR_APP_DOCROOT = "OR_APP_DOCROOT";
  public static final String OR_APP_DOCROOT_DEFAULT = "ui/app";
  public static final String OR_CUSTOM_APP_DOCROOT = "OR_CUSTOM_APP_DOCROOT";
  public static final String OR_CUSTOM_APP_DOCROOT_DEFAULT = "deployment/manager/app";
  public static final String OR_ROOT_REDIRECT_PATH = "OR_ROOT_REDIRECT_PATH";
  public static final String OR_ROOT_REDIRECT_PATH_DEFAULT = "/manager";

  public static final String API_PATH = "/api";
  public static final String SHARED_PATH = "/shared";
  public static final Path SHARED_SOURCE_DOCROOT = Paths.get("ui/app/shared");
  private static final Logger LOG = Logger.getLogger(ManagerWebService.class.getName());
  protected boolean initialised;
  protected Path builtInAppDocRoot;
  protected Path customAppDocRoot;
  protected Collection<Class<?>> apiClasses = new HashSet<>();
  protected Collection<Object> apiSingletons = new HashSet<>();

  /** Start web service after other services. */
  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    super.init(container);
    Set<ResourceSource> resourceSources = new HashSet<>();
    builtInAppDocRoot =
        Paths.get(getString(container.getConfig(), OR_APP_DOCROOT, OR_APP_DOCROOT_DEFAULT));
    customAppDocRoot =
        Paths.get(
            getString(container.getConfig(), OR_CUSTOM_APP_DOCROOT, OR_CUSTOM_APP_DOCROOT_DEFAULT));
    String rootRedirectPath =
        getString(container.getConfig(), OR_ROOT_REDIRECT_PATH, OR_ROOT_REDIRECT_PATH_DEFAULT);

    // Add a handler to redirect requests for the exact root path "/" to the configured default path
    if (!TextUtil.isNullOrEmpty(rootRedirectPath)) {
      LOG.info("Adding root redirect to: " + rootRedirectPath);
      pathHandler.addExactPath("/", new RedirectHandler(rootRedirectPath));
    }

    // Include Open API resource
    addApiSingleton(getOpenApiResource());

    initialised = true;

    // Configure the JAX-RS Manager API including resources added at startup
    List<Object> singletons =
        Stream.of(getStandardProviders(devMode), apiSingletons)
            .flatMap(Collection::stream)
            .toList();
    Application application = new WebApplication(container, apiClasses, singletons);

    deployJaxRsApplication(application, API_PATH, "Manager HTTP API", 0, true, null);

    // In dev mode serve the shared files from source so a change needs no rebuild
    if (Config.isDevMode() && Files.isDirectory(SHARED_SOURCE_DOCROOT)) {
      LOG.info("Serving shared files from: " + SHARED_SOURCE_DOCROOT.toAbsolutePath());
      deployFileServlet(
          SHARED_PATH,
          "Shared Files",
          new ResourceSource[] {new FileResource(SHARED_SOURCE_DOCROOT)},
          null,
          null,
          null);
    }

    if (Files.isDirectory(builtInAppDocRoot)) {
      resourceSources.add(new FileResource(builtInAppDocRoot));
    } else {
      LOG.info("Built in app doc root does not exist: " + builtInAppDocRoot.toAbsolutePath());
    }

    // Serve manager app from classpath if we are in dev mode (outside of docker) and it can be
    // found
    // this is used by custom projects so that they can serve the manager UI while running in an IDE
    if (Config.isDevMode()) {
      URL url = ManagerWebService.class.getClassLoader().getResource("org/openremote/web/manager");
      if (url != null) {
        resourceSources.add(
            new ClassPathResource(getClass().getClassLoader(), "org/openremote/web"));
      }
    }

    // If custom app docroot is a directory then make it the default file handler
    if (customAppDocRoot != null && Files.isDirectory(customAppDocRoot)) {
      resourceSources.add(new FileResource(customAppDocRoot));
    } else if (customAppDocRoot != null) {
      LOG.info("Custom app doc root does not exist: " + customAppDocRoot.toAbsolutePath());
    }

    // Deploy static app files unsecured
    deployFileServlet(
        "/", "App Files", resourceSources.toArray(ResourceSource[]::new), null, null, null);
  }

  protected Object getOpenApiResource() {
    ManagerObjectMapperProcessor.configure(Json.mapper());

    SwaggerConfiguration oasConfig =
        new SwaggerConfiguration()
            .resourcePackages(Set.of("org.openremote.model.*"))
            .openAPI(loadOpenApiBase())
            .defaultResponseCode("200");

    OpenApiResource openApiResource = new OpenApiResource();
    openApiResource.openApiConfiguration(oasConfig);
    return openApiResource;
  }

  private OpenAPI loadOpenApiBase() {
    try (InputStream input = ManagerWebService.class.getResourceAsStream("/openapi-base.yaml")) {
      if (input == null) {
        throw new IllegalStateException("Cannot find /openapi-base.yaml");
      }
      return Yaml.mapper().readValue(input, OpenAPI.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load /openapi-base.yaml", e);
    }
  }

  /** Add resource/provider/etc. classes to enable REST API */
  public void addApiClasses(Class<?> apiClass) {
    if (this.initialised) {
      throw new IllegalStateException(
          "API classes must be added before the service is initialised");
    }
    apiClasses.add(apiClass);
  }

  /** Add resource/provider/etc. singletons to enable REST API. */
  public void addApiSingleton(Object singleton) {
    if (this.initialised) {
      throw new IllegalStateException(
          "API singletons must be added before the service is initialised");
    }
    apiSingletons.add(singleton);
  }

  public Path getBuiltInAppDocRoot() {
    return builtInAppDocRoot;
  }

  public Path getCustomAppDocRoot() {
    return customAppDocRoot;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "builtInAppDocRoot="
        + builtInAppDocRoot
        + ", customAppDocRoot="
        + customAppDocRoot
        + '}';
  }
}
