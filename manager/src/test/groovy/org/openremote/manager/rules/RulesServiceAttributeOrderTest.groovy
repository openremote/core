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
package org.openremote.manager.rules

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.openremote.container.timer.TimerService
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.rules.GlobalRuleset
import spock.lang.Specification

class RulesServiceAttributeOrderTest extends Specification {

  private RulesEngine<GlobalRuleset> createEngine(RulesService service) {
    new RulesEngine<>(
            Stub(TimerService),
            service,
            null,
            null,
            Stub(ScheduledExecutorService),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new RulesEngineId<>(),
            null,
            null)
  }

  private AttributeEvent createEvent(AttributeRef ref, Object value, long timestamp) {
    def event = new AttributeEvent(ref, value, timestamp)
    event.oldValueTimestamp = 0L
    return event
  }

  def "concurrent attribute updates are dispatched to rules engines in timestamp order"() {
    given:
    def v2Entered = new CountDownLatch(1)
    def allowV2Dispatch = new CountDownLatch(1)
    def pauseNextDispatch = new AtomicBoolean(false)
    def service =
            new RulesService() {
              @Override
              protected List<RulesEngine<?>> getEnginesInScope(String realm, String[] assetPath) {
                if (pauseNextDispatch.compareAndSet(true, false)) {
                  v2Entered.countDown()
                  allowV2Dispatch.await(5, TimeUnit.SECONDS)
                }
                return [globalEngine.get()]
              }
            }
    def engine = createEngine(service)
    service.globalEngine.set(engine)

    def ref = new AttributeRef("asset-1", "count")
    def v1 = createEvent(ref, 1, 100L)
    def v2 = createEvent(ref, 2, 200L)
    def v3 = createEvent(ref, 3, 300L)
    service.insertOrUpdateAttributeInfo(v1)
    engine.updateInfos.clear()
    engine.insertInfos.clear()

    when: "a newer update arrives while an older update is waiting to dispatch to the engine"
    pauseNextDispatch.set(true)
    def deliveryA = Thread.ofPlatform().start { service.insertOrUpdateAttributeInfo(v2) }
    assert v2Entered.await(5, TimeUnit.SECONDS)

    def deliveryB = Thread.ofPlatform().start { service.insertOrUpdateAttributeInfo(v3) }
    deliveryB.join(200)
    allowV2Dispatch.countDown()

    deliveryA.join(5000)
    deliveryB.join(5000)

    then: "the newer attribute state wins both in RulesService and in RulesEngine"
    !deliveryA.alive
    !deliveryB.alive
    service.attributeEventsByRef.get(ref).timestamp == 300L
    service.attributeEventsByRef.get(ref).value == Optional.of(3)
    engine.updateInfos.size() == 1
    engine.updateInfos.first().timestamp == 300L
    engine.updateInfos.first().value == Optional.of(3)
  }

  def "concurrent retraction and update serialize without losing the newer update"() {
    given:
    def retractEntered = new CountDownLatch(1)
    def allowRetractDispatch = new CountDownLatch(1)
    def pauseNextDispatch = new AtomicBoolean(false)
    def service =
            new RulesService() {
              @Override
              protected List<RulesEngine<?>> getEnginesInScope(String realm, String[] assetPath) {
                if (pauseNextDispatch.compareAndSet(true, false)) {
                  retractEntered.countDown()
                  allowRetractDispatch.await(5, TimeUnit.SECONDS)
                }
                return [globalEngine.get()]
              }
            }
    def engine = createEngine(service)
    service.globalEngine.set(engine)

    def ref = new AttributeRef("asset-1", "count")
    def v1 = createEvent(ref, 1, 100L)
    def v3 = createEvent(ref, 3, 300L)
    service.insertOrUpdateAttributeInfo(v1)
    engine.updateInfos.clear()
    engine.insertInfos.clear()

    when: "a newer insert arrives while a retraction is still dispatching"
    pauseNextDispatch.set(true)
    def retractThread = Thread.ofPlatform().start { service.retractAttributeInfo(v1) }
    assert retractEntered.await(5, TimeUnit.SECONDS)

    def insertThread = Thread.ofPlatform().start { service.insertOrUpdateAttributeInfo(v3) }
    insertThread.join(200)
    allowRetractDispatch.countDown()

    retractThread.join(5000)
    insertThread.join(5000)

    then: "retraction completes before the subsequent insert"
    !retractThread.alive
    !insertThread.alive
    service.attributeEventsByRef.get(ref)?.timestamp == 300L
    service.attributeEventsByRef.get(ref)?.value == Optional.of(3)
    engine.retractInfos.isEmpty()
    engine.insertInfos.size() == 1
    engine.insertInfos.first().timestamp == 300L
    engine.insertInfos.first().value == Optional.of(3)
  }

  def "RulesEngine ignores older queued attribute info and preserves insert flag"() {
    given:
    def service = new RulesService()
    def engine = createEngine(service)
    def ref = new AttributeRef("asset-1", "count")
    def v2 = new AttributeEvent(ref, 2, 200L)
    def v3 = new AttributeEvent(ref, 3, 300L)

    when: "a newer update is queued before an older initial insert"
    engine.insertOrUpdateAttributeInfo(v3, false)
    engine.insertOrUpdateAttributeInfo(v2, true)

    then: "the newer value is kept and promoted to insertInfos"
    engine.updateInfos.isEmpty()
    engine.insertInfos.size() == 1
    engine.insertInfos.first().timestamp == 300L
    engine.insertInfos.first().value == Optional.of(3)

    when: "the attribute is retracted and later re-inserted with an older timestamp"
    engine.retractAttributeInfo(v3)
    engine.insertOrUpdateAttributeInfo(v2, true)

    then: "retraction clears the queued timestamp guard"
    engine.retractInfos.isEmpty()
    engine.insertInfos.size() == 1
    engine.insertInfos.first().timestamp == 200L
    engine.insertInfos.first().value == Optional.of(2)
  }

  def "RulesEngine clears queued timestamp tracking after firing deployments"() {
    given:
    def service = new RulesService()
    def engine = createEngine(service)
    def ref = new AttributeRef("asset-1", "count")
    def v3 = new AttributeEvent(ref, 3, 300L)
    def v2 = new AttributeEvent(ref, 2, 200L)

    when: "an update is queued and fired"
    engine.insertOrUpdateAttributeInfo(v3, true)
    engine.running = true
    engine.fireAllDeployments()

    then: "the fired fact is in RulesFacts and queued state is cleared"
    engine.facts.getAssetStates().find { it.ref == ref }?.timestamp == 300L
    engine.insertInfos.isEmpty()
    engine.updateInfos.isEmpty()

    when: "a new event is queued in the next cycle"
    engine.insertOrUpdateAttributeInfo(v2, false)

    then:
    engine.updateInfos.size() == 1
    engine.updateInfos.first().timestamp == 200L
  }
}
