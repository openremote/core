/*
 * Copyright 2024, OpenRemote Inc.
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
package org.openremote.manager.dashboard;

import static jakarta.ws.rs.core.Response.Status.*;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.Constants;
import org.openremote.model.dashboard.Dashboard;
import org.openremote.model.dashboard.DashboardAccess;
import org.openremote.model.dashboard.DashboardResource;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.DashboardQuery;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.security.ClientRole;
import org.openremote.model.util.ValueUtil;

public class DashboardResourceImpl extends ManagerWebResource implements DashboardResource {

  public static final Logger LOG = Logger.getLogger(DashboardResourceImpl.class.getName());

  protected final DashboardStorageService dashboardStorageService;
  protected final MessageBrokerService messageBrokerService;

  public DashboardResourceImpl(
      TimerService timerService,
      ManagerIdentityService identityService,
      DashboardStorageService dashboardStorageService,
      MessageBrokerService messageBrokerService) {
    super(timerService, identityService);
    this.dashboardStorageService = dashboardStorageService;
    this.messageBrokerService = messageBrokerService;
  }

  @Override
  public Dashboard[] getAllRealmDashboards(RequestParams requestParams, String realm) {
    if (realm == null) {
      throw new WebApplicationException(BAD_REQUEST);
    }
    if (!isRealmActiveAndAccessible(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }
    return dashboardStorageService.query(this.createDashboardQuery(realm), getUserId());
  }

  @Override
  public Dashboard get(RequestParams requestParams, String realm, String dashboardId) {
    if (realm == null) {
      throw new WebApplicationException(BAD_REQUEST);
    }
    Dashboard[] dashboards =
        dashboardStorageService.query(
            this.createDashboardQuery(realm).ids(dashboardId).limit(1), getUserId());

    // Don't return a different response if dashboard exists, expensive and of limited use it only
    // gives attackers more info than we should
    if (dashboards.length == 0) {
      throw new WebApplicationException(NOT_FOUND);
    }

    return dashboards[0];
  }

  @Override
  public Dashboard[] query(RequestParams requestParams, DashboardQuery query) {
    if (query == null) {
      query = this.createDashboardQuery(getRequestRealmName());
    } else {
      query = this.sanitizeDashboardQuery(query);
    }

    return dashboardStorageService.query(query, getUserId());
  }

  @Override
  public Dashboard create(RequestParams requestParams, Dashboard dashboard) {
    if (dashboard == null || dashboard.getRealm() == null) {
      throw new WebApplicationException(BAD_REQUEST);
    }
    String realm = dashboard.getRealm();
    if (!isRealmActiveAndAccessible(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }
    try {
      dashboard.setOwnerId(getUserId());
      dashboard.setAccess(DashboardAccess.SHARED);
      return this.dashboardStorageService.createNew(ValueUtil.clone(dashboard));
    } catch (IllegalArgumentException ex) {
      throw new WebApplicationException(ex, BAD_REQUEST);
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public Dashboard update(RequestParams requestParams, Dashboard dashboard) {
    if (dashboard == null || dashboard.getRealm() == null) {
      throw new WebApplicationException(BAD_REQUEST);
    }
    String realm = dashboard.getRealm();
    if (!isRealmActiveAndAccessible(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }
    try {
      return this.dashboardStorageService.update(ValueUtil.clone(dashboard), realm, getUserId());
    } catch (IllegalArgumentException ex) {
      throw new WebApplicationException(ex, NOT_FOUND);
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, INTERNAL_SERVER_ERROR);
    } catch (OptimisticLockException opEx) {
      throw new WebApplicationException(
          "Refresh the dashboard from the server and try to update the changes again",
          opEx,
          CONFLICT);
    }
  }

  @Override
  public void delete(RequestParams requestParams, String realm, String dashboardId) {
    if (realm == null) {
      throw new WebApplicationException(BAD_REQUEST);
    }
    if (!isRealmActiveAndAccessible(realm)) {
      throw new WebApplicationException(FORBIDDEN);
    }
    try {
      this.dashboardStorageService.delete(dashboardId, realm, getUserId());
    } catch (IllegalArgumentException ex) {
      throw new WebApplicationException(ex, NOT_FOUND);
    } catch (IllegalStateException ex) {
      throw new WebApplicationException(ex, INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Function that corrects an {@link DashboardQuery} object, based on user permissions.
   * Automatically fills NULL values, adjusts access filters based on roles, etc.
   *
   * @param query The query to correct
   * @return A DashboardQuery with the correct permissions based on the user.
   */
  protected DashboardQuery sanitizeDashboardQuery(DashboardQuery query) {
    Set<DashboardAccess> userAccess =
        new HashSet<>(
            Set.of(
                Optional.ofNullable(query.getConditions().getDashboard().getAccess())
                    .orElse(new DashboardAccess[0])));
    Set<DashboardQuery.AssetAccess> assetAccess =
        new HashSet<>(
            Set.of(
                Optional.ofNullable(query.getConditions().getAsset().getAccess())
                    .orElse(new DashboardQuery.AssetAccess[0])));

    if (query.getRealm() == null || query.getRealm().name == null) {
      query.realm(new RealmPredicate(getRequestRealmName()));
    }

    // Detect cross realm access
    if (isAuthenticated()
        && !isSuperUser()
        && !query.getRealm().name.equals(getAuthenticatedRealmName())) {
      throw new WebApplicationException(FORBIDDEN);
    }

    // User always has access to public dashboards
    userAccess.add(DashboardAccess.PUBLIC);
    assetAccess.add(DashboardQuery.AssetAccess.REALM);

    // Adjust query object based on user roles/permissions
    if (isAuthenticated()) {
      assetAccess.add(DashboardQuery.AssetAccess.LINKED);
      if (!hasResourceRole(ClientRole.READ_INSIGHTS.getValue(), Constants.KEYCLOAK_CLIENT_ID)) {
        userAccess.remove(DashboardAccess.SHARED);
        userAccess.remove(DashboardAccess.PRIVATE);
      }
      if (isRestrictedUser()) {
        assetAccess = new HashSet<>(Set.of(DashboardQuery.AssetAccess.RESTRICTED));
      }
    }

    // If not logged in, force only public read/write access
    else {
      userAccess = new HashSet<>(Set.of(DashboardAccess.PUBLIC));
      assetAccess = new HashSet<>(Set.of(DashboardQuery.AssetAccess.REALM));
    }

    // Build query object and return
    return query.conditions(
        new DashboardQuery.Conditions(
            new DashboardQuery.DashboardConditions()
                .access(userAccess.toArray(new DashboardAccess[0])),
            new DashboardQuery.AssetConditions()
                .access(assetAccess.toArray(new DashboardQuery.AssetAccess[0]))));
  }

  /**
   * Function that builds an {@link DashboardQuery} object, based on user permissions. Automatically
   * fills NULL values, adjusts access filters based on roles, etc.
   *
   * @param realm The realm to create a DashboardQuery for.
   * @return An instantiated DashboardQuery with the correct permissions based on the user.
   */
  protected DashboardQuery createDashboardQuery(String realm) {
    if (realm == null) {
      realm = getRequestRealmName();
    }
    return this.sanitizeDashboardQuery(new DashboardQuery().realm(new RealmPredicate(realm)));
  }
}
