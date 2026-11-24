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
package org.openremote.test.dashboard

import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.AssetResource
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.LightAsset
import org.openremote.model.attribute.MetaItem
import org.openremote.model.dashboard.*
import org.openremote.model.query.DashboardQuery
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.security.ClientRole
import org.openremote.model.security.User
import org.openremote.model.security.UserResource
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.model.Constants.*

class DashboardTest extends Specification implements ManagerContainerTrait {

  def "Test dashboard creation and access rights"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "all users are logged in"
    def adminUserAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )
    def privateUser1AccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )
    def smartcityUserAccessToken = authenticate(
            container,
            keycloakTestSetup.realmCity.name,
            KEYCLOAK_CLIENT_ID,
            "smartcity",
            "smartcity"
    )

    and: "the admin user has been retrieved"
    def serverUri = serverUri(serverPort)
    def adminUserResource = getClientApiTarget(serverUri, MASTER_REALM, adminUserAccessToken).proxy(UserResource)
    def adminUser = adminUserResource.getCurrent(null)

    and: "an additional user without READ_INSIGHTS is created"
    ClientRole[] noInsightsRoles = new ClientRole[] {
              ClientRole.READ_ASSETS
            }
    User noAccessUser = keycloakTestSetup.createUser(MASTER_REALM, "noinsights", "noinsights", "firstName", "lastName", "noinsights@openremote.local", true, noInsightsRoles)
    def noAccessUserToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, "noinsights", "noinsights")

    and: "an additional user without WRITE_INSIGHTS is created"
    ClientRole[] readonlyRoles = new ClientRole[] {
              ClientRole.READ_ASSETS, ClientRole.READ_INSIGHTS
            }
    User readonlyUser = keycloakTestSetup.createUser(MASTER_REALM, "readinsights", "readinsights", "firstName", "lastName", "readinsights@openremote.local", true, readonlyRoles)
    def readonlyUserToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, "readinsights", "readinsights")

    and: "an additional user with all permissions is created"
    User alternativeUser = keycloakTestSetup.createUser(MASTER_REALM, "alternativeuser", "alternativeuser", "alternative", "user", "alternativeuser@openremote.local", true, KeycloakTestSetup.REGULAR_USER_ROLES)
    def alternativeUserToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, "alternativeuser", "alternativeuser")

    and: "the dashboard resource"
    def adminUserDashboardResource = getClientApiTarget(serverUri, MASTER_REALM, adminUserAccessToken).proxy(DashboardResource.class)
    def privateUser1DashboardResource = getClientApiTarget(serverUri, MASTER_REALM, privateUser1AccessToken).proxy(DashboardResource.class)
    def noAccessDashboardResource = getClientApiTarget(serverUri, MASTER_REALM, noAccessUserToken).proxy(DashboardResource.class)
    def readonlyDashboardResource = getClientApiTarget(serverUri, MASTER_REALM, readonlyUserToken).proxy(DashboardResource.class)
    def alternativeUserDashboardResource = getClientApiTarget(serverUri, MASTER_REALM, alternativeUserToken).proxy(DashboardResource.class)
    def publicUserDashboardResource = getClientApiTarget(serverUri, MASTER_REALM).proxy(DashboardResource.class)
    def smartcityDashboardResource = getClientApiTarget(serverUri, keycloakTestSetup.realmCity.name, smartcityUserAccessToken).proxy(DashboardResource.class)
    def adminUserAssetResource = getClientApiTarget(serverUri, MASTER_REALM, adminUserAccessToken).proxy(AssetResource.class)

    /* ----------------------- */

    // Test to verify dashboards can be CREATED and FETCHED by different users.

    when: "a user without WRITE_INSIGHTS tries to create dashboard"
    DashboardScreenPreset[] tempScreenPresets = Arrays.asList(new DashboardScreenPreset("preset1", DashboardScalingPreset.KEEP_LAYOUT)).toArray() as DashboardScreenPreset[]
    Dashboard temp1NoAccess = new Dashboard(MASTER_REALM, "dashboard1", tempScreenPresets, noAccessUser.id)
    noAccessDashboardResource.create(null, temp1NoAccess)

    then: "an exception is thrown that reflects their permissions"
    thrown ForbiddenException

    when: "a user without WRITE_INSIGHTS, but with READ_INSIGHTS, tries to create dashboard"
    Dashboard temp1Readonly = new Dashboard(MASTER_REALM, "dashboard1", tempScreenPresets, readonlyUser.id)
    readonlyDashboardResource.create(null, temp1Readonly)

    then: "an exception is also thrown"
    thrown ForbiddenException

    when: "a dashboard is created in the authenticated realm"
    DashboardScreenPreset[] screenPresets = Arrays.asList(new DashboardScreenPreset("preset1", DashboardScalingPreset.KEEP_LAYOUT)).toArray() as DashboardScreenPreset[]
    Dashboard temp1 = new Dashboard(MASTER_REALM, "dashboard1", screenPresets, adminUser.id)
    adminUserDashboardResource.create(null, temp1)
    Dashboard temp2 = new Dashboard(MASTER_REALM, "dashboard2", screenPresets, adminUser.id).setOwnerId(keycloakTestSetup.testuser1Id)
    privateUser1DashboardResource.create(null, temp2)

    then: "requesting the dashboard should return the same object"
    def adminDashboards = adminUserDashboardResource.getAllRealmDashboards(null, MASTER_REALM)
    def adminDashboard = adminDashboards[0]
    def privateUser1Dashboard = adminDashboards[1]
    assert adminDashboards.length == 2
    def userDashboards = privateUser1DashboardResource.getAllRealmDashboards(null, MASTER_REALM)
    assert userDashboards.length == 2
    assert adminDashboard.id == userDashboards[0].id
    assert privateUser1Dashboard.id == userDashboards[1].id

    when: "a user without READ_INSIGHTS tries to fetch all realm dashboards"
    def readonlyDashboards = noAccessDashboardResource.getAllRealmDashboards(null, MASTER_REALM)

    then: "none are returned"
    assert readonlyDashboards.length == 0

    /* ----------------------- */

    // Test to verify user can query by displayName

    when: "admin user tries to query dashboard by display name"
    def displayNameQuery = new DashboardQuery().names("dashboard1")
    def dashboards = adminUserDashboardResource.query(null, displayNameQuery)

    then: "the correct dashboard should be returned"
    assert dashboards.length == 1
    assert dashboards[0].displayName == "dashboard1"
    assert dashboards[0].access == DashboardAccess.SHARED

    when: "a user without READ_INSIGHTS tries to query dashboard by display name"
    dashboards = noAccessDashboardResource.query(null, displayNameQuery)

    then: "no dashboards are returned"
    assert dashboards.length == 0

    when: "an unauthenticated user tries to query dashboard by display name"
    dashboards = publicUserDashboardResource.query(null, displayNameQuery)

    then: "no public dashboards with the display name could be found"
    assert dashboards.length == 0

    /* ----------------------- */

    // Test to verify access logic

    when: "a dashboard is updated from a different realm"
    privateUser1Dashboard.setDisplayName("New displayname")
    privateUser1Dashboard = smartcityDashboardResource.update(null, privateUser1Dashboard)

    then: "an forbidden exception is thrown for not having cross-realm access"
    thrown ForbiddenException

    when: "a shared dashboard is updated by a different user"
    privateUser1Dashboard = alternativeUserDashboardResource.update(null, privateUser1Dashboard)

    then: "the update succeeds as it is shared across the realm"
    def alternativeUpdatedDashboard = alternativeUserDashboardResource.get(null, MASTER_REALM, privateUser1Dashboard.id)
    assert alternativeUpdatedDashboard.setDisplayName("New displayname 2")

    /* ----------- */

    when: "the user without READ_INSIGHTS and WRITE_INSIGHTS tries to retrieve the shared dashboard"
    noAccessDashboardResource.get(null, MASTER_REALM, privateUser1Dashboard.id)

    then: "a not found exception should be thrown"
    thrown NotFoundException

    /* ----------- */

    when: "dashboard1 is made 'private' by its original owner"
    privateUser1Dashboard.setAccess(DashboardAccess.PRIVATE)
    privateUser1Dashboard = privateUser1DashboardResource.update(null, privateUser1Dashboard)

    then: "the dashboard should have been updated"
    privateUser1Dashboard != null

    when: "all dashboards of the original owner are retrieved"
    dashboards = privateUser1DashboardResource.getAllRealmDashboards(null, MASTER_REALM)

    then: "dashboard1 is actually private"
    assert dashboards.length == 2
    assert dashboards.find((d) -> d.id == privateUser1Dashboard.id).access == DashboardAccess.PRIVATE

    /* ----------- */

    when: "a user tries to update another user's private dashboard"
    privateUser1Dashboard.setDisplayName("another displayname")
    privateUser1Dashboard = adminUserDashboardResource.update(null, privateUser1Dashboard)

    then: "it should throw an exception"
    thrown NotFoundException

    /* ----------- */

    when: "a user makes a dashboard query for a name that matches a another user's private dashboard"
    def adminDisplaynameDashboards = adminUserDashboardResource.query(null, new DashboardQuery().names(privateUser1Dashboard.displayName))

    then: "the dashboard should not be returned"
    assert adminDisplaynameDashboards.length == 0

    /* ----------------------- */

    // Test to verify private dashboards cannot be fetched by public/unauthenticated users, even if ID is specified

    when: "when a public user specifically fetches a private dashboard"
    publicUserDashboardResource.get(null, MASTER_REALM, adminDashboard.id)

    then: "a not found exception should be thrown"
    thrown NotFoundException

    /* ----------------------- */

    // Test to verify "DELETE" logic

    when: "admin user updates his dashboard to be private"
    adminDashboard.setAccess(DashboardAccess.PRIVATE)
    adminDashboard = adminUserDashboardResource.update(null, adminDashboard)

    and: "a different user tries to delete a dashboard he doesn't own"
    privateUser1DashboardResource.delete(null, MASTER_REALM, adminDashboard.id)

    then: "it should throw a NOT_FOUND exception, since it isn't visible to him"
    thrown NotFoundException

    when: "admin user makes his dashboard SHARED again"
    adminDashboard.setAccess(DashboardAccess.SHARED)
    adminDashboard = adminUserDashboardResource.update(null, adminDashboard)

    and: "a different user tries to delete the dashboard again"
    privateUser1DashboardResource.delete(null, MASTER_REALM, adminDashboard.id)

    then: "it should succeed, and the dashboard cannot be retrieved by the admin again"
    assert adminUserDashboardResource.getAllRealmDashboards(null, MASTER_REALM).find {
      it.id === adminDashboard.id
    } == null

    /* ----------------------- */

    // Test to verify public/unauthenticated user cannot fetch dashboards by default

    when: "dashboards are requested by a public user"
    def publicDashboards1 = publicUserDashboardResource.getAllRealmDashboards(null, MASTER_REALM)

    then: "it should return an empty array"
    assert publicDashboards1.length == 0

    /* ----------------------- */

    // Test to verify public dashboards CAN be fetched by public/unauthenticated users

    when: "dashboard1 is made 'public' by its original owner"
    privateUser1Dashboard.setAccess(DashboardAccess.PUBLIC)
    privateUser1Dashboard.setDisplayName("Public dashboard")
    privateUser1Dashboard = privateUser1DashboardResource.update(null, privateUser1Dashboard) != null

    then: "the now public dashboard should be available to anonymous users"
    def publicDashboards2 = publicUserDashboardResource.getAllRealmDashboards(null, MASTER_REALM)
    assert publicDashboards2.length == 1
    assert publicDashboards2[0].access == DashboardAccess.PUBLIC
    assert publicDashboards2[0].displayName == "Public dashboard"

    and: "all users should be able to fetch the public dashboard"
    assert adminUserDashboardResource.get(null, MASTER_REALM, publicDashboards2[0].id).displayName == publicDashboards2[0].displayName
    assert privateUser1DashboardResource.get(null, MASTER_REALM, publicDashboards2[0].id).displayName == publicDashboards2[0].displayName
    assert noAccessDashboardResource.get(null, MASTER_REALM, publicDashboards2[0].id).displayName == publicDashboards2[0].displayName
    assert readonlyDashboardResource.get(null, MASTER_REALM, publicDashboards2[0].id).displayName == publicDashboards2[0].displayName
    assert alternativeUserDashboardResource.get(null, MASTER_REALM, publicDashboards2[0].id).displayName == publicDashboards2[0].displayName

    /* ------------------------- */

    // Test to check whether dashboards are queried when no assets are accessible

    when: "a new asset is created in the building realm"
    def lightAsset = new LightAsset("admin light").setRealm(keycloakTestSetup.realmBuilding.name)
    lightAsset.getAttribute("brightness").ifPresent { it ->
      it.addMeta(new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
    }
    def adminLightAsset = adminUserAssetResource.create(null, lightAsset)

    and: "a dashboard is created in the building realm using that asset"
    def gridItem = new DashboardGridItem().setX(0).setY(0).setW(1).setH(1)
    def widgetConfigStr = ('{"attributeRefs":[{"id":"assetId","name":"brightness"}]}').replace("assetId", adminLightAsset.id)
    def widget = new DashboardWidget()
            .setDisplayName("Light widget")
            .setWidgetTypeId("test")
            .setGridItem(gridItem)
            .setWidgetConfig(ValueUtil.parse(widgetConfigStr, Object.class).orElse(null))
    def template = new DashboardTemplate(screenPresets)
            .setWidgets(new DashboardWidget[]{
              widget
            })
    def buildingDashboard = new Dashboard()
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .setDisplayName("Dashboard with assets")
            .setTemplate(template)
            .setAccess(DashboardAccess.SHARED)
            .setOwnerId(adminUser.id)

    def newDashboard = adminUserDashboardResource.create(null, buildingDashboard)

    and: "a restricted user authenticates for the building realm"
    def restrictedUser1AccessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )
    def restrictedUserDashboardResource = getClientApiTarget(serverUri, keycloakTestSetup.realmBuilding.name, restrictedUser1AccessToken).proxy(DashboardResource.class)

    and: "a restricted user tries to query a dashboard without access to the assets shown"
    def restrictedDashboards = restrictedUserDashboardResource.getAllRealmDashboards(null, keycloakTestSetup.realmBuilding.name)

    then: "it should only return that dashboard"
    assert restrictedDashboards.length == 0

    when: "the user gets access to the asset"
    def assetStorageService = container.getService(AssetStorageService.class)
    assetStorageService.storeUserAssetLinks(Arrays.asList(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.getName(), keycloakTestSetup.testuser3Id, adminLightAsset.getId())
            ))

    then: "the user is able to request the dashboard"
    def restrictedDashboards2 = restrictedUserDashboardResource.getAllRealmDashboards(null, keycloakTestSetup.realmBuilding.name)
    assert restrictedDashboards2.length == 1
    assert restrictedDashboards2[0].displayName == "Dashboard with assets"


    /* ------------------------- */

    // Test for cross realm requests
    when: "the admin superuser in master realm tries requesting a dashboard in the building realm"
    def dashboardOtherRealmQuery = new DashboardQuery().realm(new RealmPredicate(keycloakTestSetup.realmBuilding.name))
    def dashboardsInOtherRealm = adminUserDashboardResource.query(null, dashboardOtherRealmQuery)

    then: "it returns the building realm dashboard"
    assert dashboardsInOtherRealm.length == 1
    assert dashboardsInOtherRealm[0].displayName == "Dashboard with assets"

    when: "the private user in master realm tries requesting a dashboard in the building realm"
    def sameDashboardOtherRealmQuery = new DashboardQuery().realm(new RealmPredicate(keycloakTestSetup.realmBuilding.name))
    def sameDashboardsInOtherRealm = privateUser1DashboardResource.query(null, sameDashboardOtherRealmQuery)

    then: "it should return 403 forbidden"
    thrown ForbiddenException



    /* ------------------------- */

    // Test to delete the dashboard

    when: "the admin user deletes the new dashboard"
    adminUserDashboardResource.delete(null, keycloakTestSetup.realmBuilding.getName(), newDashboard.getId())

    and: "tries to get the dashboard again"
    adminUserDashboardResource.get(null, keycloakTestSetup.realmBuilding.getName(), newDashboard.getId())

    then: "it cannot be found, since it got deleted"
    thrown NotFoundException

    when: "the restricted user tries to fetch the deleted dashboard"
    restrictedUserDashboardResource.get(null, keycloakTestSetup.realmBuilding.getName(), newDashboard.getId())

    then: "it cannot be found as well, since it got deleted"
    thrown NotFoundException

    /* ------------------------- */

    // Test to verify reading a dashboard does not change its version, so a fetch by
    // another user cannot make an editor's save fail with a version conflict

    when: "a dashboard with a widget is created and fetched by the admin and another user"
    def versionWidget = new DashboardWidget()
            .setDisplayName("Version widget")
            .setWidgetTypeId("linechart")
            .setGridItem(new DashboardGridItem().setX(0).setY(0).setW(1).setH(1))
    def versionDashboard = new Dashboard(MASTER_REALM, "Version dashboard", screenPresets, adminUser.id)
            .setTemplate(new DashboardTemplate(screenPresets).setWidgets([versionWidget] as DashboardWidget[]))
    def versioned = adminUserDashboardResource.create(null, versionDashboard)
    def fetchedByAdmin = adminUserDashboardResource.get(null, MASTER_REALM, versioned.id)
    def fetchedByUser = alternativeUserDashboardResource.get(null, MASTER_REALM, versioned.id)

    then: "the reads leave the version unchanged"
    assert fetchedByAdmin.version == versioned.version
    assert fetchedByUser.version == versioned.version

    when: "the admin alters their fetched copy, and saves it"
    fetchedByAdmin.displayName = "Version dashboard (edited)"
    def versionedSaved = adminUserDashboardResource.update(null, fetchedByAdmin)

    then: "the save succeeds and bumps the version by one"
    assert versionedSaved.displayName == "Version dashboard (edited)"
    assert versionedSaved.version == fetchedByAdmin.version + 1

    when: "the now stale copy of the regular user is altered and saved"
    fetchedByUser.displayName = "Stale edit"
    alternativeUserDashboardResource.update(null, fetchedByUser)

    then: "the stale save is rejected with 409 conflict"
    WebApplicationException conflictEx = thrown()
    conflictEx.response.withCloseable { r ->
      assert r.status == 409
      return true
    }

    /* ------------------------- */

    // Test to verify a dashboard with the same unique ID cannot be created.

    when: "a dashboard is created with an id that already exists"
    alternativeUserDashboardResource.create(null, new Dashboard(MASTER_REALM, "Duplicate dashboard", screenPresets, alternativeUser.id).setId(versioned.id))

    then: "the duplicate create is rejected with 400 bad request"
    WebApplicationException duplicateEx = thrown()
    duplicateEx.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
  }
}
