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
package org.openremote.manager.app;

import static org.openremote.manager.web.ManagerWebService.OR_CUSTOM_APP_DOCROOT;
import static org.openremote.manager.web.ManagerWebService.OR_CUSTOM_APP_DOCROOT_DEFAULT;
import static org.openremote.model.util.MapAccess.getString;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.file.FileInfo;
import org.openremote.model.manager.ManagerAppConfig;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public class ConfigurationService implements ContainerService {

  public static final String OR_MAP_SETTINGS_PATH = "OR_MAP_SETTINGS_PATH";
  public static final String OR_MAP_SETTINGS_PATH_DEFAULT = "manager/src/map/mapsettings.json";

  public static final String OR_MAP_TILES_PATH = "OR_MAP_TILES_PATH";
  public static final String OR_MAP_TILES_PATH_DEFAULT = "manager/src/map/mapdata.mbtiles";

  public static final String MANAGER_CONFIG_IMAGE_URL_PREFIX =
      "/api/master/configuration/manager/image/";

  protected ManagerIdentityService identityService;
  protected PersistenceService persistenceService;
  protected Path pathPublicRoot;

  private static final Logger LOG = Logger.getLogger(ConfigurationService.class.getName());

  protected Path mapTilesPath;
  protected Path mapSettingsPath;
  protected Path managerConfigPath;
  protected AtomicReference<ManagerAppConfig> managerAppConfig;

  @Override
  public void init(Container container) throws Exception {
    identityService = container.getService(ManagerIdentityService.class);
    persistenceService = container.getService(PersistenceService.class);
    pathPublicRoot =
        Paths.get(
            getString(container.getConfig(), OR_CUSTOM_APP_DOCROOT, OR_CUSTOM_APP_DOCROOT_DEFAULT));
    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new ConfigurationResourceImpl(
                container.getService(TimerService.class), identityService, this));

    // Retrieve default configuration files; Try to find all possible default locations of each
    // file, and then
    // return the first one. Since the stream maintains ordering, we use the first available one,
    // since they're placed
    // below in order of most importance. Throw an Exception if no default file could be found.

    mapSettingsPath =
        Stream.of(
                getPersistedMapConfigPath().toString(),
                getString(
                    container.getConfig(), OR_MAP_SETTINGS_PATH, OR_MAP_SETTINGS_PATH_DEFAULT),
                "/opt/map/mapsettings.json",
                OR_MAP_SETTINGS_PATH_DEFAULT)
            .map(Path::of)
            .map(Path::toAbsolutePath)
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);

    mapTilesPath =
        Stream.of(
                getString(container.getConfig(), OR_MAP_TILES_PATH, OR_MAP_TILES_PATH_DEFAULT),
                "/deployment/map/mapdata.mbtiles",
                "/opt/map/mapdata.mbtiles",
                OR_MAP_TILES_PATH_DEFAULT)
            .map(Path::of)
            .map(Path::toAbsolutePath)
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);

    managerConfigPath =
        Stream.of(getPersistedManagerConfigPath(), pathPublicRoot.resolve("manager_config.json"))
            .map(Path::toAbsolutePath)
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);

    if (mapSettingsPath == null) {
      LOG.warning("Could not find map settings");
      return;
    }

    LOG.info("Configuration Service Used files:");
    LOG.info("\t- manager_config.json: " + managerConfigPath);
    LOG.info("\t- mapsettings.json: " + mapSettingsPath);
    LOG.info(
        "\t- mapdata.mbtiles: "
            + Optional.of(getCustomMapTilesPath(true))
                .map(p -> p.toFile().isDirectory() ? null : p)
                .orElse(mapTilesPath));
  }

  @Override
  public void start(Container container) throws Exception {}

  @Override
  public void stop(Container container) throws Exception {
    /* code not overridden yet */
  }

  @Override
  public String toString() {
    return "ConfigurationService{"
        + "mapTilesPath="
        + mapTilesPath
        + ", mapSettingsPath="
        + mapSettingsPath
        + ", managerConfigPath="
        + managerConfigPath
        + '}';
  }

  public ObjectNode getMapConfig() {
    if (mapSettingsPath == null) {
      return null;
    }
    try {
      return (ObjectNode) ValueUtil.JSON.readTree(mapSettingsPath.toFile());
    } catch (IOException e) {
      LOG.severe("Could not read map_settings.json from " + mapSettingsPath);
    }

    return null;
  }

  public void saveMapConfig(ObjectNode mapConfiguration) throws RuntimeException {
    LOG.log(Level.INFO, "Saving map_settings.json to: " + getPersistedMapConfigPath());
    try {
      Path p = getPersistedMapConfigPath();
      Files.createDirectories(p.getParent());
      Files.writeString(
          p, ValueUtil.JSON.writeValueAsString(mapConfiguration), StandardCharsets.UTF_8);
      // Ensure mapSettingsPath is now pointing to persistence path
      mapSettingsPath = p;
    } catch (Exception exception) {
      String msg = "Error saving map_settings.json: msg=" + exception.getMessage();
      LOG.log(Level.WARNING, msg);
      throw new IllegalStateException(msg, exception);
    }
  }

  public Path getMapTilesPath() {
    return mapTilesPath;
  }

  /**
   * Must be called after {@link #mapTilesPath} is initialized.
   *
   * @return the parent directory path for custom mbtiles, or the custom mbtiles file if
   *     findMBTilesFile is true. If no custom mbtiles file is found or it is the same file as
   *     {@link #mapTilesPath} then the parent directory path is returned (i.e. as if no custom
   *     mbtiles file can be found).
   * @throws IOException if persistence directory cannot be created or read
   */
  public Path getCustomMapTilesPath(boolean findMBTilesFile) throws IOException {
    Path parentDir = getPersistedCustomTilesPath();

    try {
      if (!Files.exists(parentDir)) Files.createDirectories(parentDir);
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Could not create parent directory for custom tiles", e);
      throw e;
    }

    if (!findMBTilesFile) {
      return parentDir;
    }

    // If mapTilesPath has resolved to an mbtiles file in the persistence dir then it isn't
    // considered a custom file
    Path defaultMapTilesFilename =
        Optional.ofNullable(this.mapTilesPath).map(Path::toAbsolutePath).orElse(null);

    return Arrays.stream(
            Objects.requireNonNull(
                parentDir.toFile().listFiles((dir, name) -> name.endsWith(".mbtiles"))))
        .map(File::toPath)
        .filter(p -> !p.toAbsolutePath().equals(defaultMapTilesFilename))
        .findFirst()
        .orElse(parentDir);
  }

  public ManagerAppConfig getManagerConfig() {
    if (managerAppConfig != null) {
      return managerAppConfig.get();
    }
    if (managerConfigPath == null) {
      return null;
    }
    try {
      String managerConfigStr = Files.readString(managerConfigPath, StandardCharsets.UTF_8);
      return ValueUtil.parse(managerConfigStr, ManagerAppConfig.class).orElse(null);
    } catch (Exception e) {
      LOG.severe("Could not read manager_config.json from " + managerConfigPath);
      return null;
    }
  }

  public void saveManagerConfig(ManagerAppConfig managerAppConfig) throws Exception {
    LOG.log(Level.INFO, "Saving manager_config.json to: " + getPersistedManagerConfigPath());
    try {
      // Check references to images
      managerAppConfig = checkAndFixImageReferences(managerAppConfig);
      Path p = getPersistedManagerConfigPath();
      Files.createDirectories(p.getParent());
      Files.writeString(
          p, ValueUtil.JSON.writeValueAsString(managerAppConfig), StandardCharsets.UTF_8);
      // Ensure managerConfigPath is now pointing to persistence path
      managerConfigPath = p;
      if (this.managerAppConfig == null) {
        this.managerAppConfig = new AtomicReference<>(managerAppConfig);
      } else {
        this.managerAppConfig.set(managerAppConfig);
      }
    } catch (Exception exception) {
      String msg = "Error saving manager_config.json: msg=" + exception.getMessage();
      LOG.log(Level.WARNING, msg);
      throw new Exception(msg, exception);
    }
  }

  /**
   * Stores a validated image at the requested relative path, replacing an existing image. Returns
   * the stored relative path. Callers are responsible for enforcing realm authorization.
   */
  public String saveManagerConfigImage(String path, FileInfo fileInfo) throws IOException {
    String filename = normalizeImagePath(path);
    if (fileInfo == null) {
      throw new IllegalArgumentException(
          "Image content is missing; provide the image file contents");
    }
    byte[] data = ImageUploadValidator.decodeAndValidate(filename, fileInfo.getContents());
    Path stored = resolveImagePath(getPersistedManagerConfigImagePath(), filename, true);
    if (Files.isDirectory(stored, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "Image upload path is a directory; provide an image filename");
    }
    // Manager serves these images through its API; other containers do not read this storage.
    // Retain the temporary file's restrictive default permissions after the rename.
    Path temporary = Files.createTempFile(stored.getParent(), ".image-upload-", ".tmp");
    try {
      // Finish writing before replacing the image, so readers do not see a partial upload.
      Files.write(temporary, data, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      replaceManagerConfigImage(temporary, stored);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return getPersistedManagerConfigImagePath()
        .toRealPath()
        .relativize(stored)
        .toString()
        .replace(File.separatorChar, '/');
  }

  /** Replaces a complete image atomically where supported. */
  protected void replaceManagerConfigImage(Path temporary, Path stored) throws IOException {
    try {
      Files.move(
          temporary, stored, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, stored, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public Optional<File> getManagerConfigImage(String filename) {
    try {
      ImageUploadValidator.getMediaType(filename);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    for (Path root :
        new Path[] {getPersistedManagerConfigImagePath(), pathPublicRoot.resolve("images")}) {
      try {
        if (!Files.isDirectory(root)) {
          continue;
        }
        Path path = resolveImagePath(root, filename, false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        return Optional.of(path.toFile());
      } catch (IllegalArgumentException | IOException e) {
        LOG.log(
            Level.FINE,
            "Configuration image rejected for path '" + filename + "': " + e.getMessage());
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static String normalizeImagePath(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Image upload path is missing");
    }
    if (path.startsWith("/images/")) {
      return path.substring(8);
    }
    return path.startsWith("images/") ? path.substring(7) : path;
  }

  // The configured root is trusted and may itself be a symlink. No symlinks below it are allowed.
  // Compare canonical Paths, not strings, and check each descendant before creating or reading it.
  private static Path resolveImagePath(Path root, String filename, boolean createParents)
      throws IOException {
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException(
          "Image path is missing; provide a relative filename including an image extension");
    }
    if (filename.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          "Image path contains a backslash; use '/' to separate relative directories");
    }
    Path relative = Path.of(filename);
    Path base = root.toAbsolutePath().normalize();
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException(
          "Image path must be relative to the configuration image directory; absolute paths are not"
              + " allowed");
    }
    if (relative.normalize().toString().isEmpty()) {
      throw new IllegalArgumentException(
          "Image path must include a filename; it cannot refer to the configuration image directory"
              + " itself");
    }
    for (Path part : relative) {
      if ("..".equals(part.toString())) {
        throw new IllegalArgumentException(
            "Image path contains '..'; parent directory traversal is not allowed");
      }
    }
    if (createParents) {
      Files.createDirectories(base);
    }
    Path realRoot = base.toRealPath();
    Path current = realRoot;
    for (int i = 0; i < relative.getNameCount(); i++) {
      current = current.resolve(relative.getName(i)).normalize();
      if (current.equals(realRoot)) {
        throw new IllegalArgumentException(
            "Image path starts with './'; remove it and use a relative filename or subdirectory");
      }
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException(
            "Image path uses a symbolic link at '%s'; symbolic links are not allowed in the configuration image directory"
                .formatted(relative.subpath(0, i + 1)));
      }
      if (createParents && i < relative.getNameCount() - 1) {
        try {
          Files.createDirectory(current);
        } catch (FileAlreadyExistsException e) {
          if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "Image path parent '%s' is not a directory; each component before the filename must be a directory"
                    .formatted(relative.subpath(0, i + 1)));
          }
        }
      }
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        current = current.toRealPath();
      }
      if (!current.startsWith(realRoot)) {
        throw new IllegalArgumentException(
            "Image path resolves outside the configuration image directory; images must remain"
                + " inside that directory");
      }
    }
    return current;
  }

  protected Path getPersistedManagerConfigPath() {
    return persistenceService.getStorageDir().resolve("manager").resolve("manager_config.json");
  }

  protected Path getPersistedMapConfigPath() {
    return persistenceService.getStorageDir().resolve("manager").resolve("mapsettings.json");
  }

  protected Path getPersistedCustomTilesPath() {
    return persistenceService.getStorageDir().resolve("map");
  }

  protected Path getPersistedManagerConfigImagePath() {
    return this.persistenceService.resolvePath("manager").resolve("images");
  }

  protected ManagerAppConfig checkAndFixImageReferences(ManagerAppConfig managerAppConfig)
      throws IOException {
    Files.createDirectories(getPersistedManagerConfigImagePath());
    if (managerAppConfig.getRealms() != null && !managerAppConfig.getRealms().isEmpty()) {
      managerAppConfig
          .getRealms()
          .values()
          .forEach(
              managerAppRealmConfig -> {
                if (!TextUtil.isNullOrEmpty(managerAppRealmConfig.getLogo())) {
                  managerAppRealmConfig.setLogo(fixImageRef(managerAppRealmConfig.getLogo()));
                }
                if (!TextUtil.isNullOrEmpty(managerAppRealmConfig.getLogoMobile())) {
                  managerAppRealmConfig.setLogoMobile(
                      fixImageRef(managerAppRealmConfig.getLogoMobile()));
                }
                if (!TextUtil.isNullOrEmpty(managerAppRealmConfig.getFavicon())) {
                  managerAppRealmConfig.setFavicon(fixImageRef(managerAppRealmConfig.getFavicon()));
                }
              });
    }
    return managerAppConfig;
  }

  protected String fixImageRef(String image) {
    if (image.isBlank()) {
      return image;
    }
    String filename =
        normalizeImagePath(
            image.startsWith(MANAGER_CONFIG_IMAGE_URL_PREFIX)
                ? image.substring(MANAGER_CONFIG_IMAGE_URL_PREFIX.length())
                : image);
    try {
      Path persisted = resolveImagePath(getPersistedManagerConfigImagePath(), filename, false);
      if (Files.isRegularFile(persisted, LinkOption.NOFOLLOW_LINKS)) {
        return image;
      }
      Optional<File> source = getManagerConfigImage(filename);
      if (source.isEmpty()) {
        LOG.warning("manager_config.json image reference is missing or invalid: " + filename);
        return image;
      }
      // Validate a fallback image once when importing it into persisted configuration assets.
      byte[] data;
      try (var input = Files.newInputStream(source.get().toPath(), LinkOption.NOFOLLOW_LINKS)) {
        data = input.readNBytes(ImageUploadValidator.MAX_FILE_SIZE + 1);
      }
      ImageUploadValidator.validate(filename, data);
      persisted = resolveImagePath(getPersistedManagerConfigImagePath(), filename, true);
      Files.write(persisted, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return MANAGER_CONFIG_IMAGE_URL_PREFIX + filename;
    } catch (IllegalArgumentException | IOException e) {
      LOG.warning(
          "Could not resolve manager_config.json image reference '"
              + filename
              + "': "
              + e.getMessage());
      return image;
    }
  }
}
