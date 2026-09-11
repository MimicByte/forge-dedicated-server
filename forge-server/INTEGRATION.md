# Forge Dedicated Server Integration Reference

This document is the contract for software that provisions and manages Forge Dedicated Server containers. It describes the server as it exists on the `dedicated-server` branch; it does not prescribe a particular website, database, or container platform.

## Scope and compatibility

One container hosts exactly one room. The game protocol is Forge's desktop-client TCP protocol, not HTTP or WebSocket. Players join `hostname:port` from an unmodified desktop Forge client. A path, room identifier, or token cannot be included in that client connection address.

Use the same Forge snapshot for clients and server whenever possible. A version mismatch is warned about but is not rejected, so compatibility is not guaranteed.

The published image is `ghcr.io/mimicbyte/forge-dedicated-server:latest`. For a reproducible deployment, record and preferably pin the image digest or immutable image tag used for each room.

## Container contract

The container listens on these TCP ports:

| Port | Purpose | Exposure |
|---|---|---|
| `FORGE_SERVER_PORT` (default `36743`) | Forge desktop-client game protocol | Publish this port for players. |
| `FORGE_SERVER_ADMIN_PORT` (default `8080`) | Private HTTP management API, only when an admin token is set | Keep private to the container network. Never publish it to untrusted clients. |

The image runs as UID/GID `10001`. Mount `/config` with write access for that identity. It contains logs, Forge preferences, `server.status`, and crash reports, but never an in-progress game that can be resumed after restart.

Docker health is implemented by `bin/forge-server health`. It checks the server's dispatcher heartbeat; it does not prove that a game is playable. The image health check runs every 10 seconds, has a five-minute start period, and retries three times. A graceful stop can take up to 20 seconds; allow at least a 30-second container stop grace period.

The first start may take several minutes while the card database loads. The container does not need a desktop, browser panel, Docker socket, or outbound card-art download. Clients obtain card artwork themselves.

## Startup configuration

All configuration is read once from environment variables at container startup. Settings changed through the management API are memory-only and reset on container restart.

| Variable | Default | Valid values / meaning |
|---|---:|---|
| `FORGE_SERVER_PORT` | `36743` | `1`–`65535`; game TCP port |
| `FORGE_SERVER_MODE` | `COMMANDER` | `CONSTRUCTED`, `COMMANDER`, `OATHBREAKER`, `TINY_LEADERS`, `BRAWL`, `MOMIR_BASIC`, `MOJHOSTO` |
| `FORGE_SERVER_VARIANTS` | empty | Comma-separated `PLANECHASE`, `VANGUARD`, `ARCHENEMY`, `ARCHENEMY_RUMBLE` |
| `FORGE_SERVER_MAX_PLAYERS` | `4` | `2`–`8` |
| `FORGE_SERVER_GAMES_PER_MATCH` | `3` | `1`, `3`, or `5` |
| `FORGE_SERVER_COMMANDER_BRACKET` | `5` | `1`–`5` |
| `FORGE_SERVER_ENFORCE_DECK_LEGALITY` | `true` | `true` or `false` |
| `FORGE_SERVER_MULLIGAN_RULE` | `London` | `Original`, `Paris`, `Vancouver`, `London`, or `Houston` |
| `FORGE_SERVER_START_DELAY_SECONDS` | `10` | `1`–`300`; final five seconds are announced in the lobby |
| `FORGE_SERVER_RECONNECT_SECONDS` | `300` | `1`–`3600` |
| `FORGE_SERVER_POSTGAME_SECONDS` | `120` | `1`–`3600` |
| `FORGE_SERVER_AFK_TIMEOUT` | `5` | Minutes before auto-pass, `0` (disabled)–`60` |
| `FORGE_SERVER_ALLOWED_PLAYERS` | empty | Comma-separated display-name allow-list; empty permits public joins |
| `FORGE_SERVER_LOGIN_FAILURE_LIMIT` | `5` | `1`–`100` rejected logins per source IP before temporary block |
| `FORGE_SERVER_LOGIN_FAILURE_WINDOW_SECONDS` | `60` | `1`–`3600` |
| `FORGE_SERVER_LOGIN_BLOCK_SECONDS` | `900` | `1`–`86400` |
| `FORGE_SERVER_ADMIN_TOKEN` | empty | Non-empty value enables the private HTTP API |
| `FORGE_SERVER_ADMIN_PORT` | `8080` | `1`–`65535`; must differ from game port when API is enabled |
| `FORGE_SERVER_CRASH_REPORT_MAX_FILES` | `10` | `1`–`100` retained text reports |
| `FORGE_SERVER_HEAP_DUMPS` | `false` | `true` or `false`; dumps can be as large as `-Xmx` and may contain game/player data |
| `JAVA_TOOL_OPTIONS` | unset | JVM options; use this to set heap limits, for example `-Xmx2g` |

