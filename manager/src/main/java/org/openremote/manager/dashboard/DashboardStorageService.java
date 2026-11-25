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

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.camel.builder.RouteBuilder;
import org.hibernate.jpa.AvailableHints;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.dashboard.Dashboard;
import org.openremote.model.dashboard.DashboardAccess;
import org.openremote.model.query.DashboardQuery;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.util.TextUtil;

public class DashboardStorageService extends RouteBuilder implements ContainerService {

  protected static final Logger LOG = Logger.getLogger(DashboardStorageService.class.getName());

  protected ManagerIdentityService identityService;
  protected PersistenceService persistenceService;
  protected AssetStorageService assetStorageService;
  protected TimerService timerService;

  @Override
  public void configure() throws Exception {
    /* code not overridden yet */
  }

  @Override
  public void init(Container container) throws Exception {
    identityService = container.getService(ManagerIdentityService.class);
    persistenceService = container.getService(PersistenceService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    timerService = container.getService(TimerService.class);
    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new DashboardResourceImpl(
                container.getService(TimerService.class),
                identityService,
                this,
                container.getService(MessageBrokerService.class)));
  }

  @Override
  public void start(Container container) throws Exception {
    /* code not overridden yet */
  }

  @Override
  public void stop(Container container) throws Exception {
    /* code not overridden yet */

  }

  /**
   * Pulls dashboards from the database based on the query object. Useful to specifically filter and
   * request dashboards.
   *
   * @param dashboardQuery see {@link DashboardQuery} for specification
   * @return List of dashboards present in the database
   */
  @SuppressWarnings({"unchecked", "SqlSourceToSinkFlow"})
  protected Dashboard[] query(DashboardQuery dashboardQuery, String userId) {
    if (dashboardQuery.getRealm() == null) {
      return new Dashboard[0];
    }

    StringBuilder sql = new StringBuilder("SELECT * FROM Dashboard WHERE realm LIKE :realm");
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("realm", dashboardQuery.getRealm().name);

    if (dashboardQuery.getIds() != null) {
      this.appendSqlIdFilter(sql, dashboardQuery, parameters);
    }
    if (dashboardQuery.getNames() != null) {
      this.appendSqlNamesFilter(sql, dashboardQuery, parameters);
    }
    if (dashboardQuery.getUserIds() != null) {
      this.appendSqlUserIdsFilter(sql, dashboardQuery, parameters);
    }
    if (dashboardQuery.getConditions().getDashboard() != null) {
      this.appendSqlDashboardConditionsFilter(sql, dashboardQuery, parameters, userId);
    }
    if (dashboardQuery.getConditions().getAsset() != null) {
      this.appendSqlAssetConditionsFilter(sql, dashboardQuery, parameters, userId);
    }

    /** TODO: Implement SELECT filtering {@link org.openremote.model.query.DashboardQuery.Select} */

    // Apply pagination
    if (dashboardQuery.start != null) {
      sql.append(" OFFSET :start");
      parameters.put("start", dashboardQuery.start);
    }
    if (dashboardQuery.limit != null) {
      sql.append(" LIMIT :limit");
      parameters.put("limit", dashboardQuery.limit);
    }

    return (Dashboard[])
        persistenceService.doReturningTransaction(
            (em) -> {

              // Create query object and apply parameters
              Query query = em.createNativeQuery(sql.toString(), Dashboard.class);
              query.setHint(AvailableHints.HINT_READ_ONLY, true);
              parameters.forEach(query::setParameter);

              return query.getResultList().toArray(new Dashboard[0]);
            });
  }

  /**
   * Builds SQL section for filtering dashboards by ID, and applies the necessary data to the
   * sqlBuilder and sqlParams parameters.
   *
   * @return {@link StringBuilder} used for building
   */
  protected StringBuilder appendSqlIdFilter(
      StringBuilder sqlBuilder, DashboardQuery query, Map<String, Object> sqlParams) {
    sqlBuilder.append(" AND id IN (:ids)");
    sqlParams.put("ids", List.of(Optional.ofNullable(query.getIds()).orElse(new String[0])));
    return sqlBuilder;
  }

  /**
   * Builds SQL section for filtering dashboards by display name using {@link StringPredicate}, and
   * applies the necessary data to the sqlBuilder and sqlParams parameters.
   *
   * @return {@link StringBuilder} used for building
   */
  protected StringBuilder appendSqlNamesFilter(
      StringBuilder sqlBuilder, DashboardQuery query, Map<String, Object> sqlParams) {
    IntStream.range(0, query.getNames().length)
        .forEach(
            index -> {
              String key = "name" + index;
              StringPredicate pred = query.getNames()[index];
              sqlBuilder
                  .append(" AND ")
                  .append(pred.caseSensitive ? "display_name" : "UPPER(display_name)")
                  .append(pred.negate ? " NOT" : "");
              switch (pred.match) {
                case BEGIN -> sqlBuilder.append(" LIKE :").append(key).append(" || '%'");
                case CONTAINS -> sqlBuilder.append(" LIKE '%' || :").append(key).append(" || '%'");
                case END -> sqlBuilder.append(" LIKE '%' || :").append(key);
                default -> sqlBuilder.append(" = :").append(key);
              }
              sqlParams.put(key, pred.value);
            });
    return sqlBuilder;
  }

