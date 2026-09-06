# Forge Dedicated Server

Headless Forge for a trusted group using stock desktop clients. One container owns one room; all players join remotely. Commander supports 2–4 humans and Constructed supports 2. AI is used only after a disconnected player's grace period expires.

## Run

From the repository root:

```sh
docker compose -f forge-server/compose.yaml up --build -d
docker compose -f forge-server/compose.yaml logs -f
```

Join `server-hostname:36743` in desktop Forge. The server must have reachable inbound TCP; players do not need port forwarding. A home-hosted server may still need router forwarding. The image runs as UID/GID 10001 and has no desktop, web panel, admin API, Docker socket, or owner commands.

An empty volume triggers card-data initialization. Allow up to five minutes before health checks begin enforcing readiness. Each additional room needs a separate container, volume, and host port.

For Unraid, build the image first, select `forge-dedicated:8980637-1`, map TCP 36743 and `/config` to an appdata directory writable by UID/GID 10001, and set the environment values below. Bind-mount ownership must be prepared on the host. Configure a 30-second stop timeout and restart-on-failure. A volume does not preserve live matches.

## Configuration

| Environment | Default | Allowed |
|---|---|---|
| FORGE_SERVER_PORT | 36743 | 1–65535 |
| FORGE_SERVER_MODE | COMMANDER | COMMANDER, CONSTRUCTED |
| FORGE_SERVER_MAX_PLAYERS | 4 (2 for Constructed) | 2–4; Constructed requires 2 |
| FORGE_SERVER_START_DELAY_SECONDS | 15 | 1–300 |
| FORGE_SERVER_RECONNECT_SECONDS | 300 | 1–3600 |
| FORGE_SERVER_POSTGAME_SECONDS | 120 | 1–3600 |

Compose explicitly defaults capacity to four; change it to two when selecting Constructed. `JAVA_TOOL_OPTIONS` controls JVM memory, e.g. `-Xmx4g`. `FORGE_SERVER_CONFIG_DIR` changes the profile/status location for a local extracted distribution, default `/config`.

Everyone connected must choose a legal deck and ready up. With at least two players ready, the server starts a countdown. Joins, departures, and lobby changes cancel it. Changing a deck clears readiness. Teams, dev mode, limited events, manually added AI, spectators, and mid-match admission are not supported.

Reconnect using exactly the previous display name within the grace period. After expiry, AI controls that seat for the remainder of the match, including subsequent games. If every human disconnects, the room resets after the last departure's grace period. Normal Continue/New Match/QUIT decisions are preserved; unanswered postgame decisions reset the room after the configured timeout.

Still-connected players keep decks when returning to the lobby, but must ready again. Vacated seats are cleared. Container restart always creates a fresh lobby.

## Build and compatibility

Requires Maven 3.8.1+ and Java 17+ (the container uses Java 17):

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am -DskipTests -Dlaunch4j.skip=true package
mvn -B -ntp -pl forge-server -am -Dtest=ServerConfigTest,ReplyPoolTest,DedicatedNetworkTest,LobbySlotAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Extract `forge-server/target/forge-server-bin.tar.gz`, then run `bin/forge-server`. Paths are resolved from the launch script, not the working directory. `bin/forge-server health` checks dispatcher heartbeat freshness without opening a game connection.

Compatibility baseline: upstream `89806371a4d1c5b62be45f833535e89e73a27227`, tested stock version `2.0.15-SNAPSHOT-09.05`. `-Pdedicated` writes that tested version into runtime JAR manifests. Different desktop snapshot versions may join, but receive a compatibility warning because matching version labels do not guarantee revision compatibility. Override `-Dforge.client.version=...` only when rebuilding against the corresponding upstream revision and client artifact; changing the label does not make incompatible builds work. Packaging revision is reported separately.

**Release acceptance is pending until the stock desktop-client checklist below is completed.** Automated protocol clients do not establish GUI compatibility.

Name-based reconnect is inherited from stock Forge and is not authenticated. Duplicate active names are rejected, but anyone knowing a parked name can attempt to reclaim it. This release is intended for trusted friends, not authenticated public matchmaking. Preserve upstream serialization protections and avoid privileged container access.

## Release acceptance

Record exact desktop artifact version/hash, server image ID, and upstream revision. Using separate unmodified desktop processes against the headless container:

- Complete a two-player Constructed game with normal legal deck selection.
- Complete Commander games with two, three, and four players.
- Disconnect/reconnect one player; verify their hand and current prompt recover.
- Let another player's grace expire; verify AI takeover and subsequent match decisions.
- Exercise Continue, New Match, QUIT, and postgame timeout.
- Return to the lobby and complete a second match without restarting the container.
- Disconnect everyone and verify empty-room recovery; test SIGTERM during waiting, countdown, gameplay, and reconnect.

Health means the room dispatcher is alive, not that every possible card interaction has been verified. Logs and the acceptance record should accompany a tested release.
