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
import spock.lang.Timeout

import java.awt.image.BufferedImage
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

import static org.openremote.manager.app.ConfigurationService.MANAGER_CONFIG_IMAGE_URL_PREFIX

class ImageUploadValidatorTest extends Specification {

  def "decodes and validates a PNG upload"() {
    given:
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    def png = output.toByteArray()
    def contents = "data:image/png;base64," + Base64.encoder.encodeToString(png)

    expect:
    ImageUploadValidator.decodeAndValidate("logo.png", contents) == png
  }

  def "rejects unsupported extensions even when the bytes are a valid image"() {
    given:
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    def contents = Base64.encoder.encodeToString(output.toByteArray())

    when:
    ImageUploadValidator.decodeAndValidate("logo.$extension", contents)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("Unsupported image extension")

    where:
    extension << ["html", "js", "gif", "svgz"]
  }

  def "rejects non-raster content disguised with a raster extension"() {
    when:
    ImageUploadValidator.validate(filename, content.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("No image decoder recognizes")

    where:
    filename | content
    "logo.png" | "<!doctype html><script>alert(1)</script>"
    "logo.jpg" | "alert('not an image')"
    "logo.ico" | "<svg xmlns='http://www.w3.org/2000/svg'/>"
  }

  def "rejects non-SVG content disguised with an SVG extension"() {
    when:
    ImageUploadValidator.validate("logo.svg", content.getBytes(StandardCharsets.UTF_8))

    then:
    thrown(IllegalArgumentException)

    where:
    content << [
      "<html xmlns='http://www.w3.org/1999/xhtml'><script>alert(1)</script></html>",
      "alert('not an SVG')"
    ]
  }

  def "accepts SVG XML 1.0 uploads in supported encodings"() {
    given:
    def declaration = declaredEncoding == null ? "" : "<?xml version='1.0' encoding='${declaredEncoding}'?>"
    def svg = (bom ? "\uFEFF" : "") + declaration + "<svg xmlns='http://www.w3.org/2000/svg'><text>Café 日本</text></svg>"
    def data = svg.getBytes(Charset.forName(encoding))

    expect:
    ImageUploadValidator.decodeAndValidate("logo.svg", Base64.encoder.encodeToString(data)) == data

    where:
    encoding | declaredEncoding | bom
    "UTF-8" | null | false
    "UTF-8" | "UTF-8" | false
    "UTF-8" | "utf-8" | true
    "UTF-8" | null | true
    "UTF-16BE" | null | true
    "UTF-16LE" | null | true
    "UTF-16BE" | "UTF-16" | true
    "UTF-16LE" | "utf-16" | true
    "UTF-16BE" | "UTF-16BE" | false
    "UTF-16LE" | "UTF-16LE" | false
    "UTF-16LE" | "utf-16le" | true
  }

  def "rejects SVG XML 1.1 uploads"() {
    given:
    def svg = "<?xml version='1.1' encoding='${encoding}'?><svg xmlns='http://www.w3.org/2000/svg'/>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(Charset.forName(encoding)))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "SVG XML version must be 1.0"

    where:
    encoding << ["UTF-8", "UTF-16"]
  }

  def "rejects SVG XML encodings outside UTF-8 and UTF-16"() {
    given:
    def text = encoding == "ISO-2022-JP" ? "日本" : "Test"
    def declaration = declaredEncoding == null ? "<?xml version='1.0'?>" : "<?xml version='1.0' encoding='${declaredEncoding}'?>"
    def svg = (bom ? "\uFEFF" : "") + declaration + "<svg xmlns='http://www.w3.org/2000/svg'><text>${text}</text></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(Charset.forName(encoding)))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "SVG XML encoding must be UTF-8 or UTF-16"

    where:
    encoding | declaredEncoding | bom
    "UTF-32BE" | "UTF-32" | false
    "UTF-32LE" | "UTF-32LE" | false
    "UTF-32BE" | null | false
    "UTF-32LE" | null | false
    "IBM037" | "IBM037" | false
    "ISO-2022-JP" | "ISO-2022-JP" | false
    "ISO-8859-1" | "ISO-8859-1" | false
    "UTF-8" | "ISO-2022-JP" | true
  }

  def "rejects raster images whose extension does not match their content"() {
    given:
    def image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)

    when:
    ImageUploadValidator.validate("logo.jpg", output.toByteArray())

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "Image contains PNG data, but its extension requires JPEG"
  }

