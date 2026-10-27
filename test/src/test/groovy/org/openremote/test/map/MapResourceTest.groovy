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
package org.openremote.test.map

import com.fasterxml.jackson.databind.node.ObjectNode
import groovy.json.JsonSlurper
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget
import org.openremote.manager.map.MapService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.model.manager.MapConfig
import org.openremote.model.map.MapResource
import org.openremote.model.security.ClientRole
import org.openremote.model.util.ValueUtil
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.file.Files
import java.nio.file.Path

import static org.openremote.model.util.MapAccess.getString
import static org.openremote.manager.security.ManagerIdentityProvider.*
import static org.openremote.model.Constants.*

// TODO: Remove this once map service is removed and we fallback to standalone tile server
class MapResourceTest extends Specification implements ManagerContainerTrait {

  public static final Path MAPDATA_MBTILES_PATH = Path.of("manager/src/map/mapdata.mbtiles")

  @Shared
  static ResteasyWebTarget clientTarget

  @Shared
  static MapResource mapResource

  def setupSpec() {
    given: "the bundled map tiles are available"
    verifyMapTiles()

    and: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())

    and: "an access_token is retrieved"
    def realm = MASTER_REALM
    def accessToken = authenticate(
            container,
            realm,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "a test client target set"
    clientTarget = getClientApiTarget(serverUri(serverPort), realm, accessToken)

    and: "the map resource is configured"
    mapResource = clientTarget.proxy(MapResource.class)
  }

  private static void verifyMapTiles() {
    def message = """
    Invalid or missing map tiles file: ${MAPDATA_MBTILES_PATH}
    Ensure Git LFS is installed and run:
      git lfs install
      git lfs pull
  """.stripIndent()

    assert Files.isRegularFile(MAPDATA_MBTILES_PATH) : message

    def header = Files.newInputStream(MAPDATA_MBTILES_PATH).withCloseable {
      it.readNBytes(16)
    }
    assert new String(header, "US-ASCII") ==
    "SQLite format 3\u0000" : message
  }

  def "Retrieve map settings"() {
    when: "a request has been made"
    def mapSettings = mapResource.getSettings(null)

    then: "settings should be not-null"
    mapSettings != null

    and: "JSON content is valid"
    def json = new JsonSlurper().parseText(ValueUtil.asJSON(mapSettings).orElse("null"))
    json.options != null
    json.options.default != null
    json.options.default.center.size() >= 2
    json.options.default.bounds.size() == 4
    json.sources != null
    json.layers.size() > 0

    when: "custom sprite and glyphs are saved"
    mapSettings = ValueUtil.parse("""{
            "options" : { "default": {} },
            "sources" : {
                "vector_tiles" : {
                    "type" : "vector" ,
                    "tiles" : [ "https://example.com/tileset/{z}/{x}/{y}.mvt" ],
                    "custom" : true
                }
            },
            "sprite": "https://api.example.com/maps/streets/sprite",
            "glyphs": "https://api.example.com/fonts/{fontstack}/{range}.pbf"
        }""", MapConfig.class)
    (ObjectNode)mapResource.saveSettings(null, mapSettings.get())

    and: "retrieve map settings"
    mapSettings = mapResource.getSettings(null)

    then: "JSON content should contain custom sprite and glyphs URLs"
    def json1 = new JsonSlurper().parseText(ValueUtil.asJSON(mapSettings).orElse("null"))
    json1.sprite == "https://api.example.com/maps/streets/sprite"
    json1.glyphs == "https://api.example.com/fonts/{fontstack}/{range}.pbf"

    when: "saving without sprite and glyphs"
    mapSettings = ValueUtil.parse("""{
            "options" : { "default": {} },
            "sources" : {
                "vector_tiles" : {
                    "type" : "vector" ,
                    "tiles" : [ "https://example.com/tileset/{z}/{x}/{y}.mvt" ],
                    "custom" : true
                }
            }
        }""", MapConfig.class)
    (ObjectNode)mapResource.saveSettings(null, mapSettings.get())

    and: "retrieve map settings"
    mapSettings = mapResource.getSettings(null)

    then: "JSON content should contain default sprite and glyphs URLs"
    def json2 = new JsonSlurper().parseText(ValueUtil.asJSON(mapSettings).orElse("null"))
    json2.sprite == "http://127.0.0.1:" + serverPort + MapService.MAP_SHARED_DATA_BASE_URI + "/" + MapService.DEFAULT_SPRITE_PATH
    json2.glyphs == "http://127.0.0.1:" + serverPort + MapService.MAP_SHARED_DATA_BASE_URI + "/fonts/" + MapService.DEFAULT_GLYPHS_PATH
  }

  def "Retrieve a vector tile"() {
    when: "the zoom-zero tile is requested from the bundled MBTiles file"
    def response = mapResource.getTile(0, 0, 0)

    then: "the compressed vector tile is returned"
    response.withCloseable { r ->
      assert r.status == Response.Status.OK.statusCode
      assert r.getHeaderString("Content-Encoding") == "gzip"
      assert r.readEntity(byte[].class).length> 0
      return true
    }
  }

