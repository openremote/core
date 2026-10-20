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

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

/**
 * Validates decoded SVG uploads as XML 1.0 in UTF-8 or UTF-16 and restricts elements, attributes,
 * CSS, and references. Descriptive metadata, including XMP, and Inkscape, Sodipodi, Serif, Sketch,
 * Illustrator, and Visio editor metadata are allowed, with their full subtrees checked to reject
 * active or unsupported foreign content. XMP packet processing instructions are allowed within
 * metadata only. Embedded images must be Base64 data URLs containing validated static PNG or JPEG
 * data and share a limit on their count and total decoded pixels.
 */
final class SvgImageValidator {

  private static final int MAX_SVG_ELEMENTS = 10_000;
  private static final int MAX_SVG_DEPTH = 64;
  private static final int MAX_EMBEDDED_IMAGES = 32;

  private static final Set<String> XML_ENCODINGS =
      Set.of("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE");

  private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  private static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";

  // Editors store authoring metadata in both elements and attributes using these namespaces.
  private static final Set<String> EDITOR_NAMESPACES =
      Set.of(
          "http://www.inkscape.org/namespaces/inkscape",
          "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd",
          "http://www.serif.com/",
          "http://www.bohemiancoding.com/sketch/ns",
          "http://ns.adobe.com/AdobeIllustrator/10.0/",
          "http://ns.adobe.com/Graphs/1.0/",
          "http://schemas.microsoft.com/visio/2003/SVGExtensions/");

  // Foreign markup in metadata is only safe when the browser will treat it as data. In
  // particular, XHTML script elements execute even when nested inside SVG metadata.
  // Creative Commons metadata uses both the current ns# and older web.resource.org namespaces.
  private static final Set<String> METADATA_NAMESPACES =
      Set.of(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
          "http://www.w3.org/2000/01/rdf-schema#",
          "http://purl.org/dc/elements/1.1/",
          "http://purl.org/dc/terms/",
          "http://creativecommons.org/ns#",
          "http://web.resource.org/cc/",
          "adobe:ns:meta/",
          "http://ns.adobe.com/xap/1.0/",
          "http://ns.adobe.com/xap/1.0/mm/",
          "http://ns.adobe.com/xap/1.0/rights/",
          "http://ns.adobe.com/xap/1.0/sType/Dimensions#",
          "http://ns.adobe.com/xap/1.0/sType/ResourceEvent#",
          "http://ns.adobe.com/xap/1.0/sType/ResourceRef#",
          "http://ns.adobe.com/photoshop/1.0/",
          "http://ns.adobe.com/illustrator/1.0/",
          "http://ns.adobe.com/Variables/1.0/",
          "http://ns.adobe.com/SaveForWeb/1.0/");

  private static final Set<String> SVG_ELEMENTS =
      Set.of(
          "svg",
          "g",
          "defs",
          "symbol",
          "use",
          "image",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "linearGradient",
          "radialGradient",
          "stop",
          "clipPath",
          "mask",
          "pattern",
          "marker",
          "style",
          "text",
          "tspan",
          "title",
          "desc",
          "metadata");

  // Presentation attributes use CSS value parsing, just like declarations in a style attribute.
  private static final Set<String> PRESENTATION_ATTRIBUTES =
      Set.of(
          "alignment-baseline",
          "baseline-shift",
          "clip",
          "clip-path",
          "clip-rule",
          "color",
          "color-interpolation",
          "color-interpolation-filters",
          "color-profile",
          "color-rendering",
          "cursor",
          "direction",
          "display",
          "dominant-baseline",
          "enable-background",
          "fill",
          "fill-opacity",
          "fill-rule",
          "filter",
          "flood-color",
          "flood-opacity",
          "font-family",
          "font-size",
          "font-size-adjust",
          "font-stretch",
          "font-style",
          "font-variant",
          "font-weight",
          "glyph-orientation-horizontal",
          "glyph-orientation-vertical",
          "image-rendering",
          "kerning",
          "letter-spacing",
          "lighting-color",
          "marker",
          "marker-end",
          "marker-mid",
          "marker-start",
          "mask",
          "mask-type",
          "opacity",
          "overflow",
          "paint-order",
          "pointer-events",
          "shape-rendering",
          "stop-color",
          "stop-opacity",
          "stroke",
          "stroke-dasharray",
          "stroke-dashoffset",
          "stroke-linecap",
          "stroke-linejoin",
          "stroke-miterlimit",
          "stroke-opacity",
          "stroke-width",
          "text-anchor",
          "text-decoration",
          "text-overflow",
          "text-rendering",
          "transform",
          "transform-origin",
          "unicode-bidi",
          "vector-effect",
          "visibility",
          "white-space",
          "word-spacing",
          "writing-mode",
          "d",
          "x",
          "y",
          "cx",
          "cy",
          "r",
          "rx",
          "ry",
          "width",
          "height");

