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
import java.util.concurrent.TimeUnit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = 'Checks the current availability of external test services')
class VerifyTestServices extends DefaultTask {

  @TaskAction
  void verifyServices() {
    def timeoutSeconds = 30L
    def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    def postgresReady = false
    def keycloakReady = false
    while ((!postgresReady || !keycloakReady) && System.nanoTime() <deadline) {
      if (!postgresReady) {
        postgresReady = isPostgresReady()
      }
      if (!keycloakReady) {
        keycloakReady = isKeycloakReady()
      }
      if (!postgresReady || !keycloakReady) {
        Thread.sleep(500)
      }
    }

    def unavailable = []
    if (!postgresReady) {
      unavailable << 'PostgreSQL on 127.0.0.1:5432'
    }
    if (!keycloakReady) {
      unavailable << 'Keycloak at http://127.0.0.1:8081/auth'
    }
    if (unavailable) {
      throw new GradleException("Timed out after ${timeoutSeconds} seconds waiting for ${unavailable.join(' and ')}. Start the test services with `docker compose -f profile/dev-testing.yml -p openremote up -d --no-build`.")
    }
  }

  private static boolean isPostgresReady() {
    def socket = new Socket()
    try {
      socket.connect(new InetSocketAddress('127.0.0.1', 5432), 500)
      return true
    } catch (IOException ignored) {
      return false
    } finally {
      try {
        socket.close()
      } catch (IOException ignored) {
      }
    }
  }

  private static boolean isKeycloakReady() {
    HttpURLConnection connection = null
    try {
      connection = (HttpURLConnection) new URL('http://127.0.0.1:8081/auth/realms/master/.well-known/openid-configuration').openConnection()
      connection.connectTimeout = 500
      connection.readTimeout = 500
      return connection.responseCode == HttpURLConnection.HTTP_OK
    } catch (IOException ignored) {
      return false
    } finally {
      connection?.disconnect()
    }
  }
}
