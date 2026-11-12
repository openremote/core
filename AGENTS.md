# OpenRemote AGENTS.md file

## DB update scripts

Flyway is used to automate execution of DB update scripts.  
When naming scripts, use \<date>_\<time> as version number, date being YYYYMMDD and time being HHmm in UTC e.g. `V20260611_0755__Changes.sql` (the `V` prefix is required by Flyway and must be uppercase)

## Dev environment

The backend is written in Java, we target JDK 21, use modern language features up to that version during implementation.  
Gradle is used as the build system:

- `./gradlew assemble` builds normal backend and UI artifacts without verification, installing the distribution, or building Docker images.
- `./gradlew check` runs backend and UI tests, Spotless, UI dependency checks, and translation checks.
- `./gradlew build` builds and verifies the project. Use this as the normal pre-PR command.
- `./gradlew installDist` prepares the runnable distribution in `manager/build/install/manager/` and the deployment Docker build context.

Use `./gradlew clean assemble` for a clean artifact build, or `./gradlew clean build` to also run verification.
Lifecycle tasks compose individual tasks, which remain independently runnable for targeted development and CI checks.
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

- `./gradlew testBackend` runs backend unit tests followed by backend integration tests.
- `./gradlew testBackendUnit` runs backend unit tests only.
- `./gradlew testBackendIntegration` runs backend integration tests only.
- `./gradlew testUi` runs UI component tests followed by UI app end-to-end tests.
- `./gradlew testUiComponent` runs UI component tests only.
- `./gradlew testUiApp` runs UI app end-to-end tests only.
- `./gradlew test` runs `testBackend` followed by `testUi`.

Both `check` and `build` include the complete test sequence.
UI module tasks retain the `npmTest` name alongside `npmBuild`; for example, `./gradlew :ui:component:or-element:npmTest` runs one component package's tests.

Backend integration tests and UI app tests require PostgreSQL and Keycloak. Start them with `docker compose -f profile/dev-testing.yml -p openremote up -d --no-build`.

The same stack is required for `check` and `build`. Gradle waits up to 30 seconds for PostgreSQL and Keycloak before backend integration tests and Manager UI app tests. Gradle also checks that Playwright can launch Chromium before browser-backed test tasks; install it with `./gradlew :ui:test:installBrowsers` if needed.
Gradle app test tasks, including individual `npmTest` tasks, automatically prepare the Manager, Manager UI, and shared UI distributions.
Component test tasks prepare generated TypeScript sources and shared icon fonts without building the Manager application.
Pass `-PreuseUiTestArtifacts=true` only when the required outputs have already been built or downloaded, as in the CI jobs that reuse Install Dist artifacts.

Spock snapshots are stored under `model/src/test/resources/snapshots`. Rewrite them with `./gradlew :model:test -PupdateSnapshots` and review the resulting diff, since a mismatch is only reported as a test failure otherwise.

## REST resources

### Endpoint roles

Annotate every endpoint with the resource role of its domain, e.g. `read:alarms` or `write:notifications`. Use `read:admin` or `write:admin` only where the domain has no resource role, or where admin-only access is a deliberate decision. The admin roles are standalone, so holding `write:admin` does not grant `write:notifications`.

### Access control

Access control belongs in the resource implementation, not in the service. Extend `ManagerWebResource` and use its checks rather than reaching for the identity provider: `throwIfNotRealmActiveAndAccessible` and `throwIfRestrictedUser` reject with a 403, and `isRealmActiveAndAccessible` and `isRestrictedUser` are there for conditions that need more than a rejection. Resolve the entity first and return 404 when it is missing, then require access to its realm, and apply restricted user rules in the same place. Service methods take already authorised input and state that in their javadoc with "Callers are responsible for enforcing realm authorization." Alarms and notifications are the reference for this shape; equivalent endpoints across the two must enforce the same way. Flows that never pass through a resource, such as notifications published to the message broker, authorise inside the service instead.

## Spelling

Write code, comments and docs in US English, e.g. `color`, not `colour`.

## UI

Frontend conventions live in `ui/AGENTS.md`. Read it before changing anything under `ui/`.

## CI/CD pipeline

Pipeline concepts and conventions live in the [CI/CD pipeline guide](https://docs.openremote.io/docs/developer-guide/ci-cd-pipeline). Read it before changing anything under `.github/` or `.ci_cd/`; the deployment scripts and their variables are covered by `.ci_cd/README.md`.