  private static final String[] FORBIDDEN_CSS_CONSTRUCTS = {
    "@import",
    "@font-face",
    "image-set(",
    "image(",
    "src(",
    "expression(",
    "-moz-binding",
    "behavior:",
    "javascript:",
    "data:"
  };

  private static final String[] EXTERNAL_SCHEMES = {
    "javascript:", "data:", "file:", "http:", "https:"
  };

  private SvgImageValidator() {}

  static void validate(byte[] data) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);

      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

      Document document;

      try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
        document = factory.newDocumentBuilder().parse(input);
      }

      // Stored bytes are served unchanged, so limit differences in XML interpretation.
      if (!"1.0".equals(document.getXmlVersion())) {
        throw new IllegalArgumentException("SVG XML version must be 1.0");
      }

      // Xerces can report UTF-8 input even when the declaration selects ISO-2022-JP.
      String inputEncoding = document.getInputEncoding();
      String declaredEncoding = document.getXmlEncoding();
      if (inputEncoding == null
          || !XML_ENCODINGS.contains(inputEncoding.toUpperCase(Locale.ROOT))
          || (declaredEncoding != null
              && !XML_ENCODINGS.contains(declaredEncoding.toUpperCase(Locale.ROOT)))) {
        throw new IllegalArgumentException("SVG XML encoding must be UTF-8 or UTF-16");
      }

      validateDocumentNodes(document);

      Element root = document.getDocumentElement();

      if (root == null
          || !"svg".equals(root.getLocalName())
          || !SVG_NAMESPACE.equals(root.getNamespaceURI())) {
        throw new IllegalArgumentException("SVG root must be <svg> in the SVG namespace");
      }

      validateSvgElement(root, 0, new ValidationState());
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (SAXParseException e) {
      throw new IllegalArgumentException(
          "Malformed SVG XML at line "
              + e.getLineNumber()
              + ", column "
              + e.getColumnNumber()
              + ": "
              + e.getMessage(),
          e);
    } catch (Exception e) {
      throw new IllegalArgumentException("Could not parse SVG XML: " + e.getMessage(), e);
    }
  }

  private static void validateDocumentNodes(Document document) {
    for (Node child = document.getFirstChild(); child != null; child = child.getNextSibling()) {
      switch (child.getNodeType()) {
        case Node.ELEMENT_NODE, Node.COMMENT_NODE -> {
          // Allowed.
        }

        case Node.PROCESSING_INSTRUCTION_NODE,
            Node.DOCUMENT_TYPE_NODE,
            Node.ENTITY_NODE,
            Node.ENTITY_REFERENCE_NODE ->
            throw new IllegalArgumentException(
                "SVG document contains unsupported "
                    + nodeType(child)
                    + " before or after its root");

        default -> {
          // Ignore whitespace and other harmless document nodes.
        }
      }
    }
  }

  private static void validateSvgElement(Element element, int depth, ValidationState state) {

    if (depth > MAX_SVG_DEPTH) {
      throw new IllegalArgumentException("SVG nesting exceeds " + MAX_SVG_DEPTH + " levels");
    }

    if (++state.elementCount > MAX_SVG_ELEMENTS) {
      throw new IllegalArgumentException(
          "SVG contains more than " + MAX_SVG_ELEMENTS + " elements");
    }

    if (EDITOR_NAMESPACES.contains(element.getNamespaceURI())) {
      validateSvgAttributes(element, state);
      validateMetadata(element, depth, state);
      return;
    }

    if (!SVG_NAMESPACE.equals(element.getNamespaceURI())) {
      throw new IllegalArgumentException(
          "SVG element <" + element.getTagName() + "> is outside the SVG namespace");
    }

    String name = element.getLocalName();

    if (!SVG_ELEMENTS.contains(name)) {
      throw new IllegalArgumentException("Unsupported SVG element: " + name);
    }

    validateSvgAttributes(element, state);

    if ("metadata".equals(name)) {
      validateMetadata(element, depth, state);
      return;
    }

    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      switch (child.getNodeType()) {
        case Node.ELEMENT_NODE -> validateSvgElement((Element) child, depth + 1, state);

        case Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.COMMENT_NODE -> {
          // Allowed.
        }

        case Node.PROCESSING_INSTRUCTION_NODE,
            Node.ENTITY_NODE,
            Node.ENTITY_REFERENCE_NODE,
            Node.DOCUMENT_TYPE_NODE ->
            throw new IllegalArgumentException(
                "SVG <" + name + "> contains unsupported " + nodeType(child));

        default -> {
          // Ignore other harmless DOM node types.
        }
      }
    }

    // getTextContent() recursively walks descendants, so enforce subtree limits first.
    if ("style".equals(name)) {
      try {
        validateCss(element.getTextContent());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("SVG <style>: " + e.getMessage(), e);
      }
    }
  }

  private static void validateMetadata(Element metadata, int depth, ValidationState state) {

    for (Node child = metadata.getFirstChild(); child != null; child = child.getNextSibling()) {
      validateMetadataNode(child, depth + 1, state);
    }
  }

  private static void validateMetadataNode(Node node, int depth, ValidationState state) {

    if (depth > MAX_SVG_DEPTH) {
      throw new IllegalArgumentException(
          "SVG metadata nesting exceeds " + MAX_SVG_DEPTH + " levels");
    }

    switch (node.getNodeType()) {
      case Node.ELEMENT_NODE -> {
        if (++state.elementCount > MAX_SVG_ELEMENTS) {
          throw new IllegalArgumentException(
              "SVG contains more than " + MAX_SVG_ELEMENTS + " elements");
        }

        Element element = (Element) node;
        boolean editorMetadata = EDITOR_NAMESPACES.contains(element.getNamespaceURI());

        if (!editorMetadata && !METADATA_NAMESPACES.contains(element.getNamespaceURI())) {
          throw new IllegalArgumentException(
              "SVG metadata element <" + element.getTagName() + "> uses an unsupported namespace");
        }

        if (editorMetadata) {
          validateSvgAttributes(element, state);
        } else {
          validateMetadataAttributes(element);
        }

        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
          validateMetadataNode(child, depth + 1, state);
        }
      }

      case Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.COMMENT_NODE -> {
        // Allowed.
      }

      case Node.PROCESSING_INSTRUCTION_NODE -> {
        // XMP packet markers are editor data, not stylesheet or script instructions.
        if (!"xpacket".equals(node.getNodeName())) {
          throw new IllegalArgumentException("SVG metadata contains unsupported " + nodeType(node));
        }
      }

      case Node.ENTITY_NODE, Node.ENTITY_REFERENCE_NODE, Node.DOCUMENT_TYPE_NODE ->
          throw new IllegalArgumentException("SVG metadata contains unsupported " + nodeType(node));

      default -> {
        // Ignore other harmless DOM node types.
      }
    }
  }

  private static void validateMetadataAttributes(Element element) {
    NamedNodeMap attributes = element.getAttributes();

    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);

      if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
        continue;
      }

      String name =
          attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getName();

      if (name.toLowerCase(Locale.ROOT).startsWith("on")) {
        throw new IllegalArgumentException(
            "SVG metadata element <"
                + element.getTagName()
                + "> has disallowed event handler attribute '"
                + attribute.getName()
                + "'");
      }
    }
  }

  private static void validateSvgAttributes(Element element, ValidationState state) {
    NamedNodeMap attributes = element.getAttributes();

    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);

      String namespace = attribute.getNamespaceURI();

      if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) {
        continue;
      }

      String name =
          attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getName();

      String lowerName = name.toLowerCase(Locale.ROOT);
      String value = attribute.getValue().trim();

      try {
        if (lowerName.startsWith("on")) {
          throw new IllegalArgumentException("event handlers are not allowed");
        }

        // xml:base can turn otherwise relative references into external references.
        if ("base".equals(lowerName) && XMLConstants.XML_NS_URI.equals(namespace)) {
          throw new IllegalArgumentException("base URIs are not allowed");
        }

        if (namespace != null
            && !XMLConstants.XML_NS_URI.equals(namespace)
            && !XLINK_NAMESPACE.equals(namespace)
            && !EDITOR_NAMESPACES.contains(namespace)) {
          throw new IllegalArgumentException("attribute namespace is not supported");
        }

        if (XLINK_NAMESPACE.equals(namespace) && !"href".equals(lowerName)) {
          throw new IllegalArgumentException("only xlink:href is supported");
        }

        if ("href".equals(lowerName)) {
          if (SVG_NAMESPACE.equals(element.getNamespaceURI())
              && "image".equals(element.getLocalName())
              && (namespace == null || XLINK_NAMESPACE.equals(namespace))) {
            validateEmbeddedImage(value, state);
          } else {
            validateLocalReference(value);
          }
          continue;
        }

        if ("style".equals(lowerName) || PRESENTATION_ATTRIBUTES.contains(lowerName)) {
          validateCss(value);
        } else {
          validateUrlReferences(value);
        }

        String externalScheme = findExternalScheme(value);
        if (externalScheme != null) {
          throw new IllegalArgumentException("URI scheme '" + externalScheme + "' is not allowed");
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "SVG <"
                + element.getLocalName()
                + "> attribute '"
                + attribute.getName()
                + "': "
                + e.getMessage(),
            e);
      }
    }
  }

  private static void validateEmbeddedImage(String value, ValidationState state) {
    String format;
    int prefixLength;
    if (value.regionMatches(true, 0, "data:image/png;base64,", 0, 22)) {
      format = "png";
      prefixLength = 22;
    } else if (value.regionMatches(true, 0, "data:image/jpeg;base64,", 0, 23)) {
      format = "jpeg";
      prefixLength = 23;
    } else {
      throw new IllegalArgumentException(
          "embedded images must use data:image/png;base64, or data:image/jpeg;base64,; external URLs and other embedded formats are not allowed");
    }

    if (++state.embeddedImageCount > MAX_EMBEDDED_IMAGES) {
      throw new IllegalArgumentException(
          "SVG contains more than " + MAX_EMBEDDED_IMAGES + " embedded image references");
    }

    // Editors wrap Base64 data over lines; allow ASCII whitespace, but reject other invalid
    // characters.
    String encoded = value.substring(prefixLength).replaceAll("[\\t\\n\\r ]", "");
    if (encoded.isEmpty()) {
      throw new IllegalArgumentException(
          "embedded " + format.toUpperCase(Locale.ROOT) + " image data is empty");
    }
    byte[] data;
    try {
      data = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "embedded " + format.toUpperCase(Locale.ROOT) + " image data is not valid Base64", e);
    }
    try {
      state.embeddedPixels +=
          RasterImageValidator.validate(
              data, format, RasterImageValidator.MAX_PIXELS - state.embeddedPixels);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "embedded " + format.toUpperCase(Locale.ROOT) + " image: " + e.getMessage(), e);
    }
  }

  /** Limits shared by SVG graphics, metadata, and embedded raster images throughout one upload. */
  private static final class ValidationState {
    int elementCount;
    int embeddedImageCount;
    long embeddedPixels;
  }

  private static void validateLocalReference(String value) {
    if (value.length() < 2 || value.charAt(0) != '#') {
      throw new IllegalArgumentException("reference must be a local fragment starting with '#'");
    }
  }

  private static void validateUrlReferences(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!value.regionMatches(true, i, "url(", 0, 4)) {
        continue;
      }
      int cursor = skipCssWhitespace(value, i + 4);
      char quote = cursor < value.length() ? value.charAt(cursor) : 0;
      boolean quoted = quote == '\'' || quote == '"';
      if (quoted) {
        cursor++;
      }
      if (cursor >= value.length() || value.charAt(cursor++) != '#') {
        throw new IllegalArgumentException(
            "CSS url() at character %d must reference a local fragment starting with '#', for example url(#paint); external and empty references are not allowed"
                .formatted(i + 1));
      }
      int fragmentStart = cursor;
      while (cursor < value.length()) {
        char ch = value.charAt(cursor);
        if (quoted ? ch == quote : ch == ')' || isCssWhitespace(ch)) {
          break;
        }
        if (ch == '(' || !quoted && (ch == '\'' || ch == '"')) {
          throw new IllegalArgumentException(
              "Malformed CSS url() reference at character %d: %s at character %d; use url(#paint) or url('#paint')"
                  .formatted(
                      i + 1,
                      ch == '('
                          ? "nested opening parenthesis '('"
                          : "quote inside an unquoted reference",
                      cursor + 1));
        }
        cursor++;
      }
      if (cursor == fragmentStart) {
        throw new IllegalArgumentException(
            "CSS url() at character %d has an empty fragment identifier; add an SVG element ID after '#', for example url(#paint)"
                .formatted(i + 1));
      }
      if (quoted) {
        if (cursor >= value.length() || value.charAt(cursor++) != quote) {
          throw new IllegalArgumentException(
              "Unterminated CSS url() reference at character %d: missing closing %s quote; use url('#paint') or url(\"#paint\")"
                  .formatted(i + 1, quote == '\'' ? "single" : "double"));
        }
      }
      cursor = skipCssWhitespace(value, cursor);
      if (cursor >= value.length() || value.charAt(cursor) != ')') {
        throw new IllegalArgumentException(
            "Malformed or unterminated CSS url() reference at character %d: expected ')' at character %d after the local fragment; use url(#paint) or url('#paint')"
                .formatted(i + 1, cursor + 1));
      }
      // Each URL is consumed once; a malformed URL fails instead of retrying at later offsets.
      i = cursor;
    }
  }

  private static int skipCssWhitespace(String value, int cursor) {
    while (cursor < value.length() && isCssWhitespace(value.charAt(cursor))) {
      cursor++;
    }
    return cursor;
  }

  private static boolean isCssWhitespace(char ch) {
    return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\f';
  }

  private static void validateCss(String css) {
    // CSS escapes can hide function and at-rule names from the checks below. For example,
    // u\72l(...) is parsed by browsers as url(...).
    if (css.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("CSS escapes (backslashes) are not allowed");
    }

    String lower = css.toLowerCase(Locale.ROOT);

    // Image functions can load string paths without using url(...).
    for (String forbidden : FORBIDDEN_CSS_CONSTRUCTS) {
      if (lower.contains(forbidden)) {
        throw new IllegalArgumentException("CSS construct '" + forbidden + "' is not allowed");
      }
    }

    validateUrlReferences(css);
  }

  private static String findExternalScheme(String value) {
    String lower = value.toLowerCase(Locale.ROOT);

    for (String scheme : EXTERNAL_SCHEMES) {
      if (lower.contains(scheme)) {
        return scheme;
      }
    }

    return null;
  }

  private static String nodeType(Node node) {
    return switch (node.getNodeType()) {
      case Node.PROCESSING_INSTRUCTION_NODE -> "processing instruction";
      case Node.DOCUMENT_TYPE_NODE -> "document type declaration";
      case Node.ENTITY_NODE, Node.ENTITY_REFERENCE_NODE -> "entity reference";
      default -> "XML node type " + node.getNodeType();
    };
  }
}
