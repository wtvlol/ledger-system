package com.example.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.ledger.LedgerApplication;
import com.example.ledger.concurrency.WriteQueue;
import com.example.ledger.config.LedgerProperties;
import com.example.ledger.persistence.SqliteDatabase;
import com.example.ledger.service.LedgerService;

import tools.jackson.databind.json.JsonMapper;

class HttpIntegrationTest {
    @TempDir private Path temporaryDirectory;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Verifies the HTTP API, exact financial transport, and delivery of the barebones frontend.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void serve_fullApiAndFrontend_correctResponses() throws Exception {
        try (ConfigurableApplicationContext context = runWeb(false)) {
            String base = getBaseUrl(context);
            assertTrue(get(base + "/").body().contains("Internal ledger"));
            assertTrue(get(base + "/app.js").body().contains("localStorage"));
            assertEquals(30, json.readTree(get(base + "/accounts").body()).size());
            assertEquals(2, json.readTree(get(base + "/users").body()).size());
            var alice = json.readTree(get(base + "/users/alice").body());
            assertEquals("Alice", alice.get("name").asString());
            assertEquals(15, alice.get("accounts").size());
            assertEquals("alice", alice.get("accounts").get(0).get("userId").asString());
            assertEquals(404, get(base + "/users/absent").statusCode());
            assertEquals(400, get(base + "/users/bad!").statusCode());
            var configuration = json.readTree(get(base + "/configuration").body());
            assertEquals("SQLITE", configuration.get("rateSource").asString());
            assertEquals(15, configuration.get("currencies").size());
            assertEquals(210, configuration.get("rates").size());
            assertEquals("1.350000", configuration.get("rates").get("USD-SGD").asString());
            assertTrue(get(base + "/").body().contains("id=\"exchange-rates\""));
            HttpResponse<String> result =
                    post(
                            base + "/transactions",
                            "key",
                            "{\"sourceAccount\":\"account-01\","
                                    + "\"destinationAccount\":\"account-02\",\"amount\":\"10\"}");
            assertEquals(200, result.statusCode(), result.body());
            String id = json.readTree(result.body()).get("transactionId").asString();
            HttpResponse<String> retry =
                    post(
                            base + "/transactions",
                            "key",
                            "{\"sourceAccount\":\"account-01\","
                                    + "\"destinationAccount\":\"account-02\",\"amount\":\"10.00\"}");
            assertEquals(json.readTree(result.body()), json.readTree(retry.body()));
            assertEquals(
                    "990.00",
                    json.readTree(get(base + "/accounts/account-01").body())
                            .get("balance")
                            .asString());
            assertEquals(
                    1,
                    json.readTree(get(base + "/accounts/account-01/transactions").body())
                            .get("items")
                            .size());
            assertEquals(
                    400,
                    post(
                                    base + "/transactions",
                                    "number",
                                    "{\"sourceAccount\":\"account-01\","
                                            + "\"destinationAccount\":\"account-02\",\"amount\":10}")
                            .statusCode());
            assertEquals(
                    400,
                    post(
                                    base + "/transactions",
                                    null,
                                    "{\"sourceAccount\":\"account-01\","
                                            + "\"destinationAccount\":\"account-02\",\"amount\":\"1\"}")
                            .statusCode());
            assertEquals(
                    400, get(base + "/accounts/account-01/transactions?limit=bad").statusCode());
            assertEquals(404, get(base + "/accounts/absent").statusCode());
            HttpResponse<String> missing = get(base + "/missing-resource.js");
            assertEquals(404, missing.statusCode());
            assertEquals("NOT_POSTED", json.readTree(missing.body()).get("outcome").asString());
            assertEquals(405, post(base + "/accounts/account-01", null, null).statusCode());
            assertEquals(
                    200,
                    post(base + "/transactions/" + id + "/reversal", "reverse", null).statusCode());
            assertEquals(
                    "OK",
                    json.readTree(get(base + "/reconciliation").body()).get("status").asString());
            assertEquals(200, post(base + "/month-closes/2026-09", null, null).statusCode());
            assertEquals(
                    "OK",
                    json.readTree(get(base + "/month-closes/2026-09/comparison").body())
                            .get("status")
                            .asString());
            assertEquals(1, json.readTree(get(base + "/month-closes").body()).size());
            assertEquals(
                    "HALF_EVEN",
                    json.readTree(get(base + "/configuration").body())
                            .get("roundingPolicy")
                            .asString());
        }
    }

