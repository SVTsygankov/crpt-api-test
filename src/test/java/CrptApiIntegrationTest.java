import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import static java.util.concurrent.TimeUnit.SECONDS;

public class CrptApiIntegrationTest {

    private static WireMockServer server;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        // Запускаем WireMock на порту 8089
        server = new WireMockServer(WireMockConfiguration.options().port(8089));
        server.start();

        // Настраиваем заглушку для /api/v3/lk/documents/create?pg=milk
        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .withQueryParam("pg", equalTo("milk"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Должен успешно отправить документ при успешном ответе от API")
    void shouldCreateDocumentSuccessfully() throws Exception {
        // Arrange
        CrptApi api = new CrptApi("http://localhost:8089", SECONDS, 5);
        api.setAuthToken("test-token");
        api.setProductGroup("milk");

        var document = Map.of(
                "description", Map.of("participantInn", "7708004992"),
                "products", java.util.List.of(Map.of("code", "01..."))
        );
        String signature = "base64-signature";

        // Act & Assert
        Assertions.assertDoesNotThrow(() -> api.createDocument(document, signature));
    }

    @Test
    @DisplayName("Должен выбросить исключение при клиентской ошибке (400)")
    void shouldFailOnClientError() throws Exception {
        // Настроить заглушку на 400
        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .withQueryParam("pg", equalTo("milk"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"error_message\": \"Invalid document\" }")));

        CrptApi api = new CrptApi("http://localhost:8089", SECONDS, 5);
        api.setAuthToken("test-token");
        api.setProductGroup("milk");

        var document = Map.of();
        String signature = "sig";

        // Act & Assert
        Exception exception = Assertions.assertThrows(IOException.class, () -> {
            api.createDocument(document, signature);
        });
        Assertions.assertTrue(exception.getMessage().contains("Client error 400"));
    }

    @Test
    @DisplayName("Должен повторить запрос при серверной ошибке (503), затем succeed")
    void shouldRetryOnServerErrorThenSucceed() throws Exception {
        // Сначала 503 дважды, потом 200
        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .withQueryParam("pg", equalTo("milk"))
                .inScenario("Retry on 503")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("first-failed"));

        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .withQueryParam("pg", equalTo("milk"))
                .inScenario("Retry on 503")
                .whenScenarioStateIs("first-failed")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-failed"));

        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .withQueryParam("pg", equalTo("milk"))
                .inScenario("Retry on 503")
                .whenScenarioStateIs("second-failed")
                .willReturn(aResponse().withStatus(200)));

        CrptApi api = new CrptApi("http://localhost:8089", SECONDS, 5);
        api.setAuthToken("test-token");
        api.setProductGroup("milk");

        var document = Map.of("type", "LP_INTRODUCE_GOODS");
        String signature = "sig";

        // Act & Assert
        Assertions.assertDoesNotThrow(() -> api.createDocument(document, signature));
        // Проверяем, что было ровно 3 вызова
        server.verify(3, postRequestedFor(urlPathEqualTo("/api/v3/lk/documents/create")));
    }

    @Test
    @DisplayName("Должен соблюдать рейт-лимит: не более 2 запросов в секунду")
    void shouldRespectRateLimit() throws Exception {
        // Заглушка всегда 200
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/api/v3/lk/documents/create"))
                .willReturn(aResponse().withStatus(200)));

        CrptApi api = new CrptApi("http://localhost:8089", SECONDS, 2);api.setAuthToken("test-token");
        api.setProductGroup("milk");

        var document = Map.of();
        String signature = "sig";

        long start = System.currentTimeMillis();

        // Отправляем 5 запросов из разных потоков
        ExecutorService executor = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    api.createDocument(document, signature);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        }
        executor.shutdown();
        Assertions.assertTrue(executor.awaitTermination(10, SECONDS));

        long duration = System.currentTimeMillis() - start;

        // Ожидаем: минимум ~2500 мс (2 запроса за первые 1000 мс, ещё 2 за следующие, последний — позже)
        Assertions.assertTrue(duration >= 2000, "Rate limiting not respected: took only " + duration + " ms");
    }
}
