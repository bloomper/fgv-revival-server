# Nyuppsättning Server

## Overview

Nyuppsättning Server is the backend of Nyuppsättning, which supports the steering groups of Chalmersspex revivals. It
is a Spring Boot application with a GraphQL API at `/api/graphql` and an MCP server at `/api/mcp`, backed by MySQL and
the FGV realm in Keycloak ([fgv-keycloak][1]). It reads spex from Spexregister. [Nyuppsättning Web][2] is its frontend
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

## The guide

JK/FGV's continuity guide lives in package `guide`: published versions that never change, one draft that super admins
edit, search, the changes between versions, suggestions (`guide.suggestion`) and a PDF. Besides GraphQL it has two
plain HTTP endpoints:

- `POST /api/guide/import` takes a Word document (multipart part `file`, at most 10 MB) and makes it the first draft.
  It is refused once a version has been published.
- `GET /api/guide/versions/{id}/pdf` and `GET /api/revivals/{id}/guide/pdf` return the PDF, with `lang=sv|en`.

To bring guide v5 into an environment, a super admin imports `FGV_JK_Nyuppsättningsguide_v5.docx` from
`fgv-revival-spec/input_docs/` on the web's *Manage the guide* page, checks the import report and publishes the draft
as "v5". The document is never committed (constitution V); tests build fictitious documents with POI
(`GuideDocxFixtures`).

## The decision log, meetings and checklists

Each revival's decision log (package `decisionlog`), meetings (`meeting`) and checklists (`checklist`, with templates in
`checklist.template`) are written by its directeurs and steering group and read by everyone who can see the revival.
Nothing in them is ever deleted: rows are cancelled, items marked not applicable, templates retired. The guide sync
(`RevivalGuideSync`) keeps the ten phase checklists' required items and the guide's question rows in line with the
version a revival is pinned to. Besides GraphQL and the MCP tools, the log has two plain HTTP endpoints for importing an
Excel log (Ali Baba's or Nobel's layout) into an empty log:

- `POST /api/revivals/{id}/log/import/preview` reads the workbook (multipart part `file`, at most 10 MB) and returns
  what would be imported, with warnings. Nothing is saved.
- `POST /api/revivals/{id}/log/import` imports it, with the directeur's choices for unknown owners.

Closed meetings' minutes can be emailed to the steering group. Mail goes through Spring Mail (`spring.mail.*`; the
password is the `SMTP_PASSWORD` secret), from `fgv-revival.mail.from`, with links to `fgv-revival.web-url`. Without
`spring.mail.host` the send button is hidden.

## Running locally

1. Start FGV's local stack in `fgv-infra-deploy/local` (Keycloak, Mailpit and Caddy on
   `https://*.fgv.local.nu:8443`). Spexregister's local stack is optional; without it spex show as unavailable.
2. Make the JVM trust Caddy's local root certificate (`fgv-infra-deploy/local/.docker/caddy/root.crt`), e.g. by
   importing it into the JDK's trust store.
3. Put the values in the files under `.docker/secrets`, without a trailing newline, and tell git to leave them alone
   with `git update-index --skip-worktree .docker/secrets/*`:

   | File                                 | Value                                                          |
   |--------------------------------------|----------------------------------------------------------------|
   | `FGV_REVIVAL_MYSQL_ROOT_PASSWORD`    | Password of the database's root user                           |
   | `FGV_REVIVAL_MYSQL_USER_PASSWORD`    | Password of the database user in `.env`                        |
   | `API_KEYCLOAK_SERVICE_CLIENT_SECRET` | Secret of `fgv-revival-service` in the realm                   |
   | `API_KEYCLOAK_ADMIN_CLIENT_SECRET`   | Secret of `fgv-admin`, only needed when importing sample data |

4. Run the server with the `local` profile. `spring-boot-docker-compose` starts `revival-mysql` on port 3307:

   ```
   MYSQL_API_USER_PASSWORD=<FGV_REVIVAL_MYSQL_USER_PASSWORD> \
   API_KEYCLOAK_SERVICE_CLIENT_SECRET=<secret of fgv-revival-service> \
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```

   The API is then on `http://localhost:8081` (`/api/graphql`, `/api/mcp`) and actuator on port 9001.
   Introspection is on in the `local` profile only. Mail for the minutes goes to the local stack's Mailpit, shown on
   `https://mail.fgv.local.nu:8443`.

To run the image instead of `bootRun`:

```
./gradlew jibDockerBuild                  # or use the published bloomper/fgv-revival-server:develop
docker compose --profile api up -d
```

`revival-api` reaches Keycloak and Spexregister by their container names on the `fgv-local` network, so it needs no
trusted certificate. The profile keeps `bootRun` from starting it. Either way, Caddy serves the API on
`https://api.revival.fgv.local.nu:8443`, the container when it runs and otherwise port 8081 on the host. MCP tokens
are issued for `https://api.revival.fgv.local.nu:8443/api/mcp`, `FGV_REVIVAL_MCP_URL` in fgv-infra-deploy's
`local/.env`.

## Sample data

With `API_IMPORT_SAMPLE_DATA=true`, all data is replaced with sample revivals at every start. The import needs
the secret of `fgv-admin` in `API_KEYCLOAK_ADMIN_CLIENT_SECRET` (for `bootRun`) or in
`.docker/secrets/API_KEYCLOAK_ADMIN_CLIENT_SECRET` (for `revival-api`).

The sample data is built on Spexregister's, so Spexregister must run with `API_IMPORT_SAMPLE_DATA=true`:

- The spex are read from Spexregister's API. The import waits up to 5 minutes for Spexregister, which answers only
  once its own import is done.
- Spexregister's sample user `admin@fgv.nu` becomes super admin. The import refuses to run without it, which keeps it
  away from real environments.

The other users have one purpose each. The import creates them in Keycloak when they are missing, with the password in
`SAMPLE_USER_PASSWORD` (default `s3cr3t-s3cr3t`, the same as Spexregister's), and gives them roles on `fgv-revival`:

| User                             | In Nyuppsättning                                                                                                |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `admin@fgv.nu`                   | `SUPER_ADMIN`                                                                                                   |
| `observer@fgv.nu`                | `OBSERVER`                                                                                                      |
| `directeur@fgv.nu`               | Directeur of one revival in each status from `APPROVED`                                                         |
| `directeur2@fgv.nu`              | Directeur of the other revival in each status from `APPROVED`                                                   |
| `steering-group@fgv.nu`          | Steering group member without capabilities in `directeur@fgv.nu`'s revivals, removed from `directeur2@fgv.nu`'s |
| `steering-group-finance@fgv.nu`  | Steering group member with `FINANCE` in `directeur@fgv.nu`'s revivals                                           |
| `steering-group-catering@fgv.nu` | The same, with `CATERING`                                                                                       |
| `steering-group-staffing@fgv.nu` | The same, with `STAFFING`                                                                                       |
| `steering-group-comms@fgv.nu`    | The same, with `COMMS`                                                                                          |
| `no-role@fgv.nu`                 | `USER` only                                                                                                     |
| `invited@fgv.nu`                 | No Keycloak user: waiting in `directeur2@fgv.nu`'s revivals                                                     |

There are two revivals in every status, with show dates, venues, status history and one baseline revision.

Spexregister's import deletes all Keycloak users at every start, so restart Nyuppsättning after Spexregister.

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
