# Nyuppsättning Server

## Overview

Nyuppsättning Server is the backend of Nyuppsättning, which supports the steering groups of Chalmersspex revivals.
It is a Spring Boot application with a GraphQL API, a few plain HTTP endpoints and an MCP server, backed by MySQL
and Keycloak ([fgv-keycloak][1]) for authentication. It reads spex from Spexregister. [Nyuppsättning Web][2] is its
frontend and [fgv-revival-deploy][3] runs it on a server.

The product spec, feature specs and plans are in [fgv-revival-spec][4].

## Learning more

To learn more about Nyuppsättning Server, please consult the reference documentation, which is served at `/docs`.
When running locally, the reference documentation is available [here][10]. Its source is in `src/main/asciidoc`, and
`getting-started.adoc` describes how to run the whole stack locally with `docker compose`.

## Building from source

You will need Java 25 or later to build Nyuppsättning Server, and Docker for the integration tests.
It is built using [Gradle][5]:

```
./gradlew test              # unit tests
./gradlew integrationTest   # integration tests, against MySQL and Keycloak in Testcontainers
./gradlew build             # both, plus the reference documentation
```

## Published images

CI builds the image with Jib and publishes `bloomper/fgv-revival-server` for `linux/amd64` and `linux/arm64`:

| Trigger              | Tags                              |
|----------------------|-----------------------------------|
| push to `develop`    | `develop`, `sha-<commit>`         |
| push of tag `vX.Y.Z` | `X.Y.Z`, `latest`, `sha-<commit>` |

To release, set `version` in `build.gradle` to `X.Y.Z`, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
The build fails if the tag doesn't match the version or if `X.Y.Z` is already on Docker Hub.

## Contributing

Contributors to this project agree to uphold its [code of conduct][6].

You can contribute to Nyuppsättning Server by:

- Opening a [pull request][7]. Please see the [contributor guidelines][8] for details

## License

Nyuppsättning Server is open source software released under the [Apache 2.0 license][9].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-revival-web
[3]: https://github.com/bloomper/fgv-revival-deploy
[4]: https://github.com/bloomper/fgv-revival-spec
[5]: https://gradle.org
[6]: CODE_OF_CONDUCT.md
[7]: https://help.github.com/articles/using-pull-requests/
[8]: CONTRIBUTING.md
[9]: https://www.apache.org/licenses/LICENSE-2.0.html
[10]: https://api.revival.fgv.local.nu:8443/docs/index.html