  protected StringBuilder appendSqlUserIdsFilter(
      StringBuilder sqlBuilder, DashboardQuery query, Map<String, Object> sqlParams) {
    sqlBuilder.append(" AND owner_id IN (:ownerIds)");
    sqlParams.put(
        "ownerIds", List.of(Optional.ofNullable(query.getUserIds()).orElse(new String[0])));
    return sqlBuilder;
  }

  /**
   * Builds SQL section for filtering dashboards by dashboard conditions, such as {@link
   * DashboardAccess} (PRIVATE, SHARED, PUBLIC), and applies the necessary data to the sqlBuilder
   * and sqlParams parameters.
   *
   * @return {@link StringBuilder} used for building
   */
  protected StringBuilder appendSqlDashboardConditionsFilter(
      StringBuilder sqlBuilder,
      DashboardQuery query,
      Map<String, Object> sqlParams,
      String userId) {
    var dashboardConditions = query.getConditions().getDashboard();
    if (dashboardConditions.getAccess() != null) {

      List<DashboardAccess> access =
          new ArrayList<>(Arrays.asList(dashboardConditions.getAccess()));
      sqlBuilder.append(" AND (");

      // When requesting PRIVATE dashboards, check whether the user is also the owner
      if (userId != null && access.contains(DashboardAccess.PRIVATE)) {
        access.remove(DashboardAccess.PRIVATE);
        sqlBuilder.append("(access = 2 AND owner_id = :userId) OR ");
        sqlParams.put("userId", userId);
      }

      // Append simple SQL filter by dashboard access
      sqlBuilder.append("(access IN (:access)))");

      sqlParams.put(
          "access", access.stream().map(DashboardAccess::ordinal).collect(Collectors.toList()));

      /**
       * TODO: Implement filtering by {@link
       * org.openremote.model.query.DashboardQuery.DashboardConditions.minWidgets}
       */
    }
    return sqlBuilder;
  }

  /**
   * Builds SQL section for filtering dashboards by assets present in the widgets. For example, to
   * only query dashboards when the user has access to the assets used. Using {@link
   * org.openremote.model.query.DashboardQuery.AssetAccess}, it applies the necessary data to the
   * sqlBuilder and sqlParams parameters.
   *
   * @return {@link StringBuilder} used for building
   */
  protected StringBuilder appendSqlAssetConditionsFilter(
      StringBuilder sqlBuilder,
      DashboardQuery query,
      Map<String, Object> sqlParams,
      String userId) {
    var assetConditions = query.getConditions().getAsset();
    if (assetConditions.getAccess() != null) {
      List<DashboardQuery.AssetAccess> levels =
          Arrays.asList(
              Optional.ofNullable(assetConditions.getAccess())
                  .orElse(new DashboardQuery.AssetAccess[0]));
      if (levels.size() == 1 && levels.contains(DashboardQuery.AssetAccess.RESTRICTED)) {

        // Gather asset ids the user is linked to
        List<UserAssetLink> userAssetLinks =
            assetStorageService.findUserAssetLinks(query.getRealm().name, userId, null);
        List<String> assetIds =
            userAssetLinks.stream().map(ua -> ua.getId().getAssetId()).collect(Collectors.toList());

        // AT_LEAST_ONE - When user has access to the assets of at least 1 widget
        if (assetConditions.getMinAmount() == DashboardQuery.ConditionMinAmount.AT_LEAST_ONE) {
          sqlBuilder.append(" AND (template IS NULL OR template->'widgets' IS NULL OR EXISTS (");
          sqlBuilder.append(
              "SELECT 1 FROM jsonb_array_elements(COALESCE(template->'widgets', '[]')) AS j(widget) ");
          sqlBuilder.append(
              "LEFT JOIN jsonb_array_elements(COALESCE(widget->'widgetConfig'->'attributeRefs', '[]')) AS a(attributeRef) ");
          sqlBuilder.append("ON a->>'id' IN (:assetIds)))");
        }
        // ALL - User needs access to the assets of ALL widgets (or the dashboard has no widgets)
        else if (assetConditions.getMinAmount() == DashboardQuery.ConditionMinAmount.ALL) {
          sqlBuilder.append(" AND NOT EXISTS (");
          sqlBuilder.append(
              "SELECT 1 FROM jsonb_array_elements(template->'widgets') AS j(widget), ");
          sqlBuilder.append(
              "jsonb_array_elements(widget->'widgetConfig'->'attributeRefs') AS a(attributeRef) ");
          sqlBuilder.append("WHERE a->>'id' NOT IN (:assetIds))");
        }
        sqlParams.put("assetIds", assetIds);
      }
    }

    /**
     * TODO: Implement filtering by ({@link
     * org.openremote.model.query.DashboardQuery.AssetConditions.parents}
     */
    return sqlBuilder;
  }

