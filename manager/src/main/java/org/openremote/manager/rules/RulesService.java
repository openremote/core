/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.manager.rules;

import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;
import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType;
import static org.openremote.manager.gateway.GatewayService.isNotForGateway;
import static org.openremote.model.util.MapAccess.getBoolean;
import static org.openremote.model.util.MapAccess.getInteger;

import io.micrometer.core.instrument.Tags;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.alarm.AlarmService;
import org.openremote.manager.asset.AssetProcessingException;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.datapoint.AssetDatapointService;
import org.openremote.manager.datapoint.AssetPredictedDatapointService;
import org.openremote.manager.event.AttributeEventInterceptor;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.notification.NotificationService;
import org.openremote.manager.rules.flow.FlowResourceImpl;
import org.openremote.manager.rules.geofence.GeofenceAssetAdapter;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.manager.webhook.WebhookService;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeInfo;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.event.shared.EventSubscription;
import org.openremote.model.event.shared.RealmFilter;
import org.openremote.model.event.shared.RealmScopedEvent;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.RulesetQuery;
import org.openremote.model.query.filter.LocationAttributePredicate;
import org.openremote.model.rules.*;
import org.openremote.model.rules.geofence.GeofenceDefinition;
import org.openremote.model.security.ClientRole;
import org.openremote.model.security.Realm;
import org.openremote.model.util.Pair;
import org.openremote.model.value.MetaHolder;
import org.openremote.model.value.MetaItemType;

/**
 * Manages {@link RulesEngine}s for stored {@link Ruleset}s and processes asset attribute updates.
 *
 * <p>If an updated attribute doesn't have meta {@link MetaItemType#RULE_STATE} is false and the
 * attribute has an {@link org.openremote.model.asset.agent.AgentLink} meta, this implementation of
 * {@link AttributeEventInterceptor} converts the update message to an {@link AttributeInfo} fact.
 * This service keeps the facts and thus the state of rule facts are in sync with the asset state
 * changes that occur. If an asset attribute value changes, the {@link AttributeInfo} in the rules
 * engines will be updated to reflect the change.
 *
 * <p>Each asset attribute update is processed in the following order:
 *
 * <ol>
 *   <li>Global Rulesets
 *   <li>Realm Rulesets
 *   <li>Asset Rulesets (in hierarchical order from oldest ancestor down)
 * </ol>
 *
 * Processing order of rulesets with the same scope or same parent is not guaranteed.
 */
public class RulesService extends RouteBuilder implements ContainerService {

  public static final int PRIORITY = LOW_PRIORITY;

  /**
   * This value defines the periodic firing of the rules engines, and therefore has an impact on
   * system load. If a temporary fact has a shorter expiration time, it's not guaranteed to be
   * removed within that time. Any time-based operation, such as matching temporary facts in a
   * sliding time window, must be designed with this margin in mind.
   */
  public static final String OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS =
      "OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS";

  public static final int OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS_DEFAULT =
      50000; // Just under a minute to catch 1 min timer rules
  public static final String OR_RULES_QUICK_FIRE_MILLIS = "OR_RULES_QUICK_FIRE_MILLIS";
  public static final int OR_RULES_QUICK_FIRE_MILLIS_DEFAULT = 3000;
  public static final String OR_RULES_GROOVY_EXECUTION_ENABLED =
      "OR_RULES_GROOVY_EXECUTION_ENABLED";
  public static final boolean OR_RULES_GROOVY_EXECUTION_ENABLED_DEFAULT = true;
  private static final Logger LOG = Logger.getLogger(RulesService.class.getName());
  protected final AtomicReference<RulesEngine<GlobalRuleset>> globalEngine =
      new AtomicReference<>();
  protected final Map<String, RulesEngine<RealmRuleset>> realmEngines = new ConcurrentHashMap<>();
  protected final Map<String, RulesEngine<AssetRuleset>> assetEngines = new ConcurrentHashMap<>();
  protected static final Object ENGINE_LOCK = new Object();
  protected List<GeofenceAssetAdapter> geofenceAssetAdapters = new ArrayList<>();
  protected TimerService timerService;
  protected ExecutorService executorService;
  protected ScheduledExecutorService scheduledExecutorService;
  protected PersistenceService persistenceService;
  protected RulesetStorageService rulesetStorageService;
  protected ManagerIdentityService identityService;
  protected AssetStorageService assetStorageService;
  protected NotificationService notificationService;
  protected WebhookService webhookService;
  protected AlarmService alarmService;
  protected AssetProcessingService assetProcessingService;
  protected AssetDatapointService assetDatapointService;
  protected AssetPredictedDatapointService assetPredictedDatapointService;
  protected ClientEventService clientEventService;
  protected GatewayService gatewayService;
  protected Realm[] realms;
  protected AssetLocationPredicateProcessor locationPredicateRulesConsumer;
  protected final Map<RulesEngine<?>, List<RulesEngine.AssetLocationPredicates>>
      engineAssetLocationPredicateMap = new ConcurrentHashMap<>();
  protected final Set<String> assetsWithModifiedLocationPredicates = new HashSet<>();
  // Keep a keyed view of asset states that have been pushed to any engines.
  // The objects are already in memory inside the rule engines but keeping them
  // here means we can quickly insert facts into newly started engines.
  protected final Map<AttributeRef, AttributeEvent> attributeEventsByRef =
      new ConcurrentHashMap<>();
  protected final Map<AttributeRef, AttributeEvent> preInitAttributeEvents = new LinkedHashMap<>();
  protected long tempFactExpirationMillis;
  protected long quickFireMillis;
  protected boolean groovyRulesExecutionEnabled;
  protected boolean initDone;
  protected boolean startDone;
  protected io.micrometer.core.instrument.Timer rulesFiringTimer;

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    executorService = container.getExecutor();
    scheduledExecutorService = container.getScheduledExecutor();
    timerService = container.getService(TimerService.class);
    persistenceService = container.getService(PersistenceService.class);
    rulesetStorageService = container.getService(RulesetStorageService.class);
    identityService = container.getService(ManagerIdentityService.class);
    notificationService = container.getService(NotificationService.class);
    webhookService = container.getService(WebhookService.class);
    alarmService = container.getService(AlarmService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    assetProcessingService = container.getService(AssetProcessingService.class);
    assetDatapointService = container.getService(AssetDatapointService.class);
    assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class);
    clientEventService = container.getService(ClientEventService.class);
    gatewayService = container.getService(GatewayService.class);

