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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Validates decoded PNG, JPEG, and ICO uploads, including their format, dimensions, and image data.
 * Animated PNGs are rejected because the PNG decoder does not validate their additional frames.
 */
final class RasterImageValidator {

  static final int MAX_WIDTH = 4096;
  static final int MAX_HEIGHT = 4096;
  static final long MAX_PIXELS = 16_000_000;
  private static final int MAX_IMAGES = 32;
  private static final Set<Integer> DIB_HEADER_SIZES = Set.of(12, 16, 40, 52, 56, 64, 108, 124);
  private static final Set<Integer> DIB_BIT_DEPTHS = Set.of(1, 4, 8, 16, 24, 32);

  private RasterImageValidator() {}

  static void validate(byte[] data, String expectedFormat) {
    validate(data, expectedFormat, MAX_PIXELS);
  }

  /** Validates image data within a remaining pixel budget and returns its decoded pixel count. */
  static long validate(byte[] data, String expectedFormat, long remainingPixels) {
    // Upload bytes are already bounded and in memory; avoid a disk cache and shared settings.
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {

      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

      if (!readers.hasNext()) {
        throw new IllegalArgumentException("No image decoder recognizes the uploaded data");
      }

      ImageReader reader = readers.next();

      try {
        String actualFormat = normalizeFormat(reader.getFormatName());

        if (!expectedFormat.equals(actualFormat)) {
          throw new IllegalArgumentException(
              "Image contains "
                  + actualFormat.toUpperCase(Locale.ROOT)
                  + " data, but its extension requires "
                  + expectedFormat.toUpperCase(Locale.ROOT));
        }

        if ("ico".equals(actualFormat)) {
          validateIcoHeaders(data);
        }
        if ("png".equals(actualFormat)) {
          rejectAnimatedPng(data, 0, data.length);
        }

        reader.setInput(input, false, true);

        int imageCount = "ico".equals(expectedFormat) ? reader.getNumImages(true) : 1;

        if (imageCount <= 0 || imageCount > MAX_IMAGES) {
          throw new IllegalArgumentException(
              "Image contains " + imageCount + " frames; allowed range is 1 to " + MAX_IMAGES);
        }

        long totalPixels = 0;

        for (int i = 0; i < imageCount; i++) {
          int width = reader.getWidth(i);
          int height = reader.getHeight(i);

          if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new IllegalArgumentException(
                "Image frame "
                    + (i + 1)
                    + " is "
                    + width
                    + "x"
                    + height
                    + " pixels; dimensions must be between 1x1 and "
                    + MAX_WIDTH
                    + "x"
                    + MAX_HEIGHT);
          }

          totalPixels += (long) width * height;

          if (totalPixels > MAX_PIXELS) {
            throw new IllegalArgumentException(
                "Image contains "
                    + totalPixels
                    + " pixels across its frames; maximum is "
                    + MAX_PIXELS);
          }
          if (totalPixels > remainingPixels) {
            throw new IllegalArgumentException(
                "Image contains %d pixels across its frames, but the remaining pixel budget is %d; maximum across images is %d pixels"
                    .formatted(totalPixels, remainingPixels, MAX_PIXELS));
          }

          // Force the actual image data to be decoded rather than only trusting its header.
          BufferedImage image = reader.read(i);

          if (image == null) {
            throw new IllegalArgumentException("Image frame " + (i + 1) + " could not be decoded");
          }
          if (image.getWidth() != width || image.getHeight() != height) {
            throw new IllegalArgumentException(
                "Image frame %d decoded to %dx%d pixels, but its header reports %dx%d pixels; dimensions must match"
                    .formatted(i + 1, image.getWidth(), image.getHeight(), width, height));
          }
        }
        return totalPixels;
      } finally {
        reader.dispose();
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException(
          "Could not decode "
              + expectedFormat.toUpperCase(Locale.ROOT)
              + " image data: "
              + e.getMessage(),
          e);
    }
  }

