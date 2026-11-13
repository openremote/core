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

import static org.openremote.container.web.WebService.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.openremote.model.util.Config;
import org.openremote.model.util.TextUtil;

/** A simple configuration container for configuring CORS behavior */
public class CORSConfig {
  public static final int DEFAULT_CORS_MAX_AGE = 600;
  public static final String DEFAULT_CORS_ALLOW_ALL = "*";
  public static final boolean DEFAULT_CORS_ALLOW_CREDENTIALS = true;
  protected Set<String> corsAllowedOrigins;
  protected String corsAllowedMethods;
  protected String corsAllowedHeaders;
  protected String corsExposedHeaders;
  protected int corsMaxAge = DEFAULT_CORS_MAX_AGE;
  protected boolean corsAllowCredentials = DEFAULT_CORS_ALLOW_CREDENTIALS;

  public CORSConfig() {
    corsAllowedOrigins =
        Config.isDevMode()
            ? Collections.singleton(DEFAULT_CORS_ALLOW_ALL)
            : getCorsAllowedOriginsFromConfig();
    corsAllowedMethods =
        Config.isDevMode()
            ? DEFAULT_CORS_ALLOW_ALL
            : Config.getString(OR_WEBSERVER_ALLOWED_METHODS, DEFAULT_CORS_ALLOW_ALL);
    corsAllowedHeaders =
        Config.isDevMode()
            ? DEFAULT_CORS_ALLOW_ALL
            : Config.getString(OR_WEBSERVER_ALLOWED_HEADERS, DEFAULT_CORS_ALLOW_ALL);
    corsExposedHeaders = Config.getString(OR_WEBSERVER_EXPOSED_HEADERS, null);
  }

  public Set<String> getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public CORSConfig setCorsAllowedOrigins(Set<String> corsAllowedOrigins) {
    this.corsAllowedOrigins = corsAllowedOrigins;
    return this;
  }

  public CORSConfig addCorsAllowedOrigin(String origin) {
    corsAllowedOrigins.add(origin);
    return this;
  }

  public String getCorsAllowedMethods() {
    return corsAllowedMethods;
  }

  public CORSConfig setCorsAllowedMethods(String corsAllowedMethods) {
    this.corsAllowedMethods = corsAllowedMethods;
    return this;
  }

  public String getCorsAllowedHeaders() {
    return corsAllowedHeaders;
  }

  public CORSConfig setCorsAllowedHeaders(String corsAllowedHeaders) {
    this.corsAllowedHeaders = corsAllowedHeaders;
    return this;
  }

  public String getCorsExposedHeaders() {
    return corsExposedHeaders;
  }

  public CORSConfig setCorsExposedHeaders(String corsExposedHeaders) {
    this.corsExposedHeaders = corsExposedHeaders;
    return this;
  }

  public int getCorsMaxAge() {
    return corsMaxAge;
  }

  public CORSConfig setCorsMaxAge(int corsMaxAge) {
    this.corsMaxAge = corsMaxAge;
    return this;
  }

  public boolean isCorsAllowCredentials() {
    return corsAllowCredentials;
  }

  public CORSConfig setCorsAllowCredentials(boolean corsAllowCredentials) {
    this.corsAllowCredentials = corsAllowCredentials;
    return this;
  }

  public static Set<String> getCorsAllowedOriginsFromConfig() {
    // Set allowed origins using external hostnames and WEBSERVER_ALLOWED_ORIGINS
    Set<String> allowedOrigins =
        new HashSet<>(
            getExternalHostnames().stream().map(hostname -> "https://" + hostname).toList());
    String allowedOriginsStr = Config.getString(OR_WEBSERVER_ALLOWED_ORIGINS, null);

    if (!TextUtil.isNullOrEmpty(allowedOriginsStr)) {
      allowedOrigins.addAll(Arrays.stream(allowedOriginsStr.split(",")).toList());
    }

    // TODO: Enhance the CORS filter to support partial wildcard URIs
    // Check if any contain a wildcard and convert list to single allow all as CORSFilter doesn't
    // support
    // partial wildcard URIs
    boolean containsWildcard =
        allowedOrigins.stream().anyMatch(origin -> origin.contains(DEFAULT_CORS_ALLOW_ALL));

    if (containsWildcard) {
      allowedOrigins = Set.of(DEFAULT_CORS_ALLOW_ALL);
    }

    return allowedOrigins;
  }
}
