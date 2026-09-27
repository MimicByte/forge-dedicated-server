package forge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.MultipartConfigElement;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.util.FileSection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/** Small private management surface with only allow-listed room operations. */
public final class DedicatedAdminServer {
    private final Server server;

    public DedicatedAdminServer(ServerConfig config, DedicatedLobbyController controller) {
        server = new Server(config.adminPort());
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        ServletHolder api = new ServletHolder(new ApiServlet(config.adminToken(), controller));
        // This embedded registration uses an already-created servlet, so Jetty does not
        // discover @MultipartConfig. Configure the holder explicitly for request.getParts().
        api.getRegistration().setMultipartConfig(new MultipartConfigElement("", 5 * 1024 * 1024,
                5 * 1024 * 1024, 5 * 1024 * 1024));
        context.addServlet(api, "/v1/*");
        server.setHandler(context);
    }
    public void start() throws Exception { server.start(); }
    public void stop() throws Exception { server.stop(); }

    @MultipartConfig(fileSizeThreshold = 256 * 1024, maxFileSize = 5 * 1024 * 1024, maxRequestSize = 5 * 1024 * 1024)
    private static final class ApiServlet extends HttpServlet {
        private static final int MAX_BODY_BYTES = 16 * 1024;
        private static final int MAX_UPLOAD_BYTES = 256 * 1024;
        private static final Pattern MEMBER = Pattern.compile("\\\"([A-Za-z][A-Za-z0-9]*)\\\"\\s*:\\s*(\\\"[^\\\"\\\\]*\\\"|true|false|-?[0-9]+)");
        private static final Pattern AI_SLOT_PATH = Pattern.compile("/slots/(\\d+)/ai");
        private static final Pattern AI_UPLOAD_PATH = Pattern.compile("/slots/(\\d+)/ai/upload");
        private static final Pattern TEAM_SLOT_PATH = Pattern.compile("/slots/(\\d+)/team");
        private static final Pattern KICK_PATH = Pattern.compile("/slots/(\\d+)/kick");
        private static final Pattern DISCONNECTED_ACTION_PATH = Pattern.compile("/disconnected/(\\d+)/(takeover|wait-indefinitely)");
        private final byte[] token;
        private final DedicatedLobbyController controller;

        ApiServlet(String token, DedicatedLobbyController controller) {
            this.token = token.getBytes(StandardCharsets.UTF_8);
            this.controller = controller;
        }

        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            switch (request.getPathInfo()) {
            case "/status" -> write(response, HttpServletResponse.SC_OK, statusJson());
            case "/settings" -> write(response, HttpServletResponse.SC_OK, settingsJson(controller.rules()));
            case "/slots" -> write(response, HttpServletResponse.SC_OK, slotsJson());
            case "/ai/decks" -> write(response, HttpServletResponse.SC_OK, decksJson());
            case "/ai/profiles" -> write(response, HttpServletResponse.SC_OK, profilesJson());
            case "/limited/catalog" -> write(response, HttpServletResponse.SC_OK, limitedCatalogJson());
            default -> error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
            }
        }

