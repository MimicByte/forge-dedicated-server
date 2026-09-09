# Forge Dedicated Server

Headless [Forge](https://github.com/Card-Forge/forge) multiplayer rooms for **Magic: The Gathering**, using unmodified desktop Forge clients. Forge is open source and not affiliated with Wizards of the Coast.

The server supports Constructed, Commander, Oathbreaker, Tiny Leaders, Brawl, Momir Basic, and MoJhoSto, with optional Planechase, Vanguard, and Archenemy variants where compatible. One container owns one room with two to eight seats. Disconnected players can reconnect during a grace period; after it expires, AI takes over for the rest of the match.

Clients and server should use matching Forge snapshots whenever possible.

## Run from the published image

The repository's [`forge-server/compose.yaml`](forge-server/compose.yaml) builds an image from source. For a deployed room, save the following as `compose.yaml` and pull the published image instead:

```yaml
services:
  forge:
    image: ghcr.io/mimicbyte/forge-dedicated-server:latest
    restart: on-failure
    stop_grace_period: 30s
    ports:
      - "36743:36743/tcp"
    environment:
      FORGE_SERVER_MODE: COMMANDER
      FORGE_SERVER_MAX_PLAYERS: "4"
      FORGE_SERVER_GAMES_PER_MATCH: "3"
      FORGE_SERVER_COMMANDER_BRACKET: "5"
      FORGE_SERVER_ENFORCE_DECK_LEGALITY: "true"
      FORGE_SERVER_START_DELAY_SECONDS: "10"
      FORGE_SERVER_RECONNECT_SECONDS: "300"
      FORGE_SERVER_POSTGAME_SECONDS: "120"
      FORGE_SERVER_AFK_TIMEOUT: "5"
      # Empty permits public joins. Otherwise list permitted display names.
      FORGE_SERVER_ALLOWED_PLAYERS: ""
      # Leave empty to disable the private management API.
      FORGE_SERVER_ADMIN_TOKEN: ""
      FORGE_SERVER_ADMIN_PORT: "8080"
      FORGE_SERVER_LOGIN_FAILURE_LIMIT: "5"
      FORGE_SERVER_LOGIN_FAILURE_WINDOW_SECONDS: "60"
      FORGE_SERVER_LOGIN_BLOCK_SECONDS: "900"
      FORGE_SERVER_CRASH_REPORT_MAX_FILES: "10"
      FORGE_SERVER_HEAP_DUMPS: "false"
      JAVA_TOOL_OPTIONS: "-Xmx4g"
    volumes:
      - forge-config:/config

volumes:
  forge-config:
```

Start it with `docker compose up -d`, then join `server-hostname:36743` from desktop Forge. The server needs reachable inbound TCP; clients do not need port forwarding. The first start loads the card database and can take several minutes. The image runs as UID/GID 10001. Do not publish the private API port; use a private Docker network or trusted local proxy if it is enabled.

The image removes unused campaign and presentation resources, while retaining card rules, token rules, format data, AI data, and localization. Card artwork is not bundled; clients download and cache it locally. For an Unraid bind mount, make the mapped `/config` directory writable by UID/GID 10001. A configuration volume preserves logs and preferences, not an in-progress match. The image contains no desktop, web panel, Docker socket, or owner-command interface.

## Server configuration

| Environment | Default | Allowed |
|---|---|---|
| `FORGE_SERVER_PORT` | 36743 | 1–65535 |
| `FORGE_SERVER_MODE` | COMMANDER | CONSTRUCTED, COMMANDER, OATHBREAKER, TINY_LEADERS, BRAWL, MOMIR_BASIC, MOJHOSTO |
| `FORGE_SERVER_VARIANTS` | unset | Comma-separated PLANECHASE, VANGUARD, ARCHENEMY, ARCHENEMY_RUMBLE |
| `FORGE_SERVER_MAX_PLAYERS` | 4 | 2–8 |
| `FORGE_SERVER_GAMES_PER_MATCH` | 3 | 1, 3, or 5 |
| `FORGE_SERVER_COMMANDER_BRACKET` | 5 | 1–5 |
| `FORGE_SERVER_ENFORCE_DECK_LEGALITY` | true | true or false |
| `FORGE_SERVER_START_DELAY_SECONDS` | 10 | 1–300; the final five seconds are announced in the lobby |
| `FORGE_SERVER_RECONNECT_SECONDS` | 300 | 1–3600 |
| `FORGE_SERVER_POSTGAME_SECONDS` | 120 | 1–3600 |
| `FORGE_SERVER_AFK_TIMEOUT` | 5 | Minutes before auto-pass; 0 (off)–60 |
| `FORGE_SERVER_ALLOWED_PLAYERS` | unset | Comma-separated invite-only display names; empty permits public joins |
| `FORGE_SERVER_LOGIN_FAILURE_LIMIT` | 5 | 1–100 |
| `FORGE_SERVER_LOGIN_FAILURE_WINDOW_SECONDS` | 60 | 1–3600 |
| `FORGE_SERVER_LOGIN_BLOCK_SECONDS` | 900 | 1–86400 |
| `FORGE_SERVER_CRASH_REPORT_MAX_FILES` | 10 | 1–100 retained text crash reports |
| `FORGE_SERVER_HEAP_DUMPS` | false | true or false |
| `FORGE_SERVER_ADMIN_TOKEN` | unset | Enables the private management API when non-empty |
| `FORGE_SERVER_ADMIN_PORT` | 8080 | 1–65535, different from the game port |

`FORGE_SERVER_VARIANTS` can combine a base format with table variants, for example Commander with Planechase. `JAVA_TOOL_OPTIONS` controls JVM memory. `FORGE_SERVER_CONFIG_DIR` changes the profile/status location for a locally extracted distribution; it defaults to `/config` in the image.

Set `FORGE_SERVER_ALLOWED_PLAYERS=Alice,Bob` for an invite-only room. Names match case-insensitively, but this is not authentication: anyone who knows an allowed display name can impersonate it. Rejected invite-only logins are rate-limited per source IP. Name-based reconnect has the same limitation.

## Private management API and AI seats

Set `FORGE_SERVER_ADMIN_TOKEN` to enable the token-protected management API on TCP 8080 inside the container. Send `Authorization: Bearer <token>` with every request. Do not publish this port.

For the complete contract intended for service integrations, see [Dedicated Server Integration Reference](forge-server/INTEGRATION.md).

`GET /v1/status` reports state, occupancy, and disconnected players with their one-based slot and reconnect deadline. `GET /v1/settings` reports mutable rules. While the lobby is waiting, `PUT /v1/settings` accepts:

```json
{"mode":"COMMANDER","variants":"PLANECHASE","gamesPerMatch":3,"commanderBracket":5,"enforceDeckLegality":true}
```

The API can add AI opponents while the lobby is waiting. AI seats persist after a match returns to the lobby, but at least two connected human players are still required to start. `GET /v1/ai/decks` lists the bundled deck IDs valid for the current format, `GET /v1/ai/profiles` lists profiles and simulation modes, and `GET /v1/slots` lists one-based seats.

```sh
curl -X PUT http://localhost:8080/v1/slots/3/ai \
  -H 'Authorization: Bearer your-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"AI Atraxa","deck":"commander:Veloci-Ramp-Tor [LCC] [2023]","profile":"Default","simulation":"NONE"}'
```

`simulation` is `NONE`, `HYBRID`, or `FULL`; remove an AI with `DELETE /v1/slots/3/ai`. Commander uses official Commander precons and Constructed uses Forge general precons. Other modes do not currently expose AI seats because no matching bundled catalog exists. Settings updates clear player readiness and are memory-only; restart the container to restore environment values.

The private moderation endpoints are `POST /v1/messages` with `{"message":"text"}`, `POST /v1/match/abort`, `POST /v1/disconnected/{slot}/takeover`, and `POST /v1/disconnected/{slot}/wait-indefinitely`. They cannot start games or bypass player deck/ready requirements. An indefinite reconnect timeout still does not keep an entirely abandoned room alive; if every human disconnects, the normal room reset policy applies.

Every connected player must choose a legal deck and ready up. With at least two ready players, the server starts a countdown. Joins, departures, and lobby changes cancel it. Teams, dev mode, spectators, joining an active match, and replacing a human or disconnected seat with a configured AI are not supported. Planechase requires Planes, Vanguard requires an Avatar, and Archenemy requires the nominated player's Schemes. A reconnect must use exactly the prior display name during the grace period. After the grace period, AI controls the vacated seat for the remainder of the match, including later games. If all humans disconnect, the room resets after the final grace period. Connected players keep their decks after returning to the lobby but must ready again; vacated seats are cleared.

## Crash reports, builds, and validation

Unhandled failures write timestamped reports to `/config/crash-reports`. They contain build information, safe room configuration, thread name, and stack trace; secrets, allowed-player names, and game state are excluded. The newest configured number of text reports is retained. Fatal JVM error reports go there as well. Set `FORGE_SERVER_HEAP_DUMPS=true` only while diagnosing memory problems: a dump can be as large as `-Xmx` and may contain player/game data.

To build from source, use Java 17+ and Maven 3.8.1+:

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am -DskipTests -Dlaunch4j.skip=true package
mvn -B -ntp -Pdedicated -pl forge-server -am \
  -Dtest=ServerConfigTest,ReplyPoolTest,DedicatedNetworkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Extract `forge-server/target/forge-server-bin.tar.gz` and run `bin/forge-server` for a local distribution. `bin/forge-server health` checks dispatcher heartbeat freshness without opening a game connection. The daily sync workflow merges upstream snapshots into the fork without rewriting contributor commits, then publishes the rolling `dedicated-daily-snapshot` release and the `latest` container image.

Before a release, test a two-player Constructed game; two-, four-, and eight-player rooms; each supported base format and variant combination; reconnect; AI takeover; Continue, New Match, QUIT, and postgame timeout decisions; return to the lobby for another match; full-room recovery after disconnection; and SIGTERM while waiting, counting down, playing, and waiting for reconnect. Health checks show that the room dispatcher is alive; they do not prove every card interaction. Keep the tested client version, server image ID, upstream revision, and logs with the release record.
