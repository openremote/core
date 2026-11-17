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
package org.openremote.manager.event

import io.undertow.websockets.core.WebSocketChannel
import org.apache.camel.Exchange
import org.apache.camel.component.undertow.UndertowConstants
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.openremote.container.message.MessageBrokerService
import org.openremote.container.security.AuthContext
import org.openremote.manager.rules.RulesService
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.web.ManagerWebService
import org.openremote.model.Container
import org.openremote.model.event.Event
import org.openremote.model.event.shared.CancelEventSubscription
import org.openremote.model.event.shared.EventSubscription
import org.openremote.model.event.shared.RealmFilter
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.event.shared.UnauthorizedEventSubscription
import org.openremote.model.alarm.AlarmEvent
import org.openremote.model.rules.GlobalRuleset
import org.openremote.model.rules.RealmRuleset
import org.openremote.model.rules.Ruleset
import org.openremote.model.rules.RulesEngineInfo
import org.openremote.model.rules.RulesEngineStatus
import org.openremote.model.rules.RulesEngineStatusEvent
import org.openremote.model.rules.RulesetChangedEvent
import org.openremote.model.security.ClientRole
import org.openremote.model.syslog.SyslogEvent
import spock.lang.Specification
import spock.lang.Unroll

import static org.openremote.model.Constants.AUTH_CONTEXT
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.REALM_PARAM_NAME

class ClientEventServiceSubscriptionTest extends Specification {

