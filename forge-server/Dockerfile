FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /source
COPY . .
RUN mvn -B -ntp -Pdedicated -pl forge-server -am -DskipTests -Dlaunch4j.skip=true package
# The server supports networked Forge games, but does not need the desktop
# campaign assets or presentation files. Keep the full Maven artifact intact;
# this pruning only affects the runtime image copied below. Localization and
# all card/gameplay data remain in the image. Keep the bundled precon catalogs
# for server-managed AI opponents.
RUN mv forge-server/target/forge-server-bin/res/quest/commanderprecons /tmp/commanderprecons \
    && mv forge-server/target/forge-server-bin/res/quest/precons /tmp/precons \
    && rm -rf \
    forge-server/target/forge-server-bin/res/adventure \
    forge-server/target/forge-server-bin/res/conquest \
    forge-server/target/forge-server-bin/res/quest \
    forge-server/target/forge-server-bin/res/puzzle \
    forge-server/target/forge-server-bin/res/tutorial \
    forge-server/target/forge-server-bin/res/skins \
    forge-server/target/forge-server-bin/res/music \
    forge-server/target/forge-server-bin/res/sound \
    forge-server/target/forge-server-bin/res/defaults/gauntlet \
    && mkdir -p forge-server/target/forge-server-bin/res/quest \
    && mv /tmp/commanderprecons forge-server/target/forge-server-bin/res/quest/commanderprecons \
    && mv /tmp/precons forge-server/target/forge-server-bin/res/quest/precons

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 forge && useradd --uid 10001 --gid forge --create-home forge \
    && mkdir -p /config /opt/forge && chown forge:forge /config
COPY --from=build /source/forge-server/target/forge-server-bin/ /opt/forge/
USER 10001:10001
WORKDIR /opt/forge
ENV FORGE_SERVER_PORT=36743 FORGE_SERVER_MODE=COMMANDER FORGE_SERVER_VARIANTS= FORGE_SERVER_MAX_PLAYERS=4 \
    FORGE_SERVER_GAMES_PER_MATCH=3 FORGE_SERVER_COMMANDER_BRACKET=5 FORGE_SERVER_ENFORCE_DECK_LEGALITY=true \
    FORGE_SERVER_ADMIN_PORT=8080 FORGE_SERVER_ALLOWED_PLAYERS= FORGE_SERVER_LOGIN_FAILURE_LIMIT=5 \
    FORGE_SERVER_LOGIN_FAILURE_WINDOW_SECONDS=60 FORGE_SERVER_LOGIN_BLOCK_SECONDS=900 \
    FORGE_SERVER_AFK_TIMEOUT=5 \
    FORGE_SERVER_CRASH_REPORT_MAX_FILES=10 FORGE_SERVER_HEAP_DUMPS=false
EXPOSE 36743/tcp 8080/tcp
VOLUME ["/config"]
HEALTHCHECK --interval=10s --timeout=5s --start-period=300s --retries=3 CMD ["/opt/forge/bin/forge-server", "health"]
ENTRYPOINT ["/opt/forge/bin/forge-server"]