  /**
   * Creates a new dashboard; the dashboard keeps the supplied identifier or gets a newly generated
   * unique identifier. Callers are responsible for enforcing realm authorization.
   *
   * @param dashboard the dashboard to create
   * @return the created dashboard
   * @throws IllegalArgumentException if no dashboard is specified or its identifier is already in
   *     use
   */
  public Dashboard createNew(Dashboard dashboard) {
    if (dashboard == null) {
      throw new IllegalArgumentException("No dashboard is specified.");
    }
    return persistenceService.doReturningTransaction(
        em -> {
          if (dashboard.getId() != null && !dashboard.getId().isEmpty()) {
            Dashboard d =
                em.find(
                    Dashboard.class,
                    dashboard.getId()); // checking whether dashboard is already in database
            if (d != null) {
              throw new IllegalArgumentException("This dashboard has already been created.");
            }
          }
          return em.merge(dashboard);
        });
  }

  /**
   * Updates an existing dashboard; the dashboard must exist and be accessible to the specified
   * user. Callers are responsible for enforcing realm authorization.
   *
   * @param dashboard the dashboard to update
   * @param realm the realm of the dashboard
   * @param userId the id of the user performing the update
   * @return the updated dashboard
   * @throws IllegalArgumentException if a parameter is missing or invalid
   * @throws EntityNotFoundException if the dashboard does not exist or is not accessible to the
   *     specified user
   * @throws jakarta.persistence.OptimisticLockException when the supplied dashboard version is
   *     stale, i.e. the dashboard was concurrently modified
   */
  public Dashboard update(Dashboard dashboard, String realm, String userId)
      throws IllegalArgumentException, EntityNotFoundException {
    if (dashboard == null || TextUtil.isNullOrEmpty(dashboard.getId())) {
      throw new IllegalArgumentException("No dashboard is specified.");
    }
    if (TextUtil.isNullOrEmpty(realm)) {
      throw new IllegalArgumentException("No realm is specified.");
    }
    if (TextUtil.isNullOrEmpty(userId)) {
      throw new IllegalArgumentException("No userId is specified.");
    }
    return persistenceService.doReturningTransaction(
        em -> {
          // Check it exists and is associated with the specified user if private
          StringBuilder sb =
              new StringBuilder("SELECT d.id FROM Dashboard d where id = :id AND (access <> ")
                  .append(DashboardAccess.PRIVATE.ordinal())
                  .append(" OR ownerId = :userId)")
                  .append(" AND realm = :realm");

          try {
            em.createQuery(sb.toString(), String.class)
                .setParameter("id", dashboard.getId())
                .setParameter("userId", userId)
                .setParameter("realm", realm)
                .getSingleResult();

            Dashboard d = em.merge(dashboard);
            em.flush();
            return d;
          } catch (NoResultException e) {
            throw new EntityNotFoundException("Dashboard does not exist or is inaccessible.");
          }
        });
  }

  /**
   * Deletes an existing dashboard; the dashboard must exist and be accessible to the specified
   * user. Callers are responsible for enforcing realm authorization.
   *
   * @param dashboardId the identifier of the dashboard to delete
   * @param realm the realm of the dashboard
   * @param userId the id of the user performing the deletion
   * @return {@code true} when the dashboard was deleted
   * @throws IllegalArgumentException if a parameter is missing or invalid
   * @throws EntityNotFoundException if the dashboard does not exist or is not accessible to the
   *     specified user
   */
  public boolean delete(String dashboardId, String realm, String userId)
      throws IllegalArgumentException, EntityNotFoundException {
    if (TextUtil.isNullOrEmpty(dashboardId)) {
      throw new IllegalArgumentException("No dashboardId is specified.");
    }
    if (TextUtil.isNullOrEmpty(realm)) {
      throw new IllegalArgumentException("No realm is specified.");
    }
    if (TextUtil.isNullOrEmpty(userId)) {
      throw new IllegalArgumentException("No userId is specified.");
    }
    return persistenceService.doReturningTransaction(
        em -> {

          // Query the dashboards with the same ID (which is only 1)
          Dashboard[] dashboards =
              this.query(
                  new DashboardQuery().ids(dashboardId).realm(new RealmPredicate(realm)).limit(1),
                  userId);
          if (dashboards == null || dashboards.length == 0) {
            throw new EntityNotFoundException("No dashboards could be found.");
          }
          Query query = em.createQuery("DELETE from Dashboard d where d.id=?1 and d.realm =?2");
          query.setParameter(1, dashboardId);
          query.setParameter(2, realm);
          query.executeUpdate();
          return true;
        });
  }
}