  def "subscribes and unsubscribes by event type and subscription id"() {
    given:
    def service = new TestClientEventService()
    def channel = dummyChannel()
    String sessionKey = "session-1"
    service.sessionChannels.put(sessionKey, channel)

    and:
    def subscription = new EventSubscription<>(SyslogEvent.class, null, "sub-1")

    when: "subscribing"
    service.procesInboundEvent(exchangeFor(sessionKey, channel, subscription))

    then:
    service.websocketSessionSubscriptionConsumers[sessionKey].size() == 1
    service.eventSubscriptions.size() == 1
    service.sentPayloads.last().is(subscription)

    when: "unsubscribing with event type + subscription id"
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new CancelEventSubscription(SyslogEvent.class, "sub-1")))

    then:
    !service.websocketSessionSubscriptionConsumers.containsKey(sessionKey)
    service.eventSubscriptions.empty
  }

  def "unsubscribes all matching subscriptions when only event type is provided"() {
    given:
    def service = new TestClientEventService()
    def channel = dummyChannel()
    String sessionKey = "session-2"
    service.sessionChannels.put(sessionKey, channel)

    and:
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(SyslogEvent.class, null, "sub-a")))
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(SyslogEvent.class, null, "sub-b")))
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(AlarmEvent.class)))

    expect:
    service.websocketSessionSubscriptionConsumers[sessionKey].size() == 3

    when:
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new CancelEventSubscription(Event.getEventType(SyslogEvent.class), null)))

    then:
    service.websocketSessionSubscriptionConsumers[sessionKey].size() == 1
    service.websocketSessionSubscriptionConsumers[sessionKey].keySet() == ["alarm::"] as Set
    service.eventSubscriptions.size() == 1
  }

  def "unsubscribes all matching subscriptions when only subscription id is provided"() {
    given:
    def service = new TestClientEventService()
    def channel = dummyChannel()
    String sessionKey = "session-3"
    service.sessionChannels.put(sessionKey, channel)

    and:
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(SyslogEvent.class, null, "shared-id")))
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(AlarmEvent.class, null, "shared-id")))
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new EventSubscription<>(AlarmEvent.class)))

    expect:
    service.websocketSessionSubscriptionConsumers[sessionKey].size() == 3

    when:
    service.procesInboundEvent(exchangeFor(sessionKey, channel, new CancelEventSubscription("shared-id")))

    then:
    service.websocketSessionSubscriptionConsumers[sessionKey].size() == 1
    service.websocketSessionSubscriptionConsumers[sessionKey].keySet() == ["alarm::"] as Set
    service.eventSubscriptions.size() == 1
  }

  @Unroll
  def "rejects subscription when delimiter is present in #fieldName"() {
    given:
    def service = new TestClientEventService()
    def channel = dummyChannel()
    String sessionKey = "session-4"
    service.sessionChannels.put(sessionKey, channel)

    when:
    def exchange = exchangeFor(sessionKey, channel, subscription)
    service.procesInboundEvent(exchange)

    then:
    exchange.routeStop
    !service.websocketSessionSubscriptionConsumers.containsKey(sessionKey)
    service.eventSubscriptions.empty
    service.sentPayloads.size() == 1
    service.sentPayloads[0] instanceof UnauthorizedEventSubscription

    where:
    fieldName | subscription
    "eventType" | new EventSubscription<>("bad::event")
    "subscriptionId" | new EventSubscription<>(SyslogEvent.class, null, "bad::id")
  }

  def "delivers #eventClass.simpleName events to a regular user only for their own realm when requesting #requestedFilter"() {
    given: "a client event service guarded by the rules service subscription authorizer"
    def service = new RecordingClientEventService()
    new RulesService().init(containerFor(service))
    def channel = dummyChannel()
    String sessionKey = "session-5"
    service.sessionChannels.put(sessionKey, channel)

    when: "a regular user of the building realm subscribes"
    def subscription = new EventSubscription<>(eventClass, requestedFilter)
    service.procesInboundEvent(exchangeFor(sessionKey, channel, subscription, regularUser("building", ClientRole.READ_RULES), "building"))

    then: "the subscription should be accepted"
    service.sentPayloads.last().is(subscription)
    subscription.subscribed

    when: "events are published for the global engine and the smartcity and building realms"
    service.sendToSubscribers(rulesEvent(eventClass, null))
    service.sendToSubscribers(rulesEvent(eventClass, "smartcity"))
    service.sendToSubscribers(rulesEvent(eventClass, "building"))

    then: "the user should only receive the building realm event"
    service.triggeredEvents*.realm == ["building"]

    where:
    eventClass             | requestedFilter
    RulesetChangedEvent    | null
    RulesetChangedEvent    | new RealmFilter<>("smartcity")
    RulesEngineStatusEvent | null
    RulesEngineStatusEvent | new RealmFilter<>("smartcity")
  }

  def "rejects #eventClass.simpleName subscription from a regular user without the read rules role"() {
    given: "a client event service guarded by the rules service subscription authorizer"
    def service = new RecordingClientEventService()
    new RulesService().init(containerFor(service))
    def channel = dummyChannel()
    String sessionKey = "session-6"
    service.sessionChannels.put(sessionKey, channel)

    when: "a regular user of the building realm with only the read assets role subscribes"
    def exchange = exchangeFor(sessionKey, channel, new EventSubscription<>(eventClass), regularUser("building", ClientRole.READ_ASSETS), "building")
    service.procesInboundEvent(exchange)

    then: "the subscription should be rejected"
    exchange.routeStop
    service.eventSubscriptions.findAll { it.key.eventType == Event.getEventType(eventClass) }.empty
    service.sentPayloads.size() == 1
    service.sentPayloads[0] instanceof UnauthorizedEventSubscription

    where:
    eventClass << [RulesetChangedEvent, RulesEngineStatusEvent]
  }

  def "rejects #eventClass.simpleName subscription from a restricted user with the read rules role"() {
    given: "a client event service guarded by the rules service subscription authorizer that treats the user as restricted"
    def service = new RecordingClientEventService()
    new RulesService().init(containerFor(service, true))
    def channel = dummyChannel()
    String sessionKey = "session-7"
    service.sessionChannels.put(sessionKey, channel)

    when: "a restricted user of the building realm with the read rules role subscribes"
    def exchange = exchangeFor(sessionKey, channel, new EventSubscription<>(eventClass), regularUser("building", ClientRole.READ_RULES), "building")
    service.procesInboundEvent(exchange)

    then: "the subscription should be rejected"
    exchange.routeStop
    service.eventSubscriptions.findAll { it.key.eventType == Event.getEventType(eventClass) }.empty
    service.sentPayloads.size() == 1
    service.sentPayloads[0] instanceof UnauthorizedEventSubscription

    when: "events are published for the global engine and the building realm"
    service.sendToSubscribers(rulesEvent(eventClass, null))
    service.sendToSubscribers(rulesEvent(eventClass, "building"))

    then: "the user should not receive any events"
    service.triggeredEvents.empty

    where:
    eventClass << [RulesetChangedEvent, RulesEngineStatusEvent]
  }

  def "delivers #eventClass.simpleName events to a superuser for #description"() {
    given: "a client event service guarded by the rules service subscription authorizer"
    def service = new RecordingClientEventService()
    new RulesService().init(containerFor(service))
    def channel = dummyChannel()
    String sessionKey = "session-8"
    service.sessionChannels.put(sessionKey, channel)

    when: "a superuser of the master realm subscribes"
    def subscription = new EventSubscription<>(eventClass, requestedFilter)
    service.procesInboundEvent(exchangeFor(sessionKey, channel, subscription, superUser(), "master"))

    then: "the subscription should be accepted with the requested filter left untouched"
    service.sentPayloads.last().is(subscription)
    subscription.subscribed
    subscription.filter.is(requestedFilter)

    when: "events are published for the global engine and the smartcity and building realms"
    service.sendToSubscribers(rulesEvent(eventClass, null))
    service.sendToSubscribers(rulesEvent(eventClass, "smartcity"))
    service.sendToSubscribers(rulesEvent(eventClass, "building"))

    then: "the superuser should receive the expected events"
    service.triggeredEvents*.realm == expectedRealms

    where:
    eventClass             | description           | requestedFilter                | expectedRealms
    RulesetChangedEvent    | "all engines"         | null                           | [null, "smartcity", "building"]
    RulesetChangedEvent    | "the requested realm" | new RealmFilter<>("smartcity") | ["smartcity"]
    RulesEngineStatusEvent | "all engines"         | null                           | [null, "smartcity", "building"]
    RulesEngineStatusEvent | "the requested realm" | new RealmFilter<>("smartcity") | ["smartcity"]
  }

  private AuthContext superUser() {
    Stub(AuthContext) {
      getAuthenticatedRealmName() >> "master"
      isSuperUser() >> true
    }
  }

  private AuthContext regularUser(String realm, ClientRole role) {
    Stub(AuthContext) {
      getAuthenticatedRealmName() >> realm
      hasResourceRole(role.value, KEYCLOAK_CLIENT_ID) >> true
    }
  }

  private static SharedEvent rulesEvent(Class<?> eventClass, String realm) {
    eventClass == RulesetChangedEvent
            ? new RulesetChangedEvent(0L, realm, realm, realm == null
            ? new GlobalRuleset("Global rules", Ruleset.Lang.GROOVY, "global rule source")
            : new RealmRuleset(realm, "Realm rules", Ruleset.Lang.GROOVY, "realm rule source"))
            : new RulesEngineStatusEvent(0L, realm, realm, new RulesEngineInfo(RulesEngineStatus.RUNNING, 0, 0))
  }

  private Exchange exchangeFor(String sessionKey, WebSocketChannel channel, Object body) {
    exchangeFor(sessionKey, channel, body, Stub(AuthContext), "master")
  }

  private Exchange exchangeFor(String sessionKey, WebSocketChannel channel, Object body, AuthContext authContext, String realm) {
    Exchange exchange = new DefaultExchange(new DefaultCamelContext())
    exchange.in.body = body
    exchange.in.setHeader(UndertowConstants.CONNECTION_KEY, sessionKey)
    exchange.in.setHeader(UndertowConstants.CHANNEL, channel)
    exchange.in.setHeader(AUTH_CONTEXT, authContext)
    exchange.in.setHeader(REALM_PARAM_NAME, realm)
    exchange
  }

  private Container containerFor(ClientEventService clientEventService, boolean restrictedUser = false) {
    def identityService = Stub(ManagerIdentityService) {
      getIdentityProvider() >> Stub(ManagerIdentityProvider) {
        isRestrictedUser(_) >> restrictedUser
      }
    }
    Map<Class<?>, Object> services = [
      (ClientEventService)    : clientEventService,
      (ManagerIdentityService): identityService,
      (MessageBrokerService)  : Stub(MessageBrokerService),
      (ManagerWebService)     : Stub(ManagerWebService)
    ]
    Stub(Container) {
      getConfig() >> [:]
      getMeterRegistry() >> null
      getServices(_ as Class) >> []
      getService(_ as Class) >> { Class<?> type -> services[type] }
    }
  }

  private WebSocketChannel dummyChannel() {
    Stub(WebSocketChannel)
  }

  private static class TestClientEventService extends ClientEventService {
    final List<Object> sentPayloads = []

    @Override
    boolean authorizeEventSubscription(String realm, AuthContext authContext, EventSubscription<?> subscription) {
      return true
    }

    @Override
    void sendToWebsocketSession(String sessionKey, Object data) {
      sentPayloads.add(data)
    }
  }

  /** Uses the registered subscription authorizers and records events delivered to websocket subscribers */
  private static class RecordingClientEventService extends ClientEventService {
    final List<Object> sentPayloads = []
    final List<SharedEvent> triggeredEvents = []

    @Override
    void sendToWebsocketSession(String sessionKey, Object data) {
      sentPayloads.add(data)
    }

    @Override
    protected void onWebsocketSubscriptionTriggered(String sessionKey, EventSubscription<?> subscription, SharedEvent event) {
      triggeredEvents.add(event)
    }
  }
}
