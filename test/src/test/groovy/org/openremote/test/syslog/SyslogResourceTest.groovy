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
package org.openremote.test.syslog

import groovy.json.JsonSlurper
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.syslog.SyslogService
import org.openremote.model.security.ClientRole
import org.openremote.model.syslog.SyslogCategory
import org.openremote.model.syslog.SyslogConfig
import org.openremote.model.syslog.SyslogEvent
import org.openremote.model.syslog.SyslogLevel
import org.openremote.model.syslog.SyslogResource
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.util.MapAccess.getString

class SyslogResourceTest extends Specification implements ManagerContainerTrait {

  @Shared
  private static String userId

  def "Users with rules read role cannot retrieve syslog events"() {
    given: "the server container is started with the syslog resource"
    def container = startContainer(defaultConfig(), defaultServices(new SyslogService()))
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a user has the old rules role but not the logs role"
    def rulesAccessToken = createUserToken(container, keycloakTestSetup, "read-rules", ClientRole.READ_RULES)

    when: "the user requests syslog events"
    def response = getSyslogEvents(rulesAccessToken)

    then: "the resource rejects the wrong role"
    response.withCloseable { r ->
      assert r.status == Response.Status.FORBIDDEN.statusCode
      return true
    }

    cleanup: "the test user is removed"
    deleteUser(identityProvider)
  }

  def "Users with logs read role can retrieve syslog events"() {
    given: "the server container is started with the syslog resource"
    def container = startContainer(defaultConfig(), defaultServices(new SyslogService()))
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a user has the logs role"
    def logsAccessToken = createUserToken(container, keycloakTestSetup, "read-logs", ClientRole.READ_LOGS)

    when: "the user requests syslog events"
    def response = getSyslogEvents(logsAccessToken)

    then: "the resource allows the correct role"
    response.withCloseable { r ->
      assert r.status == Response.Status.OK.statusCode
      return true
    }

    cleanup: "the test user is removed"
    deleteUser(identityProvider)
  }

  def "Superuser can read and update syslog configuration and clear stored events"() {
    given: "the server container and a superuser resource proxy"
    def container = startContainer(defaultConfig(), defaultServices(new SyslogService()))
    def token = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT))
    def adminResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, token)
            .proxy(SyslogResource.class)
    def persistenceService = container.getService(PersistenceService.class)
    def originalConfig = adminResource.getConfig(null)
    def updatedConfig = new SyslogConfig(SyslogLevel.WARN,
            [SyslogCategory.API] as SyslogCategory[], originalConfig.storedMaxAgeMinutes + 1)
    String marker = "syslog-resource-test-${UUID.randomUUID()}"
    def now = Instant.now()

    when: "the superuser changes the storage configuration"
    adminResource.updateConfig(null, updatedConfig)
    def storedConfig = adminResource.getConfig(null)

    then:
    storedConfig.storedLevel == SyslogLevel.WARN
    storedConfig.storedCategories.toList() == [SyslogCategory.API]
    storedConfig.storedMaxAgeMinutes == updatedConfig.storedMaxAgeMinutes

    when: "a stored event is read through the resource"
    persistenceService.doTransaction { em ->
      em.persist(new SyslogEvent(now.toEpochMilli(), SyslogLevel.WARN,
              SyslogCategory.API, marker, "test event"))
    }
    def before = adminResource.getEvents(null, SyslogLevel.WARN, 10, 1,
            now.minusSeconds(60).toEpochMilli(), now.plusSeconds(60).toEpochMilli(),
            [SyslogCategory.API], [marker])

    then:
    before.withCloseable { r ->
      assert r.status == Response.Status.OK.statusCode
      assert new JsonSlurper().parseText(r.readEntity(String.class))*.message == ["test event"]
      return true
    }

    when: "the superuser clears stored events"
    adminResource.clearEvents(null)
    def after = adminResource.getEvents(null, SyslogLevel.WARN, 10, 1,
            now.minusSeconds(60).toEpochMilli(), now.plusSeconds(60).toEpochMilli(),
            [SyslogCategory.API], [marker])

    then:
    after.withCloseable { r ->
      assert r.status == Response.Status.OK.statusCode
      assert new JsonSlurper().parseText(r.readEntity(String.class)).isEmpty()
      return true
    }

    cleanup:
    if (originalConfig != null) {
      adminResource.updateConfig(null, originalConfig)
    }
    if (persistenceService != null && marker != null) {
      persistenceService.doTransaction { em ->
        em.createQuery("delete from SyslogEvent e where e.subCategory = :marker")
                .setParameter("marker", marker).executeUpdate()
      }
    }
  }

  def "Admin client roles alone cannot manage or read global syslog configuration"() {
    given: "a master realm user has admin client roles but no superuser realm role"
    def container = startContainer(defaultConfig(), defaultServices(new SyslogService()))
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def token = createUserToken(container, keycloakTestSetup, "admin-without-superuser",
            ClientRole.READ_ADMIN, ClientRole.WRITE_ADMIN)
    def restrictedSyslogResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, token)
            .proxy(SyslogResource.class)

    when: "the user clears stored events"
    restrictedSyslogResource.clearEvents(null)

    then:
    thrown(ForbiddenException)

    when: "the user reads the global syslog configuration"
    restrictedSyslogResource.getConfig(null)

    then:
    thrown(ForbiddenException)

    when: "the user updates the global syslog configuration"
    restrictedSyslogResource.updateConfig(null, new SyslogConfig(SyslogLevel.INFO,
            [SyslogCategory.API] as SyslogCategory[], 60))

    then:
    thrown(ForbiddenException)

    cleanup:
    deleteUser(identityProvider)
  }

  private String createUserToken(container, KeycloakTestSetup keycloakTestSetup, String roleName, ClientRole... roles) {
    def username = "syslog-test-${roleName}"
    def user = keycloakTestSetup.createUser(
            MASTER_REALM,
            username,
            username,
            "Syslog",
            roleName,
            "${username}@openremote.local",
            true,
            roles
    )

    userId = user.id
    authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, username, username)
  }

  private static void deleteUser(ManagerIdentityProvider identityProvider) {
    if (userId != null) {
      identityProvider.deleteUser(MASTER_REALM, userId)
      userId = null
    }
  }

  private def getSyslogEvents(String accessToken) {
    getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken)
            .path("syslog")
            .path("event")
            .request()
            .get()
  }
}