  def "reports dimensions and limits for an oversized raster image"() {
    given:
    def image = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB)
    def output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)

    when:
    ImageUploadValidator.validate("logo.png", output.toByteArray())

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("4097x1 pixels")
    failure.message.contains("4096x4096")
  }

  def "rejects CSS escapes that hide external URLs"() {
    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    // The escaped function name used to bypass the literal url(...) check.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("CSS escapes (backslashes) are not allowed")

    where:
    svg << [
      "<svg xmlns='http://www.w3.org/2000/svg'><style>rect{fill:u\\72l(https://example.com/image.png)}</style><rect width='1' height='1'/></svg>",
      "<svg xmlns='http://www.w3.org/2000/svg'><rect width='1' height='1' style='fill:u\\72l(https://example.com/image.png)'/></svg>"
    ]
  }

  def "accepts local CSS fragment references"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><style>rect{fill:url(#paint)}</style><defs><linearGradient id='paint'><stop offset='0%' stop-color='red'/></linearGradient></defs><rect width='1' height='1' style='stroke:url(#paint)'/></svg>"

    expect:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8)) == null
  }

  def "accepts SVG images containing validated embedded PNG and JPEG data"() {
    given:
    def encoded = Base64.encoder.encodeToString(raster(format))
    def wrapped = encoded.toList().collate(40).collect { it.join() }.join("\n ")
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
      <image width="1" height="1" ${attribute}="data:image/${format};base64,${wrapped}"/>
    </svg>"""

    expect:
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null

    where:
    format << ["png", "jpeg"]
    combined:
    attribute << ["href", "xlink:href"]
  }

  def "rejects external and unsupported image references inside SVGs"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
      <image width="1" height="1" ${attribute}="${reference}"/>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG <image> attribute '${attribute}'")
    failure.message.contains("embedded images must use data:image/png;base64, or data:image/jpeg;base64,")

    where:
    attribute << ["href", "xlink:href"]
    combined:
    reference << [
      "https://example.invalid/logo.png",
      "//example.invalid/logo.png",
      MANAGER_CONFIG_IMAGE_URL_PREFIX + "logo.png",
      "images/logo.png",
      "#another-image",
      "javascript:alert(1)",
      "file:///tmp/logo.png",
      "data:image/svg+xml;base64,PHN2Zy8+",
      "data:text/html;base64,PHNjcmlwdD4=",
      "data:image/gif;base64,R0lGODlh",
      "data:image/png,%89PNG",
      "data:image/png;charset=utf-8;base64,aW52YWxpZA=="
    ]
  }

  def "embedded SVG images must match the advertised raster format and decode successfully"() {
    given:
    def payload = switch (content) {
              case "jpeg" -> raster("jpeg")
              case "png" -> raster("png")
              case "html" -> "<html><script>alert(1)</script></html>".bytes
              default -> Arrays.copyOf(raster("png"), 33)
            }
    def encoded = Base64.encoder.encodeToString(payload)
    def svg = """<svg xmlns="http://www.w3.org/2000/svg">
      <image href="data:image/${format};base64,${encoded}"/>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG <image> attribute 'href': embedded")
    failure.message.contains(reason)

    where:
    format | content | reason
    "png" | "jpeg" | "Image contains JPEG data"
    "jpeg" | "png" | "Image contains PNG data"
    "png" | "html" | "No image decoder recognizes"
    "png" | "truncated" | "Could not decode PNG image data"
  }

  def "embedded SVG image errors describe invalid Base64 data"() {
    when:
    ImageUploadValidator.validate("logo.svg",
            "<svg xmlns='http://www.w3.org/2000/svg'><image href='data:image/png;base64,${encoded}'/></svg>".bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG <image> attribute 'href': embedded PNG image data")
    failure.message.contains(reason)

    where:
    encoded | reason
    "" | "is empty"
    "%%%" | "is not valid Base64"
    "aW52YWxpZA==!" | "is not valid Base64"
  }

  def "both SVG image references are checked even when one shadows the other"() {
    given:
    def reference = "data:image/png;base64," + Base64.encoder.encodeToString(raster("png"))
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
      <image href="${externalHref ? 'https://example.invalid/logo.png' : reference}"
             xlink:href="${externalHref ? reference : 'https://example.invalid/logo.png'}"/>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("external URLs and other embedded formats are not allowed")

    where:
    externalHref << [true, false]
  }

  def "embedded SVG images retain event handler and CSS restrictions"() {
    given:
    def encoded = Base64.encoder.encodeToString(raster("png"))
    def svg = """<svg xmlns="http://www.w3.org/2000/svg">
      <image href="data:image/png;base64,${encoded}" ${attributes}/>${content}
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    attributes | content | reason
    "onload='alert(1)'" | "" | "event handlers are not allowed"
    "style='fill:u\\72l(//example.invalid/paint.svg#paint)'" | "" | "CSS escapes"
    "xml:base='https://example.invalid/'" | "" | "base URIs are not allowed"
    "" | "<script>alert(1)</script>" | "Unsupported SVG element: script"
    "" | "<use href='data:image/png;base64,aW52YWxpZA=='/>" | "local fragment starting with '#'"
  }

  def "embedded SVG images obey raster dimensions before decoding"() {
    given:
    def header = Arrays.copyOf(raster("png"), 33)
    ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(16, 4097)
    def encoded = Base64.encoder.encodeToString(header)

    when:
    ImageUploadValidator.validate("logo.svg",
            "<svg xmlns='http://www.w3.org/2000/svg'><image width='1' height='1' href='data:image/png;base64,${encoded}'/></svg>".bytes)

    then:
    // Display dimensions must not hide the dimensions used for raster allocation.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("embedded PNG image: Image frame 1 is 4097x1 pixels")
    failure.message.contains("4096x4096")
  }

  def "embedded SVG images share one pixel budget checked before decoding"() {
    given:
    def first = Base64.encoder.encodeToString(raster("png", 4000, 2000))
    def header = Arrays.copyOf(raster("png"), 33)
    ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(16, 4000).putInt(20, 2001)
    def second = Base64.encoder.encodeToString(header)
    def svg = """<svg xmlns="http://www.w3.org/2000/svg">
      <image href="data:image/png;base64,${first}"/>
      <g><image href="data:image/png;base64,${second}"/></g>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    // The second header has no image data: budget rejection must happen before decoding it.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("remaining pixel budget is 8000000")
    failure.message.contains("maximum across images is 16000000 pixels")
  }

  def "accepts SVGs with up to 32 embedded image references"() {
    given:
    def reference = "data:image/png;base64," + Base64.encoder.encodeToString(raster("png"))
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'>" +
            "<image href='${reference}'/>".repeat(32) + "</svg>"

    expect:
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "rejects SVGs with more than 32 embedded image references"() {
    given:
    def reference = "data:image/png;base64," + Base64.encoder.encodeToString(raster("png"))
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'>" +
            "<image href='${reference}'/>".repeat(32) +
            "<g><image href='${reference}'/></g></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("more than 32 embedded image references")
  }

  def "rejects PNG animation frames in #location"() {
    given:
    def png = raster("png")
    def animation = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .putInt(8).putInt(0x6163544c).putInt(2).putInt(0).putInt(0).array()
    def crc = new java.util.zip.CRC32()
    crc.update(animation, 4, 12)
    ByteBuffer.wrap(animation).order(ByteOrder.BIG_ENDIAN).putInt(16, (int) crc.value)
    def output = new ByteArrayOutputStream()
    output.write(png, 0, 33)
    output.write(animation)
    output.write(png, 33, png.length - 33)
    def animated = output.toByteArray()

    when:
    ImageUploadValidator.validate(filename, wrap(animated))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("Animated PNG images are not supported")

    where:
    location | filename | wrap
    "a PNG upload" | "logo.png" | { byte[] payload -> payload }
    "an SVG image" | "logo.svg" | { byte[] payload ->
      def encoded = Base64.encoder.encodeToString(payload)
      "<svg xmlns='http://www.w3.org/2000/svg'><image href='data:image/png;base64,${encoded}'/></svg>".bytes
    }
    "the first ICO entry" | "logo.ico" | { byte[] payload -> ico(payload, 1, 1) }
    "an ICO entry after a static PNG" | "logo.ico" | { byte[] payload ->
      ico([raster("png"), payload], 1, 1)
    }
    "an ICO entry after a DIB" | "logo.ico" | { byte[] payload -> ico([dib(1, 2), payload], 1, 1) }
  }

  def "rejects CSS image functions that accept string URLs"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><rect width='1' height='1' style=\"mask-image:image-set('https://example.com/image.png')\"/></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG <rect> attribute 'style'")
    failure.message.contains("image-set(")
  }

  def "rejects CSS src() references"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'>${body}</svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.startsWith(context)
    failure.message.contains("CSS construct 'src(' is not allowed")

    where:
    body | context
    "<style>rect{fill:src('paint.svg#paint')}</style>" | "SVG <style>:"
    "<style>rect{fill:SRC(var(--paint))}</style>" | "SVG <style>:"
    "<rect style=\"fill:src('paint.svg#paint')\"/>" | "SVG <rect> attribute 'style':"
    "<rect style=\"fill:SRC(var(--paint))\"/>" | "SVG <rect> attribute 'style':"
    "<rect fill=\"src('paint.svg#paint')\"/>" | "SVG <rect> attribute 'fill':"
    "<rect fill=\"SRC(var(--paint))\"/>" | "SVG <rect> attribute 'fill':"
  }

  def "reports the attribute and rule for an external SVG reference"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><use href='https://example.com/icon.svg#shape'/></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG <use> attribute 'href'")
    failure.message.contains("local fragment starting with '#'")
    !failure.message.contains("example.com")
  }

  def "rejects active foreign XML inside SVG metadata"() {
    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("SVG metadata element <script> uses an unsupported namespace")

    where:
    svg << [
      '''<svg xmlns="http://www.w3.org/2000/svg"><metadata>
        <script xmlns="http://www.w3.org/1999/xhtml">document.documentElement.setAttribute('data-executed', 'yes')</script>
      </metadata></svg>''',
      '''<svg xmlns="http://www.w3.org/2000/svg"><metadata>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <script xmlns="http://www.w3.org/1999/xhtml">document.documentElement.setAttribute('data-executed', 'yes')</script>
        </rdf:RDF>
      </metadata></svg>'''
    ]
  }

  def "accepts standard descriptive SVG metadata"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg">
      <metadata>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
          <rdf:Description rdf:about=""><dc:title>Logo</dc:title></rdf:Description>
        </rdf:RDF>
      </metadata>
    </svg>'''

    expect:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8)) == null
  }

  def "accepts Creative Commons work metadata"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg">
      <metadata>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:cc="${ccNamespace}">
          <cc:Work rdf:about="">
            <dc:format>image/svg+xml</dc:format>
          </cc:Work>
        </rdf:RDF>
      </metadata>
    </svg>"""

    expect:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8)) == null

    where:
    ccNamespace << ["http://creativecommons.org/ns#", "http://web.resource.org/cc/"]
  }

  def "reports the position of malformed SVG XML"() {
    when:
    ImageUploadValidator.validate("logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'><rect></svg>".getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.startsWith("Malformed SVG XML at line 1, column ")
  }

  def "accepts supported editor attributes on SVG elements"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:editor="${namespace}" editor:version="1.4">
      <g editor:label="Logo" editor:groupmode="layer">
        <path editor:nodetypes="cccc" d="M0 0H1V1H0Z"/>
      </g>
    </svg>"""

    expect:
    // Namespace URIs identify editor metadata, regardless of the chosen XML prefix.
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null

    where:
    namespace << [
      "http://www.inkscape.org/namespaces/inkscape",
      "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd",
      "http://www.serif.com/",
      "http://www.bohemiancoding.com/sketch/ns",
      "http://ns.adobe.com/AdobeIllustrator/10.0/",
      "http://ns.adobe.com/Graphs/1.0/",
      "http://schemas.microsoft.com/visio/2003/SVGExtensions/"
    ]
  }

  def "accepts Illustrator layers, graph attributes and export metadata"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:i="http://ns.adobe.com/AdobeIllustrator/10.0/"
                     xmlns:graph="http://ns.adobe.com/Graphs/1.0/"
                     xmlns:v="http://ns.adobe.com/Variables/1.0/"
                     xmlns:sfw="http://ns.adobe.com/SaveForWeb/1.0/"
                     viewBox="0 0 20 20" i:viewOrigin="0 20" i:pageBounds="0 20 20 0">
      <metadata>
        <v:variableSets><v:variableSet varSetName="Logo"><v:variables/></v:variableSet></v:variableSets>
        <sfw:sfw><sfw:slices><sfw:sliceSourceBounds x="0" y="0" width="20" height="20"/></sfw:slices></sfw:sfw>
      </metadata>
      <g i:layer="yes" i:dimmedPercent="50" i:rgbTrio="#4F008000FFFF">
        <rect width="20" height="20" i:knockout="Off" graph:whichSeries="0"/>
      </g>
    </svg>'''

    expect:
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "accepts Visio document, page, layer and text metadata"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:v="http://schemas.microsoft.com/visio/2003/SVGExtensions/"
                     viewBox="0 0 20 20" xml:space="preserve">
      <v:documentProperties v:langID="1033" v:viewMarkup="false">
        <v:userDefs><v:ud v:nameU="msvNoAutoConnect" v:val="VT0(1):26"/></v:userDefs>
      </v:documentProperties>
      <g v:groupContext="foregroundPage">
        <v:pageProperties v:drawingScale="1" v:pageScale="1"/>
        <v:layer v:name="Logo" v:index="0"/>
        <g v:mID="1" v:groupContext="shape" v:layerMember="0">
          <v:textBlock v:margins="rect(4,4,4,4)"/>
          <v:textRect cx="10" cy="10" width="20" height="20"/>
          <rect width="20" height="20" fill="red"/>
          <text x="0" y="10" v:langID="1033"><v:paragraph v:horizAlign="1"/><v:tabList/>Logo</text>
        </g>
      </g>
    </svg>'''

    expect:
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "accepts XMP packet metadata with document history and descriptive URLs"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20">
      <metadata>
        <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                   xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
                   xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/"
                   xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
                   xmlns:illustrator="http://ns.adobe.com/illustrator/1.0/"
                   xmlns:stRef="http://ns.adobe.com/xap/1.0/sType/ResourceRef#"
                   xmlns:stEvt="http://ns.adobe.com/xap/1.0/sType/ResourceEvent#"
                   xmlns:stDim="http://ns.adobe.com/xap/1.0/sType/Dimensions#">
            <rdf:Description rdf:about="" xmp:CreatorTool="Adobe Illustrator"
                             xmpMM:DocumentID="xmp.did:logo">
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Logo</rdf:li></rdf:Alt></dc:title>
              <rdfs:label>Company logo</rdfs:label>
              <xmp:CreateDate>2026-10-07T12:00:00Z</xmp:CreateDate>
              <xmpMM:DerivedFrom rdf:parseType="Resource">
                <stRef:documentID>xmp.did:original</stRef:documentID>
              </xmpMM:DerivedFrom>
              <xmpMM:History><rdf:Seq><rdf:li rdf:parseType="Resource">
                <stEvt:action>saved</stEvt:action>
                <stEvt:softwareAgent>Adobe Illustrator</stEvt:softwareAgent>
              </rdf:li></rdf:Seq></xmpMM:History>
              <illustrator:StartupProfile>Web</illustrator:StartupProfile>
              <illustrator:ArtboardSize rdf:parseType="Resource">
                <stDim:w>20</stDim:w><stDim:h>20</stDim:h><stDim:unit>pixel</stDim:unit>
              </illustrator:ArtboardSize>
              <photoshop:AuthorsPosition>Designer</photoshop:AuthorsPosition>
              <xmpRights:WebStatement rdf:resource="https://example.invalid/license"/>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
      </metadata>
      <rect width="20" height="20" fill="red"/>
    </svg>'''

    expect:
    // Descriptive URLs are metadata values; they do not load resources in the browser.
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8)) == null
  }

  def "new editor namespaces do not bypass SVG content restrictions"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:editor="${namespace}">
      <editor:metadata ${attributes}>${content}</editor:metadata>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    namespace << [
      "http://ns.adobe.com/AdobeIllustrator/10.0/",
      "http://ns.adobe.com/Graphs/1.0/",
      "http://schemas.microsoft.com/visio/2003/SVGExtensions/"
    ]
    combined:
    attributes | content | reason
    "editor:onload='alert(1)'" | "" | "event handlers are not allowed"
    "editor:href='https://example.invalid/image.svg#shape'" | "" | "local fragment starting with '#'"
    "xml:base='https://example.invalid/'" | "" | "base URIs are not allowed"
    "editor:fill='u\\72l(//example.invalid/paint.svg#paint)'" | "" | "CSS escapes"
    "" | "<script>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<script xmlns='http://www.w3.org/1999/xhtml'>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<editor:metadata xmlns:editor='http://www.w3.org/1999/xhtml'/>" | "uses an unsupported namespace"
  }

  def "rejects unsafe content nested inside XMP metadata"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"><metadata>
      <x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
        <xmp:CreatorTool ${attributes}>${content}</xmp:CreatorTool>
      </x:xmpmeta>
    </metadata></svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    attributes | content | reason
    "onload='alert(1)'" | "" | "disallowed event handler attribute 'onload'"
    "xmp:onload='alert(1)'" | "" | "disallowed event handler attribute 'xmp:onload'"
    "" | "<script>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<script xmlns='http://www.w3.org/1999/xhtml'>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<link xmlns='http://www.w3.org/1999/xhtml' rel='stylesheet' href='https://example.invalid/style.css'/>" | "SVG metadata element <link> uses an unsupported namespace"
    "" | "<xmp:CreatorTool xmlns:xmp='https://example.invalid/metadata'/>" | "uses an unsupported namespace"
    "" | "<?xml-stylesheet href='https://example.invalid/style.css'?>" | "unsupported processing instruction"
    "" | "<?xml-model href='https://example.invalid/schema.xml'?>" | "unsupported processing instruction"
    "" | "<?xpacket-stylesheet href='https://example.invalid/style.css'?>" | "unsupported processing instruction"
  }

  def "descriptive XMP elements and packet markers are confined to metadata"() {
    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    svg | reason
    "<svg xmlns='http://www.w3.org/2000/svg'><x:xmpmeta xmlns:x='adobe:ns:meta/'/></svg>" | "outside the SVG namespace"
    "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='Illustrator'/>" | "attribute namespace is not supported"
    "<?xpacket begin=''?><svg xmlns='http://www.w3.org/2000/svg'/>" | "unsupported processing instruction before or after its root"
    "<svg xmlns='http://www.w3.org/2000/svg'/><?xpacket end='w'?>" | "unsupported processing instruction before or after its root"
    "<svg xmlns='http://www.w3.org/2000/svg'><?xpacket begin=''?></svg>" | "SVG <svg> contains unsupported processing instruction"
  }

  def "XMP metadata obeys SVG nesting and element count limits"() {
    given:
    def content = nested
            ? "<xmp:History>".repeat(65) + "</xmp:History>".repeat(65)
            : "<xmp:CreatorTool/>".repeat(10_000)
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"><metadata>
      <x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
        ${content}
      </x:xmpmeta>
    </metadata></svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    nested | reason
    true | "SVG metadata nesting exceeds 64 levels"
    false | "SVG contains more than 10000 elements"
  }

  def "accepts Sketch layer metadata with local XLink references"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:sketch="http://www.bohemiancoding.com/sketch/ns"
                     xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 20 20">
      <defs><path id="shape" sketch:type="MSShapeGroup" d="M0 0H20V20H0Z"/></defs>
      <g sketch:type="MSPage"><use sketch:type="MSShapeGroup" xlink:href="#shape"/></g>
    </svg>'''

    expect:
    // Sketch exports include editor type attributes alongside standard SVG and XLink references.
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "rejects unsafe SVG content alongside Sketch editor metadata"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:sketch="http://www.bohemiancoding.com/sketch/ns"
                      xmlns:xlink="http://www.w3.org/1999/xlink">
      <g sketch:type="MSPage">${content}</g>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    content | reason
    "<use xlink:href='https://example.invalid/image.svg#shape'/>" | "local fragment starting with '#'"
    "<use xlink:href='images/icon.svg#shape'/>" | "local fragment starting with '#'"
    "<use xlink:href='data:image/svg+xml;base64,PHN2Zy8+'/>" | "local fragment starting with '#'"
    "<use xlink:href='#shape' sketch:onload='alert(1)'/>" | "event handlers are not allowed"
    "<rect sketch:fill='u\\72l(//example.invalid/paint.svg#paint)'/>" | "CSS escapes"
    "<rect style='fill:url(//example.invalid/paint.svg#paint)'/>" | "must reference a local fragment"
    "<script>alert(1)</script>" | "Unsupported SVG element: script"
    "<sketch:metadata><script xmlns='http://www.w3.org/1999/xhtml'>alert(1)</script></sketch:metadata>" | "SVG metadata element <script> uses an unsupported namespace"
  }

  def "rejects unknown namespaces on Sketch-looking SVG attributes"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:sketch="${namespace}">
      <g sketch:type="MSPage"/>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("attribute 'sketch:type'")
    failure.message.contains("attribute namespace is not supported")

    where:
    namespace << ["http://www.w3.org/1999/xhtml", "https://example.invalid/editor"]
  }

  def "accepts Serif layer names in SVG logos"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg" xmlns:serif="http://www.serif.com/"
                     width="100%" height="100%" viewBox="0 0 20 20" xml:space="preserve"
                     style="fill-rule:evenodd;clip-rule:evenodd;stroke-linejoin:round;">
      <g id="Logo" serif:id="Company logo">
        <rect id="Background" serif:id="Logo background" x="0" y="0" width="20" height="20"
              style="fill:rgb(255,0,0);"/>
      </g>
    </svg>'''

    expect:
    // Affinity exports use serif:id for editor layer names, including names containing spaces.
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "rejects unsafe SVG content alongside Serif editor attributes"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:serif="http://www.serif.com/">
      <rect serif:id="Logo background" ${attributes}/>${content}
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    attributes | content | reason
    "onload='alert(1)'" | "" | "event handlers are not allowed"
    "serif:onload='alert(1)'" | "" | "event handlers are not allowed"
    "serif:href='//example.invalid/image.svg#shape'" | "" | "local fragment starting with '#'"
    "fill='u\\72l(//example.invalid/paint.svg#paint)'" | "" | "CSS escapes"
    "serif:fill='u\\72l(//example.invalid/paint.svg#paint)'" | "" | "CSS escapes"
    "" | "<script>alert(1)</script>" | "Unsupported SVG element: script"
    "" | "<serif:metadata><script>alert(1)</script></serif:metadata>" | "SVG metadata element <script> uses an unsupported namespace"
  }

  def "rejects unknown namespaces on Serif-looking SVG attributes"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:serif="${namespace}">
      <rect serif:id="Logo background"/>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("attribute 'serif:id'")
    failure.message.contains("attribute namespace is not supported")

    where:
    namespace << ["http://www.w3.org/1999/xhtml", "https://example.invalid/editor"]
  }

  def "rejects unknown namespaces on editor-looking SVG attributes"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:inkscape="${namespace}" inkscape:version="1.4"/>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("attribute 'inkscape:version'")
    failure.message.contains("attribute namespace is not supported")

    where:
    namespace << ["http://www.w3.org/1999/xhtml", "https://example.invalid/editor"]
  }

  def "accepts SVGs containing Inkscape views, guides, grids, pages and path effects"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"
                     xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
                     width="20" height="20" inkscape:version="1.4" sodipodi:docname="logo.svg">
      <sodipodi:namedview id="namedview1" pagecolor="#ffffff" inkscape:zoom="2"
                          inkscape:current-layer="layer1">
        <sodipodi:guide position="0,20" orientation="0,1"/>
        <inkscape:grid type="xygrid" spacingx="1" spacingy="1"/>
        <inkscape:page x="0" y="0" width="20" height="20"/>
      </sodipodi:namedview>
      <defs><inkscape:path-effect id="effect1" effect="spiro" is_visible="true"/></defs>
      <g id="layer1" inkscape:groupmode="layer" inkscape:label="Logo">
        <path d="M0 0H20V20H0Z" inkscape:path-effect="#effect1"/>
      </g>
    </svg>'''

    expect:
    // Editor elements used to be rejected even after their attributes were allowed.
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "accepts editor elements in descriptive SVG metadata with alternate namespace prefixes"() {
    given:
    def svg = '''<svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:view="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
                     xmlns:editor="http://www.inkscape.org/namespaces/inkscape">
      <metadata><view:namedview><editor:grid type="xygrid"/></view:namedview></metadata>
    </svg>'''

    expect:
    ImageUploadValidator.validate("logo.svg", svg.bytes) == null
  }

  def "rejects active content and unsupported namespaces inside editor metadata"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
                      xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape">
      <sodipodi:namedview ${attributes}>${content}</sodipodi:namedview>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    // Accepting a metadata container must not skip validation of its attributes or descendants.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    attributes | content | reason
    "" | "<script>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<script xmlns='http://www.w3.org/1999/xhtml'>alert(1)</script>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<foreignObject/>" | "SVG metadata element <foreignObject> uses an unsupported namespace"
    "" | "<inkscape:grid><script>alert(1)</script></inkscape:grid>" | "SVG metadata element <script> uses an unsupported namespace"
    "" | "<sodipodi:guide xmlns:sodipodi='https://example.invalid/editor'/>" | "SVG metadata element <sodipodi:guide> uses an unsupported namespace"
    "" | "<?xml-stylesheet type='text/css' href='https://example.invalid/style.css'?>" | "SVG metadata contains unsupported processing instruction"
    "onload='alert(1)'" | "" | "event handlers are not allowed"
    "" | "<inkscape:grid onload='alert(1)'/>" | "event handlers are not allowed"
    "xml:base='https://example.invalid/'" | "" | "base URIs are not allowed"
    "style='fill:url(//example.invalid/paint.svg#paint)'" | "" | "must reference a local fragment"
    "" | "<inkscape:grid style='fill:u\\72l(//example.invalid/paint.svg#paint)'/>" | "CSS escapes"
  }

  def "editor metadata obeys SVG nesting and element count limits"() {
    given:
    def contents = nested
            ? "<inkscape:page>".repeat(65) + "</inkscape:page>".repeat(65)
            : "<inkscape:page/>".repeat(10_000)
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
                      xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape">
      <sodipodi:namedview>${contents}</sodipodi:namedview>
    </svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    nested | reason
    true | "SVG metadata nesting exceeds 64 levels"
    false | "SVG contains more than 10000 elements"
  }

  def "editor metadata does not bypass SVG content restrictions"() {
    given:
    def svg = """<svg xmlns="http://www.w3.org/2000/svg"
                      xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"
                      inkscape:version="1.4" ${attributes}>${content}</svg>"""

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    attributes | content | reason
    "onload='alert(1)'" | "" | "event handlers are not allowed"
    "inkscape:onload='alert(1)'" | "" | "event handlers are not allowed"
    "" | "<script>alert(1)</script>" | "Unsupported SVG element: script"
    "" | "<use href='//example.invalid/image.svg#shape'/>" | "local fragment starting with '#'"
    "inkscape:href='//example.invalid/image.svg#shape'" | "" | "local fragment starting with '#'"
    "inkscape:fill='u\\72l(//example.invalid/image.svg#paint)'" | "" | "CSS escapes"
    "" | "<rect fill='u\\72l(//example.invalid/image.svg#paint)'/>" | "CSS escapes"
  }

  def "rejects active SVG elements and event handlers"() {
    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains(reason)

    where:
    svg | reason
    "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>" | "Unsupported SVG element: script"
    "<svg xmlns='http://www.w3.org/2000/svg'><foreignObject><div/></foreignObject></svg>" | "Unsupported SVG element: foreignObject"
    "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>" | "event handlers are not allowed"
    "<svg xmlns='http://www.w3.org/2000/svg'><style>@import 'https://example.com/style.css';</style></svg>" | "CSS construct '@import' is not allowed"
  }

  def "rejects external XML entities in SVG uploads"() {
    given:
    def svg = "<!DOCTYPE svg [<!ENTITY external SYSTEM 'https://example.invalid/entity'>]><svg xmlns='http://www.w3.org/2000/svg'>&external;</svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("DOCTYPE")
  }

  def "rejects truncated raster data"() {
    given:
    byte[] pngSignature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] as byte[]

    when:
    ImageUploadValidator.validate("logo.png", pngSignature)

    then:
    thrown(IllegalArgumentException)
  }

  def "accepts ICO frames containing PNG and DIB data"() {
    given:
    def output = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", output)

    expect:
    ImageUploadValidator.validate("logo.ico", ico(output.toByteArray(), 1, 1)) == null
    ImageUploadValidator.validate("logo.ico", ico(dib(1, 2), 1, 1)) == null
    ImageUploadValidator.validate("logo.ico", ico([output.toByteArray(), dib(1, 2), output.toByteArray()], 1, 1)) == null
  }

  def "rejects oversized embedded ICO images before decoding"() {
    given:
    // No pixel data is needed: the payload header must be checked before invoking a decoder.
    def png = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
            .putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452)
            .putInt(width).putInt(height).put((byte) 8).put((byte) 6).array()

    when:
    ImageUploadValidator.validate("logo.ico", ico(usePng ? png : dib(width, height * 2), 1, 1))

    then:
    // The ICO directory used to hide the embedded PNG dimensions from the limits.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("ICO frame 1 embedded image is ${width}x${height} pixels")
    failure.message.contains(limit)

    where:
    usePng | width | height | limit
    true | 4100 | 4100 | "dimensions must be between 1x1 and 4096x4096"
    true | 4096 | 4096 | "contains 16777216 pixels; maximum is 16000000 pixels"
    true | Integer.MAX_VALUE | Integer.MAX_VALUE | "dimensions must be between 1x1 and 4096x4096"
    false | 4100 | 4100 | "dimensions must be between 1x1 and 4096x4096"
    false | 4096 | 4096 | "contains 16777216 pixels; maximum is 16000000 pixels"
  }

  def "rejects inconsistent ICO directory and embedded dimensions"() {
    given:
    def output = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output)
    def payload = png ? output.toByteArray() : dib(2, 4)

    when:
    ImageUploadValidator.validate("logo.ico", ico(payload, 1, 1))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("ICO frame 1 directory reports 1x1 pixels")
    failure.message.contains("embedded image is 2x2 pixels; dimensions must match")

    where:
    png << [true, false]
  }

  def "rejects invalid ICO payload offsets"() {
    given:
    byte[] data = ico(dib(1, 2), 1, 1)
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(18, offset)

    when:
    ImageUploadValidator.validate("logo.ico", data)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("invalid payload range")
    failure.message.contains("offset ${Integer.toUnsignedLong(offset)}, size 48 bytes")
    failure.message.contains("start at or after byte 22")
    failure.message.contains("fit within the 70-byte image")

    where:
    offset << [0, -1, Integer.MAX_VALUE]
  }

  def "rejects ICO directory bit depths that disagree with DIB payloads"() {
    given:
    byte[] data = ico(dib(1, 2), 1, 1)
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(12, (short) 30)

    when:
    ImageUploadValidator.validate("logo.ico", data)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("directory bit depth is 30, but the DIB bit depth is 32")
    failure.message.contains("must be 0 or match the DIB header")
  }

  def "reports the invalid DIB header value and requirement for ICO uploads"() {
    given:
    byte[] payload = dib(1, 2)
    def header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    if (offset == 12 || offset == 14) {
      header.putShort(offset, (short) value)
    } else {
      header.putInt(offset, value)
    }

    when:
    ImageUploadValidator.validate("logo.ico", ico(payload, 1, 1))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.startsWith("ICO frame 1 DIB")
    failure.message.contains(reason)
    failure.message.contains(requirement)

    where:
    offset | value | reason | requirement
    0 | 13 | "header size is 13 bytes" | "supported sizes are 12, 16, 40, 52, 56, 64, 108, and 124 bytes"
    0 | 108 | "header declares 108 bytes" | "payload contains only 48 bytes"
    12 | 2 | "declares 2 color planes" | "exactly 1 is required"
    14 | 3 | "bit depth is 3 bits per pixel" | "supported depths are 1, 4, 8, 16, 24, and 32"
    8 | 3 | "height is 3 pixels" | "must be positive and even"
    8 | 0 | "height is 0 pixels" | "must be positive and even"
    8 | -2 | "height is -2 pixels" | "must be positive and even"
  }

  def "reports malformed embedded PNG headers in ICO uploads"() {
    given:
    def payload = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .putLong(0x89504e470d0a1a0aL).putInt(length)
    if (size >= 16) {
      payload.putInt(type)
    }

    when:
    ImageUploadValidator.validate("logo.ico", ico(payload.array(), 1, 1))

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.startsWith("ICO frame 1 embedded PNG")
    failure.message.contains(reason)
    failure.message.contains(requirement)

    where:
    size | length | type | reason | requirement
    12 | 13 | 0x49484452 | "payload contains 12 bytes" | "at least 33 are required"
    33 | 12 | 0x49484452 | "chunk length 12 bytes" | "expected an IHDR chunk with 13 bytes"
    33 | 13 | 0x49444154 | "chunk type 0x49444154" | "expected an IHDR chunk with 13 bytes"
  }

  @Timeout(5)
  def "rejects deeply nested SVG elements before collecting style text"() {
    given:
    def nestedElements = "<g>".repeat(150_000) + "</g>".repeat(150_000)
    def content = withStyle ? "<style>${nestedElements}</style>" : nestedElements
    def data = "<svg xmlns='http://www.w3.org/2000/svg'>${content}</svg>".getBytes(StandardCharsets.UTF_8)
    assert data.length <ImageUploadValidator.MAX_FILE_SIZE

    when:
    ImageUploadValidator.validate("logo.svg", data)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message == "SVG nesting exceeds 64 levels"

    where:
    withStyle << [false, true]
  }

  @Timeout(5)
  def "rejects repeated unterminated CSS URLs within bounded time"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><style>" + prefix.repeat(131_072) + "</style></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.getBytes(StandardCharsets.UTF_8))

    then:
    // Regex matching used to rescan the remaining input for every unterminated url(.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("url()")

    where:
    prefix << ["url(", "url(#"]
  }

  def "reports the CSS context, position and reason for rejected URLs"() {
    when:
    ImageUploadValidator.validate("logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'><style>rect{fill:${css}}</style></svg>".bytes)

    then:
    def failure = thrown(IllegalArgumentException)
    failure.message.startsWith("SVG <style>:")
    failure.message.contains("at character 11")
    failure.message.contains(reason)
    failure.message.contains("url(#paint)") || failure.message.contains("url('#paint')")

    where:
    css | reason
    "url(#paint" | "expected ')'"
    "url('#paint)" | "missing closing single quote"
    'url("#paint)' | "missing closing double quote"
    "url(#)" | "empty fragment identifier"
    "url(#paint extra)" | "expected ')'"
    "url(#paint(url(#other))" | "nested opening parenthesis '('"
    "url(#paint'other)" | "quote inside an unquoted reference"
    "url()" | "external and empty references are not allowed"
    "url(https://example.invalid/paint.svg#paint)" | "must reference a local fragment starting with '#'"
  }

  def "rejects escaped URLs in SVG presentation attributes"() {
    given:
    def svg = "<svg xmlns='http://www.w3.org/2000/svg'><rect ${attribute}='u\\72l(//example.invalid/paint.svg#paint)'/></svg>"

    when:
    ImageUploadValidator.validate("logo.svg", svg.bytes)

    then:
    // Presentation attributes use CSS parsing and used to bypass the style-only escape check.
    def failure = thrown(IllegalArgumentException)
    failure.message.contains("attribute '${attribute}'")
    failure.message.contains("CSS escapes")

    where:
    attribute << ["fill", "stroke", "filter", "clip-path", "mask", "marker-start", "cursor"]
  }

  def "accepts quoted local URLs in SVG presentation attributes"() {
    expect:
    ImageUploadValidator.validate("logo.svg", '''<svg xmlns="http://www.w3.org/2000/svg">
      <rect fill="url( '#paint' )" stroke="URL(#paint)"/></svg>'''.bytes) == null
  }

  private static byte[] ico(byte[] payload, int width, int height) {
    ico([payload], width, height)
  }

  private static byte[] ico(List<byte[]> payloads, int width, int height) {
    int offset = 6 + payloads.size() * 16
    def buffer = ByteBuffer.allocate(offset + (int) payloads.sum {
      it.length
    }).order(ByteOrder.LITTLE_ENDIAN)
    .putShort((short) 0).putShort((short) 1).putShort((short) payloads.size())
    payloads.each { payload ->
      buffer.put((byte) width).put((byte) height).put((byte) 0).put((byte) 0)
              .putShort((short) 1).putShort((short) 32).putInt(payload.length).putInt(offset)
      offset += payload.length
    }
    payloads.each { buffer.put(it) }
    buffer.array()
  }

  private static byte[] raster(String format, int width = 1, int height = 1) {
    def output = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)
    output.toByteArray()
  }

  private static byte[] dib(int width, int height) {
    ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 32).array()
  }
}
