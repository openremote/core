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
package org.openremote.test.protocol.http

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.*
import org.apache.http.client.utils.URIBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClient
import org.jboss.resteasy.specimpl.ResteasyUriInfo
import org.jboss.resteasy.util.BasicAuthHelper
import org.openremote.container.web.DynamicValueInjectorFilter
import org.openremote.container.web.OAuthServerResponse
import org.openremote.container.web.WebTargetBuilder
import org.openremote.model.auth.OAuthClientCredentialsGrant
import org.openremote.model.auth.OAuthGrant
import org.openremote.model.auth.OAuthPasswordGrant
import org.openremote.model.auth.OAuthRefreshTokenGrant
import org.openremote.model.util.ValueUtil
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class WebTargetTest extends Specification {

  @Shared
  def mockServer = new ClientRequestFilter() {

    private boolean supportsRefresh
    private int accessTokenCount
    private int refreshTokenCount
    private String accessToken = null
    private String refreshToken = null

    @Override
    void filter(ClientRequestContext requestContext) throws IOException {
      def requestUri = requestContext.uri
      def requestPath = requestUri.scheme + "://" + requestUri.host + requestUri.path

      switch (requestPath) {
        case "https://basicserver/get":
          def authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)
          if (authHeader != null) {
            def usernameAndPassword = BasicAuthHelper.parseHeader(authHeader)
            if (usernameAndPassword != null
                    && usernameAndPassword[0] == "testuser"
                    && usernameAndPassword[1] == "password1") {
              requestContext.abortWith(Response.ok().build())
              return
            }
          }
          break
        case "https://oauthserver/token":
        case "https://oauthreuseserver/token":
        // OAuth token request extract the grant info
          def grant = ((Form)requestContext.getEntity()).asMap()
          if (grant.getFirst(OAuthGrant.VALUE_KEY_GRANT_TYPE) == "password"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_ID) == "client1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_SECRET) == "secret1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_SCOPE) == "scope1 scope2"
                  && grant.getFirst(OAuthPasswordGrant.VALUE_KEY_USERNAME) == "testuser"
                  && grant.getFirst(OAuthPasswordGrant.VALUE_KEY_PASSWORD) == "password1") {
            accessToken = "accesstoken" + accessTokenCount++
            def response = new OAuthServerResponse()
            response.accessToken = accessToken
            response.expiresIn = requestPath == "https://oauthserver/token" ? 5 : 100
            response.tokenType = "Bearer"

            // Include refresh token if configured to support it
            if (supportsRefresh) {
              refreshToken = "refreshtoken" + accessTokenCount
              response.refreshToken = refreshToken
            }

            requestContext.abortWith(
                    Response.ok(response, MediaType.APPLICATION_JSON_TYPE).build()
            )
            return
          } else if (grant.getFirst(OAuthGrant.VALUE_KEY_GRANT_TYPE) == "client_credentials"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_ID) == "client1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_SECRET) == "secret1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_SCOPE) == "scope1 scope2") {
            accessToken = "accesstoken" + accessTokenCount++
            def response = new OAuthServerResponse()
            response.accessToken = accessToken
            response.expiresIn = requestPath == "https://oauthserver/token" ? 1 : 100
            response.tokenType = "Bearer"

            requestContext.abortWith(
                    Response.ok(response, MediaType.APPLICATION_JSON_TYPE).build()
            )
            return
          } else if (supportsRefresh && grant.getFirst(OAuthGrant.VALUE_KEY_GRANT_TYPE) == "refresh_token"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_ID) == "client1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_CLIENT_SECRET) == "secret1"
                  && grant.getFirst(OAuthGrant.VALUE_KEY_SCOPE) == "scope1 scope2"
                  && grant.getFirst(OAuthRefreshTokenGrant.REFRESH_TOKEN_GRANT_TYPE) == refreshToken) {
            refreshTokenCount++
            accessToken = "accesstoken" + accessTokenCount++
            refreshToken = "refreshtoken" + accessTokenCount
            def response = new OAuthServerResponse()
            response.accessToken = accessToken
            response.refreshToken = refreshToken
            response.expiresIn = requestPath == "https://oauthserver/token" ? 1 : 100
            response.tokenType = "Bearer"

            requestContext.abortWith(
                    Response.ok(response, MediaType.APPLICATION_JSON_TYPE).build()
            )
            return
          }
          break
        case "https://oauthserver/get":
        // Check access token is what we sent
          def authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)
          def accessToken = authHeader.substring(7)
          if (accessToken != null && this.accessToken != null && accessToken == this.accessToken) {
            requestContext.abortWith(Response.ok().build())
            return
          }
          break
        case "https://forbiddenserver":
        // Use access token counter to track requests
          accessTokenCount++
          requestContext.abortWith(
                  Response.status(Response.Status.FORBIDDEN).build()
          )
          return
        case "https://headerserver":
          if (requestContext.getHeaderString("MyHeader") == "MyHeaderValue"
                  && requestContext.getHeaderString("MyMultiHeader") == "MyMultiHeaderValue1,MyMultiHeaderValue2") {
            requestContext.abortWith(Response.ok().build())
            return
          }
          break
        case "https://headerserver/get":
          if (requestContext.getHeaderString("MyHeader") == "MyHeaderValue"
                  && requestContext.getHeaderString("MyHeaderNew") == "MyHeaderNewValue"
                  && requestContext.getHeaderString("MyMultiHeader") == "MyMultiHeaderValue1,MyMultiHeaderValue2,MyMultiHeaderValue3") {
            requestContext.abortWith(Response.ok().build())
            return
          }
          break
        case "https://redirectserver":
          requestContext.abortWith(Response.temporaryRedirect(new URI("https://redirectedserver")).build())
          return
        case "https://redirectedserver":
          requestContext.abortWith(Response.ok().build())
          return
        case "https://paramserver":
          UriInfo uriInfo = new ResteasyUriInfo(requestContext.uri)
          def queryParams = uriInfo.getQueryParameters(true)
          if (queryParams.get("param1").size() == 1
                  && queryParams.getFirst("param1") == "param1Value1"
                  && queryParams.get("param2").size() == 2
                  && queryParams.get("param2").get(0) == "param2Value1"
                  &&queryParams.get("param2").get(1) == "param2Value2") {

            requestContext.abortWith(Response.ok().build())
            return
          }
          break
        case "https://paramserver/get":
          UriInfo uriInfo = new ResteasyUriInfo(requestUri)
          def queryParams = uriInfo.getQueryParameters(true)
          if (queryParams.get("param1").size() == 2
                  && queryParams.get("param1").contains("param1Value2")
                  && queryParams.get("param1").contains("param1Value1")
                  && queryParams.get("param2").size() == 2
                  && queryParams.get("param2").contains("param2Value1")
                  && queryParams.get("param2").contains("param2Value2")
                  && queryParams.get("param3").size() == 1
                  && queryParams.get("param3").contains("param3Value1")) {

            requestContext.abortWith(Response.ok().build())
            return
          }
          break
        case "https://dynamicparamserver":
          UriInfo uriInfo = new ResteasyUriInfo(requestContext.uri)
          def queryParams = uriInfo.getQueryParameters(true)
          if (queryParams.get("param1").size() == 1
                  && queryParams.getFirst("param1") == "dynamicValue1"
                  && queryParams.get("param2").size() == 2
                  && queryParams.get("param2").contains("param2Value1")
                  &&queryParams.get("param2").contains("param2Value2")) {

            requestContext.abortWith(Response.ok().build())
            return
          }
      }

      requestContext.abortWith(Response.serverError().build())
    }
  }

  @AutoCleanup
  @Shared
  ResteasyClient client

  def setupSpec() {
    // Initialize the web target builder and load in the mock 'server'
    client = WebTargetBuilder.createClient(null)
    client.register(mockServer, Integer.MAX_VALUE)
  }

  def cleanup() {
    mockServer.supportsRefresh = false
    mockServer.accessToken = null
    mockServer.refreshToken = null
    mockServer.accessTokenCount = 0
    mockServer.refreshTokenCount = 0
  }

  def "OAuth grant serialization and deserialization"() {

    given: "a grant object"
    OAuthPasswordGrant grant = new OAuthPasswordGrant("https://mockapi/token",
            "TestClient",
            "TestSecret",
            "scope1 scope2",
            "testuser",
            "password")

    when: "it is serialized"
    String grantStr = ValueUtil.asJSON(grant).orElse(null)

    and: "deserialized again"
    OAuthGrant grant2 = ValueUtil.JSON.readValue(grantStr, OAuthGrant.class)

    then: "the two grant objects should be the same"
    assert grant2.tokenEndpointUri == grant.tokenEndpointUri
    assert grant2.grantType == grant.grantType
    assert grant2.clientId == grant.clientId
    assert grant2.clientSecret == grant.clientSecret
    assert grant2.scope == grant.scope
    assert ((OAuthPasswordGrant)grant2).username == grant.username
    assert ((OAuthPasswordGrant)grant2).password == grant.password

    when: "serialized"
    Map<String, Object> map = new HashMap<>()
    String v = "{\"test\": 123}"
    map.put("value", v)
    String mapStr = ValueUtil.JSON.writeValueAsString(map)

    and: "deserialized"
    def newMap = ValueUtil.JSON.readValue(mapStr, Map.class)

    then: "should work"
    assert newMap != null
  }

  def "Check Basic authentication"() {

    given: "a web target for a server requiring basic authentication"
    def target = new WebTargetBuilder(client, new URIBuilder("https://basicserver").build())
            .setBasicAuthentication("testuser", "password1")
            .build()

    when: "a request is made to an endpoint on that server"
    def response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200
  }

  def "Check OAuth resource owner credentials (password) grant authentication"() {

    given: "a web target for a server requiring OAuth 'password' grant authentication"
    def target = new WebTargetBuilder(client, new URIBuilder("https://oauthserver").build())
            .setOAuthAuthentication(
            new OAuthPasswordGrant(
                    "https://oauthserver/token",
                    "client1",
                    "secret1",
                    "scope1 scope2",
                    "testuser",
                    "password1"
                    )
            )
            .build()

    when: "a request is made to an endpoint on that server"
    def response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    when: "another request is made to an endpoint on the server that will require renewing the access token"
    response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    and: "the server should have generated 2 access tokens but no refresh tokens"
    assert mockServer.accessTokenCount == 2
    assert mockServer.refreshTokenCount == 0

    when: "the server supports token refresh"
    mockServer.supportsRefresh = true
    mockServer.accessToken = null
    mockServer.refreshToken = null
    mockServer.accessTokenCount = 0

    and: "a request is made to an endpoint on that server"
    response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    when: "another request is made to an endpoint on the server that will require renewing the access token"
    response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    and: "the server should have generated 2 access tokens and 1 refresh token"
    assert mockServer.accessTokenCount == 2
    assert mockServer.refreshTokenCount == 1
  }

  def "Check OAuth client credentials grant authentication"() {

    given: "a web target for a server requiring OAuth 'client_credentials' grant authentication"
    def target = new WebTargetBuilder(client, new URIBuilder("https://oauthserver").build())
            .setOAuthAuthentication(
            new OAuthClientCredentialsGrant(
                    "https://oauthserver/token",
                    "client1",
                    "secret1",
                    "scope1 scope2"
                    )
            )
            .build()

    when: "a request is made to an endpoint on that server"
    def response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    when: "another request is made to an endpoint on the server that will require renewing the access token"
    response = target.path("get").request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    and: "the server should have generated 2 access tokens but no refresh tokens"
    assert mockServer.accessTokenCount == 2
    assert mockServer.refreshTokenCount == 0
  }

  def "Check OAuth token reuse functionality"() {

    given: "a web target for a server requiring OAuth 'client_credentials' grant authentication"
    def target = new WebTargetBuilder(client, new URIBuilder("https://oauthserver/get").build())
            .setOAuthAuthentication(
            new OAuthClientCredentialsGrant(
                    "https://oauthreuseserver/token",
                    "client1",
                    "secret1",
                    "scope1 scope2"
                    ))
            .build()

    when: "a request is made to an endpoint on that server"
    def response = target.request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    when: "another request is made to an endpoint on the server"
    response = target.request().get()

    then: "the response should be 200 OK"
    assert response.getStatus() == 200

    and: "the server should have generated 1 access tokens and no refresh tokens"
    assert mockServer.accessTokenCount == 1
    assert mockServer.refreshTokenCount == 0
  }

  def "Check permanent failure functionality"() {

    given: "a web target for a server set to permanently fail on 403 errors"
    def target = new WebTargetBuilder(client, new URIBuilder("https://forbiddenserver").build())
            .addPermanentFailureResponse(Response.Status.FORBIDDEN)
            .build()

    when: "a request is made that generates a 403 response"
    def response = target.request().get()

    then: "the server should have been hit and response should be 403 forbidden"
    assert mockServer.accessTokenCount == 1
    assert response.getStatus() == 403

    when: "another request is made to the server"
    response = target.request().get()

    then: "the server should not have been hit and the response should be 405 method not allowed"
    assert mockServer.accessTokenCount == 1
    assert response.getStatus() == 405
  }

  def "Check header handling"() {

    given: "custom headers to inject"
    def headers = new MultivaluedHashMap<String, String>(2)
    headers.add("MyHeader", "MyHeaderValue")
    headers.put("MyMultiHeader", ["MyMultiHeaderValue1", "MyMultiHeaderValue2"])

    and: "a web target for a server"
    def target = new WebTargetBuilder(client, new URIBuilder("https://headerserver").build())
            .build()

    when: "a request is made to the server"
    def request = target.request()
    request = WebTargetBuilder.addHeaders(request, headers)
    def response = request.get()

    then: "the response should be a 200 OK indicating the custom headers reached the server"
    assert response.getStatus() == 200

    when: "a request is made to the server with additional headers"
    headers.add("MyHeaderNew", "MyHeaderNewValue")
    headers.add("MyMultiHeader", "MyMultiHeaderValue3")
    response = WebTargetBuilder.addHeaders(target.path("get").request(), headers).get()

    then: "the response should be a 200 OK indicating the custom headers reached the server"
    assert response.getStatus() == 200
  }

  def "Check redirect handling"() {

    given: "a web target for a server"
    def target = new WebTargetBuilder(client, new URIBuilder("https://redirectserver").build())
            .followRedirects(true)
            .build()

    when: "a request is made to the server that returns a redirect response"
    def response = target.request().get()

    then: "the response should be a 200 OK and it should have come from the redirected server"
    assert response.getStatus() == 200
    assert response.getLocation().toString() == "https://redirectedserver"
  }

  def "Check query parameter handling"() {

    given: "query parameters to inject"
    def params = new MultivaluedHashMap<String, String>(2)
    params.add("param1", "param1Value1")
    params.put("param2", ["param2Value1", "param2Value2"])

    and: "a web target for a server"
    def target = new WebTargetBuilder(client, new URIBuilder("https://paramserver").build())
            .build()

    and: "some query parameters"
    target = WebTargetBuilder.addQueryParams(target, params)

    when: "a request is made to the server"
    def response = target.request().get()

    then: "the response should be a 200 OK indicating the query parameters reached the server"
    assert response.getStatus() == 200

    when: "a request is made to the server with a different path and additional query parameters"
    params = new MultivaluedHashMap<String, String>()
    params.add("param1", "param1Value2")
    params.add("param3", "param3Value1")
    def newTarget = target.path("get")
    newTarget = WebTargetBuilder.addQueryParams(newTarget, params)
    response = newTarget
            .request()
            .get()

    then: "the response should be a 200 OK indicating the custom headers reached the server"
    assert response.getStatus() == 200
  }

  def "Check dynamic query parameter handling"() {

    given: "query parameters to inject with dynamic placeholders"
    def params = new MultivaluedHashMap<String, String>(2)
    params.add("param1", "%VALUE%")
    params.put("param2", ["param2Value1", "param2Value2"])

    and: "a web target for a server"
    def target = new WebTargetBuilder(client, new URIBuilder("https://dynamicparamserver").build())
            .build()

    and: "dynamic query params"
    target = WebTargetBuilder.addQueryParams(target, params)

    when: "a request is made to the server with a dynamic value"
    def response = target.request()
            .property(DynamicValueInjectorFilter.DYNAMIC_VALUE_PROPERTY, "dynamicValue1")
            .get()

    then: "the response should be a 200 OK indicating the query parameters reached the server"
    assert response.getStatus() == 200
  }


  //    def "Check real OAuth resource owner credentials (password) grant authentication"() {
  //
  //        given: "A web target for a server requiring OAuth 'password' grant authentication"
  //        def target = new WebTargetBuilder("client, new URIBuilder(https://www.telecontrolnet.nl/api/v1").build())
  //            .setOAuthAuthentication(
  //            new OAuthPasswordGrant(
  //                "https://www.telecontrolnet.nl/oauth/token",
  //                "Ha7xO3cCNtNQDdx2bhY7.openremote.org",
  //                "9ACGpDbVkirUpsh7df0e",
  //                null,
  //                "developers@openremote.io",
  //                "M5HtdBiRUCi4GKOoRNVL"
  //            )
  //        )
  //            .build()
  //
  //        when: "A request is made to an endpoint on that server"
  //        def response = target.path("locations").request().get()
  //
  //        then: "The response should be 200 OK"
  //        def str = response.readEntity(String.class);
  //        assert response.getStatus() == 200
  //    }
}
