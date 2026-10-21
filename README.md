# Nyuppsättning Server

## Overview

Nyuppsättning Server is the backend of Nyuppsättning, which supports the steering groups of Chalmersspex revivals. It
is a Spring Boot application with a GraphQL API at `/api/graphql` and an MCP server at `/api/mcp`, backed by MySQL and
the FGV realm in Keycloak ([fgv-keycloak][1]). It reads spexes from Spexregister. [Nyuppsättning Web][2] is its frontend
and [fgv-revival-deploy][3] runs it on a server.

The product spec, feature specs and plans are in [fgv-revival-spec][4], starting with `docs/product-spec.md`.

## Stack

Java 25, Spring Boot 4, Spring for GraphQL, Spring AI MCP server (stateless, WebMVC), Spring Data JPA with Envers,
Flyway, MySQL 8.4, MapStruct and Lombok. Tests use JUnit 5, Mockito, Testcontainers (MySQL, Keycloak) and WireMock as
a stand-in for Spexregister.

## Roles and access

Global roles are client roles on `fgv-revival`: `USER` (access to the application at all), `SUPER_ADMIN` and
`OBSERVER`. Revival roles (directeur, steering group) are stored here, per revival, and apply on the next request.
Every service method resolves the caller and asks `member.AccessPolicy` before it acts; anything it does not permit
is refused, and a revival the caller may not see is reported as not found. GraphQL controllers and MCP tools hold no
rules of their own.

The server calls Spexregister as `fgv-revival-service`, which holds only Spexregister's `REVIVAL` role. While
Spexregister is down, revivals still work and only picking a spex is refused.

## Running locally

1. Start FGV's local stack in `fgv-infra-deploy/local` (Keycloak, Mailpit and Caddy on
   `https://*.fgv.local.nu:8443`). Spexregister's local stack is optional; without it spexes show as unavailable.
2. Make the JVM trust Caddy's local root certificate (`fgv-infra-deploy/local/.docker/caddy/root.crt`), e.g. by
   importing it into the JDK's trust store.
3. Run the server with the `local` profile. `spring-boot-docker-compose` starts the MySQL in `docker-compose.yaml`:

   ```
   KEYCLOAK_SERVICE_CLIENT_SECRET=<secret of fgv-revival-service> ./gradlew bootRun --args='--spring.profiles.active=local'
   ```

   The API is then on `http://localhost:8081` (`/api/graphql`, `/api/mcp`) and actuator on port 9001.
   Introspection is on in the `local` profile only.

To run the image instead of `bootRun`, on the same port 8081, put the secret of `fgv-revival-service` in
`.docker/secrets/KEYCLOAK_SERVICE_CLIENT_SECRET` and run:

```
./gradlew jibDockerBuild                  # or use the published bloomper/fgv-revival-server:develop
docker compose --profile api up -d
```

`revival-api` reaches Keycloak and Spexregister by their container names on the `fgv-local` network, so it needs no
trusted certificate. The profile keeps `bootRun` from starting it.

## Building and testing

You need Java 25 and Docker.

```
./gradlew test              # unit tests
./gradlew integrationTest   # integration tests against MySQL and Keycloak in Testcontainers
./gradlew check             # both, compiler warnings as errors, and Jacoco
```

The integration tests import a committed copy of `fgv-keycloak/realm/fgv.json`. Refresh it with
`./gradlew syncRealm` from a sibling `fgv-keycloak` checkout after every realm change, and commit it.

## Published images

CI publishes `bloomper/fgv-revival-server` with Jib for `linux/amd64` and `linux/arm64`: tag `develop` on pushes to
`develop`, and the version on tags `vX.Y.Z`.

## License

Nyuppsättning Server is Open Source software released under the [Apache 2.0 license][5].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-revival-web
[3]: https://github.com/bloomper/fgv-revival-deploy
[4]: https://github.com/bloomper/fgv-revival-spec
[5]: https://www.apache.org/licenses/LICENSE-2.0.html
