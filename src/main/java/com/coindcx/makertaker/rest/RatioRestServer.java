package com.coindcx.makertaker.rest;

import com.coindcx.makertaker.model.TimeWindow;
import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RatioRestServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RatioRestServer.class);
    private static final Pattern USER_RATIO_PATH = Pattern.compile("/api/v1/users/(\\d+)/ratio");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final HttpServer server;
    private final InMemoryRatioStore store;

    public RatioRestServer(int port, InMemoryRatioStore store) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/api/v1/users/", this::handleRequest);
        this.server.createContext("/health", this::handleHealth);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
        log.info("REST server started on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(2);
        log.info("REST server stopped");
    }

    @Override
    public void close() {
        stop();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of("status", "ok"));
    }

    void handleRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Matcher matcher = USER_RATIO_PATH.matcher(path);
        if (!matcher.matches()) {
            sendJson(exchange, 404, Map.of("error", "Not found"));
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid userId"));
            return;
        }

        Map<String, String> queryParams = parseQuery(exchange.getRequestURI());
        String windowParam = queryParams.get("window");
        String symbolParam = queryParams.get("symbol");

        UserSymbolState state;
        if (symbolParam != null && !symbolParam.isEmpty()) {
            state = store.getPerSymbol(userId, symbolParam);
        } else {
            state = store.getOverall(userId);
        }

        if (state == null) {
            sendJson(exchange, 404, Map.of("error", "User not found"));
            return;
        }

        if (windowParam != null && !windowParam.isEmpty()) {
            handleSingleWindow(exchange, state, windowParam, symbolParam);
        } else {
            handleAllWindows(exchange, state, symbolParam);
        }
    }

    private void handleSingleWindow(HttpExchange exchange, UserSymbolState state,
                                     String windowLabel, String symbol) throws IOException {
        TimeWindow window;
        try {
            window = TimeWindow.fromLabel(windowLabel);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid window: " + windowLabel));
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", state.getUserId());
        response.put("symbol", symbol);
        response.put("window", window.label());
        response.put("makerCount", state.getMakerCount(window));
        response.put("takerCount", state.getTakerCount(window));
        response.put("ratio", state.getRatio(window));
        response.put("lastUpdated", state.getLastUpdatedTimestamp());
        sendJson(exchange, 200, response);
    }

    private void handleAllWindows(HttpExchange exchange, UserSymbolState state,
                                   String symbol) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", state.getUserId());
        response.put("symbol", symbol);

        Map<String, Object> windows = new LinkedHashMap<>();
        for (TimeWindow tw : TimeWindow.values()) {
            Map<String, Object> windowData = new LinkedHashMap<>();
            windowData.put("makerCount", state.getMakerCount(tw));
            windowData.put("takerCount", state.getTakerCount(tw));
            windowData.put("ratio", state.getRatio(tw));
            windows.put(tw.label(), windowData);
        }
        response.put("windows", windows);
        response.put("lastUpdated", state.getLastUpdatedTimestamp());
        sendJson(exchange, 200, response);
    }

    static void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        String json = GSON.toJson(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }
}
