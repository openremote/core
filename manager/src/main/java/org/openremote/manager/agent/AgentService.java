/*
 * Copyright 2016, OpenRemote Inc.
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
package org.openremote.manager.agent;

import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType;
import static org.openremote.manager.gateway.GatewayService.isNotForGateway;
import static org.openremote.model.attribute.Attribute.getAddedOrModifiedAttributes;
import static org.openremote.model.value.MetaItemType.AGENT_LINK;

import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingException;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.asset.AssetTreeNode;
import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.agent.Protocol;
import org.openremote.model.attribute.*;
import org.openremote.model.protocol.ProtocolAssetDiscovery;
import org.openremote.model.protocol.ProtocolAssetImport;
import org.openremote.model.protocol.ProtocolAssetService;
import org.openremote.model.protocol.ProtocolInstanceDiscovery;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.NameValuePredicate;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.util.Pair;
import org.openremote.model.util.TextUtil;

/**
 * This service's role is to communicate asset attribute writes to actuators, through protocol
 * instances. It also handles redeploying {@link Protocol} instances when an {@link Attribute} of
 * the associated {@link Agent} is modified either via Asset CRUD or just via an {@link
 * AttributeEvent}.
 *
 * <p>Only an {@link AttributeEvent} for an {@link Attribute} containing a {@link
 * org.openremote.model.value.MetaItemType#AGENT_LINK} {@link MetaItem} will be intercepted here and
 * passed to the associated {@link Protocol} instance for processing; the event will not be
 * committed to the DB and it is up to the {@link Protocol} to generate a new {@link AttributeEvent}
 * to signal that the action has been successfully handled.
 *
 * <p>Any {@link AttributeEvent} that originates from an {@link Agent} {@link Protocol} will not be
 * consumed by the source {@link Protocol} when it passes back through this service; this is to
 * prevent infinite loops.
 */
public class AgentService extends RouteBuilder implements ContainerService {

  protected class AgentProtocolAssetService implements ProtocolAssetService {
    protected Agent<?, ?, ?> agent;

    public AgentProtocolAssetService(Agent<?, ?, ?> agent) {
      this.agent = agent;
    }

    @Override
    public <T extends Asset<?>> T mergeAsset(T asset) {
      Objects.requireNonNull(asset.getId());

      if (TextUtil.isNullOrEmpty(asset.getRealm())) {
        asset.setRealm(agent.getRealm());
      } else if (!Objects.equals(asset.getRealm(), agent.getRealm())) {
        String msg = "Protocol attempting to merge asset into another realm: " + agent;
        Protocol.LOG.warning(msg);
        throw new IllegalArgumentException(msg);
      }

      // TODO: Define access permissions for merged asset (user asset links inherit from parent
      // agent?)
      LOG.fine("Merging asset with protocol-provided: " + asset);
      return assetStorageService.merge(asset, true);
    }

