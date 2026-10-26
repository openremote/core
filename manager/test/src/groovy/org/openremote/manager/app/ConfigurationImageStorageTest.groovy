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

import org.openremote.model.file.FileInfo
import spock.lang.Specification
import spock.lang.TempDir

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

import static org.openremote.manager.app.ConfigurationService.MANAGER_CONFIG_IMAGE_URL_PREFIX

class ConfigurationImageStorageTest extends Specification {

  @TempDir
  Path directory

  ConfigurationService service
  Path images
  Path publicImages
  byte[] png

  def setup() {
    images = Files.createDirectories(directory.resolve("stored/images"))
    publicImages = Files.createDirectories(directory.resolve("public/images"))
    service = new TestConfigurationService(imageRoot: images, pathPublicRoot: directory.resolve("public"))
    def output = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output)
    png = output.toByteArray()
  }

  def "repeated uploads replace the image at the requested filename without accumulating files"() {
    given:
    Files.createDirectories(images.resolve("default"))
    Files.write(images.resolve("default/logo.png"), png)
    def file = new FileInfo("logo.png", Base64.encoder.encodeToString(png), true)
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, 0xff0000)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    def replacement = output.toByteArray()

    when:
    def first = service.saveManagerConfigImage("default/logo.png", file)
    def second = service.saveManagerConfigImage("/images/default/logo.png",
            new FileInfo("logo.png", Base64.encoder.encodeToString(replacement), true))

    then:
    first == "default/logo.png"
    second == first
    Files.readAllBytes(images.resolve("default/logo.png")) == replacement
    Files.readAllBytes(service.getManagerConfigImage(first).orElseThrow().toPath()) == replacement
    Files.list(images.resolve("default")).withCloseable { it.count() } == 1
  }

  def "a rejected replacement leaves the existing image intact"() {
    given:
    Files.write(images.resolve("logo.png"), png)

    when:
    service.saveManagerConfigImage("logo.png",
            new FileInfo("logo.png", Base64.encoder.encodeToString("not an image".bytes), true))

    then:
    thrown(IllegalArgumentException)
    Files.readAllBytes(images.resolve("logo.png")) == png
    Files.list(images).withCloseable { it.count() } == 1
  }

  def "a failed replacement removes the temporary file and preserves the stored image"() {
    given:
    def stored = images.toRealPath().resolve("logo.png") // toRealPath() is used for macOS compatibility
    def original = "existing image".bytes
    Files.write(stored, original)
    service = Spy(service)
    def moveFailure = new IOException("image replacement failed")
    Path temporary

    when:
    service.saveManagerConfigImage("logo.png",
            new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    1 * service.replaceManagerConfigImage(_ as Path, stored) >> { Path source, Path target ->
      temporary = source
      assert Files.readAllBytes(source) == png
      assert Files.readAllBytes(target) == original
      throw moveFailure
    }
    def failure = thrown(IOException)
    failure.is(moveFailure)
    temporary != null
    !Files.exists(temporary)
    Files.readAllBytes(stored) == original
    Files.list(images).withCloseable { it.count() } == 1
  }

  def "rejects uploads targeting an existing directory"() {
    given:
    def target = Files.createDirectories(images.resolve("default/logo.png"))
    def existingFile = target.resolve("keep.txt")
    if (populated) {
      Files.writeString(existingFile, "existing content")
    }

    when:
    service.saveManagerConfigImage("default/logo.png",
            new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "Image upload path is a directory; provide an image filename"
    Files.isDirectory(target)
    Files.list(target).withCloseable { it.count() } == (populated ? 1 : 0)
    !populated || Files.readString(existingFile) == "existing content"
    Files.list(target.parent).withCloseable { it.count() } == 1

    where:
    populated << [false, true]
  }

  def "upload path errors explain which path rule was violated"() {
    when:
    service.saveManagerConfigImage(filename, new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)
    !failure.message.contains(images.toString())
    Files.list(images).withCloseable { it.count() } == 0

    where:
    filename | reason
    "/logo.png" | "absolute paths are not allowed"
    "default\\logo.png" | "contains a backslash; use '/'"
    "../logo.png" | "contains '..'; parent directory traversal is not allowed"
    "./logo.png" | "starts with './'; remove it"
  }

  def "upload errors identify a parent path that is a file without exposing the storage root"() {
    given:
    Files.write(images.resolve("parent"), png)

    when:
    service.saveManagerConfigImage("parent/logo.png", new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("parent 'parent' is not a directory")
    !failure.message.contains(images.toString())
    Files.readAllBytes(images.resolve("parent")) == png
  }

  def "legacy files with supported image extensions are returned without decoding their contents"() {
    given:
    Files.write(images.resolve(filename), contents.bytes)

    expect:
    Files.readAllBytes(service.getManagerConfigImage(filename).orElseThrow().toPath()) == contents.bytes

    where:
    filename | contents
    "old.svg" | "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
    "old.SvG" | "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
    "old.png" | "<html/>"
    "old.PNG" | "<html/>"
    "old.jpg" | "legacy JPEG"
    "old.JPEG" | "legacy JPEG"
    "old.ICO" | "legacy ICO"
  }

  def "unsupported image extensions are rejected in persisted and public image directories"() {
    given:
    [images, publicImages].each { imageRoot ->
      def file = imageRoot.resolve(filename)
      Files.createDirectories(file.parent)
      Files.write(file, "<html><a href='https://example.invalid/'>Continue</a></html>".bytes)
    }

    expect:
    service.getManagerConfigImage(filename).isEmpty()

    where:
    filename << [
      "old.html",
      "old.HTML",
      "payload.js",
      "logo.gif",
      "logo.webp",
      "logo.svg.html",
      "logo",
      "logo.",
      "nested.svg/page.html"
    ]
  }

  def "legacy images larger than the upload limit are returned without truncation"() {
    given:
    Files.write(images.resolve("large.png"), new byte[ImageUploadValidator.MAX_FILE_SIZE + 1])

    expect:
    service.getManagerConfigImage("large.png").orElseThrow().length() == ImageUploadValidator.MAX_FILE_SIZE + 1
  }

  def "public fallback images are limited to the images directory without decoding"() {
    given:
    Files.write(publicImages.resolve("logo.png"), png)
    Files.write(publicImages.resolve("unsafe.svg"), "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".bytes)
    Files.write(directory.resolve("public/secret.png"), png)

    expect:
    Files.readAllBytes(service.getManagerConfigImage("logo.png").orElseThrow().toPath()) == png
    service.getManagerConfigImage("unsafe.svg").orElseThrow().toPath() == publicImages.resolve("unsafe.svg").toRealPath()
    service.getManagerConfigImage("../secret.png").isEmpty()
  }

  def "paths containing the root as a later substring are still outside the image directory"() {
    given:
    def outside = directory.resolve("outside").resolve(images.toString().substring(1))
    Files.createDirectories(outside)
    Files.write(outside.resolve("logo.png"), png)
    def relative = images.relativize(outside.resolve("logo.png")).toString()

    expect:
    // A string contains() check accepted an outside path containing the full allowed path later on.
    service.getManagerConfigImage(relative).isEmpty()

    when:
    service.saveManagerConfigImage(relative, new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("parent directory traversal is not allowed")
    Files.readAllBytes(outside.resolve("logo.png")) == png
  }

  def "symbolic links below image roots cannot be read or used for uploads"() {
    given:
    def outside = Files.createDirectories(directory.resolve("outside"))
    Files.write(outside.resolve("logo.png"), png)
    Files.createSymbolicLink(images.resolve("linked"), outside)
    Files.write(images.resolve("original.png"), png)
    Files.createSymbolicLink(images.resolve("alias.png"), images.resolve("original.png"))

    expect:
    service.getManagerConfigImage("linked/logo.png").isEmpty()
    service.getManagerConfigImage("alias.png").isEmpty()

    when:
    service.saveManagerConfigImage("linked/logo.png", new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def directoryFailure = thrown(IllegalArgumentException)
    directoryFailure.message.contains("symbolic link at 'linked'")
    directoryFailure.message.contains("symbolic links are not allowed")
    !directoryFailure.message.contains(images.toString())
    Files.list(outside).withCloseable { it.count() } == 1

    when:
    service.saveManagerConfigImage("alias.png", new FileInfo("logo.png", Base64.encoder.encodeToString(png), true))

    then:
    def fileFailure = thrown(IllegalArgumentException)
    fileFailure.message.contains("symbolic link at 'alias.png'")
    !fileFailure.message.contains(images.toString())
    Files.isSymbolicLink(images.resolve("alias.png"))
    Files.readAllBytes(images.resolve("original.png")) == png
  }

  def "copying a fallback branding image validates it and preserves the filename"() {
    given:
    Files.write(publicImages.resolve("logo.png"), png)

    when:
    def reference = service.fixImageRef("images/logo.png")
    def filename = reference.replace(MANAGER_CONFIG_IMAGE_URL_PREFIX, "")

    then:
    filename == "logo.png"
    Files.readAllBytes(service.getManagerConfigImage(filename).orElseThrow().toPath()) == png
    Files.exists(images.resolve("logo.png"))

    and:
    service.fixImageRef("../secret.png") == "../secret.png"
  }

  def "invalid fallback branding images are not imported into persisted storage"() {
    given:
    Files.write(publicImages.resolve(filename), contents.bytes)

    expect:
    service.fixImageRef("images/${filename}") == "images/${filename}"
    !Files.exists(images.resolve(filename))

    where:
    filename | contents
    "old.html" | "<html><script>alert(1)</script></html>"
    "old.svg" | "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
    "old.png" | "<html/>"
  }

  private static class TestConfigurationService extends ConfigurationService {
    Path imageRoot

    @Override
    protected Path getPersistedManagerConfigImagePath() {
      imageRoot
    }
  }
}