    tempFactExpirationMillis =
        getInteger(
            container.getConfig(),
            OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS,
            OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS_DEFAULT);
    quickFireMillis =
        getInteger(
            container.getConfig(), OR_RULES_QUICK_FIRE_MILLIS, OR_RULES_QUICK_FIRE_MILLIS_DEFAULT);
    groovyRulesExecutionEnabled =
        getBoolean(
            container.getConfig(),
            OR_RULES_GROOVY_EXECUTION_ENABLED,
            OR_RULES_GROOVY_EXECUTION_ENABLED_DEFAULT);

    if (initDone) {
      return;
    }

    clientEventService.addSubscriptionAuthorizer(
        (realm, authContext, eventSubscription) -> {
          if (!(eventSubscription.isEventType(RulesEngineStatusEvent.class)
                  || eventSubscription.isEventType(RulesetChangedEvent.class))
              || authContext == null) {
            return false;
          }

          if (authContext.isSuperUser()) {
            return true;
          }

          // Regular user must have role
          if (!authContext.hasResourceRole(
                  ClientRole.READ_RULES.getValue(), Constants.KEYCLOAK_CLIENT_ID)
              || identityService.getIdentityProvider().isRestrictedUser(authContext)) {
            return false;
          }

          // Force a filter for the users realm; global engine events have no realm so never match
          @SuppressWarnings("unchecked")
          EventSubscription<RealmScopedEvent> subscription =
              (EventSubscription<RealmScopedEvent>) eventSubscription;
          subscription.setFilter(new RealmFilter<>(authContext.getAuthenticatedRealmName()));
          return true;
        });

    clientEventService.addSubscription(AttributeEvent.class, null, this::onAttributeEvent);

    ServiceLoader.load(GeofenceAssetAdapter.class)
        .forEach(
            geofenceAssetAdapter -> {
              LOG.fine("Adding GeofenceAssetAdapter: " + geofenceAssetAdapter.getClass().getName());
              geofenceAssetAdapters.add(geofenceAssetAdapter);
            });

