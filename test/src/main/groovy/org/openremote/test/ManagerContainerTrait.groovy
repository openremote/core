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
package org.openremote.test

import com.google.common.collect.Lists
import org.apache.camel.spi.BrowsableEndpoint
import org.openremote.container.Container
import org.openremote.container.message.MessageBrokerService
import org.openremote.container.timer.TimerService
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.model.ContainerService
import org.openremote.model.util.TextUtil
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

import static org.openremote.container.persistence.PersistenceService.OR_DB_PORT
import static org.openremote.container.security.keycloak.KeycloakIdentityProvider.OR_KEYCLOAK_PORT
import static org.openremote.container.timer.TimerService.Clock.PSEUDO
import static org.openremote.container.timer.TimerService.TIMER_CLOCK_TYPE
import static org.openremote.container.web.WebService.OR_WEBSERVER_LISTEN_PORT
import static org.openremote.manager.mqtt.MQTTBrokerService.MQTT_SERVER_LISTEN_HOST
import static org.openremote.manager.rules.RulesService.OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS
import static org.openremote.manager.rules.RulesService.OR_RULES_QUICK_FIRE_MILLIS

trait ManagerContainerTrait extends ContainerTrait {

  Map<String, String> defaultConfig(Integer serverPort) {
    if (serverPort == null) {
      serverPort = findEphemeralPort()
    }
    def config = [
      (OR_WEBSERVER_LISTEN_PORT): Integer.toString(serverPort),
      (MQTT_SERVER_LISTEN_HOST) : "127.0.0.1", // Works best for cross platform test running,
      (MQTTBrokerService.MQTT_FORCE_USER_DISCONNECT_DEBOUNCE_MILLIS): "10",
      (OR_RULES_QUICK_FIRE_MILLIS): "500",
      (OR_RULES_MIN_TEMP_FACT_EXPIRATION_MILLIS): "500",
      (TIMER_CLOCK_TYPE) : PSEUDO.name(),
      // Ports of the test stack, so the tests never clean the development database or Keycloak
      (OR_DB_PORT): "15432",
      (OR_KEYCLOAK_PORT): "18081"
    ] << System.getenv()

    config.values().removeIf { TextUtil.isNullOrEmpty(it)}
    return config
  }

  Iterable<ContainerService> defaultServices(Iterable<ContainerService> additionalServices) {
    [
      *Lists.newArrayList(ServiceLoader.load(ContainerService.class)),
      *additionalServices
    ].stream()
    .sorted(Comparator.comparingInt{it.getPriority()})
    .collect(Collectors.<ContainerService>toList()) as Iterable<ContainerService>
  }

  Iterable<ContainerService> defaultServices(ContainerService... additionalServices) {
    defaultServices(Arrays.asList(additionalServices))
  }

  /**
   * Execute pseudo clock operations in Container.
   */
  void withClockOf(Container container, Closure<TimerService.Clock> clockConsumer) {
    clockConsumer.call(container.getService(TimerService.class).getClock())
  }

  long getClockTimeOf(Container container) {
    container.getService(TimerService.class).getCurrentTimeMillis()
  }

  Instant getInstantTimeOf(Container container) {
    container.getService(TimerService.class).getNow()
  }

  void advancePseudoClock(long amount, TimeUnit unit) {
    advancePseudoClock(amount, unit, container)
  }

  void advancePseudoClock(long amount, TimeUnit unit, Container container) {
    withClockOf(container) { it.advanceTime(amount, unit) }
  }

  // Used in custom project tests
  void setPseudoClock(LocalDate date, LocalTime time, ZoneId zoneId) {
    withClockOf(container) { it.setTime(date, time, zoneId) }
  }

  void setPseudoClock(String iso8601Timestamp) {
    withClockOf(container) { it.setTime(iso8601Timestamp) }
  }

  void stopPseudoClock() {
    withClockOf(container) { it.stop() }
  }

  void startPseudoClock() {
    withClockOf(container) { it.start() }
  }

  void noPendingExchangesOnMessageEndpoint(Container container, String... endpointName) {
    for (String name : endpointName) {
      def endpoint = container.getService(MessageBrokerService.class).getContext().getEndpoint(name)
      if (!endpoint) {
        throw new IllegalArgumentException("Messaging endpoint not found: " + name)
      }
      new PollingConditions(initialDelay: 0.1, delay: 0.05).eventually {
        assert ((BrowsableEndpoint)endpoint).exchanges.size() == 0
      }
    }
  }
}
