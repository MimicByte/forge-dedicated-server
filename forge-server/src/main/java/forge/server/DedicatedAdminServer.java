package forge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
            default -> error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
            }
        }

        @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!authorized(request, response)) { return; }
            if (!"/settings".equals(request.getPathInfo())) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown endpoint");
                return;
            }
            final ServerConfig.LobbyRules rules;
            try { rules = parseRules(readBody(request)); }
            catch (IllegalArgumentException e) { error(response, 422, "invalid_settings", e.getMessage()); return; }
            if (!controller.updateRules(rules)) {
                error(response, HttpServletResponse.SC_CONFLICT, "lobby_not_waiting", "Settings may only change while the lobby is waiting.");
                return;
            }
            write(response, HttpServletResponse.SC_OK, settingsJson(rules));
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
            String trimmed = json.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) { throw new IllegalArgumentException("Expected a JSON object"); }
            Map<String, String> values = new LinkedHashMap<>();
            Matcher matcher = MEMBER.matcher(trimmed);
            int cursor = 1;
            while (matcher.find()) {
                if (!trimmed.substring(cursor, matcher.start()).trim().matches(",?")) { throw new IllegalArgumentException("Malformed JSON settings object"); }
                if (values.put(matcher.group(1), matcher.group(2)) != null) { throw new IllegalArgumentException("Duplicate setting " + matcher.group(1)); }
                cursor = matcher.end();
            }
            if (!trimmed.substring(cursor, trimmed.length() - 1).trim().isEmpty() || values.size() != 5) {
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
                    + ",\"seats\":" + controller.seatCapacity() + ",\"settings\":" + settingsJson(controller.rules()) + "}";
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
