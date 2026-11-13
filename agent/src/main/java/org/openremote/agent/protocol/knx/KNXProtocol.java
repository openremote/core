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
package org.openremote.agent.protocol.knx;

import static org.openremote.model.asset.agent.AgentLink.getOrThrowAgentLinkProperty;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;
import static org.openremote.model.value.MetaItemType.AGENT_LINK;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.commons.io.IOUtils;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetTreeNode;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.*;
import org.openremote.model.protocol.ProtocolAssetImport;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueDescriptor;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.datapoint.CommandDP;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.DatapointMap;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.xml.KNXMLException;
import tuwien.auto.calimero.xml.XmlReader;

/** This protocol is used to connect to a KNX bus via an IP interface. */
public class KNXProtocol extends AbstractProtocol<KNXAgent, KNXAgentLink>
    implements ProtocolAssetImport {

  private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, KNXProtocol.class);
  public static final String PROTOCOL_DISPLAY_NAME = "KNX";
  protected static final String SUPPORT_DTD = XMLInputFactory.SUPPORT_DTD;
  protected static final String SUPPORTING_EXTERNAL_ENTITIES =
      "javax.xml.stream.isSupportingExternalEntities";

  protected KNXConnection connection;
  protected final Map<AttributeRef, Datapoint> attributeActionMap = new HashMap<>();
  protected final Map<AttributeRef, StateDP> attributeStatusMap = new HashMap<>();

  public KNXProtocol(KNXAgent agent) {
    super(agent);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  protected void doStart(Container container) throws Exception {

    boolean isNat = agent.isNATMode().orElse(false);
    boolean isRouting = agent.isRoutingMode().orElse(false);
    String gatewayAddress =
        agent
            .getHost()
            .orElseThrow(
                () -> {
                  String msg = "No KNX gateway IP address provided for protocol: " + this;
                  LOG.info(msg);
                  return new IllegalArgumentException(msg);
                });

    String bindAddress = agent.getBindHost().orElse(null);
    Integer gatewayPort = agent.getPort().orElse(3671);
    String messageSourceAddress = agent.getMessageSourceAddress().orElse("0.0.0");

    connection =
        new KNXConnection(
            gatewayAddress, bindAddress, gatewayPort, messageSourceAddress, isRouting, isNat);
    connection.addConnectionStatusConsumer(this::setConnectionStatus);
    connection.connect();
  }

  @Override
  protected void doStop(Container container) throws Exception {
    if (connection != null) {
      connection.removeConnectionStatusConsumer(this::setConnectionStatus);
      connection.disconnect();
    }
  }

  @Override
  protected void doLinkAttribute(String assetId, Attribute<?> attribute, KNXAgentLink agentLink)
      throws RuntimeException {
    final AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

    // Check there is a META_KNX_DPT
    String dpt = getOrThrowAgentLinkProperty(agentLink.getDpt(), "DPT");

    Optional<String> statusGA = agentLink.getStatusGroupAddress();
    Optional<String> actionGA = agentLink.getActionGroupAddress();

    if (!statusGA.isPresent() && !actionGA.isPresent()) {
      LOG.warning(
          "No status group address or action group address provided so nothing to do for protocol attribute: "
              + attributeRef);
      return;
    }

    // If this attribute relates to a read group then start monitoring that measurement and
    // broadcast any changes to the value
    statusGA.ifPresent(
        groupAddress -> {
          try {
            addStatusDatapoint(attributeRef, groupAddress, dpt);
          } catch (KNXFormatException e) {
            LOG.severe(
                "Give action group address is invalid for protocol attribute: "
                    + attributeRef
                    + " - "
                    + e.getMessage());
          }
        });

    // If this attribute relates to an action then store it
    actionGA.ifPresent(
        groupAddress -> {
          try {
            addActionDatapoint(attributeRef, groupAddress, dpt);
          } catch (KNXFormatException e) {
            LOG.severe(
                "Give action group address is invalid for protocol attribute: "
                    + attributeRef
                    + " - "
                    + e.getMessage());
          }
        });
  }

  @Override
  protected void doUnlinkAttribute(String assetId, Attribute<?> attribute, KNXAgentLink agentLink) {
    final AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

    // If this attribute is registered for status updates then un-subscribe it
    removeStatusDatapoint(attributeRef);

    // If this attribute has a stored action then remove it
    removeActionDatapoint(attributeRef);
  }

  @Override
  protected void doLinkedAttributeWrite(
      KNXAgentLink agentLink, AttributeEvent event, Object processedValue) {

    synchronized (attributeActionMap) {
      Datapoint datapoint = attributeActionMap.get(event.getRef());

      if (datapoint == null) {
        LOG.fine("Attribute isn't linked to a KNX datapoint so cannot process write: " + event);
        return;
      }

      connection.sendCommand(datapoint, event.getValue());

      // We assume KNX actuator will send new status on relevant status group address which will be
      // picked up by listener and updates the state again later
      updateLinkedAttribute(event.getRef(), event.getValue());
    }
  }

  protected void addActionDatapoint(AttributeRef attributeRef, String groupAddress, String dpt)
      throws KNXFormatException {
    synchronized (attributeActionMap) {
      Datapoint datapoint = new CommandDP(new GroupAddress(groupAddress), attributeRef.getName());
      datapoint.setDPT(0, dpt);

      attributeActionMap.put(attributeRef, datapoint);
      LOG.info(
          "Attribute registered for sending commands: "
              + attributeRef
              + " with datapoint: "
              + datapoint);
    }
  }

  protected void removeActionDatapoint(AttributeRef attributeRef) {
    synchronized (attributeActionMap) {
      attributeActionMap.remove(attributeRef);
    }
  }

  protected void addStatusDatapoint(AttributeRef attributeRef, String groupAddress, String dpt)
      throws KNXFormatException {
    synchronized (attributeStatusMap) {
      StateDP datapoint =
          new StateDP(new GroupAddress(groupAddress), attributeRef.getName(), 0, dpt);
      connection.addDatapointValueConsumer(
          datapoint, value -> handleKNXValueChange(attributeRef, value));

      attributeStatusMap.put(attributeRef, datapoint);
      LOG.info(
          "Attribute registered for status updates: "
              + attributeRef
              + " with datapoint: "
              + datapoint);
    }
  }

  protected void handleKNXValueChange(AttributeRef attributeRef, Object value) {
    LOG.fine("KNX protocol received value '" + value + "' for : " + attributeRef);
    updateLinkedAttribute(attributeRef, value);
  }

  protected void removeStatusDatapoint(AttributeRef attributeRef) {
    synchronized (attributeStatusMap) {
      StateDP statusDP = attributeStatusMap.remove(attributeRef);
      if (statusDP != null) {
        connection.removeDatapointValueConsumer(statusDP);
      }
    }
  }

  @Override
  public String getProtocolInstanceUri() {
    return "knx://" + connection;
  }

  @Override
  public Future<Void> startAssetImport(byte[] fileData, Consumer<AssetTreeNode[]> assetConsumer) {
    return executorService.submit(
        () -> {
          ZipInputStream zin = null;

          try {
            boolean fileFound = false;
            zin = new ZipInputStream(new ByteArrayInputStream(fileData));
            ZipEntry zipEntry = zin.getNextEntry();
            while (zipEntry != null) {
              if (zipEntry.getName().endsWith("/0.xml")) {
                fileFound = true;
                break;
              }
              zipEntry = zin.getNextEntry();
            }

            if (!fileFound) {
              String msg = "Failed to find '0.xml' in project file";
              LOG.info(msg);
              throw new IllegalStateException(msg);
            }

            // Create a transformer for the stylesheet.
            InputStream inputStream =
                KNXProtocol.class.getResourceAsStream(
                    "/org/openremote/agent/protocol/knx/ets_calimero_group_name.xsl");
            String xsd = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            xsd =
                xsd.trim()
                    .replaceFirst("^([\\W]+)<", "<"); // Get weird behavior sometimes without this
            Transformer transformer =
                createSecureTransformerFactory()
                    .newTransformer(new StreamSource(new StringReader(xsd)));

            // Set the URIResolver
            transformer.setURIResolver(new ETSFileURIResolver(fileData));

            // Transform the source XML
            String xml = IOUtils.toString(zin, StandardCharsets.UTF_8);
            xml =
                xml.trim()
                    .replaceFirst("^([\\W]+)<", "<"); // Get weird behavior sometimes without this
            StringWriter writer = new StringWriter();
            StringReader reader = new StringReader(xml);
            transformer.transform(new StreamSource(reader), new StreamResult(writer));
            xml = writer.toString();

            // we use a map of state-based data points and read from the transformed xml
            final DatapointMap<StateDP> datapoints = new DatapointMap<>();
            validateSecureXml(xml);
            try (final XmlReader r =
                tuwien.auto.calimero.xml.XmlInputFactory.newInstance()
                    .createXMLStreamReader(new StringReader(xml))) {
              datapoints.load(r);
            } catch (final KNXMLException e) {
              String msg = "Error loading parsed ETS file: " + e.getMessage();
              LOG.warning(msg);
              throw new IllegalStateException(msg, e);
            }

            Map<String, Asset<?>> createdAssets = new HashMap<>();
            for (StateDP dp : datapoints.getDatapoints()) {
              if (dp.getName().endsWith("#A")) {
                createAsset(dp, false, createdAssets);
              } else if (dp.getName().endsWith("#S")) {
                createAsset(dp, true, createdAssets);
              } else if (dp.getName().endsWith("#SA") || dp.getName().endsWith("#AS")) {
                createAsset(dp, false, createdAssets);
                createAsset(dp, true, createdAssets);
              } else {
                LOG.info(
                    "Only group addresses ending on #A, #S, #AS or #SA will be imported. Ignoring: "
                        + dp.getName());
              }
            }

            assetConsumer.accept(
                createdAssets.values().stream()
                    .map(AssetTreeNode::new)
                    .toArray(AssetTreeNode[]::new));

          } catch (Exception e) {
            LOG.log(Level.WARNING, "ETS import error", e);
          } finally {
            if (zin != null) {
              try {
                zin.close();
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
          }
        },
        null);
  }

  protected TransformerFactory createSecureTransformerFactory() throws Exception {
    TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    return factory;
  }

  protected XMLInputFactory createSecureXmlInputFactory() {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(SUPPORT_DTD, false);
    factory.setProperty(SUPPORTING_EXTERNAL_ENTITIES, false);
    setXmlInputFactoryPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
    setXmlInputFactoryPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  protected void setXmlInputFactoryPropertyIfSupported(
      XMLInputFactory factory, String property, Object value) {
    try {
      factory.setProperty(property, value);
    } catch (IllegalArgumentException ignored) {
      LOG.fine("XMLInputFactory does not support property: " + property);
    }
  }

  protected void validateSecureXml(String xml) throws Exception {
    XMLStreamReader reader = null;
    try {
      reader = createSecureXmlInputFactory().createXMLStreamReader(new StringReader(xml));
      while (reader.hasNext()) {
        reader.next();
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  protected void createAsset(
      StateDP datapoint, boolean isStatusGA, Map<String, Asset<?>> createdAssets) {
    String name = datapoint.getName().substring(0, datapoint.getName().length() - 3);
    String assetName = name.replaceAll(" -.*-", "");
    Asset<?> asset;

    if (createdAssets.containsKey(assetName)) {
      asset = createdAssets.get(assetName);
    } else {
      asset = new ThingAsset(assetName);
    }

    String attrName = assetName.replaceAll(" ", "");
    ValueDescriptor<?> type = TypeMapper.toAttributeType(datapoint);

    KNXAgentLink agentLink =
        new KNXAgentLink(
            agent.getId(),
            datapoint.getDPT(),
            !isStatusGA ? datapoint.getMainAddress().toString() : null,
            isStatusGA ? datapoint.getMainAddress().toString() : null);

    Attribute<?> attr =
        asset
            .getAttributes()
            .get(attrName)
            .orElse(
                new Attribute<>(attrName, type)
                    .addMeta(
                        new MetaItem<>(MetaItemType.LABEL, name),
                        new MetaItem<>(AGENT_LINK, agentLink)));

    asset.getAttributes().addOrReplace(attr);

    createdAssets.put(assetName, asset);
  }
}
