package id.fatarc.portfolio.payflowreliability.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider API over HTTP with explicit connect and read timeouts. Built from Spring's RestClient
 * builder, so calls are traced and timed like every other HTTP request.
 *
 * <p>Classification: 2xx with SUCCEEDED is success; 2xx with FAILED is a refusal of the operation; other
 * 4xx responses are refusals of the request; timeouts, connection errors, 401/403 (our credentials, not
 * the operation), 408/409/429 and 5xx are unknown outcomes. A lookup by idempotency key is definite only
 * when it answers 2xx (found) or 404 (never received); anything else is unknown.
 */
@Component
public class HttpPaymentProviderClient implements PaymentProviderClient {
    private final RestClient.Builder builder;
    private final ProviderProperties properties;
    private final LocalServer localServer;
    private volatile RestClient client;

    public HttpPaymentProviderClient(RestClient.Builder builder, ProviderProperties properties, LocalServer localServer) {
        this.builder = builder;
        this.properties = properties;
        this.localServer = localServer;
    }

    @Override
    public ProviderResult refund(String idempotencyKey, String providerTransactionId, BigDecimal amount, String currency,
                                 String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerTransactionId", providerTransactionId);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        return send("/v1/refunds", idempotencyKey, body);
    }

    @Override
    public ProviderResult reverse(String idempotencyKey, String providerTransactionId, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerTransactionId", providerTransactionId);
        body.put("reference", reference);
        return send("/v1/reversals", idempotencyKey, body);
    }

    @Override
    public Optional<ProviderResult> findRefund(String idempotencyKey) {
        return lookup("/v1/refunds/{key}", idempotencyKey);
    }

    @Override
    public Optional<ProviderResult> findReversal(String idempotencyKey) {
        return lookup("/v1/reversals/{key}", idempotencyKey);
    }

    @Override
    public Optional<ProviderPaymentStatus> paymentStatus(UUID paymentIntentId) {
        try {
            JsonNode node = client().get().uri("/v1/payments/{id}", paymentIntentId).retrieve().body(JsonNode.class);
            return Optional.of(new ProviderPaymentStatus(paymentIntentId, node.path("providerTransactionId").asText(),
                    node.path("status").asText(), node.path("amount").decimalValue(), node.path("currency").asText()));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new ProviderUnavailableException("Provider returned " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("Provider unreachable: " + ex.getMessage(), ex);
        }
    }

    private ProviderResult send(String path, String idempotencyKey, Map<String, Object> body) {
        try {
            JsonNode node = client().post().uri(path)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return interpret(node);
        } catch (RestClientResponseException ex) {
            return classify(ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            return new ProviderResult.Unknown("No response from provider: " + ex.getMessage());
        } catch (RestClientException ex) {
            return new ProviderResult.Unknown("Provider call failed: " + ex.getMessage());
        }
    }

    private Optional<ProviderResult> lookup(String path, String idempotencyKey) {
        try {
            return Optional.of(interpret(client().get().uri(path, idempotencyKey).retrieve().body(JsonNode.class)));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            return Optional.of(new ProviderResult.Unknown("Provider lookup returned " + ex.getStatusCode().value()));
        } catch (RestClientException ex) {
            return Optional.of(new ProviderResult.Unknown("Provider lookup failed: " + ex.getMessage()));
        }
    }

    private static ProviderResult interpret(JsonNode node) {
        String status = node.path("status").asText();
        return switch (status) {
            case "SUCCEEDED" -> new ProviderResult.Succeeded(node.path("id").asText(), node.path("amount").decimalValue());
            case "FAILED" -> new ProviderResult.Rejected(node.path("failureReason").asText("refused by provider"), true);
            default -> new ProviderResult.Unknown("Unexpected provider status: " + status);
        };
    }

    private static ProviderResult classify(HttpStatusCode status, String body) {
        int code = status.value();
        if (code == 401 || code == 403 || code == 408 || code == 409 || code == 429 || status.is5xxServerError()) {
            return new ProviderResult.Unknown("Provider returned " + code);
        }
        return new ProviderResult.Rejected("Provider returned " + code + ": " + body, false);
    }

    private RestClient client() {
        RestClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
                            ? localServer.baseUrl() + "/provider-sim"
                            : properties.baseUrl();
                    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
                    requestFactory.setReadTimeout(properties.readTimeout());
                    client = builder.clone()
                            .baseUrl(baseUrl)
                            .requestFactory(requestFactory)
                            .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                            .build();
                }
                current = client;
            }
        }
        return current;
    }
}
