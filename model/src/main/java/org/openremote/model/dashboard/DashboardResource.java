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
package org.openremote.model.dashboard;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.openremote.model.http.OpenApiDescriptions.EXAMPLE_REALM;
import static org.openremote.model.http.OpenApiDescriptions.REALM;
import static org.openremote.model.http.OpenApiExamples.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import org.openremote.model.Constants;
import org.openremote.model.http.OpenApiResponses;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.DashboardQuery;

@Tag(
    name = "Dashboard",
    description = "Query public or accessible dashboards and manage dashboards for the Insights UI")
@Path("dashboard")
public interface DashboardResource {

  /**
   * Retrieve all dashboards from a realm, where the user has access to.
   *
   * @return An array of {@link Dashboard} from the realm
   */
  @GET
  @Path("all/{realm}")
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "getAllRealmDashboards",
      summary = "Retrieve all accessible dashboards",
      description =
          "Returns dashboards visible to the caller in the requested realm. Anonymous callers receive public dashboards only; authenticated access also depends on roles, ownership, and linked assets.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  Dashboard[] getAllRealmDashboards(
      @BeanParam RequestParams requestParams,
      @Parameter(description = REALM, example = EXAMPLE_REALM) @PathParam("realm") String realm);

  /**
   * Queries a specific {@link Dashboard} by its ID and realm, if a user has access to it.
   *
   * @return {@link Dashboard}
   */
  @GET
  @Path("{realm}/{dashboardId}")
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "getDashboard",
      summary = "Retrieve a dashboard",
      description =
          "Returns one dashboard only when it is visible to the caller. Inaccessible and nonexistent dashboards both return 404 to avoid disclosing their existence.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  Dashboard get(
      @BeanParam RequestParams requestParams,
      @Parameter(description = REALM, example = EXAMPLE_REALM) @PathParam("realm") String realm,
      @Parameter(description = "Dashboard identifier.", example = "operations-overview")
          @PathParam("dashboardId")
          String dashboardId);

  /**
   * Advanced query endpoint for retrieving {@link Dashboard} from the database. Based on the {@link
   * DashboardQuery} given, it will specifically filter the entries that are returned. For example
   * filtering by displayName, ID, or dashboard access.
   *
   * @return An array of {@link Dashboard}
   */
  @POST
  @Path("query")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Operation(
      operationId = "queryDashboards",
      summary = "Retrieve dashboards using a query",
      description =
          "Executes a dashboard query after constraining it to the caller's realm, roles, ownership, and asset access. A missing body uses the request realm and default visibility rules.")
  @OpenApiResponses.Ok
  @OpenApiResponses.BadRequest
  Dashboard[] query(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              description =
                  "Optional dashboard filters, realm, access rules, sorting, and pagination. An omitted body uses the request realm and default visibility rules.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(implementation = DashboardQuery.class),
                      examples =
                          @ExampleObject(
                              name = "Find operations dashboards",
                              summary = "Find public or shared dashboards by name in one realm",
                              value = DASHBOARD_QUERY)))
          @Valid DashboardQuery dashboardQuery);

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_INSIGHTS_ROLE})
  @Operation(
      operationId = "createDashboard",
      summary = "Create a dashboard",
      description =
          "Creates a dashboard in an accessible realm. The server assigns the authenticated user as owner and initially stores the dashboard with shared access.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.Conflict
  @OpenApiResponses.ServerError
  Dashboard create(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description =
                  "Dashboard definition to create. The server assigns ownerId and initially forces SHARED access.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(implementation = Dashboard.class),
                      examples =
                          @ExampleObject(
                              name = "Operations dashboard",
                              summary = "Create an empty responsive dashboard",
                              value = DASHBOARD_CREATE)))
          @Valid Dashboard dashboard);

  @PUT
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_INSIGHTS_ROLE})
  @Operation(
      operationId = "updateDashboard",
      summary = "Update a dashboard",
      description =
          "Updates an existing dashboard when the authenticated user can modify it in the dashboard's realm.")
  @OpenApiResponses.Ok
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  @OpenApiResponses.Conflict
  @OpenApiResponses.ServerError
  Dashboard update(
      @BeanParam RequestParams requestParams,
      @RequestBody(
              required = true,
              description = "Complete dashboard definition to update, including its id and realm.",
              content =
                  @Content(
                      mediaType = APPLICATION_JSON,
                      schema = @Schema(implementation = Dashboard.class),
                      examples =
                          @ExampleObject(
                              name = "Update operations dashboard",
                              summary = "Rename an existing shared dashboard",
                              value = DASHBOARD_UPDATE)))
          @Valid Dashboard dashboard);

  @DELETE
  @Path("{realm}/{dashboardId}")
  @Produces(APPLICATION_JSON)
  @RolesAllowed({Constants.WRITE_INSIGHTS_ROLE})
  @Operation(
      operationId = "deleteDashboard",
      summary = "Delete a dashboard",
      description =
          "Permanently removes a dashboard when the authenticated user can modify it in the requested realm.")
  @OpenApiResponses.NoContent
  @OpenApiResponses.Authenticated
  @OpenApiResponses.BadRequest
  @OpenApiResponses.NotFound
  @OpenApiResponses.ServerError
  void delete(
      @BeanParam RequestParams requestParams,
      @Parameter(description = REALM, example = EXAMPLE_REALM) @PathParam("realm") String realm,
      @Parameter(description = "Dashboard identifier.", example = "operations-overview")
          @PathParam("dashboardId")
          String dashboardId);
}