  def "Upload custom map tiles"() {
    given:
    def mapService = container.getService(MapService.class)
    def originalLimit = mapService.customMapLimit

    when: "valid tiles are uploaded"
    def response = clientTarget
            .path("map/upload")
            .queryParam("filename", "eindhoven.mbtiles")
            .request()
            .post(Entity.entity(Files.readAllBytes(MAPDATA_MBTILES_PATH), MediaType.APPLICATION_OCTET_STREAM_TYPE))

    then: "the custom tiles should be saved"
    new PollingConditions(timeout: 20, delay: 1).eventually {
      assert Files.exists(Path.of("tmp/map/eindhoven.mbtiles"))
    }

    and: "and the mapsettings match with the custom mbtiles metadata"
    def json = ValueUtil.JSON.readValue(response.getEntity(), MapConfig.class)
    json.options != null
    json.options.default != null
    json.options.default.center.size() == 3
    json.options.default.bounds.size() == 4
    json.sources != null

    and: "the custom-map status reports the uploaded file"
    def customMapInfo = mapResource.getCustomMapInfo()
    customMapInfo.get("filename").asText() == "eindhoven.mbtiles"
    customMapInfo.get("limit").asInt() == originalLimit

    when: "the custom map is deleted"
    mapResource.deleteMap(null)

    then: "the uploaded file and custom-map status are cleared"
    !Files.exists(Path.of("tmp/map/eindhoven.mbtiles"))
    mapResource.getCustomMapInfo().get("filename").isNull()

    when: "invalid tiles are uploaded"
    response = clientTarget
            .path("map/upload")
            .queryParam("filename", "eindhoven.mbtiles")
            .request()
            .post(Entity.entity(Files.readAllBytes(Path.of("manager/src/map/mapsettings.json")), MediaType.APPLICATION_OCTET_STREAM_TYPE))

    then: "an internal server error should occur"
    response.withCloseable { r ->
      assert r.status == 500
      return true
    }

    when: "illegal filename was specified"
    response = clientTarget
            .path("map/upload")
            .queryParam("filename", "../mapdata.mbtiles")
            .request()
            .post(Entity.entity(new byte[16], MediaType.APPLICATION_OCTET_STREAM_TYPE))

    then: "bad request error should occur"
    response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "the tiles are too large"
    container.getService(MapService.class).customMapLimit = 10
    response = clientTarget
            .path("map/upload")
            .queryParam("filename", "mapdata.mbtiles")
            .request()
            .post(Entity.entity(new byte[11], MediaType.APPLICATION_OCTET_STREAM_TYPE))

    then: "request entity too large error should occur"
    response.withCloseable { r ->
      assert r.status == 413
      return true
    }

    cleanup:
    mapService.customMapLimit = originalLimit
  }

  def "Configure custom tile server URL"() {
    when: "mapsettings with a valid tile server URL is configured"
    def mapSettings = ValueUtil.parse("""{
            "options" : { "default": {} },
            "sources" : {
                "vector_tiles" : {
                    "type" : "vector" ,
                    "url" : "https://example.com/tileset/tile.json",
                    "tiles" : [ "https://example.com/tileset/{z}/{x}/{y}.mvt" ],
                    "custom" : true
                }
            },
            "sprite": "https://api.example.com/maps/streets/sprite",
            "glyphs": "https://api.example.com/fonts/{fontstack}/{range}.pbf"
        }""", MapConfig.class)
    def savedSettings = (ObjectNode)mapResource.saveSettings(null, mapSettings.get())

    then: "the custom tile server URL should be returned"
    savedSettings.get("sources").get("vector_tiles").get("url").textValue() == "https://example.com/tileset/tile.json"
    savedSettings.get("sources").get("vector_tiles").get("tiles").get(0).textValue() == "https://example.com/tileset/{z}/{x}/{y}.mvt"
    savedSettings.get("sprite").textValue() == "https://api.example.com/maps/streets/sprite"
    savedSettings.get("glyphs").textValue() == "https://api.example.com/fonts/{fontstack}/{range}.pbf"
    savedSettings.get("layers").isArray() && savedSettings.get("layers").size() > 0

    when: "an invalid tile server URL is configured"
    mapSettings = ValueUtil.parse("""{
            "options" : { "default": {} },
            "sources" : {
                "vector_tiles" : {
                    "type" : "vector" ,
                    "tiles" : [ "https://example.com/tileset.mvt" ],
                    "custom" : false
                }
            }
        }""", MapConfig.class)
    savedSettings = (ObjectNode)mapResource.saveSettings(null, mapSettings.get())

    then: "the URL should be reset back to normal"
    !savedSettings.get("sources").get("vector_tiles").has("tiles")
    savedSettings.get("sources").get("vector_tiles").get("url").textValue() == MapService.DEFAULT_VECTOR_TILES_URL
    savedSettings.get("sprite").textValue() == MapService.DEFAULT_SPRITE_PATH
    savedSettings.get("glyphs").textValue() == MapService.DEFAULT_GLYPHS_PATH
    savedSettings.get("layers").isArray() && savedSettings.get("layers").size() > 0
  }

  def "Admin client roles alone cannot change global map settings"() {
    given: "a master realm user has admin client roles but no superuser realm role"
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def username = "map-admin-without-superuser"
    def user = keycloakTestSetup.createUser(MASTER_REALM, username, username, "Map", "Admin",
            "${username}@openremote.local", true,
            [ClientRole.READ_ADMIN, ClientRole.WRITE_ADMIN] as ClientRole[])
    def token = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, username, username)
    def restrictedMapResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, token)
            .proxy(MapResource.class)

    when: "the user saves map settings"
    restrictedMapResource.saveSettings(null, new MapConfig())

    then:
    thrown(ForbiddenException)

    when: "the user uploads custom map tiles"
    restrictedMapResource.uploadMap(null, "map.mbtiles")

    then:
    thrown(ForbiddenException)

    when: "the user deletes custom map tiles"
    restrictedMapResource.deleteMap(null)

    then:
    thrown(ForbiddenException)

    cleanup:
    if (user != null) {
      identityProvider.deleteUser(MASTER_REALM, user.id)
    }
  }
}
