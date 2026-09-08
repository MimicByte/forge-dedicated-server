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
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/** Small private management surface. It deliberately has no game-control endpoints. */
public final class DedicatedAdminServer {
    private final Server server;

    public DedicatedAdminServer(ServerConfig config, DedicatedLobbyController controller) {
        server = new Server(config.adminPort());
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ApiServlet(config.adminToken(), controller)), "/v1/*");
        server.setHandler(context);
    }
    public void start() throws Exception { server.start(); }
    public void stop() throws Exception { server.stop(); }

    private static final class ApiServlet extends HttpServlet {
        private static final int MAX_BODY_BYTES = 16 * 1024;
        private static final Pattern MEMBER = Pattern.compile("\\\"([A-Za-z][A-Za-z0-9]*)\\\"\\s*:\\s*(\\\"[^\\\"\\\\]*\\\"|true|false|-?[0-9]+)");
        private static final Pattern AI_SLOT_PATH = Pattern.compile("/slots/(\\d+)/ai");
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
            default -> error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
            }
        }

        @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            if ("/settings".equals(request.getPathInfo())) {
                updateRules(request, response);
                return;
            }
            Matcher path = AI_SLOT_PATH.matcher(request.getPathInfo());
            if (!path.matches()) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
                return;
            }
            updateAiSlot(Integer.parseInt(path.group(1)), request, response);
        }

        @Override protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            Matcher path = AI_SLOT_PATH.matcher(request.getPathInfo());
            if (!path.matches()) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
                return;
            }
            writeAiResult(response, controller.removeAiSlot(Integer.parseInt(path.group(1))));
        }

        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
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

        private void updateAiSlot(int slot, HttpServletRequest request, HttpServletResponse response) throws IOException {
            final Map<String, String> values;
            try { values = parseObject(readBody(request), 4); }
            catch (IllegalArgumentException e) { error(response, 422, "invalid_ai", e.getMessage()); return; }
            final DedicatedLobbyController.AiSlotConfiguration configuration;
            try {
                configuration = new DedicatedLobbyController.AiSlotConfiguration(slot,
                        string(values, "name"), string(values, "deck"), string(values, "profile"),
                        string(values, "simulation").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) { error(response, 422, "invalid_ai", e.getMessage()); return; }
            writeAiResult(response, controller.updateAiSlot(configuration));
        }

        private static void writeAiResult(HttpServletResponse response, DedicatedLobbyController.AiResult result) throws IOException {
            if (result.success()) { write(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\"}"); }
            else { error(response, HttpServletResponse.SC_CONFLICT, result.code(), result.message()); }
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
            Map<String, String> values = parseObject(json, 5);
            if (values.size() != 5) {
                throw new IllegalArgumentException("Provide mode, variants, gamesPerMatch, commanderBracket, and enforceDeckLegality");
            }
            try {
                ServerConfig.Mode mode = ServerConfig.Mode.valueOf(string(values, "mode").toUpperCase(java.util.Locale.ROOT));
                EnumSet<ServerConfig.Variant> variants = EnumSet.noneOf(ServerConfig.Variant.class);
                String variantText = string(values, "variants");
                if (!variantText.isBlank()) for (String value : variantText.split(",")) {
                    variants.add(ServerConfig.Variant.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
                }
                return new ServerConfig.LobbyRules(mode, variants, integer(values, "gamesPerMatch"),
                        integer(values, "commanderBracket"), bool(values, "enforceDeckLegality"));
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
                    + ",\"disconnected\":" + disconnectedJson() + "}";
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
                        .append("\",\"name\":").append(jsonString(slot.name()));
                if (slot.deckId() != null) {
                    json.append(",\"deck\":").append(jsonString(slot.deckId()))
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
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        private static String settingsJson(ServerConfig.LobbyRules rules) {
            String variants = rules.variants().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
            return "{\"mode\":\"" + rules.mode() + "\",\"variants\":\"" + variants + "\",\"gamesPerMatch\":"
                    + rules.gamesPerMatch() + ",\"commanderBracket\":" + rules.commanderBracket()
                    + ",\"enforceDeckLegality\":" + rules.enforceDeckLegality() + "}";
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