`ARCHENEMY` and `ARCHENEMY_RUMBLE` cannot be combined. Momir Basic and MoJhoSto cannot use any listed table variant. Commander with Planechase is valid, for example.

## Room lifecycle

The management API reports one of these states: `WAITING`, `COUNTDOWN`, `STARTING`, `PLAYING`, `POSTGAME`, `RESETTING`, or `STOPPING`.

While waiting, connected players select decks and ready themselves. At least two connected human players with valid decks must be ready before the countdown begins. A player joining, leaving, changing deck, changing lobby settings, or changing an AI seat cancels the countdown. The API cannot start a game or make a player ready.

During a match, new players cannot join. A disconnected player may reconnect only with exactly the same display name. After the reconnect timeout, their seat becomes AI-controlled for the remainder of the match. If every human disconnects, the room resets after the normal reconnect period, even if an administrator disabled takeover for an individual player. After a match, connected humans return to the lobby with their decks but must ready again; unoccupied seats are cleared and configured AI seats remain.

An AFK timeout auto-passes a player; it does not kick them. The first timeout uses the configured number of minutes and later inactivity intervals are shortened by Forge.

## Private management API

Enable the API with a high-entropy, per-room `FORGE_SERVER_ADMIN_TOKEN`. All endpoints require:

```http
Authorization: Bearer <token>
```

The endpoint accepts only a small, allow-listed set of room operations. Do not give browsers the token. The service that owns the container should call the API through a private network.

Responses are JSON. A successful mutation returns `{"status":"ok"}`. Errors use this form:

```json
{"error":"machine_readable_code","message":"human-readable explanation"}
```

Authentication failure is `401`; unknown paths are `404`; malformed JSON or invalid values are `422`; a valid request that cannot apply to the current room state is `409`. Request bodies are limited to 16 KiB. JSON bodies support simple strings without embedded escape sequences.

### Read endpoints

| Method and path | Response |
|---|---|
| `GET /v1/status` | State, connected-human count, seat capacity, current mutable settings, and pending disconnected players. |
| `GET /v1/settings` | Current mutable lobby settings. |
| `GET /v1/slots` | One-based seat list, including type and AI details where configured. |
| `GET /v1/ai/decks` | Built-in AI decks allowed by the current base format. |
| `GET /v1/ai/profiles` | Available AI profiles and simulations. |

Example status response:

```json
{
  "state":"PLAYING",
  "players":3,
  "seats":4,
  "settings":{"mode":"COMMANDER","variants":"PLANECHASE","gamesPerMatch":3,"commanderBracket":5,"enforceDeckLegality":true},
  "disconnected":[{"slot":2,"name":"Alice","reconnectSecondsRemaining":184}]
}
```

`disconnected[].slot` is one-based. A `reconnectSecondsRemaining` value of `null` means automatic AI takeover was disabled for that player. `GET /v1/slots` returns this shape:

```json
{"slots":[{"slot":1,"type":"REMOTE","name":"Bob","team":1},{"slot":2,"type":"AI","name":"AI Atraxa","deck":"commander:example","profile":"Default","simulation":"NONE","team":1}]}
```

### Lobby and AI endpoints

`PUT /v1/settings` works only in `WAITING`. Its body must contain exactly all five fields:

```json
{"mode":"COMMANDER","variants":"PLANECHASE","gamesPerMatch":3,"commanderBracket":5,"enforceDeckLegality":true}
```

Changing settings clears readiness. The base mode cannot change while configured AI seats exist.

`PUT /v1/slots/{slot}/team` changes the team of an occupied human or AI seat while the room is waiting. `{slot}` and its `team` value are both one-based seat numbers:

```json
{"team":2}
```

The change clears that seat's Ready state. Open seats are rejected. Archenemy teams are derived from the nominated Archenemy seat and cannot be changed through this endpoint. When used for an AI seat, the selected team persists after the room resets.

`PUT /v1/slots/{slot}/ai` adds or replaces a configured AI in an open seat. `{slot}` is one-based. Its body contains exactly:

```json
{"name":"AI Atraxa","deck":"commander:deck-id","profile":"Default","simulation":"NONE","team":1}
```

Use IDs returned by `GET /v1/ai/decks` and a profile returned by `GET /v1/ai/profiles`. `simulation` is `NONE`, `HYBRID`, or `FULL`. `team` is a one-based seat number; use a human player's slot number to put an AI on that player's team. AI seat changes work only in `WAITING`, cannot replace a human or disconnected player, and are presently available only for Commander and Constructed. Remove an AI seat with `DELETE /v1/slots/{slot}/ai`.

### Moderation endpoints

| Method and path | Effect |
|---|---|
| `POST /v1/messages` | Broadcast a server-labelled message. Body: `{"message":"text"}`; message length is 1–500 characters. |
| `POST /v1/match/abort` | End a current game as a draw and return the room to the lobby. A waiting room is reset harmlessly. |
| `POST /v1/slots/{slot}/kick` | Disconnect the connected player in the one-based slot and free the seat. Available only while the room is waiting; the player cannot use the reconnect grace period. |
| `POST /v1/disconnected/{slot}/takeover` | Immediately replace the disconnected player in the one-based slot with AI. |
| `POST /v1/disconnected/{slot}/wait-indefinitely` | Disable automatic AI takeover for that disconnected player while other humans remain. |

These are room operations, not arbitrary command forwarding. There is no endpoint for shell commands, Java execution, forced player inputs, cheats, or forced game start.

## Security boundaries

The Forge game connection has no password or website-login protocol. `FORGE_SERVER_ALLOWED_PLAYERS` compares the submitted display name case-insensitively; anyone who knows an allowed name can impersonate it. It is an admission deterrent, not authentication. Rejected private-room logins are rate-limited by source IP.

Keep the management API private and keep its token only in trusted backend storage. Treat the game TCP port as public to anyone who can discover it. If strong identity-based private rooms are required, the unmodified Forge client/server protocol is insufficient.

## Operations and diagnostics

Use container lifecycle controls to create, stop, and remove rooms. The management API does not create containers or expose a shutdown command. Before removing a room, collect container logs and `/config/crash-reports` if diagnostics are wanted.

Unhandled server failures write timestamped text reports under `/config/crash-reports`. They include build information, safe room configuration, thread name, and stack trace; secrets, allow-list names, and game state are excluded. Heap dumps are off by default and should be enabled only for memory diagnosis.

The image does not support dev mode, spectators, joining an active match, Draft/Sealed/Limited event hosting, a local host player, or arbitrary host chat commands. Connected players may choose their own teams while waiting; the private API can manage any occupied seat's team. Planechase requires Planes, Vanguard requires an Avatar, and Archenemy requires one nominated player with Schemes.

## Validation reference

The server project uses Java 17+ and Maven 3.8.1+. Run the focused tests from the repository root:

```sh
mvn -B -ntp -Pdedicated -pl forge-server -am \
  -Dtest=ServerConfigTest,ReplyPoolTest,DedicatedNetworkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

For a release candidate, also test real desktop clients with two, four, and eight players; every supported mode/variant combination; reconnect and AI takeover; moderation actions; postgame choices; shutdown; and crash-report collection.
