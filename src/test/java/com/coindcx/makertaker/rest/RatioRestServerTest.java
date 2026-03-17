package com.coindcx.makertaker.rest;

import com.coindcx.makertaker.model.TimeWindow;
import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RatioRestServerTest {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final long USER_ID = 42L;
    private static final long UNKNOWN_USER_ID = 99999L;
    private static final String SYMBOL = "BTC-USDT";

    private RatioRestServer server;
    private InMemoryRatioStore store;
    private int port;

    @BeforeAll
    void startServer() throws IOException {
        store = new InMemoryRatioStore();
        populateTestData();

        port = findFreePort();
        server = new RatioRestServer(port, store);
        server.start();
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private void populateTestData() {
        long now = System.currentTimeMillis();
        UserSymbolState overall = store.getOrCreateOverall(USER_ID);
        for (int i = 0; i < 10; i++) {
            overall.record(now - i * 1000L, i % 3 == 0);
        }

        UserSymbolState perSymbol = store.getOrCreatePerSymbol(USER_ID, SYMBOL);
        for (int i = 0; i < 5; i++) {
            perSymbol.record(now - i * 1000L, i % 2 == 0);
        }
    }

    // --- /health ---

    @Test
    void healthEndpointReturns200WithOkStatus() throws IOException {
        HttpURLConnection conn = openConnection("/health");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals("ok", body.get("status"));
    }

    // --- GET /api/v1/users/{userId}/ratio (all windows) ---

    @Test
    void getAllWindowsForKnownUser() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio");
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/json", conn.getContentType());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals((double) USER_ID, body.get("userId"));
        assertNull(body.get("symbol"));
        assertNotNull(body.get("windows"));
        assertNotNull(body.get("lastUpdated"));

        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) body.get("windows");
        for (TimeWindow tw : TimeWindow.values()) {
            assertTrue(windows.containsKey(tw.label()), "Missing window: " + tw.label());
            @SuppressWarnings("unchecked")
            Map<String, Object> wData = (Map<String, Object>) windows.get(tw.label());
            assertNotNull(wData.get("makerCount"));
            assertNotNull(wData.get("takerCount"));
            assertNotNull(wData.get("ratio"));
        }
    }

    // --- GET /api/v1/users/{userId}/ratio?window=24H ---

    @Test
    void getSingleWindowForKnownUser() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=24H");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals((double) USER_ID, body.get("userId"));
        assertEquals("24H", body.get("window"));
        assertNotNull(body.get("makerCount"));
        assertNotNull(body.get("takerCount"));
        assertNotNull(body.get("ratio"));
        assertNotNull(body.get("lastUpdated"));
        assertNull(body.get("windows"));
    }

    @Test
    void getSingleWindow1H() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=1H");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals("1H", body.get("window"));
    }

    @Test
    void getSingleWindow7D() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=7D");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals("7D", body.get("window"));
    }

    @Test
    void getSingleWindow30D() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=30D");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals("30D", body.get("window"));
    }

    // --- GET /api/v1/users/{userId}/ratio?symbol=BTC-USDT ---

    @Test
    void getPerSymbolAllWindows() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?symbol=" + SYMBOL);
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals((double) USER_ID, body.get("userId"));
        assertEquals(SYMBOL, body.get("symbol"));
        assertNotNull(body.get("windows"));
    }

    @Test
    void getPerSymbolSingleWindow() throws IOException {
        HttpURLConnection conn = openConnection(
            "/api/v1/users/" + USER_ID + "/ratio?symbol=" + SYMBOL + "&window=1H");
        assertEquals(200, conn.getResponseCode());

        Map<String, Object> body = readJsonResponse(conn);
        assertEquals(SYMBOL, body.get("symbol"));
        assertEquals("1H", body.get("window"));
    }

    // --- error cases ---

    @Test
    void unknownUserReturns404() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + UNKNOWN_USER_ID + "/ratio");
        assertEquals(404, conn.getResponseCode());

        Map<String, Object> body = readErrorResponse(conn);
        assertEquals("User not found", body.get("error"));
    }

    @Test
    void unknownSymbolForKnownUserReturns404() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?symbol=DOGE-BTC");
        assertEquals(404, conn.getResponseCode());

        Map<String, Object> body = readErrorResponse(conn);
        assertEquals("User not found", body.get("error"));
    }

    @Test
    void invalidWindowReturns400() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=INVALID");
        assertEquals(400, conn.getResponseCode());

        Map<String, Object> body = readErrorResponse(conn);
        String error = (String) body.get("error");
        assertTrue(error.contains("Invalid window"));
        assertTrue(error.contains("INVALID"));
    }

    @Test
    void postMethodReturns405() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio");
        conn.setRequestMethod("POST");
        assertEquals(405, conn.getResponseCode());

        Map<String, Object> body = readErrorResponse(conn);
        assertEquals("Method not allowed", body.get("error"));
    }

    @Test
    void putMethodReturns405() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio");
        conn.setRequestMethod("PUT");
        assertEquals(405, conn.getResponseCode());
    }

    @Test
    void nonMatchingPathReturns404() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/notanumber/ratio");
        assertEquals(404, conn.getResponseCode());

        Map<String, Object> body = readErrorResponse(conn);
        assertEquals("Not found", body.get("error"));
    }

    @Test
    void pathWithoutRatioSuffixReturns404() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/42/something");
        assertEquals(404, conn.getResponseCode());
    }

    // --- parseQuery utility ---

    @Test
    void parseQueryWithNoParams() {
        Map<String, String> result = RatioRestServer.parseQuery(URI.create("/test"));
        assertTrue(result.isEmpty());
    }

    @Test
    void parseQueryWithEmptyQueryString() {
        Map<String, String> result = RatioRestServer.parseQuery(URI.create("/test?"));
        assertTrue(result.isEmpty());
    }

    @Test
    void parseQueryWithSingleParam() {
        Map<String, String> result = RatioRestServer.parseQuery(URI.create("/test?window=1H"));
        assertEquals(1, result.size());
        assertEquals("1H", result.get("window"));
    }

    @Test
    void parseQueryWithMultipleParams() {
        Map<String, String> result = RatioRestServer.parseQuery(
            URI.create("/test?window=1H&symbol=BTC-USDT"));
        assertEquals(2, result.size());
        assertEquals("1H", result.get("window"));
        assertEquals("BTC-USDT", result.get("symbol"));
    }

    @Test
    void parseQueryIgnoresKeyOnlyParams() {
        Map<String, String> result = RatioRestServer.parseQuery(URI.create("/test?keyonly&a=b"));
        assertEquals(1, result.size());
        assertEquals("b", result.get("a"));
    }

    @Test
    void parseQueryWithValueContainingEquals() {
        Map<String, String> result = RatioRestServer.parseQuery(URI.create("/test?key=val=ue"));
        assertEquals("val=ue", result.get("key"));
    }

    // --- response shape verification ---

    @Test
    void allWindowsResponseContainsAllFourWindows() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio");
        Map<String, Object> body = readJsonResponse(conn);

        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) body.get("windows");
        assertEquals(4, windows.size());
        assertTrue(windows.containsKey("1H"));
        assertTrue(windows.containsKey("24H"));
        assertTrue(windows.containsKey("7D"));
        assertTrue(windows.containsKey("30D"));
    }

    @Test
    void singleWindowResponseDoesNotContainWindowsMap() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=1H");
        Map<String, Object> body = readJsonResponse(conn);
        assertFalse(body.containsKey("windows"));
        assertTrue(body.containsKey("window"));
    }

    @Test
    void ratioValuesArePlausible() throws IOException {
        HttpURLConnection conn = openConnection("/api/v1/users/" + USER_ID + "/ratio?window=1H");
        Map<String, Object> body = readJsonResponse(conn);

        double ratio = ((Number) body.get("ratio")).doubleValue();
        assertTrue(ratio >= 0.0 && ratio <= 1.0,
            "ratio should be between 0 and 1 but was " + ratio);

        double makerCount = ((Number) body.get("makerCount")).doubleValue();
        double takerCount = ((Number) body.get("takerCount")).doubleValue();
        assertTrue(makerCount >= 0);
        assertTrue(takerCount >= 0);
    }

    // --- helpers ---

    private HttpURLConnection openConnection(String path) throws IOException {
        URI uri = URI.create("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private Map<String, Object> readJsonResponse(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(json, MAP_TYPE);
        }
    }

    private Map<String, Object> readErrorResponse(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getErrorStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(json, MAP_TYPE);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