    geofenceAssetAdapters.addAll(container.getServices(GeofenceAssetAdapter.class));
    geofenceAssetAdapters.sort(Comparator.comparingInt(GeofenceAssetAdapter::getPriority));
    container.getService(MessageBrokerService.class).getContext().addRoutes(this);

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new FlowResourceImpl(
                container.getService(TimerService.class),
                container.getService(ManagerIdentityService.class)));

    if (container.getMeterRegistry() != null) {
      rulesFiringTimer = container.getMeterRegistry().timer("or.rules", Tags.empty());
    }

    initDone = true;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void configure() throws Exception {
    // If any ruleset was modified in the database then check its' status and undeploy, deploy, or
    // update it
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-Ruleset")
        .filter(isPersistenceEventForEntityType(Ruleset.class))
        .filter(isNotForGateway(gatewayService))
        .process(
            exchange -> {
              PersistenceEvent<?> persistenceEvent =
                  exchange.getIn().getBody(PersistenceEvent.class);
              processRulesetChange(
                  (Ruleset) persistenceEvent.getEntity(), persistenceEvent.getCause());
            });

    // If any realm was modified in the database then check its status and undeploy, deploy or
    // update any
    // associated rulesets
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-RulesRealm")
        .filter(isPersistenceEventForEntityType(Realm.class))
        .filter(isNotForGateway(gatewayService))
        .process(
            exchange -> {
              PersistenceEvent<?> persistenceEvent =
                  exchange.getIn().getBody(PersistenceEvent.class);
              Realm realm = (Realm) persistenceEvent.getEntity();
              processRealmChange(realm, persistenceEvent.getCause());
            });

    // If any asset was modified in the database, detect changed attributes
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-RulesAsset")
        .filter(isPersistenceEventForEntityType(Asset.class))
        .process(
            exchange -> {
              PersistenceEvent<Asset<?>> persistenceEvent =
                  (PersistenceEvent<Asset<?>>) exchange.getIn().getBody(PersistenceEvent.class);
              final Asset<?> eventAsset = persistenceEvent.getEntity();
              processAssetChange(eventAsset, persistenceEvent);
            });
  }

  @Override
  public void start(Container container) throws Exception {
    startDone = false;

    if (!geofenceAssetAdapters.isEmpty()) {
      LOG.fine("GeofenceAssetAdapters found: " + geofenceAssetAdapters.size());
      locationPredicateRulesConsumer = this::onEngineLocationRulesChanged;

      for (GeofenceAssetAdapter geofenceAssetAdapter : geofenceAssetAdapters) {
        geofenceAssetAdapter.start(container);
      }
    }

    LOG.fine("Deploying global rulesets");
    rulesetStorageService
        .findAll(
            GlobalRuleset.class, new RulesetQuery().setEnabledOnly(true).setFullyPopulate(true))
        .forEach(this::deployGlobalRuleset);

    LOG.fine("Deploying realm rulesets");
    realms =
        Arrays.stream(identityService.getIdentityProvider().getRealms())
            .filter(Realm::getEnabled)
            .toArray(Realm[]::new);
    rulesetStorageService
        .findAll(RealmRuleset.class, new RulesetQuery().setEnabledOnly(true).setFullyPopulate(true))
        .stream()
        .filter(
            rd -> Arrays.stream(realms).anyMatch(realm -> rd.getRealm().equals(realm.getName())))
        .forEach(this::deployRealmRuleset);

    LOG.fine("Deploying asset rulesets");
    // Group by asset ID then realm and check realm is enabled
    //noinspection ResultOfMethodCallIgnored
    deployAssetRulesets(
            rulesetStorageService.findAll(
                AssetRuleset.class, new RulesetQuery().setEnabledOnly(true).setFullyPopulate(true)))
        .count(); // Needed in order to execute the stream. TODO: can this be done differently?

    LOG.fine("Loading all assets with fact attributes to initialize state of rules engines");
    Stream<Pair<Asset<?>, Stream<Attribute<?>>>> stateAttributes = findRuleStateAttributes();

    // Push each attribute as an asset update through the rule engine chain
    // that will ensure the insert only happens to the engines in scope
    stateAttributes.forEach(
        pair -> {
          Asset<?> asset = pair.key;
          pair.value.forEach(
              ruleAttribute -> {
                AttributeEvent attributeEvent =
                    new AttributeEvent(
                        asset,
                        ruleAttribute,
                        null,
                        ruleAttribute.getValue().orElse(null),
                        ruleAttribute.getTimestamp().orElse(0L),
                        ruleAttribute.getValue().orElse(null),
                        ruleAttribute.getTimestamp().orElse(0L));
                insertOrUpdateAttributeInfo(attributeEvent);
              });
        });

    // Start the engines

    synchronized (ENGINE_LOCK) {
      RulesEngine<?> globalRulesEngine = globalEngine.get();
      if (globalRulesEngine != null) {
        globalRulesEngine.start();
      }

      realmEngines.values().forEach(RulesEngine::start);
      assetEngines.values().forEach(RulesEngine::start);

      startDone = true;
      Map<AttributeRef, AttributeEvent> bufferedEvents;
      synchronized (preInitAttributeEvents) {
        bufferedEvents = new LinkedHashMap<>(preInitAttributeEvents);
        preInitAttributeEvents.clear();
      }
      bufferedEvents.values().forEach(this::doProcessAttributeUpdate);
    }
  }

  @Override
  public void stop(Container container) throws Exception {
    for (GeofenceAssetAdapter geofenceAssetAdapter : geofenceAssetAdapters) {
      try {
        geofenceAssetAdapter.stop(container);
      } catch (Exception e) {
        LOG.log(SEVERE, "Exception thrown while stopping geofence adapter", e);
      }
    }

    assetEngines.forEach((assetId, rulesEngine) -> rulesEngine.stop());
    assetEngines.clear();
    realmEngines.forEach((realm, rulesEngine) -> rulesEngine.stop());
    realmEngines.clear();

    RulesEngine<?> globalRulesEngine = globalEngine.get();
    if (globalRulesEngine != null) {
      globalRulesEngine.stop();
      globalEngine.set(null);
    }

    attributeEventsByRef.clear();
    synchronized (preInitAttributeEvents) {
      preInitAttributeEvents.clear();
    }

    for (GeofenceAssetAdapter geofenceAssetAdapter : geofenceAssetAdapters) {
      geofenceAssetAdapter.stop(container);
    }
  }

  protected static boolean isRuleState(MetaHolder metaHolder) {
    if (metaHolder.getMeta() == null) {
      return false;
    }

    return metaHolder
        .getMeta()
        .getValue(MetaItemType.RULE_STATE)
        .orElse(metaHolder.getMeta().has(MetaItemType.AGENT_LINK));
  }

  public boolean isGroovyRulesExecutionEnabled() {
    return groovyRulesExecutionEnabled;
  }

  /**
   * React to events that have been committed to the DB and inject them into the appropriate {@link
   * RulesEngine}s.
   */
  public void onAttributeEvent(AttributeEvent event) throws AssetProcessingException {
    if (!startDone) {
      bufferPreInitAttributeEvent(event);
    } else {
      doProcessAttributeUpdate(event);
    }
  }

  protected void doProcessAttributeUpdate(AttributeEvent attributeEvent) {
    if (isRuleState(attributeEvent) && !attributeEvent.isDeleted()) {
      insertOrUpdateAttributeInfo(attributeEvent);
    } else {
      retractAttributeInfo(attributeEvent);
    }
  }

  public boolean isRulesetKnown(Ruleset ruleset) {
    if (ruleset instanceof GlobalRuleset) {
      RulesEngine<?> globalRulesEngine = globalEngine.get();
      return globalRulesEngine != null
          && globalRulesEngine.deployments.containsKey(ruleset.getId())
          && globalRulesEngine
              .deployments
              .get(ruleset.getId())
              .ruleset
              .getRules()
              .equals(ruleset.getRules());
    }
    if (ruleset instanceof RealmRuleset realmRuleset) {
      return realmEngines.get(realmRuleset.getRealm()) != null
          && realmEngines.get(realmRuleset.getRealm()).deployments.containsKey(ruleset.getId())
          && realmEngines
              .get(realmRuleset.getRealm())
              .deployments
              .get(ruleset.getId())
              .ruleset
              .getRules()
              .equals(ruleset.getRules());
    }
    if (ruleset instanceof AssetRuleset assetRuleset) {
      return assetEngines.get(assetRuleset.getAssetId()) != null
          && assetEngines.get(assetRuleset.getAssetId()).deployments.containsKey(ruleset.getId())
          && assetEngines
              .get(assetRuleset.getAssetId())
              .deployments
              .get(ruleset.getId())
              .ruleset
              .getRules()
              .equals(ruleset.getRules());
    }
    return false;
  }

  public GeofenceDefinition[] getAssetGeofences(String assetId) {
    LOG.finest("Requesting geofences for asset: " + assetId);

    for (GeofenceAssetAdapter geofenceAdapter : geofenceAssetAdapters) {
      GeofenceDefinition[] geofences = geofenceAdapter.getAssetGeofences(assetId);
      if (geofences != null) {
        LOG.finest(
            "Retrieved geofences from geofence adapter '"
                + geofenceAdapter.getName()
                + "' for asset: "
                + assetId);
        return geofences;
      }
    }

    return new GeofenceDefinition[0];
  }

  protected void processRealmChange(Realm realm, PersistenceEvent.Cause cause) {
    // Check if enabled status has changed
    boolean wasEnabled =
        Arrays.stream(realms)
            .anyMatch(t -> realm.getName().equals(t.getName()) && realm.getId().equals(t.getId()));
    boolean isEnabled = realm.getEnabled() && cause != PersistenceEvent.Cause.DELETE;
    realms =
        Arrays.stream(identityService.getIdentityProvider().getRealms())
            .filter(Realm::getEnabled)
            .toArray(Realm[]::new);

    if (wasEnabled == isEnabled) {
      // Nothing to do here
      return;
    }

    if (wasEnabled) {
      // Remove realm rules engine for this realm if it exists
      RulesEngine<RealmRuleset> realmRulesEngine = realmEngines.get(realm.getName());
      if (realmRulesEngine != null) {
        realmRulesEngine.stop();
        realmEngines.remove(realm.getName());
      }

      // Remove any asset rules engines for assets in this realm
      assetEngines
          .values()
          .removeIf(
              engine -> {
                boolean remove =
                    engine.getId().getRealm().map(r -> r.equals(realm.getName())).orElse(false);
                if (remove) {
                  engine.stop();
                }
                return remove;
              });
    } else {
      // Create realm rules engines for this realm if it has any rulesets
      rulesetStorageService
          .findAll(
              RealmRuleset.class,
              new RulesetQuery()
                  .setRealm(realm.getName())
                  .setFullyPopulate(true)
                  .setEnabledOnly(true))
          .stream()
          .map(this::deployRealmRuleset)
          .filter(Objects::nonNull)
          .forEach(RulesEngine::start);

      // Create any asset rules engines for assets in this realm that have rulesets
      deployAssetRulesets(
              rulesetStorageService.findAll(
                  AssetRuleset.class,
                  new RulesetQuery()
                      .setRealm(realm.getName())
                      .setEnabledOnly(true)
                      .setFullyPopulate(true)))
          .forEach(RulesEngine::start);
    }
  }

  protected void processAssetChange(Asset<?> asset, PersistenceEvent<Asset<?>> persistenceEvent) {
    switch (persistenceEvent.getCause()) {
      case DELETE ->
          // Remove any asset rules engines for this asset
          assetEngines
              .values()
              .removeIf(
                  re -> {
                    if (re.getId()
                        .getAssetId()
                        .map(aId -> aId.equals(asset.getId()))
                        .orElse(false)) {
                      re.stop();
                      return true;
                    }
                    return false;
                  });
      case UPDATE -> {
        // Attribute events are also published for updated/new attributes so nothing to do here
      }
    }
  }

  protected void processRulesetChange(Ruleset ruleset, PersistenceEvent.Cause cause) {
    if (cause == PersistenceEvent.Cause.DELETE || !ruleset.isEnabled()) {
      if (ruleset instanceof GlobalRuleset) {
        undeployGlobalRuleset((GlobalRuleset) ruleset);
      } else if (ruleset instanceof RealmRuleset) {
        undeployRealmRuleset((RealmRuleset) ruleset);
      } else if (ruleset instanceof AssetRuleset) {
        undeployAssetRuleset((AssetRuleset) ruleset);
      }
    } else {
      if (ruleset instanceof GlobalRuleset) {

        RulesEngine<GlobalRuleset> engine = deployGlobalRuleset((GlobalRuleset) ruleset);
        engine.start();

      } else if (ruleset instanceof RealmRuleset) {

        RulesEngine<RealmRuleset> engine = deployRealmRuleset((RealmRuleset) ruleset);
        engine.start();

      } else if (ruleset instanceof AssetRuleset) {

        // Must reload from the database, the ruleset might not be completely hydrated on CREATE or
        // UPDATE
        AssetRuleset assetRuleset = rulesetStorageService.find(AssetRuleset.class, ruleset.getId());
        if (assetRuleset == null) {
          undeployAssetRuleset((AssetRuleset) ruleset);
          return;
        }
        RulesEngine<AssetRuleset> engine = deployAssetRuleset(assetRuleset);
        engine.start();
      }
    }
  }

  /** Deploy the ruleset into the global engine creating the engine if necessary. */
  protected RulesEngine<GlobalRuleset> deployGlobalRuleset(GlobalRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<GlobalRuleset> engine = globalEngine.get();
      boolean isNewEngine = engine == null;

      // Global rules have access to everything in the system
      if (isNewEngine) {
        engine =
            new RulesEngine<>(
                timerService,
                this,
                identityService,
                executorService,
                scheduledExecutorService,
                assetStorageService,
                assetProcessingService,
                notificationService,
                webhookService,
                alarmService,
                clientEventService,
                assetDatapointService,
                assetPredictedDatapointService,
                new RulesEngineId<>(),
                locationPredicateRulesConsumer,
                rulesFiringTimer);
        globalEngine.set(engine);
      }

      if (isNewEngine) {
        // Push all existing facts into the engine
        RulesEngine<GlobalRuleset> finalEngine = engine;
        getAttributeEvents()
            .forEach(assetState -> finalEngine.insertOrUpdateAttributeInfo(assetState, true));
      }

      engine.addRuleset(ruleset);
      return engine;
    }
  }

  protected void undeployGlobalRuleset(GlobalRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<GlobalRuleset> engine = globalEngine.get();

      if (engine == null) {
        return;
      }

      if (engine.removeRuleset(ruleset)) {
        globalEngine.set(null);
      }
    }
  }

  protected RulesEngine<RealmRuleset> deployRealmRuleset(RealmRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<RealmRuleset> realmRulesEngine = realmEngines.get(ruleset.getRealm());
      boolean isNewEngine = realmRulesEngine == null;

      if (isNewEngine) {
        realmRulesEngine =
            new RulesEngine<>(
                timerService,
                this,
                identityService,
                executorService,
                scheduledExecutorService,
                assetStorageService,
                assetProcessingService,
                notificationService,
                webhookService,
                alarmService,
                clientEventService,
                assetDatapointService,
                assetPredictedDatapointService,
                new RulesEngineId<>(ruleset.getRealm()),
                locationPredicateRulesConsumer,
                rulesFiringTimer);
        realmEngines.put(ruleset.getRealm(), realmRulesEngine);

        // Push all existing facts into the engine
        RulesEngine<RealmRuleset> finalRealmRulesEngine = realmRulesEngine;
        getAttributeEvents()
            .forEach(
                assetState -> {
                  if (assetState.getRealm().equals(ruleset.getRealm())) {
                    finalRealmRulesEngine.insertOrUpdateAttributeInfo(assetState, true);
                  }
                });
      }

      realmRulesEngine.addRuleset(ruleset);
      return realmRulesEngine;
    }
  }

  protected void undeployRealmRuleset(RealmRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<RealmRuleset> realmRulesEngine = realmEngines.get(ruleset.getRealm());

      if (realmRulesEngine == null) {
        return;
      }

      if (realmRulesEngine.removeRuleset(ruleset)) {
        realmEngines.remove(ruleset.getRealm());
      }
    }
  }

  protected Stream<RulesEngine<AssetRuleset>> deployAssetRulesets(List<AssetRuleset> rulesets) {
    return rulesets.stream()
        .collect(Collectors.groupingBy(AssetRuleset::getAssetId))
        .entrySet()
        .stream()
        .map(
            es ->
                new Pair<Asset<?>, List<AssetRuleset>>(
                    assetStorageService.find(es.getKey(), true), es.getValue()))
        .filter(assetAndRules -> assetAndRules.key != null)
        .collect(Collectors.groupingBy(assetAndRules -> assetAndRules.key.getRealm()))
        .entrySet()
        .stream()
        .filter(es -> Arrays.stream(realms).anyMatch(at -> es.getKey().equals(at.getName())))
        .flatMap(
            es -> {
              List<Pair<Asset<?>, List<AssetRuleset>>> realmAssetAndRules = es.getValue();

              // RT: Not sure we need ordering here for starting engines so removing it
              // Order rulesets by asset hierarchy within this realm
              return realmAssetAndRules.stream()
                  // .sorted(Comparator.comparingInt(item -> item.key.getPath().length))
                  .flatMap(assetAndRules -> assetAndRules.value.stream())
                  .map(this::deployAssetRuleset);
            });
  }

  protected RulesEngine<AssetRuleset> deployAssetRuleset(AssetRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<AssetRuleset> assetRulesEngine = assetEngines.get(ruleset.getAssetId());
      boolean isNewEngine = assetRulesEngine == null;

      if (isNewEngine) {
        assetRulesEngine =
            new RulesEngine<>(
                timerService,
                this,
                identityService,
                executorService,
                scheduledExecutorService,
                assetStorageService,
                assetProcessingService,
                notificationService,
                webhookService,
                alarmService,
                clientEventService,
                assetDatapointService,
                assetPredictedDatapointService,
                new RulesEngineId<>(ruleset.getRealm(), ruleset.getAssetId()),
                locationPredicateRulesConsumer,
                rulesFiringTimer);
        assetEngines.put(ruleset.getAssetId(), assetRulesEngine);

        // Push all existing facts for this asset (and it's children into the engine)
        RulesEngine<AssetRuleset> finalAssetRulesEngine = assetRulesEngine;
        getAssetStatesInScope(ruleset.getAssetId())
            .forEach(
                assetState -> finalAssetRulesEngine.insertOrUpdateAttributeInfo(assetState, true));
      }

      assetRulesEngine.addRuleset(ruleset);
      return assetRulesEngine;
    }
  }

  protected void undeployAssetRuleset(AssetRuleset ruleset) {
    synchronized (ENGINE_LOCK) {
      RulesEngine<AssetRuleset> rulesEngine = assetEngines.get(ruleset.getAssetId());
      if (rulesEngine == null) {
        return;
      }

      if (rulesEngine.removeRuleset(ruleset)) {
        assetEngines.remove(ruleset.getAssetId());
      }
    }
  }

  protected void insertOrUpdateAttributeInfo(AttributeEvent attributeEvent) {
    if (attributeEvent.isOutdated()) {
      // Attribute event is old so ignore
      return;
    }

    final AttributeEvent[] previousEventHolder = new AttributeEvent[1];
    final boolean[] appliedHolder = new boolean[1];
    attributeEventsByRef.compute(
        attributeEvent.getRef(),
        (ref, currentEvent) -> {
          previousEventHolder[0] = currentEvent;
          if (isOlderThanCurrent(attributeEvent, currentEvent)) {
            LOG.log(
                FINEST,
                () ->
                    "Ignoring attribute event older than current rule state: incoming="
                        + attributeEvent
                        + ", current="
                        + currentEvent);
            return currentEvent;
          }

          LOG.log(FINEST, () -> "Inserting attribute event: " + attributeEvent);
          appliedHolder[0] = true;
          return attributeEvent;
        });
    if (!appliedHolder[0]) {
      return;
    }
    boolean inserted = previousEventHolder[0] == null;

    // Get the chain of rule engines that we need to pass through
    List<RulesEngine<?>> rulesEngines =
        getEnginesInScope(attributeEvent.getRealm(), attributeEvent.getPath());

    // Pass through each rules engine
    for (RulesEngine<?> rulesEngine : rulesEngines) {
      rulesEngine.insertOrUpdateAttributeInfo(attributeEvent, inserted);
    }
  }

  protected void retractAttributeInfo(AttributeEvent attributeEvent) {
    LOG.log(FINEST, () -> "Retracting attribute event: " + attributeEvent);
    attributeEventsByRef.remove(attributeEvent.getRef());

    // Get the chain of rule engines that we need to pass through
    List<RulesEngine<?>> rulesEngines =
        getEnginesInScope(attributeEvent.getRealm(), attributeEvent.getPath());

    // Pass through each rules engine
    for (RulesEngine<?> rulesEngine : rulesEngines) {
      rulesEngine.retractAttributeInfo(attributeEvent);
    }
  }

  protected List<AttributeInfo> getAssetStatesInScope(String assetId) {
    return getAttributeEvents().stream()
        .filter(assetState -> Arrays.asList(assetState.getPath()).contains(assetId))
        .collect(Collectors.toList());
  }

  protected Collection<AttributeEvent> getAttributeEvents() {
    return attributeEventsByRef.values();
  }

  protected void bufferPreInitAttributeEvent(AttributeEvent attributeEvent) {
    synchronized (preInitAttributeEvents) {
      AttributeEvent currentEvent = preInitAttributeEvents.get(attributeEvent.getRef());
      if (!shouldReplaceBufferedPreInitEvent(currentEvent, attributeEvent)) {
        LOG.log(
            FINEST,
            () ->
                "Ignoring buffered pre-init attribute event older than current buffered state: incoming="
                    + attributeEvent
                    + ", current="
                    + currentEvent);
        return;
      }
      preInitAttributeEvents.put(attributeEvent.getRef(), attributeEvent);
    }
  }

  protected boolean isOlderThanCurrent(AttributeEvent incomingEvent, AttributeEvent currentEvent) {
    return currentEvent != null && incomingEvent.getTimestamp() < currentEvent.getTimestamp();
  }

  protected boolean shouldReplaceBufferedPreInitEvent(
      AttributeEvent currentEvent, AttributeEvent incomingEvent) {
    if (currentEvent == null) {
      return true;
    }

    boolean currentInserts = isRuleState(currentEvent) && !currentEvent.isDeleted();
    boolean incomingInserts = isRuleState(incomingEvent) && !incomingEvent.isDeleted();

    if (currentInserts && incomingInserts) {
      return !isOlderThanCurrent(incomingEvent, currentEvent);
    }

    return true;
  }

  protected List<RulesEngine<?>> getEnginesInScope(String realm, String[] assetPath) {
    List<RulesEngine<?>> rulesEngines = new ArrayList<>();

    // Add global engine (if it exists)
    RulesEngine<?> globalRulesEngine = globalEngine.get();
    if (globalRulesEngine != null) {
      rulesEngines.add(globalRulesEngine);
    }

    // Add realm engine (if it exists)
    RulesEngine<?> realmRulesEngine = realmEngines.get(realm);

    if (realmRulesEngine != null) {
      rulesEngines.add(realmRulesEngine);
    }

    // Add asset engines, iterate through asset hierarchy using asset IDs from asset path
    for (String assetId : assetPath) {
      RulesEngine<?> assetRulesEngine = assetEngines.get(assetId);
      if (assetRulesEngine != null) {
        rulesEngines.add(assetRulesEngine);
      }
    }

    return rulesEngines;
  }

  protected Stream<Pair<Asset<?>, Stream<Attribute<?>>>> findRuleStateAttributes() {
    // Get all assets then filter out any attributes with RULE_STATE=false
    List<Asset<?>> assets = assetStorageService.findAll(new AssetQuery());

    return assets.stream()
        .map(
            asset ->
                new Pair<>(
                    asset, asset.getAttributes().stream().filter(RulesService::isRuleState)));
  }

  /**
   * Called when an engine's rules change identifying assets with location attributes that also have
   * {@link LocationAttributePredicate} in the rules. The job here is to identify the asset's (via
   * {@link AttributeInfo}) that have modified {@link LocationAttributePredicate}s and to notify the
   * {@link GeofenceAssetAdapter}s.
   */
  protected void onEngineLocationRulesChanged(
      RulesEngine<?> rulesEngine,
      List<RulesEngine.AssetLocationPredicates> newEngineAssetStateLocationPredicates) {

    synchronized (assetsWithModifiedLocationPredicates) {
      int initialModifiedCount = assetsWithModifiedLocationPredicates.size();

      if (newEngineAssetStateLocationPredicates == null) {
        engineAssetLocationPredicateMap.computeIfPresent(
            rulesEngine,
            (re, existingAssetStateLocationPredicates) -> {
              // All location predicates have been removed so record each asset state as modified
              assetsWithModifiedLocationPredicates.addAll(
                  existingAssetStateLocationPredicates.stream()
                      .map(RulesEngine.AssetLocationPredicates::getAssetId)
                      .toList());
              // Remove this engine from the map
              return null;
            });
      } else {
        engineAssetLocationPredicateMap.compute(
            rulesEngine,
            (re, existingEngineAssetStateLocationPredicates) -> {
              // Check if this not the first time this engine has been seen with location predicates
              // so we can check
              // for any removed asset states
              if (existingEngineAssetStateLocationPredicates == null) {
                // All asset states are new so record them all as modified
                assetsWithModifiedLocationPredicates.addAll(
                    newEngineAssetStateLocationPredicates.stream()
                        .map(RulesEngine.AssetLocationPredicates::getAssetId)
                        .toList());
              } else {
                // Find obsolete and modified asset states
                existingEngineAssetStateLocationPredicates.forEach(
                    existingAssetStateLocationPredicates -> {
                      // Check if there are no longer any location predicates for this asset
                      Optional<RulesEngine.AssetLocationPredicates>
                          newAssetStateLocationPredicates =
                              newEngineAssetStateLocationPredicates.stream()
                                  .filter(
                                      assetStateLocationPredicates ->
                                          assetStateLocationPredicates
                                              .getAssetId()
                                              .equals(
                                                  existingAssetStateLocationPredicates
                                                      .getAssetId()))
                                  .findFirst();

                      if (newAssetStateLocationPredicates.isPresent()) {
                        // Compare existing and new location predicate sets if there is any change
                        // then record it
                        if (!newAssetStateLocationPredicates
                            .get()
                            .getLocationPredicates()
                            .equals(existingAssetStateLocationPredicates.getLocationPredicates())) {
                          assetsWithModifiedLocationPredicates.add(
                              existingAssetStateLocationPredicates.getAssetId());
                        }
                      } else {
                        // This means that there are no longer any location predicates so old ones
                        // are obsolete
                        assetsWithModifiedLocationPredicates.add(
                            existingAssetStateLocationPredicates.getAssetId());
                      }
                    });

                // Check for asset states in the new map but not in the old one
                newEngineAssetStateLocationPredicates.forEach(
                    newAssetStateLocationPredicates -> {
                      boolean isNewAssetState =
                          existingEngineAssetStateLocationPredicates.stream()
                              .noneMatch(
                                  assetStateLocationPredicates ->
                                      assetStateLocationPredicates
                                          .getAssetId()
                                          .equals(newAssetStateLocationPredicates.getAssetId()));

                      if (isNewAssetState) {
                        // This means that all predicates for this asset are new
                        assetsWithModifiedLocationPredicates.add(
                            newAssetStateLocationPredicates.getAssetId());
                      }
                    });
              }
              return newEngineAssetStateLocationPredicates;
            });
      }

      if (assetsWithModifiedLocationPredicates.size() != initialModifiedCount) {
        processModifiedGeofences();
      }
    }
  }

  protected void processModifiedGeofences() {

    synchronized (assetsWithModifiedLocationPredicates) {
      LOG.finest(
          "Processing geofence modifications: modified asset geofence count="
              + assetsWithModifiedLocationPredicates.size());

      try {
        // Find all location predicates associated with modified assets and pass through to the
        // geofence adapters
        List<RulesEngine.AssetLocationPredicates> assetLocationPredicates =
            new ArrayList<>(assetsWithModifiedLocationPredicates.size());

        assetsWithModifiedLocationPredicates.forEach(
            assetId -> {
              RulesEngine.AssetLocationPredicates locationPredicates =
                  new RulesEngine.AssetLocationPredicates(assetId, new HashSet<>());

              engineAssetLocationPredicateMap.forEach(
                  (rulesEngine, engineAssetStateLocationPredicates) ->
                      engineAssetStateLocationPredicates.stream()
                          .filter(
                              assetStateLocationPredicates ->
                                  assetStateLocationPredicates.getAssetId().equals(assetId))
                          .findFirst()
                          .ifPresent(
                              assetStateLocationPredicate -> {
                                locationPredicates
                                    .getLocationPredicates()
                                    .addAll(assetStateLocationPredicate.getLocationPredicates());
                              }));

              assetLocationPredicates.add(locationPredicates);
            });

        for (GeofenceAssetAdapter geofenceAssetAdapter : geofenceAssetAdapters) {
          LOG.finest("Passing modified geofences to adapter: " + geofenceAssetAdapter.getName());
          geofenceAssetAdapter.processLocationPredicates(assetLocationPredicates);

          if (assetLocationPredicates.isEmpty()) {
            LOG.finest("All modified geofences handled");
            break;
          }
        }

      } catch (Exception e) {
        LOG.log(
            SEVERE,
            "Exception thrown by geofence adapter while processing location predicates",
            e);
      } finally {
        // Clear modified assets ready for next batch
        assetsWithModifiedLocationPredicates.clear();
      }
    }
  }

  protected Optional<RulesetDeployment> getRulesetDeployment(Long rulesetId) {

    RulesEngine<?> globalRulesEngine = globalEngine.get();
    if (globalRulesEngine != null) {
      if (globalRulesEngine.deployments.containsKey(rulesetId)) {
        return Optional.of(globalRulesEngine.deployments.get(rulesetId));
      }
    }

    for (Map.Entry<String, RulesEngine<RealmRuleset>> realmAndEngine : realmEngines.entrySet()) {
      if (realmAndEngine.getValue().deployments.containsKey(rulesetId)) {
        return Optional.of(realmAndEngine.getValue().deployments.get(rulesetId));
      }
    }

    for (Map.Entry<String, RulesEngine<AssetRuleset>> realmAndEngine : assetEngines.entrySet()) {
      if (realmAndEngine.getValue().deployments.containsKey(rulesetId)) {
        return Optional.of(realmAndEngine.getValue().deployments.get(rulesetId));
      }
    }

    return Optional.empty();
  }

  /**
   * Trigger rules engines which have the {@link org.openremote.model.value.MetaItemDescriptor}
   * {@link org.openremote.model.rules.Ruleset#TRIGGER_ON_PREDICTED_DATA} and contain {@link
   * AttributeInfo} of the specified asset id. Use this when {@link PredictedDatapoints} has changed
   * for this asset.
   *
   * @param assetId of the asset which has new predicated data points.
   */
  public void fireDeploymentsWithPredictedDataForAsset(String assetId) {
    List<AttributeInfo> assetStates = getAssetStatesInScope(assetId);
    if (!assetStates.isEmpty()) {
      String realm = assetStates.getFirst().getRealm();
      String[] assetPaths =
          assetStates.stream()
              .flatMap(assetState -> Arrays.stream(assetState.getPath()))
              .toArray(String[]::new);
      synchronized (ENGINE_LOCK) {
        for (RulesEngine<?> rulesEngine : getEnginesInScope(realm, assetPaths)) {
          rulesEngine.scheduleFire(false);
        }
      }
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{}";
  }
}
