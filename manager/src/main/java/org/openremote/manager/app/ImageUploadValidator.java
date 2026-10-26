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
package org.openremote.manager.app;

import java.util.Locale;
import java.util.Map;
import org.openremote.container.util.CodecUtil;

/**
 * Validates images uploaded for use by the Manager UI.
 *
 * <p>Only PNG, JPEG, ICO, and SVG images are accepted. Raster images are decoded to verify their
 * actual format and are subject to file size and dimension limits. SVG images are parsed with
 * external XML entities disabled and checked against restricted element, metadata, CSS, and
 * reference rules before the original bytes are stored.
 *
 * <p>SVG metadata may contain RDF, Dublin Core, Creative Commons, and Adobe XMP XML, including XMP
 * packet markers. Inkscape, Sodipodi, Serif, Sketch, Illustrator, and Visio editor elements and
 * attributes are also supported. Other foreign XML namespaces, including XHTML, are rejected. SVG
 * image elements may contain Base64 static PNG or JPEG data, validated against raster limits and a
 * combined pixel budget; external image references and nested SVG data are rejected.
 *
 * <p>The filename extension must correspond with the actual image format. Both plain Base64 content
 * and Base64 data URLs are supported.
 */
public final class ImageUploadValidator {

  public static final int MAX_FILE_SIZE = 2 * 1024 * 1024;

  private static final Map<String, String> MEDIA_TYPES =
      Map.of(
          "png", "image/png",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "ico", "image/vnd.microsoft.icon",
          "svg", "image/svg+xml");

  // Base64 requires approximately four characters for every three decoded bytes. Leave some room
  // for a data URL prefix such as "data:image/png;base64,".
  private static final int MAX_ENCODED_SIZE = ((MAX_FILE_SIZE + 2) / 3) * 4 + 256;

  private ImageUploadValidator() {}

  /**
   * Decodes and validates an uploaded image.
   *
   * @param filename filename or relative path including the extension
   * @param contents Base64 encoded image contents, optionally as a data URL
   * @return decoded image bytes
   * @throws IllegalArgumentException if the image is invalid or violates the upload restrictions
   */
  public static byte[] decodeAndValidate(String filename, String contents) {
    if (contents == null || contents.isBlank()) {
      throw new IllegalArgumentException("Image content is empty");
    }

    if (contents.length() > MAX_ENCODED_SIZE) {
      throw new IllegalArgumentException(
          "Encoded image exceeds the " + MAX_FILE_SIZE + " byte upload limit");
    }

    byte[] data = CodecUtil.decodeBase64(contents);

    validate(filename, data);

    return data;
  }

  /**
   * Validates decoded image data.
   *
   * @param filename filename or relative path including the extension
   * @param data decoded image bytes
   * @throws IllegalArgumentException if the image is invalid or violates the upload restrictions
   */
  public static void validate(String filename, byte[] data) {
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Image is empty");
    }

    if (data.length > MAX_FILE_SIZE) {
      throw new IllegalArgumentException(
          "Image is " + data.length + " bytes; maximum is " + MAX_FILE_SIZE + " bytes");
    }

    switch (getMediaType(filename)) {
      case "image/vnd.microsoft.icon" -> RasterImageValidator.validate(data, "ico");
      case "image/jpeg" -> RasterImageValidator.validate(data, "jpeg");
      case "image/png" -> RasterImageValidator.validate(data, "png");
      case "image/svg+xml" -> SvgImageValidator.validate(data);
      default -> throw new IllegalStateException("No validator for image media type");
    }
  }

  /** Returns the fixed media type for an allowed image extension, including legacy reads. */
  static String getMediaType(String filename) {
    String mediaType = MEDIA_TYPES.get(getExtension(filename));
    if (mediaType == null) {
      throw new IllegalArgumentException(
          "Unsupported image extension; allowed extensions are png, jpg, jpeg, ico, and svg");
    }
    return mediaType;
  }

  private static String getExtension(String filename) {
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException("Image filename is missing");
    }

    int slashIndex = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    int dotIndex = filename.lastIndexOf('.');

    if (dotIndex <= slashIndex || dotIndex == filename.length() - 1) {
      throw new IllegalArgumentException("Image file extension is missing");
    }

    return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
  }
}
