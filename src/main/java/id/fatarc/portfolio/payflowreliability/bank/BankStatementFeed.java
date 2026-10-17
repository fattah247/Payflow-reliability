package id.fatarc.portfolio.payflowreliability.bank;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Downloads statements from the bank's statement API: the accounts, the statements after a sequence number, each file. */
@Component
public class BankStatementFeed {
    private final RestClient.Builder builder;
    private final BankProperties properties;
    private final LocalServer localServer;
    private volatile RestClient client;

    public BankStatementFeed(RestClient.Builder builder, BankProperties properties, LocalServer localServer) {
        this.builder = builder;
        this.properties = properties;
        this.localServer = localServer;
    }

    public record Listed(String accountId, String statementId, long sequenceNumber) {
    }

    public List<String> accounts() {
        JsonNode accounts = client().get().uri("/v1/accounts").retrieve().body(JsonNode.class);
        List<String> ids = new ArrayList<>();
        accounts.forEach(account -> ids.add(account.path("accountId").asText()));
        return ids;
    }

    public List<Listed> statementsAfter(String accountId, long afterSequence) {
        JsonNode statements = client().get().uri("/v1/accounts/{account}/statements?afterSequence={after}", accountId, afterSequence)
                .retrieve().body(JsonNode.class);
        List<Listed> listed = new ArrayList<>();
        statements.forEach(statement -> listed.add(new Listed(accountId, statement.path("statementId").asText(),
                statement.path("sequenceNumber").asLong())));
        return listed;
    }

    public byte[] download(String statementId) {
        String xml = client().get().uri("/v1/statements/{id}", statementId).retrieve().body(String.class);
        return xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8);
    }

    private RestClient client() {
        RestClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
                            ? localServer.baseUrl() + "/bank-sim"
                            : properties.baseUrl();
                    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
                    requestFactory.setReadTimeout(properties.readTimeout());
                    client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory)
                            .defaultHeader("Authorization", "Bearer " + properties.apiKey()).build();
                }
                current = client;
            }
        }
        return current;
    }
}
