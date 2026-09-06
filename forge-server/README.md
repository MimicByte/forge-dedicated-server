# Forge Dedicated Server

Headless Forge for remote multiplayer rooms using unmodified desktop Forge clients. One container owns one room and all players join over the network. The current release supports Constructed (2 players) and Commander (2–4 players). A disconnected player can reconnect during the grace period; after that, the server transfers that seat to AI for the rest of the match.

## Run

From the repository root:

```sh
docker compose -f forge-server/compose.yaml up --build -d
docker compose -f forge-server/compose.yaml logs -f
```

Join `server-hostname:36743` from desktop Forge. The server needs reachable inbound TCP; clients do not need port forwarding. A home-hosted server may need router port forwarding. The image runs as UID/GID 10001 and has no desktop, web panel, admin API, Docker socket, or owner commands.

The first start loads the card database and can take several minutes. Health checks begin enforcing readiness after five minutes. Each additional room needs its own container, configuration volume, and host port.

The image removes campaign and presentation resources that are not used by the dedicated server. Card rules, token rules, format data, AI data, and localization remain included. Card artwork is not bundled; clients download and cache artwork locally.

For Unraid, build the image first and select the resulting `forge-dedicated` image. Map TCP 36743 and `/config` to an appdata directory writable by UID/GID 10001. Prepare bind-mount ownership on the host. Configure a 30-second stop timeout and restart-on-failure. A configuration volume does not preserve a live match.

## Configuration

| Environment | Default | Allowed |
|---|---|---|
| FORGE_SERVER_PORT | 36743 | 1–65535 |
| FORGE_SERVER_MODE | COMMANDER | COMMANDER, CONSTRUCTED |
| FORGE_SERVER_MAX_PLAYERS | 4 (2 for Constructed) | 2–4; Constructed requires 2 |
| FORGE_SERVER_START_DELAY_SECONDS | 15 | 1–300 |
| FORGE_SERVER_RECONNECT_SECONDS | 300 | 1–3600 |
| FORGE_SERVER_POSTGAME_SECONDS | 120 | 1–3600 |

Compose defaults to four seats. Set `FORGE_SERVER_MODE=CONSTRUCTED` and `FORGE_SERVER_MAX_PLAYERS=2` for a Constructed room. `JAVA_TOOL_OPTIONS` controls JVM memory, for example `-Xmx4g`. `FORGE_SERVER_CONFIG_DIR` changes the profile and status location for a locally extracted distribution; it defaults to `/config` in the image.

Every connected player must choose a legal deck and ready up. With at least two ready players, the server starts a countdown. Joins, departures, and lobby changes cancel the countdown. Changing a deck clears readiness. Teams, dev mode, manually added AI, spectators, and joining an active match are not supported.

Reconnect with exactly the previous display name during the grace period. After the period expires, AI controls that seat for the remainder of the match, including later games. If all human players disconnect, the room resets after the final grace period. Continue, New Match, and QUIT decisions are preserved; an unanswered postgame decision resets the room after the configured timeout.

Players who remain connected keep their decks when returning to the lobby, but must ready up again. Vacated seats are cleared. Restarting the container creates a fresh room.

## Build and compatibility

Requires Maven 3.8.1+ and Java 17+ (the container uses Java 17):

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am -DskipTests -Dlaunch4j.skip=true package
mvn -B -ntp -Pdedicated -pl forge-server -am \
  -Dtest=ServerConfigTest,ReplyPoolTest,DedicatedNetworkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Extract `forge-server/target/forge-server-bin.tar.gz`, then run `bin/forge-server`. Paths are resolved from the launch script rather than the working directory. `bin/forge-server health` checks dispatcher heartbeat freshness without opening a game connection.

The dedicated-server branch is rebased daily onto the upstream `daily-snapshots` revision. Clients and server should use the same Forge snapshot whenever possible. Different snapshot versions may connect, but receive a compatibility warning because matching version labels do not guarantee wire compatibility.

The image build and focused tests run through `.github/workflows/dedicated-ci.yml`. The upstream synchronization workflow updates `master`, rebases `dedicated-server`, and stops for manual conflict resolution if upstream changes overlap the dedicated implementation.

Name-based reconnect is not authenticated. Duplicate active names are rejected, but anyone who knows a disconnected player’s name can attempt to reclaim it. This server is intended for trusted groups rather than public matchmaking.

## Release acceptance

Before using a release, record the exact desktop client version and hash, server image ID, and upstream revision. Using separate unmodified desktop Forge processes against the headless container:

- Complete a two-player Constructed game with legal decks.
- Complete Commander games with two, three, and four players.
- Disconnect and reconnect a player; verify their hand and current prompt recover.
- Let a disconnected player’s grace period expire; verify AI takeover and later match decisions.
- Exercise Continue, New Match, QUIT, and the postgame timeout.
- Return to the lobby and complete another match without restarting the container.
- Disconnect everyone and verify room recovery.
- Test SIGTERM while waiting, counting down, playing, and waiting for reconnect.

Health means the room dispatcher is alive. It does not verify every card interaction. Keep logs and the acceptance record with each tested release.