    /**
     * Verifies bounded HTTP waiting and original-key recovery after an unknown timeout outcome.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void serve_responseTimeoutAndWaiterLimit_sameKeyResolvesCommittedOutcome() throws Exception {
        BlockingConfiguration.started = new CountDownLatch(1);
        BlockingConfiguration.release = new CountDownLatch(1);
        try (ConfigurableApplicationContext context = runWeb(true)) {
            String base = getBaseUrl(context);
            String body =
                    "{\"sourceAccount\":\"account-01\",\"destinationAccount\":\"account-02\",\"amount\":\"1\"}";
            var pending =
                    client.sendAsync(
                            request(base + "/transactions", "timeout-key", body),
                            HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(BlockingConfiguration.started.await(3, TimeUnit.SECONDS));
                HttpResponse<String> overload = post(base + "/transactions", "other", body);
                assertEquals(503, overload.statusCode());
                assertEquals(
                        "NOT_POSTED", json.readTree(overload.body()).get("outcome").asString());
                HttpResponse<String> timeout = pending.get(5, TimeUnit.SECONDS);
                assertEquals(503, timeout.statusCode(), timeout.body());
                assertEquals("UNKNOWN", json.readTree(timeout.body()).get("outcome").asString());
                assertEquals(
                        "1000.00",
                        json.readTree(get(base + "/accounts/account-01").body())
                                .get("balance")
                                .asString());
                BlockingConfiguration.release.countDown();
                HttpResponse<String> replay = post(base + "/transactions", "timeout-key", body);
                assertEquals(200, replay.statusCode(), replay.body());
                assertEquals(
                        1,
                        json.readTree(get(base + "/accounts/account-01/transactions").body())
                                .get("items")
                                .size());
            } finally {
                BlockingConfiguration.release.countDown();
            }
        }
    }

    /**
     * Verifies that an unsupported startup rounding policy prevents application initialization.
     */
    @Test
    void configure_invalidRoundingPolicy_startupFails() {
        assertThrows(
                Exception.class,
                () ->
                        new SpringApplicationBuilder(LedgerApplication.class)
                                .web(WebApplicationType.NONE)
                                .run(
                                        "--ledger.fx.rounding-policy=INVALID",
                                        "--ledger.database="
                                                + temporaryDirectory.resolve("invalid.db"),
                                        "--spring.main.banner-mode=off",
                                        "--logging.level.root=OFF"));
    }

    /**
     * Starts an isolated HTTP application with normal or deliberately blocked worker execution.
     *
     * @param isBlocking Whether to inject a debit hook that pauses the worker for timeout tests.
     * @return Application context that the test must close after verification.
     */
    private ConfigurableApplicationContext runWeb(boolean isBlocking) {
        Class<?>[] sources =
                isBlocking
                        ? new Class<?>[] {LedgerApplication.class, BlockingConfiguration.class}
                        : new Class<?>[] {LedgerApplication.class};
        return new SpringApplicationBuilder(sources)
                .run(
                        "--server.port=0",
                        "--ledger.database=" + temporaryDirectory.resolve("web.db"),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=ERROR",
                        "--ledger.response-timeout=" + (isBlocking ? "100ms" : "30s"),
                        "--ledger.max-waiters=" + (isBlocking ? "1" : "200"));
    }

    /**
     * Finds the local base URL of the application's ephemeral HTTP port.
     *
     * @param context Running application context exposing the ephemeral HTTP server port.
     * @return Local HTTP base URL containing the ephemeral port.
     */
    private static String getBaseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    /**
     * Sends a bounded GET request to the isolated ledger server.
     *
     * @param url Local HTTP endpoint served by the isolated application context.
     * @return Response status, headers, and body from the local GET request.
     * @throws Exception if the HTTP exchange fails, times out, or is interrupted.
     */
    private HttpResponse<String> get(String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a bounded POST request with an optional idempotency key and exact JSON body.
     *
     * @param url Local HTTP endpoint served by the isolated application context.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @param body Parsed HTTP request body containing exact string values.
     * @return Response status, headers, and body from the local POST request.
     * @throws Exception if the HTTP exchange fails, times out, or is interrupted.
     */
    private HttpResponse<String> post(String url, String key, String body) throws Exception {
        return client.send(request(url, key, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Builds a POST request without altering its exact body or idempotency key.
     *
     * @param url Local HTTP endpoint served by the isolated application context.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @param body Parsed HTTP request body containing exact string values.
     * @return Bounded POST request carrying the supplied exact body and optional key.
     */
    private HttpRequest request(String url, String key, String body) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingConfiguration {
        private static CountDownLatch started;
        private static CountDownLatch release;

        /**
         * Creates a test service whose post-debit hook blocks until the test releases it.
         *
         * @param database Owned SQLite database supplying configured reader and writer connections.
         * @param queue Bounded single-worker queue used for financial writes.
         * @param properties Startup configuration containing database settings and validated arithmetic
         *     limits.
         * @param clock Clock supplying posting timestamps and reconciliation cutoffs.
         * @return Service whose actual worker transaction can be paused after its debit.
         */
        @Bean
        @Primary
        LedgerService blockingService(
                SqliteDatabase database,
                WriteQueue queue,
                LedgerProperties properties,
                Clock clock) {
            return new LedgerService(
                    database,
                    queue,
                    properties,
                    clock,
                    id -> {
                        started.countDown();
                        release.await();
                    });
        }
    }
}
