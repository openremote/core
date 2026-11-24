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
package org.openremote.manager.map;

import static org.openremote.manager.web.ManagerWebService.API_PATH;
import static org.openremote.model.util.MapAccess.getInteger;
import static org.openremote.model.util.MapAccess.getString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;
import org.openremote.container.web.WebService;
import org.openremote.manager.app.ConfigurationService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.manager.MapConfig;
import org.openremote.model.manager.MapSourceConfig;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public class MapService implements ContainerService {

  public static final String MAP_SHARED_DATA_BASE_URI = "/shared";
  public static final String OR_MAP_TILESERVER_HOST = "OR_MAP_TILESERVER_HOST";
  public static final String OR_MAP_TILESERVER_HOST_DEFAULT = null;
  public static final String OR_MAP_TILESERVER_PORT = "OR_MAP_TILESERVER_PORT";
  public static final int OR_MAP_TILESERVER_PORT_DEFAULT = 8082;
  public static final String OR_CUSTOM_MAP_SIZE_LIMIT = "OR_CUSTOM_MAP_SIZE_LIMIT";
  public static final int OR_CUSTOM_MAP_SIZE_LIMIT_DEFAULT = 30_000_000;
  public static final String RASTER_MAP_TILE_PATH = "/raster_map/tile";
  public static final String TILESERVER_TILE_PATH = "/styles/standard";
  public static final String OR_MAP_TILESERVER_REQUEST_TIMEOUT =
      "OR_MAP_TILESERVER_REQUEST_TIMEOUT";
  public static final int OR_MAP_TILESERVER_REQUEST_TIMEOUT_DEFAULT = 10000;
  public static final String OR_PATH_PREFIX = "OR_PATH_PREFIX";
  public static final String OR_PATH_PREFIX_DEFAULT = "";
  private static final Logger LOG = Logger.getLogger(MapService.class.getName());
  private static final String DEFAULT_VECTOR_TILES_URL = "mbtiles://mapdata.mbtiles";
  private static final String DEFAULT_SPRITE_PATH = "map/sprites/osm-liberty";
  private static final String DEFAULT_GLYPHS_PATH = "{fontstack}/{range}.pbf";
  private static ConfigurationService configurationService;

  // Shared SQL connection is fine concurrently in SQLite
  protected Connection connection;
  protected Metadata metadata;
  protected ObjectNode mapConfig;
  protected ConcurrentMap<String, ObjectNode> mapSettings = new ConcurrentHashMap<>();
  protected ConcurrentMap<String, ObjectNode> mapSettingsJs = new ConcurrentHashMap<>();
  protected String pathPrefix;
  protected int customMapLimit = OR_CUSTOM_MAP_SIZE_LIMIT_DEFAULT;

  public ObjectNode saveMapConfig(MapConfig mapConfiguration) throws RuntimeException {
    if (mapConfig == null) {
      mapConfig = ValueUtil.JSON.createObjectNode();
    }

    ObjectNode sources = ValueUtil.JSON.valueToTree(mapConfiguration.sources);
    ObjectNode vectorTiles = (ObjectNode) sources.get("vector_tiles");
    String tileJsonUrl =
        Optional.ofNullable(vectorTiles.get("url"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::textValue)
            .orElse(null);
    ArrayNode tileServerUrls =
        Optional.ofNullable(vectorTiles.get("tiles"))
            .map(
                node -> {
                  ArrayNode result = JsonNodeFactory.instance.arrayNode();
                  StreamSupport.stream(node.spliterator(), false)
                      .filter(JsonNode::isTextual)
                      .map(JsonNode::textValue)
                      .filter(v -> v.contains("/{z}/{x}/{y}"))
                      .forEach(result::add);
                  return result;
                })
            .orElseGet(JsonNodeFactory.instance::arrayNode);

    // Saves custom tile server url if custom is true in the vector_tiles source and the url follows
    // the required template parameters.
    // Otherwise, replace it with the default configuration.
    if (vectorTiles.has("custom") && vectorTiles.get("custom").booleanValue()) {
      vectorTiles.put("url", tileJsonUrl);
      vectorTiles.set("tiles", tileServerUrls);
    } else {
      vectorTiles =
          ValueUtil.JSON
              .createObjectNode()
              .put("type", "vector")
              .put("url", DEFAULT_VECTOR_TILES_URL);
      mapConfig.put("sprite", DEFAULT_SPRITE_PATH);
      mapConfig.put("glyphs", DEFAULT_GLYPHS_PATH);
    }
    mapConfiguration.sources.clear();
    mapConfiguration.sources.put(
        "vector_tiles", ValueUtil.JSON.convertValue(vectorTiles, MapSourceConfig.class));

    sources
        .propertyStream()
        .filter(field -> !Objects.equals(field.getKey(), "vector_tiles"))
        .forEach(
            field -> {
              mapConfiguration.sources.put(
                  field.getKey(),
                  ValueUtil.JSON.convertValue(field.getValue(), MapSourceConfig.class));
            });

    mapConfig.remove("override");
    if (mapConfiguration.override != null) {
      mapConfig.put("override", mapConfiguration.override);
    }
    mapConfig.putPOJO("options", mapConfiguration.options);
    mapConfig.putPOJO("sources", mapConfiguration.sources);
    mapConfig.put(
        "sprite", Optional.ofNullable(mapConfiguration.sprite).orElse(DEFAULT_SPRITE_PATH));
    mapConfig.put(
        "glyphs", Optional.ofNullable(mapConfiguration.glyphs).orElse(DEFAULT_GLYPHS_PATH));
    mapConfig.putPOJO(
        "layers",
        Optional.ofNullable(mapConfiguration.layers)
            .filter(v -> v.length > 0)
            .orElse(
                StreamSupport.stream(
                        configurationService.getMapConfig().get("layers").spliterator(), false)
                    .toArray()));

    configurationService.saveMapConfig(mapConfig);
    mapConfig = configurationService.getMapConfig();
    mapSettings.clear();

    return mapConfig;
  }

  protected static Metadata getMetadata(Connection connection) {

    PreparedStatement query = null;
    ResultSet result = null;
    Metadata metadata = null;

    try {
      query = connection.prepareStatement("select NAME, VALUE from METADATA");
      result = query.executeQuery();

      Map<String, String> resultMap = new HashMap<>();
      while (result.next()) {
        resultMap.put(result.getString(1), result.getString(2));
      }

      if (resultMap.isEmpty()) {
        return new Metadata();
      }

      String attribution = resultMap.get("attribution");
      int maxZoom = Integer.parseInt(resultMap.get("maxzoom"));
      int minZoom = Integer.parseInt(resultMap.get("minzoom"));

      ArrayNode vectorLayer =
          resultMap.containsKey("json")
              ? (ArrayNode) ValueUtil.JSON.readTree(resultMap.get("json")).get("vector_layers")
              : null;
      ArrayNode center =
          resultMap.containsKey("center")
              ? (ArrayNode) ValueUtil.JSON.readTree("[" + resultMap.get("center") + "]")
              : null;
      ArrayNode bounds =
          resultMap.containsKey("bounds")
              ? (ArrayNode) ValueUtil.JSON.readTree("[" + resultMap.get("bounds") + "]")
              : null;

      if (!TextUtil.isNullOrEmpty(attribution)
          && vectorLayer != null
          && !vectorLayer.isEmpty()
          && maxZoom > 0) {
        metadata = new Metadata(attribution, vectorLayer, bounds, center, maxZoom, minZoom);
      } else {
        metadata = new Metadata();
        LOG.log(Level.SEVERE, "Required metadata missing in mbtiles DB");
      }
    } catch (Exception ex) {
      metadata = new Metadata();
      LOG.log(Level.SEVERE, "Failed to get metadata from mbtiles DB", ex);
    } finally {
      closeQuietly(query, result);
    }

    return metadata;
  }

  protected static void closeQuietly(PreparedStatement query, ResultSet result) {
    try {
      if (result != null) {
        result.close();
      }
      if (query != null) {
        query.close();
      }
    } catch (Exception ex) {
      LOG.warning("Error closing query/result: " + ex);
    }
  }

  @Override
  public void init(Container container) throws Exception {

    configurationService = container.getService(ConfigurationService.class);

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new MapResourceImpl(this, container.getService(ManagerIdentityService.class)));

    String tileServerHost =
        getString(container.getConfig(), OR_MAP_TILESERVER_HOST, OR_MAP_TILESERVER_HOST_DEFAULT);
    int tileServerPort =
        getInteger(container.getConfig(), OR_MAP_TILESERVER_PORT, OR_MAP_TILESERVER_PORT_DEFAULT);
    pathPrefix = getString(container.getConfig(), OR_PATH_PREFIX, OR_PATH_PREFIX_DEFAULT);
    customMapLimit =
        getInteger(
            container.getConfig(), OR_CUSTOM_MAP_SIZE_LIMIT, OR_CUSTOM_MAP_SIZE_LIMIT_DEFAULT);

    if (!TextUtil.isNullOrEmpty(tileServerHost)) {

      WebService webService = container.getService(WebService.class);

      UriBuilder tileServerUri =
          UriBuilder.fromPath("/").scheme("http").host(tileServerHost).port(tileServerPort);

      @SuppressWarnings("deprecation")
      ProxyHandler proxyHandler =
          new ProxyHandler(
                  new io.undertow.server.handlers.proxy.SimpleProxyClientProvider(
                      tileServerUri.build()),
                  getInteger(
                      container.getConfig(),
                      OR_MAP_TILESERVER_REQUEST_TIMEOUT,
                      OR_MAP_TILESERVER_REQUEST_TIMEOUT_DEFAULT),
                  ResponseCodeHandler.HANDLE_404)
              .setReuseXForwarded(true);

      HttpHandler proxyWrapper =
          exchange -> {
            // Change request path to match what the tile server expects
            String path = exchange.getRequestPath().substring(RASTER_MAP_TILE_PATH.length());

            exchange.setRequestURI(TILESERVER_TILE_PATH + path, true);
            exchange.setRequestPath(TILESERVER_TILE_PATH + path);
            exchange.setRelativePath(TILESERVER_TILE_PATH + path);
            proxyHandler.handleRequest(exchange);
          };

      webService.deploy(RASTER_MAP_TILE_PATH, proxyWrapper);
    }
  }

  @Override
  public void start(Container container) throws Exception {
    setData(false);
  }

  /**
   * Connects to mbtiles DB and loads metadata
   *
   * @return Path of connected mbtiles file
   * @throws IOException
   * @throws ClassNotFoundException
   * @throws SQLException
   * @throws NullPointerException
   */
  public Path setData(boolean skipCustom) {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException e) {
      LOG.log(Level.WARNING, "Could not close existing connection", e);
    }

    Path connectedFile = null;

    if (!skipCustom) {
      // Try and load custom map tiles first
      try {
        Path customMapTilesPath = configurationService.getCustomMapTilesPath(true);

        if (customMapTilesPath.toFile().isFile()) {
          Class.forName(org.sqlite.JDBC.class.getName());
          connection = DriverManager.getConnection("jdbc:sqlite:" + customMapTilesPath);
          metadata = getMetadata(connection);

          if (!metadata.isValid()) {
            LOG.warning("Custom map meta data could not be loaded, falling back to default map");
            try {
              if (connection != null) {
                connection.close();
              }
            } catch (SQLException e) {
              LOG.log(Level.WARNING, "Could not close connection", e);
            }
          } else {
            connectedFile = customMapTilesPath;
          }
        }
      } catch (IOException | ClassNotFoundException | SQLException e) {
        LOG.log(Level.WARNING, "An error occurred while trying to load custom map tiles file", e);
      }
    }

    // Fallback on default map tiles
    if (connectedFile == null) {
      try {
        Path mapTilesPath = configurationService.getMapTilesPath();
        if (mapTilesPath != null) {
          Class.forName(org.sqlite.JDBC.class.getName());
          connection = DriverManager.getConnection("jdbc:sqlite:" + mapTilesPath);
          metadata = getMetadata(connection);

          if (!metadata.isValid()) {
            LOG.warning("Default map meta data could not be loaded, map will not work");
            try {
              if (connection != null) {
                connection.close();
              }
            } catch (SQLException e) {
              LOG.log(Level.WARNING, "Could not close connection", e);
            }
          } else {
            connectedFile = mapTilesPath;
          }
        }
      } catch (ClassNotFoundException | SQLException e) {
        LOG.log(Level.WARNING, "An error occurred while trying to load map tiles file", e);
      }
    }

    if (connectedFile == null) {
      return null;
    }

    mapConfig = configurationService.getMapConfig();

    if (mapConfig == null) {
      return connectedFile;
    }

    ObjectNode options =
        Optional.ofNullable((ObjectNode) mapConfig.get("options")).orElse(mapConfig.objectNode());
    ObjectNode defaultOptions =
        Optional.ofNullable((ObjectNode) options.get("default")).orElse(mapConfig.objectNode());
    options.replace("default", defaultOptions);
    mapConfig.replace("options", options);

    if (!defaultOptions.has("maxZoom")) {
      defaultOptions.put("maxZoom", metadata.maxZoom);
    }
    if (!defaultOptions.has("minZoom")) {
      defaultOptions.put("minZoom", metadata.minZoom);
    }

    if (metadata.getCenter() != null) {
      if (!defaultOptions.has("center")) {
        ArrayNode center = metadata.getCenter().deepCopy();
        center.remove(2);
        defaultOptions.set("center", center);
      }
      if (!defaultOptions.has("zoom")) {
        defaultOptions.put("zoom", metadata.getCenter().get(2).asDouble(13d));
      }
    }

    if (!defaultOptions.has("bounds") && metadata.getBounds() != null) {
      defaultOptions.set("bounds", metadata.getBounds());
    }

    return connectedFile;
  }

  @Override
  public void stop(Container container) throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  /** Dynamically build MapLibre GL settings based on mapsettings.json */
  public ObjectNode getMapSettings(String realm, URI host) {
    String realmUriKey = realm + host.toString();
    if (mapSettings.containsKey(realmUriKey)) {
      return mapSettings.get(realmUriKey);
    }

    if (mapConfig == null) {
      return null;
    }

    final ObjectNode settings =
        mapSettings.computeIfAbsent(
            realmUriKey,
            r -> {
              if (metadata.isValid() && !mapConfig.isEmpty()) {
                // Use config as a settings base and convert URLs
                return mapConfig.deepCopy();
              }
              return mapConfig.objectNode();
            });

    if (!metadata.isValid() || mapConfig.isEmpty()) {
      return settings;
    }

    // Set vector_tiles URL to MapResource getSource endpoint
    Optional.ofNullable(settings.get("sources"))
        .map(s -> s.get("vector_tiles"))
        .filter(JsonNode::isObject)
        .ifPresent(
            vectorTilesNode -> {
              ObjectNode vectorTilesObj = (ObjectNode) vectorTilesNode;
              if (vectorTilesObj.has("custom")
                  && vectorTilesObj.get("custom").booleanValue()
                  && vectorTilesObj.hasNonNull("url")) {
                return;
              }
              vectorTilesObj.remove("url");

              vectorTilesObj.put("attribution", metadata.attribution);
              vectorTilesObj.put("maxzoom", metadata.maxZoom);
              vectorTilesObj.put("minzoom", metadata.minZoom);
              vectorTilesObj.replace("vector_layers", metadata.vectorLayers);

              Optional.ofNullable(mapConfig.get("center"))
                  .ifPresent(
                      center ->
                          Optional.ofNullable(
                                  mapConfig.has("zoom") && mapConfig.get("zoom").isInt()
                                      ? mapConfig.get("zoom")
                                      : null)
                              .ifPresent(
                                  zoom -> {
                                    ArrayNode centerArray = center.deepCopy();
                                    centerArray.add(zoom);
                                    vectorTilesObj.replace("center", centerArray);
                                  }));

              ArrayNode tilesArray = mapConfig.arrayNode();
              String tileUrl =
                  UriBuilder.fromUri(host)
                          .replacePath(pathPrefix + API_PATH)
                          .path(realm)
                          .path("map/tile")
                          .build()
                          .toString()
                      + "/{z}/{x}/{y}";
              tilesArray.insert(0, tileUrl);

              Optional.ofNullable(vectorTilesObj.get("tiles"))
                  .map(tiles -> tiles.get(0))
                  .filter(JsonNode::isTextual)
                  .map(JsonNode::textValue)
                  .ifPresent(
                      url -> {
                        if (!url.contentEquals(DEFAULT_VECTOR_TILES_URL)) {
                          tilesArray.remove(0);
                          tilesArray.insert(0, url);
                        }
                      });

              vectorTilesObj.replace("tiles", tilesArray);
            });

    // Set sprite URL to shared folder
    Optional.ofNullable(
            mapConfig.has("sprite") && mapConfig.get("sprite").isTextual()
                ? mapConfig.get("sprite").asText()
                : null)
        .ifPresent(
            sprite -> {
              String spriteUri =
                  sprite.equals(DEFAULT_SPRITE_PATH)
                      ? UriBuilder.fromUri(host)
                          .replacePath(pathPrefix + MAP_SHARED_DATA_BASE_URI)
                          .path(sprite)
                          .build()
                          .toString()
                      : sprite;
              settings.put("sprite", spriteUri);
            });

    // Set glyphs URL to shared folder (tileserver-gl glyphs url cannot contain a path segment so
    // add /fonts here
    Optional.ofNullable(
            mapConfig.has("glyphs") && mapConfig.get("glyphs").isTextual()
                ? mapConfig.get("glyphs").asText()
                : null)
        .ifPresent(
            glyphs -> {
              String glyphsUri =
                  glyphs.equals(DEFAULT_GLYPHS_PATH)
                      ? UriBuilder.fromUri(host)
                              .replacePath(pathPrefix + MAP_SHARED_DATA_BASE_URI)
                              .build()
                              .toString()
                          + "/fonts/"
                          + glyphs
                      : glyphs;
              settings.put("glyphs", glyphsUri);
            });

    return settings;
  }

  public byte[] getMapTile(int zoom, int column, int row) {
    // Flip y, oh why
    row = Double.valueOf(Math.pow(2, zoom) - 1 - row).intValue();

    PreparedStatement query = null;
    ResultSet result = null;
    try {
      query =
          connection.prepareStatement(
              "select TILE_DATA from TILES where ZOOM_LEVEL = ? and TILE_COLUMN = ? and TILE_ROW = ?");

      int index = 0;
      query.setInt(++index, zoom);
      query.setInt(++index, column);
      query.setInt(++index, row);

      result = query.executeQuery();

      if (result.next()) {
        return result.getBytes(1);
      } else {
        return null;
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      closeQuietly(query, result);
    }
  }

  public void saveUploadedFile(String filename, InputStream fileInputStream)
      throws IOException, IllegalArgumentException {
    Path customTilesDir = configurationService.getCustomMapTilesPath(false);
    Path tilesPath = customTilesDir.resolve(filename);
    Path previousCustomTilesPath = configurationService.getCustomMapTilesPath(true);

    // Check there's no back refs in the filename
    boolean isValid =
        tilesPath
            .toFile()
            .getCanonicalPath()
            .contains(customTilesDir.toFile().getCanonicalPath() + File.separator);
    if (!isValid) {
      String msg = "Filename outside permitted directory: " + filename;
      LOG.warning(msg);
      throw new IllegalArgumentException(msg);
    }

    try (OutputStream outputStream = Files.newOutputStream(tilesPath)) {
      byte[] buffer = new byte[4096];
      int bytesRead;
      int written = 0;
      while ((bytesRead = fileInputStream.read(buffer)) != -1) {
        if (written > customMapLimit) {
          String msg = "Stream continued passed custom map limit size: " + tilesPath;
          LOG.log(Level.SEVERE, msg);
          deletePartiallyWrittenFile(tilesPath, filename);
          throw new WebApplicationException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }
        outputStream.write(buffer, 0, bytesRead);
        written += bytesRead;
      }
    } catch (IOException e) {
      deletePartiallyWrittenFile(tilesPath, filename);
      throw e;
    }

    // Ensure we can access this new file and that it is a valid mbtiles file
    Path loadedFile = setData(false);
    // The metadata is set to ensure the bounding box is not outside the visible tile area.
    saveMapMetadata(metadata);

    if (loadedFile == null || !loadedFile.toAbsolutePath().equals(tilesPath.toAbsolutePath())) {
      deletePartiallyWrittenFile(tilesPath, filename);
      throw new IOException(
          "Failed to load map data ensure the uploaded file is a valid mbtiles file: " + filename);
    }

    // Now delete any previous custom mbtiles file
    if (previousCustomTilesPath.toFile().isFile()) {
      try {
        // Attempt to delete this old map data file
        Files.deleteIfExists(previousCustomTilesPath);
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Could not delete old file: " + previousCustomTilesPath, ex);
        // This is problematic as which custom file gets discovered first on next startup??
      }
    }
  }

  private void deletePartiallyWrittenFile(Path file, String filename) {
    try {
      // Attempt to delete this invalid map data file
      Files.deleteIfExists(file);
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Could not delete partially written file: " + filename, ex);
    }
  }

  public void deleteUploadedFile() throws IOException {
    Path previousCustomTilesPath = configurationService.getCustomMapTilesPath(true);

    if (previousCustomTilesPath.toFile().isFile()) {

      setData(true);
      // The metadata is set to ensure the bounding box is not outside the visible tile area.
      saveMapMetadata(metadata);

      // Attempt to delete this old map data file
      Files.deleteIfExists(previousCustomTilesPath);
    }
  }

  public void saveMapMetadata(Metadata metadata) {
    Optional<JsonNode> options = Optional.ofNullable(mapConfig.get("options"));
    if (metadata.isValid() && options.isPresent()) {
      options
          .get()
          .propertyStream()
          .forEach(
              field -> {
                ObjectNode value = (ObjectNode) field.getValue();
                value.set("center", metadata.getCenter());
                value.set("bounds", metadata.getBounds());
              });
      configurationService.saveMapConfig(mapConfig);
      mapConfig = configurationService.getMapConfig();
      mapSettings.clear();
    }
  }

  public ObjectNode getCustomMapInfo() throws IOException {
    return ValueUtil.JSON
        .createObjectNode()
        .put("limit", this.customMapLimit)
        .put(
            "filename",
            Optional.ofNullable(configurationService.getCustomMapTilesPath(true))
                .map(p -> p.toFile().isFile() ? p : null)
                .map(p -> p.getFileName().toString())
                .orElse(null));
  }

  /**
   * The {@link Metadata} class validates that {@link #bounds} and {@link #center} follow the
   * mbtiles-spec.
   *
   * @implSpec https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md#metadata
   */
  protected static final class Metadata {
    protected String attribution;
    protected ArrayNode vectorLayers;
    protected int maxZoom;
    protected int minZoom;
    protected ArrayNode bounds;
    protected ArrayNode center;
    protected boolean valid;

    public Metadata(
        String attribution,
        ArrayNode vectorLayers,
        ArrayNode bounds,
        ArrayNode center,
        int maxZoom,
        int minZoom) {
      this.attribution = attribution;
      this.vectorLayers = vectorLayers;
      this.bounds = bounds;
      this.center = center;
      this.maxZoom = maxZoom;
      this.minZoom = minZoom;

      boolean boundsValid =
          this.bounds.size() == 4
              && StreamSupport.stream(Spliterators.spliterator(this.bounds.elements(), 0, 4), false)
                  .allMatch(v -> v.numberType() != null);
      boolean centerValid =
          this.center.size() >= 2
              && StreamSupport.stream(Spliterators.spliterator(this.bounds.elements(), 0, 3), false)
                  .allMatch(v -> v.numberType() != null);
      if (!boundsValid) {
        LOG.log(Level.WARNING, "Map bounds are invalid.");
      }
      if (!centerValid) {
        LOG.log(Level.WARNING, "Map center is invalid.");
      }
      valid = boundsValid && centerValid;
    }

    public Metadata() {}

    public String getAttribution() {
      return attribution;
    }

    public ArrayNode getVectorLayers() {
      return vectorLayers;
    }

    public ArrayNode getBounds() {
      return bounds;
    }

    public ArrayNode getCenter() {
      return center;
    }

    public int getMaxZoom() {
      return maxZoom;
    }

    public int getMinZoom() {
      return minZoom;
    }

    public boolean isValid() {
      return valid;
    }
  }
}
