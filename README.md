# ⚔️ Forge Dedicated Server

Run a headless [Forge](https://github.com/Card-Forge/forge) multiplayer room for **Magic: The Gathering** using unmodified desktop Forge clients.

Join the [Forge community on Discord](https://discord.gg/HcPJNyD66a) for help and discussion.

Forge is open source and is not affiliated with Wizards of the Coast.

## What this repository provides

- A dedicated server that owns one remote multiplayer room per container.
- Constructed, Commander, Oathbreaker, Tiny Leaders, and Brawl rooms.
- Optional Planechase, Vanguard, and Archenemy table variants.
- Two to eight seats per room; Compose defaults to four.
- Reconnect support during a configurable grace period.
- AI takeover when a disconnected player does not return.
- Docker packaging for Linux hosts, home servers, and Unraid.
- The card rules engine, token data, format data, AI data, and localization required for networked play.

The server does not include card artwork. Desktop clients download and cache artwork locally. Campaign, quest, adventure, and other single-player resources are removed from the runtime image.

## Run with Docker

From the repository root:

```sh
docker compose -f forge-server/compose.yaml up --build -d
docker compose -f forge-server/compose.yaml logs -f
```

Connect from desktop Forge to `server-hostname:36743`. The host needs reachable inbound TCP; clients do not need port forwarding. A home-hosted server may need router port forwarding.

The first start loads the card database and can take several minutes. Health checks begin enforcing readiness after five minutes. Each additional room needs its own container, configuration volume, and host port.

The image runs as UID/GID `10001`, with no desktop, web panel, admin API, Docker socket, or owner commands. If `/config` is bind-mounted, its host directory must be writable by UID/GID `10001`. A configuration volume does not preserve a live match.

For Unraid, build the image first, map TCP `36743`, and map `/config` to an appdata directory with the required ownership. Configure a 30-second stop timeout and restart-on-failure.

## Configuration

| Environment | Default | Allowed |
|---|---|---|
| `FORGE_SERVER_PORT` | `36743` | `1–65535` |
| `FORGE_SERVER_MODE` | `COMMANDER` | `CONSTRUCTED`, `COMMANDER`, `OATHBREAKER`, `TINY_LEADERS`, `BRAWL`, `MOMIR_BASIC`, `MOJHOSTO` |
| `FORGE_SERVER_VARIANTS` | unset | Comma-separated `PLANECHASE`, `VANGUARD`, `ARCHENEMY`, and `ARCHENEMY_RUMBLE` |
| `FORGE_SERVER_MAX_PLAYERS` | `4` | `2–8` |
| `FORGE_SERVER_GAMES_PER_MATCH` | `3` | `1`, `3`, or `5` |
| `FORGE_SERVER_COMMANDER_BRACKET` | `5` | `1–5` |
| `FORGE_SERVER_ENFORCE_DECK_LEGALITY` | `true` | `true` or `false` |
| `FORGE_SERVER_START_DELAY_SECONDS` | `15` | `1–300` |
| `FORGE_SERVER_RECONNECT_SECONDS` | `300` | `1–3600` |
| `FORGE_SERVER_POSTGAME_SECONDS` | `120` | `1–3600` |
| `FORGE_SERVER_ALLOWED_PLAYERS` | unset | Comma-separated invite-only display names; empty permits public joins |
| `FORGE_SERVER_LOGIN_FAILURE_LIMIT` | `5` | `1–100` failed invite-only logins before a temporary block |
| `FORGE_SERVER_LOGIN_FAILURE_WINDOW_SECONDS` | `60` | `1–3600` |
| `FORGE_SERVER_LOGIN_BLOCK_SECONDS` | `900` | `1–86400` |
| `FORGE_SERVER_ADMIN_TOKEN` | unset | Enables the private management API when non-empty |
| `FORGE_SERVER_ADMIN_PORT` | `8080` | `1–65535`, different from the game port |

Compose defaults to four seats. Set `FORGE_SERVER_MAX_PLAYERS=8` for a larger room. Add table variants to a base format, for example `FORGE_SERVER_MODE=COMMANDER` with `FORGE_SERVER_VARIANTS=PLANECHASE`. `JAVA_TOOL_OPTIONS` controls JVM memory, for example `-Xmx4g`. `FORGE_SERVER_CONFIG_DIR` changes the profile and status location for a locally extracted distribution; it defaults to `/config` in the image.

Set `FORGE_SERVER_ALLOWED_PLAYERS=Alice,Bob` for an invite-only room. Names are matched case-insensitively, but this is not authentication: anyone who knows an allowed display name can impersonate it. Rejected invite-only logins are rate-limited per source IP using the login-failure settings above.

## Private management API

Set `FORGE_SERVER_ADMIN_TOKEN` to enable a token-protected HTTP API on port 8080 inside the container. Compose deliberately does not publish this port. Use a private Docker network or a trusted local proxy to reach it, and use `Authorization: Bearer <token>` on every request.

`GET /v1/status` reports lifecycle state and occupancy. `GET /v1/settings` reports the mutable rules. `PUT /v1/settings` replaces all mutable rules while the lobby is waiting:

```json
{"mode":"COMMANDER","variants":"PLANECHASE","gamesPerMatch":3,"commanderBracket":5,"enforceDeckLegality":true}
```

The API never starts, cancels, or aborts games. It rejects changes once a countdown or match has begun, clears all ready states after a successful change, and does not persist updates: restarting restores the environment values.

Some rule variants are supported by the dedicated server even where stock remote Forge clients do not provide a matching lobby control. Clients still need the deck sections required by the selected variant, such as planes or schemes.

Clients must still supply the deck sections required by selected rules: every player needs Planes for Planechase and an Avatar for Vanguard, while the nominated Archenemy needs Schemes. The dedicated server applies configured rules even when a stock remote client does not display a matching lobby control; use a matching client build that can submit the needed deck sections and Archenemy nomination. The server prevents a second nomination and starts only when exactly one player is nominated.

Every connected player must choose a legal deck and ready up. With at least two ready players, the server starts a countdown. Joins, departures, and lobby changes cancel the countdown. Changing a deck clears readiness. Teams, dev mode, manually added AI, spectators, and joining an active match are not supported by the current room controller.

Reconnect with exactly the previous display name during the grace period. After it expires, AI controls that seat for the remainder of the match. If all humans disconnect, the room resets after the final grace period. Continue, New Match, and QUIT decisions are preserved; unanswered postgame decisions reset the room after the configured timeout.

## Build locally

Requires Maven 3.8.1+ and Java 17+:

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am \
  -DskipTests -Dlaunch4j.skip=true package
```

The distribution is written to `forge-server/target/forge-server-bin.tar.gz`. Extract it and run `bin/forge-server`. The launcher resolves paths from its own location. Run `bin/forge-server health` to check dispatcher heartbeat freshness.

Focused tests:

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am \
  -Dtest=ServerConfigTest,ReplyPoolTest,DedicatedNetworkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Snapshot branch workflow

`master` is kept aligned with Forge’s upstream `daily-snapshots` revision. `dedicated-server` contains the server changes and is rebased onto `master` after each published snapshot.

GitHub Actions runs the dedicated tests and Docker build from `dedicated-server`. The scheduled synchronization workflow updates `master`, rebases `dedicated-server`, and stops for manual conflict resolution if upstream changes overlap the server implementation.

Clients and server should use the same Forge snapshot whenever possible. Different snapshot versions may connect, but receive a compatibility warning because matching version labels do not guarantee wire compatibility.

## Operations and release checks

Before using a release, record the desktop client version and hash, server image ID, and upstream revision. Test with separate unmodified desktop Forge processes:

- Complete a legal two-player Constructed game.
- Complete rooms with two, four, and eight players.
- Complete Oathbreaker, Tiny Leaders, and Brawl games.
- Complete Planechase, Vanguard, Archenemy, and a supported combined-variant game using stock matching-snapshot clients.
- Disconnect and reconnect a player; verify their hand and current prompt recover.
- Let a grace period expire and verify AI takeover.
- Exercise Continue, New Match, QUIT, and postgame timeout behavior.
- Return to the lobby and complete another match without restarting the container.
- Disconnect everyone and verify room recovery.
- Test SIGTERM while waiting, counting down, playing, and waiting for reconnect.

Name-based reconnect is not authenticated. Duplicate active names are rejected, but anyone who knows a disconnected player’s name can attempt to reclaim it. This server is intended for trusted groups rather than public matchmaking.

## Support and contributing

For help, read the [dedicated-server guide](forge-server/README.md) and join the [Forge Discord](https://discord.gg/HcPJNyD66a). Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GPL-3.0](LICENSE)