  private static void rejectAnimatedPng(byte[] data, int start, int size) {
    ByteBuffer png = ByteBuffer.wrap(data, start, size).slice().order(ByteOrder.BIG_ENDIAN);
    for (int offset = 8; offset <= size - 12; ) {
      long length = Integer.toUnsignedLong(png.getInt(offset));
      if (length > size - offset - 12) {
        // The decoder reports malformed or truncated PNG data.
        return;
      }
      if (png.getInt(offset + 4) == 0x6163544c) { // acTL
        throw new IllegalArgumentException(
            "Animated PNG images are not supported; export a static PNG so all image data can be validated");
      }
      offset += (int) length + 12;
    }
  }

  // ICO readers report directory dimensions, but allocate from the embedded PNG/DIB header.
  // Check every payload before decoding any frame, using unsigned bounds to avoid overflow.
  private static void validateIcoHeaders(byte[] data) {
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (data.length < 6) {
      throw new IllegalArgumentException(
          "ICO directory header is truncated: image contains %d bytes; at least 6 are required"
              .formatted(data.length));
    }
    if (buffer.getShort(0) != 0 || buffer.getShort(2) != 1) {
      throw new IllegalArgumentException(
          "ICO directory header has reserved value %d and image type %d; expected reserved value 0 and image type 1 (icon)"
              .formatted(
                  Short.toUnsignedInt(buffer.getShort(0)),
                  Short.toUnsignedInt(buffer.getShort(2))));
    }
    int count = Short.toUnsignedInt(buffer.getShort(4));
    int directoryEnd = 6 + count * 16;
    if (count == 0 || count > MAX_IMAGES) {
      throw new IllegalArgumentException(
          "ICO contains %d frames; allowed range is 1 to %d".formatted(count, MAX_IMAGES));
    }
    if (directoryEnd > data.length) {
      throw new IllegalArgumentException(
          "ICO directory for %d frames requires %d bytes, but the image contains only %d bytes"
              .formatted(count, directoryEnd, data.length));
    }

    for (int i = 0; i < count; i++) {
      int entry = 6 + i * 16;
      int width = Byte.toUnsignedInt(data[entry]);
      int height = Byte.toUnsignedInt(data[entry + 1]);
      width = width == 0 ? 256 : width;
      height = height == 0 ? 256 : height;
      long size = Integer.toUnsignedLong(buffer.getInt(entry + 8));
      long offset = Integer.toUnsignedLong(buffer.getInt(entry + 12));
      if (offset < directoryEnd || size < 12 || offset + size > data.length) {
        throw new IllegalArgumentException(
            "ICO frame %d has an invalid payload range: offset %d, size %d bytes; the payload must start at or after byte %d, contain at least 12 bytes, and fit within the %d-byte image"
                .formatted(i + 1, offset, size, directoryEnd, data.length));
      }

      int start = (int) offset;
      long embeddedWidth;
      long embeddedHeight;
      if (buffer.getLong(start) == 0x0a1a0a0d474e5089L) {
        ByteBuffer png = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (size < 33) {
          throw new IllegalArgumentException(
              "ICO frame %d embedded PNG header is truncated: payload contains %d bytes; at least 33 are required"
                  .formatted(i + 1, size));
        }
        if (png.getInt(start + 8) != 13 || png.getInt(start + 12) != 0x49484452) {
          throw new IllegalArgumentException(
              "ICO frame %d embedded PNG starts with chunk type 0x%08x and chunk length %d bytes; expected an IHDR chunk with 13 bytes of image header data"
                  .formatted(
                      i + 1,
                      png.getInt(start + 12),
                      Integer.toUnsignedLong(png.getInt(start + 8))));
        }
        rejectAnimatedPng(data, start, (int) size);
        embeddedWidth = Integer.toUnsignedLong(png.getInt(start + 16));
        embeddedHeight = Integer.toUnsignedLong(png.getInt(start + 20));
      } else {
        int headerSize = buffer.getInt(start);
        if (!DIB_HEADER_SIZES.contains(headerSize)) {
          throw new IllegalArgumentException(
              "ICO frame %d DIB header size is %d bytes; supported sizes are 12, 16, 40, 52, 56, 64, 108, and 124 bytes"
                  .formatted(i + 1, Integer.toUnsignedLong(headerSize)));
        }
        if (headerSize > size) {
          throw new IllegalArgumentException(
              "ICO frame %d DIB header declares %d bytes, but its payload contains only %d bytes"
                  .formatted(i + 1, headerSize, size));
        }
        embeddedWidth =
            headerSize == 12
                ? Short.toUnsignedInt(buffer.getShort(start + 4))
                : buffer.getInt(start + 4);
        int planes = Short.toUnsignedInt(buffer.getShort(start + (headerSize == 12 ? 8 : 12)));
        int bits = Short.toUnsignedInt(buffer.getShort(start + (headerSize == 12 ? 10 : 14)));
        int directoryBits = Short.toUnsignedInt(buffer.getShort(entry + 6));
        // The decoder also uses the directory bit depth for palette allocation.
        if (planes != 1) {
          throw new IllegalArgumentException(
              "ICO frame %d DIB header declares %d color planes; exactly 1 is required"
                  .formatted(i + 1, planes));
        }
        if (!DIB_BIT_DEPTHS.contains(bits)) {
          throw new IllegalArgumentException(
              "ICO frame %d DIB bit depth is %d bits per pixel; supported depths are 1, 4, 8, 16, 24, and 32"
                  .formatted(i + 1, bits));
        }
        if (directoryBits != 0 && directoryBits != bits) {
          throw new IllegalArgumentException(
              "ICO frame %d directory bit depth is %d, but the DIB bit depth is %d; the directory bit depth must be 0 or match the DIB header"
                  .formatted(i + 1, directoryBits, bits));
        }
        long dibHeight = headerSize == 12 ? buffer.getShort(start + 6) : buffer.getInt(start + 8);
        // DIB icons contain the image followed by an equally sized transparency mask.
        if (dibHeight <= 0 || dibHeight % 2 != 0) {
          throw new IllegalArgumentException(
              "ICO frame %d DIB height is %d pixels; it must be positive and even because it includes both the image and its transparency mask"
                  .formatted(i + 1, dibHeight));
        }
        embeddedHeight = dibHeight / 2;
      }

      if (embeddedWidth <= 0
          || embeddedHeight <= 0
          || embeddedWidth > MAX_WIDTH
          || embeddedHeight > MAX_HEIGHT) {
        throw new IllegalArgumentException(
            "ICO frame %d embedded image is %dx%d pixels; dimensions must be between 1x1 and %dx%d"
                .formatted(i + 1, embeddedWidth, embeddedHeight, MAX_WIDTH, MAX_HEIGHT));
      }
      long pixels = embeddedWidth * embeddedHeight;
      if (pixels > MAX_PIXELS) {
        throw new IllegalArgumentException(
            "ICO frame %d embedded image is %dx%d pixels and contains %d pixels; maximum is %d pixels"
                .formatted(i + 1, embeddedWidth, embeddedHeight, pixels, MAX_PIXELS));
      }
      if (embeddedWidth != width || embeddedHeight != height) {
        throw new IllegalArgumentException(
            "ICO frame %d directory reports %dx%d pixels, but the embedded image is %dx%d pixels; dimensions must match"
                .formatted(i + 1, width, height, embeddedWidth, embeddedHeight));
      }
    }
  }

  private static String normalizeFormat(String format) {
    String normalized = format.toLowerCase(Locale.ROOT);

    return switch (normalized) {
      case "jpg" -> "jpeg";
      default -> normalized;
    };
  }
}
