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
package org.openremote.manager.app

import spock.lang.Specification

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream

class RasterImageValidatorTest extends Specification {

  private static final byte[] TEST_IMAGE = [0x52, 0x41, 0x53, 0x54] as byte[]

  ImageReader reader = Mock()
  ImageReaderSpi provider
  IIORegistry registry = IIORegistry.getDefaultInstance()

  def setup() {
    reader.getFormatName() >> "png"
    reader.getHeight(0) >> 1
    provider = Stub(ImageReaderSpi) {
      canDecodeInput(_ as ImageInputStream) >> { ImageInputStream input ->
        input.mark()
        try {
          byte[] signature = new byte[TEST_IMAGE.length]
          input.readFully(signature)
          return Arrays.equals(signature, TEST_IMAGE)
        } finally {
          input.reset()
        }
      }
      createReaderInstance() >> reader
    }
    registry.registerServiceProvider(provider, ImageReaderSpi.class)
  }

  def cleanup() {
    registry.deregisterServiceProvider(provider, ImageReaderSpi.class)
  }

  def "uses a memory cache without changing the shared ImageIO cache preference"() {
    given:
    def useCache = ImageIO.getUseCache()
    reader.getWidth(0) >> 1
    reader.read(0) >> new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)

    when:
    ImageUploadValidator.validate("logo.png", TEST_IMAGE)

    then:
    1 * reader.setInput({ ImageInputStream input ->
      input.isCachedMemory() && !input.isCachedFile()
    }, false, true)
    1 * reader.dispose()
    ImageIO.getUseCache() == useCache
  }

  def "reports #decoderFailure.class.simpleName during #phase as a validation failure"() {
    given:
    reader.getWidth(0) >> {
      if (phase == "header parsing") {
        throw decoderFailure
      }
      return 1
    }
    reader.read(0) >> { throw decoderFailure }

    when:
    ImageUploadValidator.validate("logo.png", TEST_IMAGE)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "Could not decode PNG image data: ${decoderFailure.message}"
    failure.cause.is(decoderFailure)
    1 * reader.dispose()

    where:
    phase | decoderFailure
    "header parsing" | new IllegalStateException("invalid header state")
    "pixel decoding" | new ArrayIndexOutOfBoundsException("invalid sample index")
    "pixel decoding" | new NegativeArraySizeException("negative palette size")
    "pixel decoding" | new IllegalStateException("invalid decoder state")
    "pixel decoding" | new IOException("truncated pixel data")
  }

  def "preserves an IllegalArgumentException reported by the decoder"() {
    given:
    def decoderFailure = new IllegalArgumentException("invalid color model")
    reader.getWidth(0) >> 1
    reader.read(0) >> { throw decoderFailure }

    when:
    ImageUploadValidator.validate("logo.png", TEST_IMAGE)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.is(decoderFailure)
    1 * reader.dispose()
  }

  def "preserves the detailed dimensions validation message"() {
    given:
    reader.getWidth(0) >> 4097

    when:
    ImageUploadValidator.validate("logo.png", TEST_IMAGE)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "Image frame 1 is 4097x1 pixels; dimensions must be between 1x1 and 4096x4096"
    failure.cause == null
    0 * reader.read(_)
    1 * reader.dispose()
  }
}
