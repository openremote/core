# OpenRemote AGENTS.md file

## DB update scripts

Flyway is used to automate execution of DB update scripts.  
When naming scripts, use \<date>_\<time> as version number, date being YYYYMMDD and time being HHmm in UTC e.g. `V20260611_0755__Changes.sql` (the `V` prefix is required by Flyway and must be uppercase)

## Dev environment

The backend is written in Java, we target JDK 21, use modern language features up to that version during implementation.  
Gradle is used as the build system; run `./gradlew clean installDist` for a full clean build.  
In sandboxed environments, add `--offline` (requires dependencies to already be cached).

## Tests

### Writing tests

All backend tests are Groovy Spock specifications. Unit tests live in the project that holds the code, under `<project>/src/test/groovy`. Use an integration test in the `test` project only when the behaviour needs a running manager. Tests for anything under `ui/` follow `ui/AGENTS.md`.

Since Spock 2.0, unrolling is the default, don't add un-necessary @Unroll annotations.

A `WebApplicationException` thrown by a JAX-RS proxy carries a response that holds its connection open until it is closed. Read the status through `ex.response.withCloseable`, so a spec that asserts on many rejections does not run the connection pool dry. The closure result becomes the condition, so assert inside it and return `true`:

```groovy
then: "the request should be forbidden"
WebApplicationException ex = thrown()
ex.response.withCloseable { r ->
  assert r.status == 403
  return true
}
```

### Running tests

When running integration tests, part of the stack (PostgreSQL and Keycloak) must be running. Start it with `docker compose -f profile/dev-testing.yml -p openremote up -d --no-build`.

## REST resources

### Endpoint roles

Annotate every endpoint with the resource role of its domain, e.g. `read:alarms` or `write:notifications`. Use `read:admin` or `write:admin` only where the domain has no resource role, or where admin-only access is a deliberate decision. The admin roles are standalone, so holding `write:admin` does not grant `write:notifications`.

### Access control

Access control belongs in the resource implementation, not in the service. Extend `ManagerWebResource` and use its checks rather than reaching for the identity provider: `throwIfNotRealmActiveAndAccessible` and `throwIfRestrictedUser` reject with a 403, and `isRealmActiveAndAccessible` and `isRestrictedUser` are there for conditions that need more than a rejection. Resolve the entity first and return 404 when it is missing, then require access to its realm, and apply restricted user rules in the same place. Service methods take already authorised input and state that in their javadoc with "Callers are responsible for enforcing realm authorization." Alarms and notifications are the reference for this shape; equivalent endpoints across the two must enforce the same way. Flows that never pass through a resource, such as notifications published to the message broker, authorise inside the service instead.

## Spelling

Write code, comments and docs in US English, e.g. `color`, not `colour`.

## UI

Frontend conventions live in `ui/AGENTS.md`. Read it before changing anything under `ui/`.
