package id.fatarc.portfolio.payflowreliability.api;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every list an operator or a shop reads comes a page at a time: the body is the same JSON array as before, and the
 * next page is in {@code Link: rel="next"} and {@code X-Next-After}. Walking the pages must return every row once, in
 * order, even when rows arrive between pages.
 */
class PagingTest extends IntegrationTest {
    /** Follows the Link header from the first page to the last, collecting one field of every row. */
    private List<String> walk(String apiKey, Supplier<MockHttpServletRequestBuilder> first, String field) throws Exception {
        List<String> seen = new ArrayList<>();
        MockHttpServletRequestBuilder request = first.get();
        for (int page = 0; page < 50; page++) {
            MockHttpServletResponse response = mockMvc.perform(as(apiKey, request)).andExpect(status().isOk()).andReturn().getResponse();
            JsonNode rows = objectMapper.readTree(response.getContentAsString());
            rows.forEach(row -> seen.add(row.path(field).asText()));
            String link = response.getHeader("Link");
            if (link == null) {
                assertThat(response.getHeader("X-Next-After")).isNull();
                return seen;
            }
            assertThat(link).endsWith("; rel=\"next\"");
            String next = link.substring(1, link.indexOf('>'));
            assertThat(next).contains("after=" + response.getHeader("X-Next-After"));
            request = get(next.substring(next.indexOf("/api/")));
        }
        throw new AssertionError("more than 50 pages");
    }

    @Test
    void shouldWalkTheReviewQueueOldestFirstWithoutGapsOrRepeats() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PaymentIntent intent = startedIntent("ORDER-PAGE-R" + i, "1000");
            webhook(intent.getId(), "evt-page-" + i, "tx-page-" + i, ProviderStatus.SUCCESS, "1100");
            expected.add(reviewWorkflowService.list(java.util.Set.of(id.fatarc.portfolio.payflowreliability.review.ReviewStatus.OPEN),
                    null, intent.getId()).getFirst().id().toString());
            clock.advance(java.time.Duration.ofSeconds(1));
        }

        List<String> walked = walk(OPS_ANA_KEY, () -> get("/api/review-cases").param("limit", "2"), "id");

        assertThat(walked).containsExactlyElementsOf(expected);
    }

    @Test
    void shouldWalkPayoutsBankLinesStatementsAndProviderCalls() throws Exception {
        for (int i = 0; i < 3; i++) {
            paidIntent("ORDER-PAGE-B" + i, String.valueOf(1000 + i));
            settle(MERCHANT_A);
            clock.advance(java.time.Duration.ofSeconds(1));
        }
        landPayouts();

        List<String> batches = walk(OPS_ANA_KEY, () -> get("/api/settlements/batches").param("limit", "2"), "id");
        assertThat(batches).hasSize(3).doesNotHaveDuplicates();
        assertThat(walk(OPS_ANA_KEY, () -> get("/api/bank/lines").param("limit", "1"), "id")).hasSize(3).doesNotHaveDuplicates();
        assertThat(walk(OPS_ANA_KEY, () -> get("/api/bank/lines").param("status", "MATCHED").param("limit", "2"), "matchStatus"))
                .hasSize(3).containsOnly("MATCHED");
        assertThat(walk(OPS_ANA_KEY, () -> get("/api/bank/statements").param("limit", "1"), "statementId"))
                .hasSize(new LinkedHashSet<>(walk(OPS_ANA_KEY, () -> get("/api/bank/statements"), "statementId")).size())
                .doesNotHaveDuplicates();
        assertThat(walk(OPS_ANA_KEY, () -> get("/api/outbox").param("status", "SUCCEEDED").param("limit", "2"), "id"))
                .hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void shouldWalkBreaksAndAMerchantsReferenceLookup() throws Exception {
        bankFaults.inject(id.fatarc.portfolio.payflowreliability.banksim.BankFaults.Mode.DROP_CREDIT, 3, null, null);
        for (int i = 0; i < 3; i++) {
            paidIntent("ORDER-PAGE-M" + i, "2000");
            settle(MERCHANT_A);
        }
        drainOutbox();
        closeBankDays("IDR", settlementBatchRepository.findAll().getFirst().getExpectedValueDate().plusDays(2));
        importStatements();
        threeWayReconciliationService.run(OPS_ANA.name());
        for (int i = 0; i < 3; i++) {
            postIntent(merchantAKey, "page-ref-" + i, """
                    {"merchantReference": "ORDER-SAME-REF", "amount": %d, "currency": "IDR"}""".formatted(1000 + i));
            clock.advance(java.time.Duration.ofSeconds(1));
        }

        assertThat(walk(OPS_ANA_KEY, () -> get("/api/reconciliation/breaks").param("status", "OPEN").param("limit", "2"), "category"))
                .hasSize(3).containsOnly("MISSING_CREDIT");
        assertThat(walk(merchantAKey, () -> get("/api/payment-intents").param("merchantReference", "ORDER-SAME-REF").param("limit", "2"), "amount"))
                .as("newest first").containsExactly("1002.0", "1001.0", "1000.0");
    }

    @Test
    void shouldRefuseACursorItDidNotIssue() throws Exception {
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/review-cases").param("after", "not-a-cursor")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/worklist").param("after", "bWFkZS11cA")))
                .andExpect(status().isBadRequest());
    }
}
