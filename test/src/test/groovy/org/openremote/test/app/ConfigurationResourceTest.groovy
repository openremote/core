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
package org.openremote.test.app

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget
import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.model.file.FileInfo
import org.openremote.model.manager.ConfigurationResource
import org.openremote.model.manager.ManagerAppConfig
import org.openremote.model.security.ClientRole
import org.openremote.model.util.ValueUtil
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

import static org.openremote.manager.app.ConfigurationService.MANAGER_CONFIG_IMAGE_URL_PREFIX
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.util.MapAccess.getString

class ConfigurationResourceTest extends Specification implements ManagerContainerTrait {

  private static final String IMAGE_CONTENT_SECURITY_POLICY =
  "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

  @Shared
  ConfigurationResource adminResource

  @Shared
  ConfigurationResource publicResource

  @Shared
  ConfigurationResource nonSuperuserResource

  @Shared
  ResteasyWebTarget publicTarget

  @Shared
  String nonSuperuserId

  @Shared
  String adminToken

  @Shared
  HttpClient rawHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def setupSpec() {
    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a superuser and an admin-role user without the superuser realm role are authenticated"
    adminToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT))
    def username = "configuration-admin-without-superuser"
    def user = keycloakTestSetup.createUser(MASTER_REALM, username, username,
            "Configuration", "Admin", "${username}@openremote.local", true,
            [ClientRole.READ_ADMIN, ClientRole.WRITE_ADMIN] as ClientRole[])
    nonSuperuserId = user.id
    def nonSuperuserToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID,
            username, username)

    and: "the configuration resource proxies are configured"
    adminResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminToken)
            .proxy(ConfigurationResource.class)
    publicTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM)
    publicResource = publicTarget.proxy(ConfigurationResource.class)
    nonSuperuserResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM,
            nonSuperuserToken).proxy(ConfigurationResource.class)
  }

  def cleanupSpec() {
    rawHttpClient.close()
    if (nonSuperuserId != null) {
      container.getService(ManagerIdentityService.class).getIdentityProvider()
              .deleteUser(MASTER_REALM, nonSuperuserId)
    }
  }

  def "Public clients can read the Manager configuration"() {
    when:
    def config = publicResource.getManagerConfig()

    then:
    config != null
    config.realms.containsKey("default")
  }

  def "Superuser can update and read the Manager configuration"() {
    given:
    def original = adminResource.getManagerConfig()
    def updated = ValueUtil.JSON.readValue(ValueUtil.asJSON(original).orElseThrow(),
            ManagerAppConfig.class)
    updated.loadLocales = !original.loadLocales

    when:
    def saved = adminResource.update(null, updated)

    then:
    saved.loadLocales == updated.loadLocales
    publicResource.getManagerConfig().loadLocales == updated.loadLocales

    cleanup:
    if (original != null) {
      adminResource.update(null, original)
    }
  }

  def "Superuser can upload an image and public clients can retrieve it"() {
    given:
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    def png = output.toByteArray()
    def filename = "configuration-resource-test-${UUID.randomUUID()}.png"
    def storedFile = container.getService(PersistenceService.class)
            .resolvePath("manager/images/${filename}")
    def fileInfo = new FileInfo(filename, Base64.encoder.encodeToString(png), true)

    when:
    def path = adminResource.imageUpload(null, filename, fileInfo)
    def response = publicTarget.path("configuration/manager/image/${filename}").request().get()

    then:
    path == MANAGER_CONFIG_IMAGE_URL_PREFIX + filename
    response.withCloseable { r ->
      assert r.status == Response.Status.OK.statusCode
      assert r.mediaType.toString() == "image/png"
      assert r.getHeaderString("X-Content-Type-Options") == "nosniff"
      assert r.getHeaderString("Content-Security-Policy") == IMAGE_CONTENT_SECURITY_POLICY
      assert r.readEntity(byte[].class) == png
      return true
    }

    cleanup:
    Files.deleteIfExists(storedFile)
  }

  def "Superuser can replace a branding image while preserving its configuration reference"() {
    given:
    def original = adminResource.getManagerConfig()
    def updated = ValueUtil.JSON.readValue(ValueUtil.asJSON(original).orElseThrow(), ManagerAppConfig.class)
    def output = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output)
    def filename = "default/configuration-branding-${UUID.randomUUID()}.png"
    def storedFile = container.getService(PersistenceService.class).resolvePath("manager/images/${filename}")
    def file = new FileInfo("logo.png", Base64.encoder.encodeToString(output.toByteArray()), true)
    def replacementImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    replacementImage.setRGB(0, 0, 0xff0000)
    def replacementOutput = new ByteArrayOutputStream()
    ImageIO.write(replacementImage, "png", replacementOutput)
    def replacement = replacementOutput.toByteArray()

    when:
    def path = adminResource.imageUpload(null, filename, file)
    updated.realms.default.logo = path
    adminResource.update(null, updated)
    def replacementPath = adminResource.imageUpload(null, filename,
            new FileInfo("logo.png", Base64.encoder.encodeToString(replacement), true))

    then:
    replacementPath == path
    publicResource.getManagerConfig().realms.default.logo == path
    publicTarget.path(path.replace("/api/master/", "")).request().get().withCloseable { response ->
      assert response.status == Response.Status.OK.statusCode
      assert response.getHeaderString("Content-Security-Policy") == IMAGE_CONTENT_SECURITY_POLICY
      assert response.readEntity(byte[].class) == replacement
      return true
    }

    cleanup:
    adminResource.update(null, original)
    Files.deleteIfExists(storedFile)
  }

  def "Public clients retrieve legacy images with fixed image types and browser content restrictions"() {
    given:
    def storedFile = container.getService(PersistenceService.class).resolvePath("manager/images/legacy-${filename}")
    Files.createDirectories(storedFile.parent)
    Files.write(storedFile, contents.bytes)

    expect:
    // Legacy content is streamed under the type for its allowed image extension.
    publicTarget.path("configuration/manager/image/legacy-${filename}").request().get().withCloseable { response ->
      assert response.status == Response.Status.OK.statusCode
      assert response.mediaType.toString() == mediaType
      assert response.getHeaderString("X-Content-Type-Options") == "nosniff"
      assert response.getHeaderString("Content-Security-Policy") == IMAGE_CONTENT_SECURITY_POLICY
      assert response.readEntity(byte[].class) == contents.bytes
      return true
    }

    cleanup:
    Files.deleteIfExists(storedFile)

    where:
    filename | contents | mediaType
    "logo.svg" | "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>" | "image/svg+xml"
    "logo.SvG" | "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>" | "image/svg+xml"
    "logo.png" | "<html/>" | "image/png"
    "logo.PNG" | "<html/>" | "image/png"
    "logo.jpg" | "legacy JPEG" | "image/jpeg"
    "logo.jpeg" | "legacy JPEG" | "image/jpeg"
    "logo.JpEg" | "legacy JPEG" | "image/jpeg"
    "logo.ico" | "legacy ICO" | "image/vnd.microsoft.icon"
    "logo.ICO" | "legacy ICO" | "image/vnd.microsoft.icon"
  }

  def "Public clients cannot retrieve legacy files with unsupported image extensions"() {
    given:
    def storedFile = container.getService(PersistenceService.class).resolvePath("manager/images/legacy-${filename}")
    Files.createDirectories(storedFile.parent)
    Files.write(storedFile, "<html><a href='https://example.invalid/'>Continue</a></html>".bytes)

    expect:
    publicTarget.path("configuration/manager/image/legacy-${filename}").request().get().withCloseable { response ->
      assert response.status == Response.Status.NOT_FOUND.statusCode
      return true
    }

    cleanup:
    Files.deleteIfExists(storedFile)

    where:
    filename << ["page.html", "page.HTML", "payload.js", "logo.svg.html", "logo", "logo."]
  }

  def "Encoded unsafe image paths are rejected over HTTP without changing stored files"() {
    given:
    def images = container.getService(PersistenceService.class).resolvePath("manager/images").toAbsolutePath().normalize()
    Files.createDirectories(images)
    def filename = "configuration-path-${UUID.randomUUID()}.png"
    def stored = images.resolve(filename)
    def outside = images.parent.resolve(filename)
    def original = png(0)
    def privateContents = "Private file ${filename}".bytes
    Files.write(stored, original)
    Files.write(outside, privateContents)
    def encodedPath = switch (vector) {
              case "lowercase traversal" -> "%2e%2e%2f${filename}"
              case "uppercase traversal" -> "%2E%2E%2F${filename}"
              case "encoded dots" -> "%2e%2e/${filename}"
              case "absolute path" -> URLEncoder.encode(outside.toString(), StandardCharsets.UTF_8).replace("%2F", "%2f")
              default -> "${filename}%00ignored.png"
            }
    def path = method == "GET" ? "image/${encodedPath}": "image?path=${encodedPath}"
    def upload = new FileInfo(filename, Base64.encoder.encodeToString(png(0xff0000)), true)

    when:
    def response = rawImageRequest(method, path, upload)

    then:
    response.statusCode() in (method == "GET" ? [400, 404] : [400])
    Files.readAllBytes(outside) == privateContents
    Files.readAllBytes(stored) == original
    Files.list(images).withCloseable { files ->
      files.filter { it.fileName.toString().contains(filename) }.count()
    } == 1

    cleanup:
    Files.deleteIfExists(stored)
    Files.deleteIfExists(outside)

    where:
    method << ["GET", "POST"]
    combined:
    vector << ["lowercase traversal", "uppercase traversal", "encoded dots", "absolute path", "NUL"]
  }

  def "Nested image paths support literal and encoded separators over HTTP"() {
    given:
    def folder = "configuration-encoded-${UUID.randomUUID()}"
    def path = "${folder}${separator}logo.png"
    def stored = container.getService(PersistenceService.class).resolvePath("manager/images/${folder}/logo.png")
    def contents = png(0xff0000)
    def upload = new FileInfo("logo.png", Base64.encoder.encodeToString(contents), true)

    when:
    def uploaded = rawImageRequest("POST", "image?path=${path}", upload)

    then:
    uploaded.statusCode() == 200
    new String(uploaded.body(), StandardCharsets.UTF_8) == MANAGER_CONFIG_IMAGE_URL_PREFIX + "${folder}/logo.png"
    Files.readAllBytes(stored) == contents

    when:
    def downloaded = rawImageRequest("GET", "image/${path}")

    then:
    downloaded.statusCode() == 200
    downloaded.body() == contents

    cleanup:
    Files.deleteIfExists(stored)
    Files.deleteIfExists(stored.parent)

    where:
    separator << ["/", "%2f", "%2F"]
  }

  def "The removed manager file endpoint returns not found"() {
    given:
    def filename = "configuration-old-endpoint-${UUID.randomUUID()}.png"
    def stored = container.getService(PersistenceService.class).resolvePath("manager/images/${filename}")
    def upload = new FileInfo(filename, Base64.encoder.encodeToString(png(0)), true)

    when:
    def response = rawImageRequest(method, "file?path=${filename}", upload)

    then:
    response.statusCode() == 404
    !Files.exists(stored)

    cleanup:
    Files.deleteIfExists(stored)

    where:
    method << ["GET", "POST"]
  }

  def "Missing Manager configuration images return not found"() {
    when:
    publicResource.getManagerConfigImage("missing-${UUID.randomUUID()}.png")

    then:
    thrown(NotFoundException)
  }

  def "Superuser cannot upload a file with an unsupported image extension"() {
    when:
    adminResource.imageUpload(null, "page.html",
            new FileInfo("page.html", Base64.encoder.encodeToString("<html/>".bytes), true))

    then:
    def failure = thrown(BadRequestException)
    failure.response.readEntity(String.class).contains("Unsupported image extension")
  }

  def "Image upload errors return the detailed validation reason to the client"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><style>rect{fill:url(#paint}</style></svg>"

    when:
    adminResource.imageUpload(null, "logo.svg",
            new FileInfo("logo.svg", Base64.encoder.encodeToString(svg.bytes), true))

    then:
    def failure = thrown(BadRequestException)
    def reason = failure.response.readEntity(String.class)
    reason.contains("SVG <style>:")
    reason.contains("at character 11")
    reason.contains("expected ')'")
    reason.contains("use url(#paint) or url('#paint')")
  }

  def "Admin client roles alone cannot change global Manager configuration or images"() {
    when: "the user updates the Manager configuration"
    nonSuperuserResource.update(null, new ManagerAppConfig())

    then:
    thrown(ForbiddenException)

    when: "the user uploads an image"
    nonSuperuserResource.imageUpload(null, "logo.png", new FileInfo("logo.png", "", false))

    then:
    thrown(ForbiddenException)
  }

  // Construct the URI directly so encoded attack paths reach the server without double encoding.
  private HttpResponse<byte[]> rawImageRequest(String method, String path, FileInfo upload = null) {
    def request = HttpRequest.newBuilder(URI.create("${publicTarget.uri}/configuration/manager/${path}"))
            .timeout(Duration.ofSeconds(10))
    if (method == "POST") {
      request.header("Authorization", "Bearer ${adminToken}")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(ValueUtil.asJSON(upload).orElseThrow()))
    } else {
      request.GET()
    }
    return rawHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
  }

  private static byte[] png(int color) {
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, color)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
  }
}