    @Override
    public boolean deleteAssets(String... assetIds) {
      for (String assetId : assetIds) {
        Asset<?> asset = findAsset(assetId);
        if (asset != null) {
          if (!Objects.equals(asset.getRealm(), agent.getRealm())) {
            Protocol.LOG.warning(
                "Protocol attempting to delete asset from another realm: " + agent);
            throw new IllegalArgumentException(
                "Protocol attempting to delete asset from another realm");
          }
        }
      }
      LOG.fine("Deleting protocol-provided: " + Arrays.toString(assetIds));
      return assetStorageService.delete(Arrays.asList(assetIds), false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Asset<?>> T findAsset(String assetId) {
      LOG.fine("Getting protocol-provided: " + assetId);
      T asset = (T) assetStorageService.find(assetId);
      if (asset != null) {
        if (!Objects.equals(asset.getRealm(), agent.getRealm())) {
          Protocol.LOG.warning("Protocol attempting to find asset from another realm: " + agent);
          throw new IllegalArgumentException(
              "Protocol attempting to find asset from another realm");
        }
      }
      return asset;
    }

    @Override
    public List<Asset<?>> findAssets(AssetQuery assetQuery) {
      List<Asset<?>> assets =
          assetStorageService.findAll(assetQuery.realm(new RealmPredicate(agent.getRealm())));
      for (Asset<?> asset : assets) {
        if (!Objects.equals(asset.getRealm(), agent.getRealm())) {
          Protocol.LOG.warning("Protocol attempting to find asset from another realm: " + agent);
          throw new IllegalArgumentException(
              "Protocol attempting to find asset from another realm");
        }
      }
      return assets;
    }

    @Override
    public void sendAttributeEvent(AttributeEvent attributeEvent) {
      if (TextUtil.isNullOrEmpty(attributeEvent.getRealm())) {
        attributeEvent.setRealm(agent.getRealm());
      } else if (!Objects.equals(attributeEvent.getRealm(), agent.getRealm())) {
        Protocol.LOG.warning(
            "Protocol attempting to send attribute event to another realm: " + agent);
        throw new IllegalArgumentException(
            "Protocol attempting to send attribute event to another realm");
      }
      AgentService.this.sendAttributeEvent(attributeEvent);
    }

    @Override
    public void subscribeChildAssetChange(
        Consumer<PersistenceEvent<Asset<?>>> assetChangeConsumer) {
      if (!getAgents().containsKey(agent.getId())) {
        LOG.fine(
            "Attempt to subscribe to child asset changes with an invalid agent ID: "
                + agent.getId());
        return;
      }

      Set<Consumer<PersistenceEvent<Asset<?>>>> consumers =
          childAssetSubscriptions.computeIfAbsent(
              agent.getId(), (id) -> Collections.synchronizedSet(new HashSet<>()));
      consumers.add(assetChangeConsumer);
    }

    @Override
    public void unsubscribeChildAssetChange(
        Consumer<PersistenceEvent<Asset<?>>> assetChangeConsumer) {
      childAssetSubscriptions.computeIfPresent(
          agent.getId(),
          (id, consumers) -> {
            consumers.remove(assetChangeConsumer);
            return consumers.isEmpty() ? null : consumers;
          });
    }
  }

  private static final Logger LOG = Logger.getLogger(AgentService.class.getName());
  public static final int PRIORITY =
      MessageBrokerService.PRIORITY
          + 100; // Start quite late to ensure asset model etc. are initialized
  protected AssetProcessingService assetProcessingService;
  protected AssetStorageService assetStorageService;
  protected ClientEventService clientEventService;
  protected GatewayService gatewayService;
  protected ExecutorService executorService;
  protected Map<String, Agent<?, ?, ?>> agentMap;
  protected final Map<String, Future<Void>> agentDiscoveryImportFutureMap =
      new ConcurrentHashMap<>();
  protected final Map<String, Protocol<?>> protocolInstanceMap = new ConcurrentHashMap<>();
  protected final Map<String, Set<Consumer<PersistenceEvent<Asset<?>>>>> childAssetSubscriptions =
      new ConcurrentHashMap<>();
  protected boolean initDone;
  protected Container container;
  protected final Object agentLock = new Object();

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init(Container container) throws Exception {
    this.container = container;
    assetProcessingService = container.getService(AssetProcessingService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    clientEventService = container.getService(ClientEventService.class);
    gatewayService = container.getService(GatewayService.class);
    executorService = container.getExecutor();

    if (initDone) {
      return;
    }

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new AgentResourceImpl(
                container.getService(TimerService.class),
                container.getService(ManagerIdentityService.class),
                assetStorageService,
                this,
                container.getExecutor()));

    assetProcessingService.addEventInterceptor(this::onAttributeEventIntercepted);

    clientEventService.addSubscription(
        AttributeEvent.class,
        new AssetFilter<AttributeEvent>().setAssetClasses(Collections.singletonList(Agent.class)),
        this::onAgentAttributeEvent);

    initDone = true;
  }

  @Override
  public void start(Container container) throws Exception {
    container.getService(MessageBrokerService.class).getContext().addRoutes(this);

    // Load all enabled agents and instantiate a protocol instance for each
    LOG.fine("Loading agents...");
    Collection<Agent<?, ?, ?>> agents = getAgents().values();
    LOG.fine("Found agent count = " + agents.size());

    agents.forEach(this::doAgentInit);
  }

  @Override
  public void stop(Container container) throws Exception {
    if (agentMap != null) {
      List<Agent<?, ?, ?>> agents = new ArrayList<>(agentMap.values());
      agents.forEach(agent -> this.undeployAgent(agent.getId()));
      agentMap.clear();
    }
    protocolInstanceMap.clear();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void configure() throws Exception {
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-Agent")
        .filter(isPersistenceEventForEntityType(Asset.class))
        .filter(isNotForGateway(gatewayService))
        .process(
            exchange -> {
              PersistenceEvent<Asset<?>> persistenceEvent =
                  (PersistenceEvent<Asset<?>>) exchange.getIn().getBody(PersistenceEvent.class);

              if (isPersistenceEventForEntityType(Agent.class).matches(exchange)) {
                PersistenceEvent<Agent<?, ?, ?>> agentEvent =
                    (PersistenceEvent<Agent<?, ?, ?>>) (PersistenceEvent<?>) persistenceEvent;
                processAgentChange(agentEvent);
              } else {
                processAssetChange(persistenceEvent);
              }
            });
  }

  /** Called when an {@link Agent} is modified in the DB */
  protected void processAgentChange(PersistenceEvent<Agent<?, ?, ?>> persistenceEvent) {

    LOG.finest("Processing agent persistence event: " + persistenceEvent.getCause());
    Agent<?, ?, ?> agent = persistenceEvent.getEntity();

    switch (persistenceEvent.getCause()) {
      case CREATE, UPDATE -> deployAgent(agent);
      case DELETE -> undeployAgent(agent.getId());
    }
  }

  /**
   * Deploy the {@link Agent} by creating a protocol instance, starting it and linking all
   * attributes
   */
  protected void deployAgent(Agent<?, ?, ?> agent) {
    synchronized (agentLock) {
      undeployAgent(agent.getId());
      agent = addAgent(agent);

      if (agent == null) {
        return;
      }

      doAgentInit(agent);
    }
  }

  /**
   * Looks for new, modified and obsolete AGENT_LINK attributes and links / unlinks them with the
   * protocol
   */
  protected void processAssetChange(PersistenceEvent<Asset<?>> persistenceEvent) {

    LOG.finest("Processing asset persistence event: " + persistenceEvent.getCause());
    Asset<?> asset = persistenceEvent.getEntity();

    switch (persistenceEvent.getCause()) {
      case CREATE ->

          // Link any AGENT_LINK attributes to their referenced agent asset
          getGroupedAgentLinkAttributes(asset.getAttributes().stream(), attribute -> true)
              .forEach(
                  (agent, attributes) -> this.linkAttributes(agent, asset.getId(), attributes));
      case UPDATE -> {
        if (!persistenceEvent.hasPropertyChanged("attributes")) {
          return;
        }
        List<Attribute<?>> oldLinkedAttributes =
            ((AttributeMap) persistenceEvent.getPreviousState("attributes"))
                .stream().filter(attr -> attr.hasMeta(AGENT_LINK)).collect(toList());
        List<Attribute<?>> newLinkedAttributes =
            ((AttributeMap) persistenceEvent.getCurrentState("attributes"))
                .stream().filter(attr -> attr.hasMeta(AGENT_LINK)).collect(Collectors.toList());

        // Unlink obsolete or modified linked attributes
        List<Attribute<?>> obsoleteOrModified =
            getAddedOrModifiedAttributes(newLinkedAttributes, oldLinkedAttributes).toList();
        getGroupedAgentLinkAttributes(obsoleteOrModified.stream(), attribute -> true)
            .forEach(
                (agent, attributes) -> unlinkAttributes(agent.getId(), asset.getId(), attributes));

        // Link new or modified attributes
        getGroupedAgentLinkAttributes(
                newLinkedAttributes.stream()
                    .filter(
                        attr ->
                            !oldLinkedAttributes.contains(attr)
                                || obsoleteOrModified.contains(attr)),
                attribute -> true)
            .forEach((agent, attributes) -> linkAttributes(agent, asset.getId(), attributes));
      }
      case DELETE -> // Unlink any AGENT_LINK attributes from the referenced protocol
          getGroupedAgentLinkAttributes(asset.getAttributes().stream(), attribute -> true)
              .forEach(
                  (agent, attributes) ->
                      unlinkAttributes(agent.getId(), asset.getId(), attributes));
    }

    notifyAgentAncestor(asset, persistenceEvent);
  }

  protected void notifyAgentAncestor(Asset<?> asset, PersistenceEvent<Asset<?>> persistenceEvent) {
    String parentId = asset.getParentId();

    if ((asset instanceof Agent) || parentId == null) {
      return;
    }

    String ancestorAgentId = null;

    if (agentMap.containsKey(parentId)) {
      ancestorAgentId = parentId;
    } else {
      // If path is not loaded then get the parents path as the asset might have been deleted
      if (asset.getPath() == null) {
        Asset<?> parentAsset = assetStorageService.find(parentId);
        if (parentAsset != null && parentAsset.getPath() != null) {
          ancestorAgentId =
              Arrays.stream(parentAsset.getPath())
                  .filter(assetId -> getAgents().containsKey(assetId))
                  .findFirst()
                  .orElse(null);
        }
      }
    }

    if (ancestorAgentId != null) {
      notifyChildAssetChange(ancestorAgentId, persistenceEvent);
    }
  }

  protected void sendAttributeEvent(AttributeEvent event) {
    // Set the source so we can ignore the intercept when the event comes back through the chain
    assetProcessingService.sendAttributeEvent(event, getClass().getSimpleName());
  }

  protected void doAgentInit(Agent<?, ?, ?> agent) {
    boolean isDisabled = agent.isDisabled().orElse(false);
    if (isDisabled) {
      LOG.fine("Agent is disabled so not starting: " + agent);
      sendAttributeEvent(
          new AttributeEvent(agent.getId(), Agent.STATUS.getName(), ConnectionStatus.DISABLED));
    } else {
      executorService.execute(() -> this.startAgent(agent));
    }
  }

  protected void startAgent(Agent<?, ?, ?> agent) {

    synchronized (agentLock) {
      Protocol<?> protocol = null;

      try {
        protocol = agent.getProtocolInstance();
        protocol.setAssetService(new AgentProtocolAssetService(agent));

        LOG.fine("Starting protocol instance: " + protocol);
        protocol.start(container);
        protocolInstanceMap.put(agent.getId(), protocol);
        LOG.fine("Started protocol instance: " + protocol);

        LOG.finest("Linking attributes to protocol instance: " + protocol);

        // Get all assets that have attributes with agent link meta for this agent
        List<Asset<?>> assets =
            assetStorageService.findAll(
                new AssetQuery()
                    .attributes(
                        new AttributePredicate()
                            .meta(
                                new NameValuePredicate(
                                    AGENT_LINK,
                                    new StringPredicate(agent.getId()),
                                    false,
                                    new NameValuePredicate.Path("id")))));

        LOG.finest(
            "Found '"
                + assets.size()
                + "' asset(s) with attributes linked to this protocol instance: "
                + protocol);

        assets.forEach(
            asset ->
                getGroupedAgentLinkAttributes(
                        asset.getAttributes().stream(),
                        assetAttribute ->
                            assetAttribute
                                .getMetaValue(AGENT_LINK)
                                .map(agentLink -> agentLink.getId().equals(agent.getId()))
                                .orElse(false))
                    .forEach(
                        (agnt, attributes) -> linkAttributes(agnt, asset.getId(), attributes)));
      } catch (Exception e) {
        if (protocol != null) {
          try {
            protocol.stop(container);
          } catch (Exception ignored) {
          }
        }
        protocolInstanceMap.remove(agent.getId());
        LOG.log(
            Level.SEVERE,
            "Failed to start protocol '" + protocol + "': " + agent + " msg=" + e.getMessage());
        sendAttributeEvent(
            new AttributeEvent(agent.getId(), Agent.STATUS.getName(), ConnectionStatus.ERROR));
      }
    }
  }

  protected void undeployAgent(String agentId) {
    synchronized (agentLock) {
      removeAgent(agentId);
      Protocol<?> protocol = protocolInstanceMap.get(agentId);

      if (protocol == null) {
        return;
      }

      Map<String, List<Attribute<?>>> groupedAttributes =
          protocol.getLinkedAttributes().entrySet().stream()
              .collect(
                  Collectors.groupingBy(
                      entry -> entry.getKey().getId(), mapping(Map.Entry::getValue, toList())));

      groupedAttributes.forEach(
          (assetId, linkedAttributes) -> unlinkAttributes(agentId, assetId, linkedAttributes));

      // Stop the protocol instance
      try {
        protocol.stop(container);
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Protocol instance threw an exception while being stopped", e);
      }

      // Remove child asset subscriptions for this agent
      childAssetSubscriptions.remove(agentId);
      protocolInstanceMap.remove(agentId);
    }
  }

  protected void linkAttributes(
      Agent<?, ?, ?> agent, String assetId, Collection<Attribute<?>> attributes) {
    final Protocol<?> protocol = getProtocolInstance(agent.getId());

    if (protocol == null) {
      return;
    }

    synchronized (protocol) {
      LOG.fine(
          "Linking asset '"
              + assetId
              + "' attributes linked to protocol: assetId="
              + assetId
              + ", attributes="
              + attributes.size()
              + ", protocol="
              + protocol);

      attributes.forEach(
          attribute -> {
            AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
            try {
              if (!protocol.getLinkedAttributes().containsKey(attributeRef)) {
                LOG.finest("Linking attribute '" + attributeRef + "' to protocol: " + protocol);
                protocol.linkAttribute(assetId, attribute);
              }
            } catch (Exception ex) {
              LOG.log(
                  Level.SEVERE,
                  "Failed to link attribute '"
                      + attributeRef
                      + "' to protocol: "
                      + protocol
                      + " msg="
                      + ex.getMessage());
            }
          });
    }
  }

  protected void unlinkAttributes(String agentId, String assetId, List<Attribute<?>> attributes) {
    final Protocol<?> protocol = getProtocolInstance(agentId);

    if (protocol == null) {
      return;
    }

    synchronized (protocol) {
      LOG.fine(
          "Unlinking asset '"
              + assetId
              + "' attributes linked to protocol: assetId="
              + assetId
              + ", attributes="
              + attributes.size()
              + ", protocol="
              + protocol);

      attributes.forEach(
          attribute -> {
            try {
              AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
              if (protocol.getLinkedAttributes().containsKey(attributeRef)) {
                LOG.finest("Unlinking attribute '" + attributeRef + "' to protocol: " + protocol);
                protocol.unlinkAttribute(assetId, attribute);
              }
            } catch (Exception ex) {
              LOG.log(
                  Level.SEVERE,
                  "Ignoring error on unlinking attribute '"
                      + attribute
                      + "' from protocol: "
                      + protocol,
                  ex);
            }
          });
    }
  }

  /**
   * Intercepts any {@link AttributeEvent} for an {@link Attribute} that has an {@link
   * org.openremote.model.value.MetaItemType#AGENT_LINK} {@link MetaItem} and passes it to the
   * {@link Agent}'s {@link Protocol#processLinkedAttributeWrite} method for handling.
   *
   * <p>If the {@link AttributeEvent} originated from the {@link Agent} that the {@link Attribute}
   * is linked to then it is not intercepted.
   */
  protected boolean onAttributeEventIntercepted(EntityManager em, AttributeEvent event)
      throws AssetProcessingException {

    // Don't intercept an event that was generated by this service
    if (getClass().getSimpleName().equals(event.getSource())) {
      return false;
    }

    Optional<AgentLink> agentLinkOptional = event.getMetaValue(AGENT_LINK);

    // Never intercept events with no agent link
    return agentLinkOptional
        .map(
            agentLink -> {
              LOG.finest(
                  "Attribute event for agent linked attribute: agent="
                      + agentLink.getId()
                      + ", ref="
                      + event.getRef());

              if (event.isOutdated()) {
                // Don't process outdated events but still intercept them
                return true;
              }

              Protocol<?> protocolInstance = getProtocolInstance(agentLink.getId());
              if (protocolInstance == null) {
                throw new AssetProcessingException(
                    AttributeWriteFailure.CANNOT_PROCESS,
                    "Agent protocol instance not found, agent may be disabled or has been deleted: attributeRef="
                        + event.getRef()
                        + ", agentLink="
                        + agentLink);
              }
              try {
                protocolInstance.processLinkedAttributeWrite(event);
              } catch (Exception e) {
                AttributeWriteFailure failure = AttributeWriteFailure.UNKNOWN;
                String msg = e.getMessage();
                if (e instanceof AssetProcessingException assetProcessingException) {
                  failure = assetProcessingException.getReason();
                }
                throw new AssetProcessingException(
                    failure,
                    "An exception occurred while the protocol was trying to process the attribute write request: agentLink="
                        + agentLink
                        + ", msg="
                        + msg);
              }
              return true; // Processing complete, skip other processors
            })
        .orElse(false); // This is a regular attribute so allow the processing to continue
  }

  /**
   * Called when an {@link AttributeEvent} for an {@link Agent} is broadcast on the client event bus
   * (i.e. the attribute has been updated in the DB).
   *
   * <p>We use this to try and react to agent changes in a generic way by re-initializing the agent
   * to simplify each agent implementation.
   */
  // TODO: Queue up agent attribute events and do a single agent replacement
  protected void onAgentAttributeEvent(AttributeEvent event) {

    synchronized (agentLock) {
      Agent<?, ?, ?> agent = getAgent(event.getId());

      if (agent == null) {
        return;
      }

      // Check that the event has a newer timestamp than the existing agent attribute - if the
      // attribute doesn't
      // exist on the agent either then assume the agent has been modified and attribute removed
      boolean eventOutdated =
          agent
              .getAttribute(event.getName())
              .flatMap(Attribute::getTimestamp)
              .map(timestamp -> event.getTimestamp() <= timestamp)
              .orElse(true);

      if (eventOutdated) {
        return;
      }

      // Ignore events that have come from the AssetStorageService (i.e. Asset merges as these are
      // handled separately)
      if (AssetStorageService.class.getSimpleName().equals(event.getSource())) {
        return;
      }

      // Update in memory agent
      agent
          .getAttribute(event.getName())
          .ifPresent(attr -> attr.setValue(event.getValue().orElse(null), event.getTimestamp()));

      Protocol<?> protocolInstance = getProtocolInstance(agent.getId());

      if (protocolInstance == null) {
        if (Agent.DISABLED.getName().equals(event.getName())) {
          // Maybe agent was disabled and now isn't - use standard mechanism
          deployAgent(agent);
        }
        return;
      }

      LOG.finer(
          "Notifying protocol instance of an event for one of its agent attributes: "
              + event.getRef());
      if (protocolInstance.onAgentAttributeChanged(event)) {
        LOG.info(
            "Protocol has requested recreation following agent attribute event: " + event.getRef());
        deployAgent(agent);
      }
    }
  }

  /** Gets all agent link attributes and their linked agent and groups them by agent */
  protected Map<Agent<?, ?, ?>, List<Attribute<?>>> getGroupedAgentLinkAttributes(
      Stream<Attribute<?>> attributes, Predicate<Attribute<?>> filter) {
    return attributes
        .filter(
            attribute ->
                // Exclude attributes without agent link or with agent link to not recognized agents
                // (could be gateway agents)
                attribute
                    .getMetaValue(AGENT_LINK)
                    .map(
                        agentLink -> {
                          if (!getAgents().containsKey(agentLink.getId())) {
                            LOG.finest(
                                "Agent linked attribute, agent not found or this is a gateway asset: "
                                    + attribute);
                            return false;
                          }
                          return true;
                        })
                    .orElse(false))
        .filter(filter)
        .map(
            attribute ->
                new Pair<Agent<?, ?, ?>, Attribute<?>>(
                    attribute
                        .getMetaValue(AGENT_LINK)
                        .map(AgentLink::getId)
                        .map(agentId -> getAgents().get(agentId))
                        .orElse(null),
                    attribute))
        .filter(agentAttribute -> agentAttribute.key != null)
        .collect(
            Collectors.groupingBy(
                agentAttribute -> agentAttribute.key,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    agentAttribute ->
                        agentAttribute.stream()
                            .map(item -> item.value)
                            .collect(
                                toList())) // TODO had to change to this because compiler has issues
                // with inferring types, need to check for a better
                // solution
                ));
  }

  public String toString() {
    return getClass().getSimpleName() + "{" + "}";
  }

  protected Agent<?, ?, ?> addAgent(Agent<?, ?, ?> agent) {

    // Fully load agent asset if path and parent info not loaded
    if (agent.getPath() == null || (agent.getPath().length > 1 && agent.getParentId() == null)) {
      LOG.fine("Agent is not fully loaded so retrieving the agent from the DB: " + agent.getId());
      final Agent<?, ?, ?> loadedAgent = assetStorageService.find(agent.getId(), true, Agent.class);
      if (loadedAgent == null) {
        LOG.fine("Agent not found in the DB, maybe it has been removed: " + agent.getId());
        return null;
      }
      agent = loadedAgent;
    }

    getAgents().put(agent.getId(), agent);
    return agent;
  }

  @SuppressWarnings({"BooleanMethodIsAlwaysInverted"})
  protected boolean removeAgent(String agentId) {
    return getAgents().remove(agentId) != null;
  }

  public Agent<?, ?, ?> getAgent(String agentId) {
    return getAgents().get(agentId);
  }

  protected Map<String, Agent<?, ?, ?>> getAgents() {
    synchronized (agentLock) {
      if (agentMap == null) {
        agentMap =
            assetStorageService.findAll(new AssetQuery().types(Agent.class)).stream()
                .filter(
                    asset ->
                        gatewayService.getLocallyRegisteredGatewayId(asset.getId(), null) == null)
                .collect(Collectors.toConcurrentMap(Asset::getId, agent -> (Agent<?, ?, ?>) agent));
      }
      return agentMap;
    }
  }

  public Protocol<?> getProtocolInstance(Agent<?, ?, ?> agent) {
    return getProtocolInstance(agent.getId());
  }

  public Protocol<?> getProtocolInstance(String agentId) {
    return protocolInstanceMap.get(agentId);
  }

  protected void notifyChildAssetChange(
      String agentId, PersistenceEvent<Asset<?>> assetPersistenceEvent) {
    childAssetSubscriptions.computeIfPresent(
        agentId,
        (id, consumers) -> {
          LOG.finest(
              "Notifying child asset change consumers of change to agent child asset: Agent ID="
                  + id
                  + ", Asset<?> ID="
                  + assetPersistenceEvent.getEntity().getId());
          try {
            consumers.forEach(consumer -> consumer.accept(assetPersistenceEvent));
          } catch (Exception e) {
            LOG.log(
                Level.WARNING,
                "Child asset change consumer threw an exception: Agent ID="
                    + id
                    + ", Asset<?> ID="
                    + assetPersistenceEvent.getEntity().getId(),
                e);
          }
          return consumers;
        });
  }

  public boolean isProtocolAssetDiscoveryOrImportRunning(String agentId) {
    return agentDiscoveryImportFutureMap.containsKey(agentId);
  }

  public Future<Void> doProtocolInstanceDiscovery(
      String parentId,
      Class<? extends ProtocolInstanceDiscovery> instanceDiscoveryProviderClass,
      Consumer<Agent<?, ?, ?>[]> onDiscovered) {

    LOG.fine(
        "Initiating protocol instance discovery: Provider = " + instanceDiscoveryProviderClass);

    Runnable task =
        () -> {
          if (parentId != null
              && gatewayService.getLocallyRegisteredGatewayId(parentId, null) != null) {
            // TODO: Implement gateway instance discovery using client event bus
            return;
          }

          try {
            ProtocolInstanceDiscovery instanceDiscovery =
                instanceDiscoveryProviderClass.getDeclaredConstructor().newInstance();
            Future<Void> discoveryFuture = instanceDiscovery.startInstanceDiscovery(onDiscovered);
            discoveryFuture.get();
          } catch (InterruptedException e) {
            LOG.fine("Protocol instance discovery was canceled");
          } catch (Exception e) {
            LOG.log(
                Level.WARNING,
                "Failed to do protocol instance discovery: Provider = "
                    + instanceDiscoveryProviderClass,
                e);
          } finally {
            LOG.fine(
                "Finished protocol instance discovery: Provider = "
                    + instanceDiscoveryProviderClass);
          }
        };

    return executorService.submit(task, null);
  }

  public Future<Void> doProtocolAssetDiscovery(
      Agent<?, ?, ?> agent, Consumer<AssetTreeNode[]> onDiscovered) throws RuntimeException {

    Protocol<?> protocol = getProtocolInstance(agent.getId());

    if (protocol == null) {
      throw new UnsupportedOperationException(
          "Agent is either invalid, disabled or mis-configured: " + agent);
    }

    if (!(protocol instanceof ProtocolAssetDiscovery)) {
      throw new UnsupportedOperationException("Agent protocol doesn't support asset discovery");
    }

    LOG.fine("Initiating protocol asset discovery: Agent = " + agent);

    synchronized (agentDiscoveryImportFutureMap) {
      okToContinueWithImportOrDiscovery(agent.getId());

      Runnable task =
          () -> {
            try {
              if (gatewayService.getLocallyRegisteredGatewayId(agent.getId(), null) != null) {
                // TODO: Implement gateway instance discovery using client event bus
                return;
              }

              ProtocolAssetDiscovery assetDiscovery = (ProtocolAssetDiscovery) protocol;
              Future<Void> discoveryFuture = assetDiscovery.startAssetDiscovery(onDiscovered);
              discoveryFuture.get();
            } catch (InterruptedException e) {
              LOG.fine("Protocol asset discovery was canceled");
            } catch (Exception e) {
              LOG.log(Level.WARNING, "Failed to do protocol asset discovery: Agent = " + agent, e);
            } finally {
              LOG.fine("Finished protocol asset discovery: Agent = " + agent);
              agentDiscoveryImportFutureMap.remove(agent.getId());
            }
          };

      Future<Void> future = executorService.submit(task, null);
      agentDiscoveryImportFutureMap.put(agent.getId(), future);
      return future;
    }
  }

  public Future<Void> doProtocolAssetImport(
      Agent<?, ?, ?> agent, byte[] fileData, Consumer<AssetTreeNode[]> onDiscovered)
      throws RuntimeException {

    Protocol<?> protocol = getProtocolInstance(agent.getId());

    if (protocol == null) {
      throw new UnsupportedOperationException(
          "Agent is either invalid, disabled or mis-configured: " + agent);
    }

    if (!(protocol instanceof ProtocolAssetImport)) {
      throw new UnsupportedOperationException("Agent protocol doesn't support asset import");
    }

    LOG.fine("Initiating protocol asset import: Agent = " + agent);
    synchronized (agentDiscoveryImportFutureMap) {
      okToContinueWithImportOrDiscovery(agent.getId());

      Runnable task =
          () -> {
            try {
              if (gatewayService.getLocallyRegisteredGatewayId(agent.getId(), null) != null) {
                // TODO: Implement gateway instance discovery using client event bus
                return;
              }

              ProtocolAssetImport assetImport = (ProtocolAssetImport) protocol;
              Future<Void> discoveryFuture = assetImport.startAssetImport(fileData, onDiscovered);
              discoveryFuture.get();
            } catch (InterruptedException e) {
              LOG.fine("Protocol asset import was canceled");
            } catch (Exception e) {
              LOG.log(Level.WARNING, "Failed to do protocol asset import: Agent = " + agent, e);
            } finally {
              LOG.fine("Finished protocol asset import: Agent = " + agent);
              agentDiscoveryImportFutureMap.remove(agent.getId());
            }
          };

      Future<Void> future = executorService.submit(task, null);
      agentDiscoveryImportFutureMap.put(agent.getId(), future);
      return future;
    }
  }

  protected void okToContinueWithImportOrDiscovery(String agentId) {
    if (agentDiscoveryImportFutureMap.containsKey(agentId)) {
      String msg =
          "Protocol asset discovery or import already running for requested agent: " + agentId;
      LOG.fine(msg);
      throw new IllegalStateException(msg);
    }
  }
}
