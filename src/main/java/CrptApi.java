import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Класс для работы с API Честного ЗНАК (ГИС МТ).
 * Предназначен для создания документов, включая ввод в оборот товаров, произведённых в РФ.
 *
 */
public class CrptApi {

    private static final Logger logger = Logger.getLogger(CrptApi.class.getName());

    // Базовый URL для создания документов
//    private static final String BASE_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private final String baseUrl;

    // Товарная группа по умолчанию (можно задать через setter)
    private String productGroup = "milk"; // например, milk, shoes, tobacco и т.д.

    private final int requestLimit;
    private final long intervalMillis;
    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String authToken;

    public CrptApi(String baseUrl, TimeUnit timeUnit, int requestLimit) {
        this.baseUrl = baseUrl;
        if (timeUnit == null) {
            throw new IllegalArgumentException("timeUnit cannot be null");
        }
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit must be positive");
        }

        this.requestLimit = requestLimit;
        this.intervalMillis = timeUnit.toMillis(1);
        this.rateLimiter = new RateLimiter(requestLimit, intervalMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Устанавливает токен авторизации.
     * Должен быть вызван до первого вызова createDocument().
     *
     * @param authToken Bearer-токен
     */
    public void setAuthToken(String authToken) {
        if (authToken == null || authToken.trim().isEmpty()) {
            throw new IllegalArgumentException("authToken cannot be null or empty");
        }
        this.authToken = authToken.strip();
    }

    /**
     * Устанавливает товарную группу (pg), например: "milk", "shoes", "tobacco".
     *
     * @param productGroup товарная группа
     */
    public void setProductGroup(String productGroup) {
        if (productGroup == null || productGroup.trim().isEmpty()) {
            throw new IllegalArgumentException("productGroup cannot be null or empty");
        }
        this.productGroup = productGroup.trim();
    }

    /**
     * Создаёт документ для ввода в оборот товара, произведённого в РФ.
     *
     * @param document  Java-объект, представляющий содержимое документа
     * @param signature Подпись в формате Base64
     * @throws IOException          при сетевых ошибках
     * @throws InterruptedException если поток был прерван
     */
    public void createDocument(Object document, String signature) throws IOException, InterruptedException {
        // Проверяем авторизацию
        if (this.authToken == null) {
            throw new IllegalStateException("authToken is not set. Call setAuthToken() before using the API.");
        }

        rateLimiter.acquire();

        DocumentRequest requestDto = new DocumentRequest("LP_INTRODUCE_GOODS", "MANUAL", document, signature);
        String jsonBody;


        try {
            jsonBody = objectMapper.writeValueAsString(requestDto);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize document to JSON", e);
        }

        String url = this.baseUrl + "/api/v3/lk/documents/create?pg=" + this.productGroup;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "*/*")
                .header("Authorization", "Bearer " + this.authToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Выполняем запрос с повторными попытками
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    logger.info("Document created successfully. Status: " + statusCode);
                    return;
                } else if (statusCode >= 400 && statusCode < 500) {
                    String errorMsg = extractErrorMessage(response.body());
                    throw new IOException("Client error " + statusCode + ": " + errorMsg);
                } else if (statusCode >= 500) {
                    if (attempt < 3) {
                        long delay = 1000 * attempt;
                        logger.warning("Server error " + statusCode + ". Retrying in " + delay + " ms... (attempt " + attempt + "/3)");
                        Thread.sleep(delay);
                    } else {
                        throw new IOException("Server error after retries: " + statusCode);
                    }
                } else {
                    throw new IOException("Unexpected status code: " + statusCode);
                }
            } catch (IOException | InterruptedException e) {
                if (attempt == 3) {
                    logger.severe("Request failed after 3 attempts: " + e.getMessage());
                    throw e;
                }
                Thread.sleep(1000 * attempt);
            }
        }
    }

    /**
     * Извлекает сообщение об ошибке из тела ответа.
     */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return "No error details";
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = objectMapper.readValue(responseBody, java.util.Map.class);
            Object msg = map.get("error_message");
            return msg != null ? msg.toString() : responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }


    private record DocumentRequest(
            String type,
            String documentFormat,
            Object content,
            String signature
    ) {

    }

    // === Потокобезопасный Rate Limiter (Token Bucket) ===

    private static class RateLimiter {
        private final int maxRequests;
        private final long refillPeriodMs;
        private final AtomicInteger availableTokens;
        private volatile long lastRefillTimestamp;
        private final Lock lock = new ReentrantLock(true); // fair mode

        RateLimiter(int maxRequests, long refillPeriodMs) {
            this.maxRequests = maxRequests;
            this.refillPeriodMs = refillPeriodMs;
            this.availableTokens = new AtomicInteger(maxRequests);
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        void acquire() throws InterruptedException {
            while (true) {
                lock.lock();
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastRefillTimestamp > refillPeriodMs) {
                        availableTokens.set(maxRequests);
                        lastRefillTimestamp = now;
                    }

                    if (availableTokens.get() > 0) {
                        availableTokens.decrementAndGet();
                        return;
                    }
                } finally {
                    lock.unlock();
                }
                Thread.onSpinWait();
            }
        }
    }
}