        @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            if ("/settings".equals(request.getPathInfo())) {
                updateRules(request, response);
                return;
            }
            if ("/limited/event".equals(request.getPathInfo())) {
                updateLimitedEvent(request, response);
                return;
            }
            Matcher aiPath = AI_SLOT_PATH.matcher(request.getPathInfo());
            if (aiPath.matches()) {
                updateAiSlot(Integer.parseInt(aiPath.group(1)), request, response);
                return;
            }
            Matcher teamPath = TEAM_SLOT_PATH.matcher(request.getPathInfo());
            if (teamPath.matches()) {
                updateSlotTeam(Integer.parseInt(teamPath.group(1)), request, response);
                return;
            }
            error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
        }

        @Override protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            if ("/limited/event".equals(request.getPathInfo())) {
                writeActionResult(response, controller.cancelLimited());
                return;
            }
            Matcher path = AI_SLOT_PATH.matcher(request.getPathInfo());
            if (!path.matches()) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
                return;
            }
            writeAiResult(response, controller.removeAiSlot(Integer.parseInt(path.group(1))));
        }

        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            Matcher upload = AI_UPLOAD_PATH.matcher(request.getPathInfo());
            if (upload.matches()) {
                uploadAiSlot(Integer.parseInt(upload.group(1)), request, response);
                return;
            }
            if ("/messages".equals(request.getPathInfo())) {
                final Map<String, String> values;
                try { values = parseObject(readBody(request), 1); }
                catch (IllegalArgumentException e) { error(response, 422, "invalid_message", e.getMessage()); return; }
                final String message;
                try { message = string(values, "message"); }
                catch (IllegalArgumentException e) { error(response, 422, "invalid_message", e.getMessage()); return; }
                writeActionResult(response, controller.announce(message));
                return;
            }
            if ("/match/abort".equals(request.getPathInfo())) {
                writeActionResult(response, controller.abortByAdministrator());
                return;
            }
            if ("/limited/event/start".equals(request.getPathInfo())) {
                writeActionResult(response, controller.startLimited());
                return;
            }
            if ("/limited/assets".equals(request.getPathInfo())) {
                uploadLimitedAsset(request, response);
                return;
            }
            Matcher kick = KICK_PATH.matcher(request.getPathInfo());
            if (kick.matches()) {
                writeActionResult(response, controller.kickPlayer(Integer.parseInt(kick.group(1))));
                return;
            }
            Matcher path = DISCONNECTED_ACTION_PATH.matcher(request.getPathInfo());
            if (!path.matches()) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
                return;
            }
            int slot = Integer.parseInt(path.group(1));
            DedicatedLobbyController.ActionResult result = "takeover".equals(path.group(2))
                    ? controller.takeOverDisconnectedPlayer(slot)
                    : controller.waitIndefinitelyForDisconnectedPlayer(slot);
            writeActionResult(response, result);
        }

        private void updateRules(HttpServletRequest request, HttpServletResponse response) throws IOException {
            final ServerConfig.LobbyRules rules;
            try { rules = parseRules(readBody(request)); }
            catch (IllegalArgumentException e) { error(response, 422, "invalid_settings", e.getMessage()); return; }
            boolean changingModeWithAi = controller.hasAiSlots() && rules.mode() != controller.rules().mode();
            if (!controller.updateRules(rules)) {
                if (changingModeWithAi) {
                    error(response, HttpServletResponse.SC_CONFLICT, "ai_slots_present", "Remove AI seats before changing the base mode.");
                } else {
                    error(response, HttpServletResponse.SC_CONFLICT, "lobby_not_waiting", "Settings may only change while the lobby is waiting.");
                }
                return;
            }
            write(response, HttpServletResponse.SC_OK, settingsJson(rules));
        }

        private void updateLimitedEvent(HttpServletRequest request, HttpServletResponse response) throws IOException {
            final Map<String, String> values;
            try { values = parseObject(readBody(request), 8); }
            catch (IllegalArgumentException e) { error(response, 422, "invalid_event", e.getMessage()); return; }
            try {
                forge.gamemodes.net.EventFormat format = forge.gamemodes.net.EventFormat.valueOf(
                        string(values, "format").toUpperCase(java.util.Locale.ROOT));
                if (format != forge.gamemodes.net.EventFormat.SEALED
                        && format != forge.gamemodes.net.EventFormat.BOOSTER_DRAFT) {
                    throw new IllegalArgumentException("format must be SEALED or BOOSTER_DRAFT");
                }
                DedicatedLobbyController.LimitedConfiguration configuration =
                        new DedicatedLobbyController.LimitedConfiguration(format,
                                string(values, "source"), string(values, "setCode"), string(values, "assetId"),
                                integer(values, "packs"), integer(values, "podSize"),
                                integer(values, "pickTimerSeconds"), integer(values, "disconnectGraceSeconds"));
                writeActionResult(response, controller.configureLimited(configuration));
            } catch (IllegalArgumentException e) {
                error(response, 422, "invalid_event", e.getMessage());
            }
        }

        private void uploadLimitedAsset(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (request.getContentLengthLong() > 5L * 1024 * 1024) {
                error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "upload_too_large", "Upload must be at most 5 MiB.");
                return;
            }
            Part cube = null;
            try {
                for (Part part : request.getParts()) {
                    if (!"cube".equals(part.getName()) || part.getSubmittedFileName() == null || cube != null) {
                        throw new IllegalArgumentException("Provide exactly one cube file.");
                    }
                    cube = part;
                }
                if (cube == null || !cube.getSubmittedFileName().toLowerCase(java.util.Locale.ROOT).endsWith(".dck")) {
                    throw new IllegalArgumentException("cube must have a .dck filename.");
                }
                byte[] contents = cube.getInputStream().readNBytes(5 * 1024 * 1024 + 1);
                if (contents.length > 5 * 1024 * 1024) { throw new UploadTooLargeException(); }
                Deck deck = DeckSerializer.fromSections(FileSection.parseSections(java.util.Arrays.asList(
                        new String(contents, StandardCharsets.UTF_8).split("\\R", -1))));
                if (deck == null) { throw new IllegalArgumentException("cube is not a valid Forge .dck file."); }
                DedicatedLobbyController.LimitedAssetResult result = controller.uploadLimitedCube(deck);
                if (result.success()) {
                    write(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\",\"id\":" + jsonString(result.id()) + "}");
                } else {
                    error(response, HttpServletResponse.SC_CONFLICT, result.code(), result.message());
                }
            } catch (UploadTooLargeException | IllegalStateException e) {
                error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "upload_too_large", "Upload must be at most 5 MiB.");
            } catch (ServletException | IllegalArgumentException e) {
                error(response, 422, "invalid_upload", e.getMessage());
            } catch (RuntimeException e) {
                error(response, 422, "invalid_deck", "cube could not be parsed as a Forge .dck file.");
            }
        }

        private void updateAiSlot(int slot, HttpServletRequest request, HttpServletResponse response) throws IOException {
            final Map<String, String> values;
            try { values = parseObject(readBody(request), 5); }
            catch (IllegalArgumentException e) { error(response, 422, "invalid_ai", e.getMessage()); return; }
            final DedicatedLobbyController.AiSlotConfiguration configuration;
            try {
                configuration = new DedicatedLobbyController.AiSlotConfiguration(slot,
                        string(values, "name"), string(values, "deck"), string(values, "profile"),
                        string(values, "simulation").toUpperCase(java.util.Locale.ROOT), integer(values, "team"));
            } catch (IllegalArgumentException e) { error(response, 422, "invalid_ai", e.getMessage()); return; }
            writeAiResult(response, controller.updateAiSlot(configuration));
        }

        private void uploadAiSlot(int slot, HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (request.getContentLengthLong() > MAX_UPLOAD_BYTES) {
                error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "upload_too_large", "Upload must be at most 256 KiB.");
                return;
            }
            final Map<String, String> fields = new LinkedHashMap<>();
            Part deckPart = null;
            try {
                for (Part part : request.getParts()) {
                    if ("deck".equals(part.getName())) {
                        if (deckPart != null || part.getSubmittedFileName() == null) { throw new IllegalArgumentException("Provide exactly one deck file."); }
                        deckPart = part;
                    } else {
                        if (part.getSubmittedFileName() != null || fields.put(part.getName(), readPart(part)) != null) {
                            throw new IllegalArgumentException("Duplicate or invalid multipart field " + part.getName());
                        }
                    }
                }
            } catch (IllegalStateException e) {
                error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "upload_too_large", "Upload must be at most 256 KiB.");
                return;
            } catch (ServletException | IllegalArgumentException e) {
                error(response, 422, "invalid_upload", e.getMessage());
                return;
            }
            if (deckPart == null || fields.size() != 4 || !fields.keySet().containsAll(java.util.Set.of("name", "profile", "simulation", "team"))) {
                error(response, 422, "invalid_upload", "Provide deck, name, profile, simulation, and team multipart fields.");
                return;
            }
            String filename = deckPart.getSubmittedFileName();
            if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".dck")) {
                error(response, 422, "invalid_upload", "deck must have a .dck filename.");
                return;
            }
            final Deck deck;
            try {
                byte[] contents = deckPart.getInputStream().readNBytes(MAX_UPLOAD_BYTES + 1);
                if (contents.length > MAX_UPLOAD_BYTES) { throw new UploadTooLargeException(); }
                deck = DeckSerializer.fromSections(FileSection.parseSections(java.util.Arrays.asList(
                        new String(contents, StandardCharsets.UTF_8).split("\\R", -1))));
                if (deck == null) { throw new IllegalArgumentException("deck is not a valid Forge .dck file."); }
            } catch (UploadTooLargeException e) {
                error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "upload_too_large", "Upload must be at most 256 KiB.");
                return;
            } catch (RuntimeException e) {
                error(response, 422, "invalid_deck", "deck could not be parsed as a Forge .dck file.");
                return;
            }
            final DedicatedLobbyController.UploadedAiSlotConfiguration configuration;
            try {
                configuration = new DedicatedLobbyController.UploadedAiSlotConfiguration(slot,
                        requiredField(fields, "name"), requiredField(fields, "profile"),
                        requiredField(fields, "simulation").toUpperCase(java.util.Locale.ROOT),
                        Integer.parseInt(requiredField(fields, "team")), deck);
            } catch (IllegalArgumentException e) {
                error(response, 422, "invalid_ai", e.getMessage());
                return;
            }
            writeUploadAiResult(response, controller.updateUploadedAiSlot(configuration));
        }

        private static String readPart(Part part) throws IOException {
            byte[] bytes = part.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) { throw new IllegalArgumentException(part.getName() + " is too large"); }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        private static String requiredField(Map<String, String> fields, String name) {
            String value = fields.get(name);
            if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
            return value;
        }
        private static final class UploadTooLargeException extends RuntimeException { }

        private void updateSlotTeam(int slot, HttpServletRequest request, HttpServletResponse response) throws IOException {
            final int team;
            try {
                Map<String, String> values = parseObject(readBody(request), 1);
                team = integer(values, "team");
            } catch (IllegalArgumentException e) { error(response, 422, "invalid_team", e.getMessage()); return; }
            writeActionResult(response, controller.updateSlotTeam(slot, team));
        }

        private static void writeAiResult(HttpServletResponse response, DedicatedLobbyController.AiResult result) throws IOException {
            if (result.success()) { write(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\"}"); }
            else { error(response, HttpServletResponse.SC_CONFLICT, result.code(), result.message()); }
        }
        private static void writeUploadAiResult(HttpServletResponse response, DedicatedLobbyController.AiResult result) throws IOException {
            if (result.success()) { write(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\"}"); return; }
            int status = "lobby_not_waiting".equals(result.code()) || "slot_occupied".equals(result.code())
                    ? HttpServletResponse.SC_CONFLICT : 422;
            error(response, status, result.code(), result.message());
        }
        private static void writeActionResult(HttpServletResponse response, DedicatedLobbyController.ActionResult result) throws IOException {
            if (result.success()) { write(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\"}"); }
            else { error(response, HttpServletResponse.SC_CONFLICT, result.code(), result.message()); }
        }

        private boolean authorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String header = request.getHeader("Authorization");
            byte[] supplied = header != null && header.startsWith("Bearer ")
                    ? header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!MessageDigest.isEqual(token, supplied)) {
                response.setHeader("WWW-Authenticate", "Bearer");
                error(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Bearer token required");
                return false;
            }
            return true;
        }

        private String readBody(HttpServletRequest request) throws IOException {
            int length = request.getContentLength();
            if (length > MAX_BODY_BYTES) { throw new IllegalArgumentException("Request body is too large"); }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) { throw new IllegalArgumentException("Request body is too large"); }
            return new String(body, StandardCharsets.UTF_8);
        }

        private static ServerConfig.LobbyRules parseRules(String json) {
            Map<String, String> values = parseObject(json, 9);
            if (values.size() != 9) {
                throw new IllegalArgumentException("Provide mode, variants, gamesPerMatch, commanderBracket, enforceDeckLegality, manaBurn, legacyOrderCombatants, filteredHands, and aiTimeoutSeconds");
            }
            try {
                ServerConfig.Mode mode = ServerConfig.Mode.valueOf(string(values, "mode").toUpperCase(java.util.Locale.ROOT));
                EnumSet<ServerConfig.Variant> variants = EnumSet.noneOf(ServerConfig.Variant.class);
                String variantText = string(values, "variants");
                if (!variantText.isBlank()) for (String value : variantText.split(",")) {
                    variants.add(ServerConfig.Variant.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
                }
                return new ServerConfig.LobbyRules(mode, variants, integer(values, "gamesPerMatch"),
                        integer(values, "commanderBracket"), bool(values, "enforceDeckLegality"),
                        bool(values, "manaBurn"), bool(values, "legacyOrderCombatants"),
                        bool(values, "filteredHands"), integer(values, "aiTimeoutSeconds"));
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException(e.getMessage()); }
        }
        private static Map<String, String> parseObject(String json, int expectedMembers) {
            String trimmed = json.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) { throw new IllegalArgumentException("Expected a JSON object"); }
            Map<String, String> values = new LinkedHashMap<>();
            Matcher matcher = MEMBER.matcher(trimmed);
            int cursor = 1;
            while (matcher.find()) {
                if (!trimmed.substring(cursor, matcher.start()).trim().matches(",?")) { throw new IllegalArgumentException("Malformed JSON object"); }
                if (values.put(matcher.group(1), matcher.group(2)) != null) { throw new IllegalArgumentException("Duplicate setting " + matcher.group(1)); }
                cursor = matcher.end();
            }
            if (!trimmed.substring(cursor, trimmed.length() - 1).trim().isEmpty() || values.size() != expectedMembers) {
                throw new IllegalArgumentException("Provide exactly " + expectedMembers + " settings");
            }
            return values;
        }
        private static String string(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) { throw new IllegalArgumentException(key + " must be a string"); }
            return value.substring(1, value.length() - 1);
        }
        private static int integer(Map<String, String> values, String key) {
            try { return Integer.parseInt(values.get(key)); }
            catch (RuntimeException e) { throw new IllegalArgumentException(key + " must be a number"); }
        }
        private static boolean bool(Map<String, String> values, String key) {
            String value = values.get(key);
            if (!"true".equals(value) && !"false".equals(value)) { throw new IllegalArgumentException(key + " must be true or false"); }
            return Boolean.parseBoolean(value);
        }
        private String statusJson() {
            return "{\"state\":\"" + controller.state() + "\",\"players\":" + controller.connectedPlayerCount()
                    + ",\"seats\":" + controller.seatCapacity() + ",\"settings\":" + settingsJson(controller.rules())
                    + ",\"limited\":" + limitedJson() + ",\"disconnected\":" + disconnectedJson() + "}";
        }
        private String limitedJson() {
            DedicatedLobbyController.LimitedView event = controller.limitedView();
            if (event == null) { return "null"; }
            return "{\"format\":" + jsonString(event.format()) + ",\"phase\":" + jsonString(event.phase())
                    + ",\"source\":" + jsonString(event.source()) + ",\"product\":" + jsonString(event.product())
                    + ",\"podSize\":" + event.podSize() + ",\"participants\":" + event.participants() + "}";
        }
        private static String limitedCatalogJson() {
            return "{\"formats\":[\"SEALED\",\"BOOSTER_DRAFT\"],\"sources\":[\"FULL\",\"SET\",\"CUBE\"],"
                    + "\"limits\":{\"packs\":\"1-12\",\"podSize\":\"2-8\",\"pickTimerSeconds\":\"0-600\",\"disconnectGraceSeconds\":\"0-3600\"}}";
        }
        private String disconnectedJson() {
            List<DedicatedLobbyController.DisconnectedPlayerView> players = controller.disconnectedPlayerViews();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < players.size(); i++) {
                if (i > 0) { json.append(','); }
                DedicatedLobbyController.DisconnectedPlayerView player = players.get(i);
                json.append("{\"slot\":").append(player.slot()).append(",\"name\":")
                        .append(jsonString(player.name())).append(",\"reconnectSecondsRemaining\":");
                if (player.reconnectSecondsRemaining() == null) { json.append("null"); }
                else { json.append(player.reconnectSecondsRemaining()); }
                json.append('}');
            }
            return json.append(']').toString();
        }
        private String slotsJson() {
            List<DedicatedLobbyController.AiSlotView> slots = controller.aiSlotViews();
            StringBuilder json = new StringBuilder("{\"slots\":[");
            for (int i = 0; i < slots.size(); i++) {
                DedicatedLobbyController.AiSlotView slot = slots.get(i);
                if (i > 0) { json.append(','); }
                json.append("{\"slot\":").append(slot.slot()).append(",\"type\":\"").append(slot.type())
                        .append("\",\"name\":").append(jsonString(slot.name()))
                        .append(",\"team\":").append(slot.team());
                if (slot.deckId() != null) {
                    json.append(",\"deck\":").append(jsonString(slot.deckId()))
                            .append(",\"deckSource\":").append(jsonString(slot.deckId().startsWith("upload:") ? "upload" : "built_in"))
                            .append(",\"profile\":").append(jsonString(slot.profile()))
                            .append(",\"simulation\":").append(jsonString(slot.simulation()));
                }
                json.append('}');
            }
            return json.append("]}").toString();
        }
        private String decksJson() {
            List<BuiltInPreconCatalog.Entry> decks = controller.availableAiDecks();
            StringBuilder json = new StringBuilder("{\"decks\":[");
            for (int i = 0; i < decks.size(); i++) {
                if (i > 0) { json.append(','); }
                BuiltInPreconCatalog.Entry deck = decks.get(i);
                json.append("{\"id\":").append(jsonString(deck.id())).append(",\"name\":").append(jsonString(deck.name())).append('}');
            }
            return json.append("]}").toString();
        }
        private String profilesJson() {
            List<String> profiles = controller.availableAiProfiles();
            StringBuilder json = new StringBuilder("{\"profiles\":[");
            for (int i = 0; i < profiles.size(); i++) {
                if (i > 0) { json.append(','); }
                json.append(jsonString(profiles.get(i)));
            }
            return json.append("],\"simulations\":[\"NONE\",\"HYBRID\",\"FULL\"]}").toString();
        }
        private static String jsonString(String value) {
            if (value == null) { return "null"; }
            StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append("\\u").append(Character.forDigit((ch >>> 12) & 0xf, 16))
                                .append(Character.forDigit((ch >>> 8) & 0xf, 16))
                                .append(Character.forDigit((ch >>> 4) & 0xf, 16))
                                .append(Character.forDigit(ch & 0xf, 16));
                    } else { escaped.append(ch); }
                }
                }
            }
            return escaped.append('"').toString();
        }
        private static String settingsJson(ServerConfig.LobbyRules rules) {
            String variants = rules.variants().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
            return "{\"mode\":\"" + rules.mode() + "\",\"variants\":\"" + variants + "\",\"gamesPerMatch\":"
                    + rules.gamesPerMatch() + ",\"commanderBracket\":" + rules.commanderBracket()
                    + ",\"enforceDeckLegality\":" + rules.enforceDeckLegality()
                    + ",\"manaBurn\":" + rules.manaBurn()
                    + ",\"legacyOrderCombatants\":" + rules.legacyOrderCombatants()
                    + ",\"filteredHands\":" + rules.filteredHands()
                    + ",\"aiTimeoutSeconds\":" + rules.aiTimeoutSeconds() + "}";
        }
        private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
            write(response, status, "{\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
        }
        private static void write(HttpServletResponse response, int status, String json) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json);
        }
    }
}
