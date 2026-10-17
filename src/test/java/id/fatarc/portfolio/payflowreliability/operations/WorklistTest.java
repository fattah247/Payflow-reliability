package id.fatarc.portfolio.payflowreliability.operations;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One queue of everything waiting for a person, across review cases, proposals and reconciliation breaks: most urgent
 * first, with an owner, the money, a due time, the next step and the calls that resolve each item. The test follows
 * the worklist's own instructions to resolve an item, so the calls it hands out are proven to work.
 */
class WorklistTest extends IntegrationTest {
    @Autowired
    private WorklistService worklistService;

    private SettlementBatch referenceless;
    private SettlementBatch dropped;

    /** A held payment, a reversal waiting for a second operator, a credit without a reference, and a credit that never came. */
    private void aBusyMorning() {
        PaymentIntent held = startedIntent("WL-HOLD", "4000");
        webhook(held.getId(), "evt-wl-hold", "tx-wl-hold", ProviderStatus.SUCCESS, "4100");

        PaymentIntent chargedBack = settledIntent("WL-CB", "60000");
        drainOutbox();
        providerSimulator.chargeback(chargedBack.getId(), null);
        deliverWebhooks();
        var reversalCase = reviewWorkflowService.list(ReviewStatus.UNRESOLVED, null, chargedBack.getId()).getFirst();
        reviewWorkflowService.resolve(reversalCase.id(), new ResolveReviewCaseRequest("Chargeback evidence is valid",
                ReviewAction.REQUIRE_REVERSAL), OPS_ANA);

        bankFaults.inject(BankFaults.Mode.MISSING_REFERENCE, 1, null, null);
        paidIntent("WL-NOREF", "3000");
        referenceless = settle(MERCHANT_A);
        drainOutbox();
        bankFaults.inject(BankFaults.Mode.DROP_CREDIT, 1, null, null);
        paidIntent(MERCHANT_B, "WL-DROP", "5000", "IDR");
        dropped = settle(MERCHANT_B);
        drainOutbox();
        closeBankDays("IDR", reload(dropped).getExpectedValueDate().plusDays(2));
        importStatements();
        threeWayReconciliationService.run(OPS_ANA.name());
    }

    private static List<String> titles(WorklistService.Worklist worklist) {
        List<String> titles = new ArrayList<>();
        worklist.items().forEach(item -> titles.add(item.kind() + ": " + item.title()));
        return titles;
    }

    @Test
    void shouldPutEverythingWaitingForAPersonInOneQueueMostUrgentFirst() {
        aBusyMorning();

        WorklistService.Worklist worklist = worklistService.worklist(null, null);

        assertThat(titles(worklist)).as("due order: a proposal within 1 h, review cases within 4 h, breaks by category")
                .first().asString().startsWith("APPROVAL: Approve REQUIRE_REVERSAL");
        assertThat(worklist.items()).extracting(WorklistService.Item::dueAt).isSorted();
        assertThat(titles(worklist)).anyMatch(title -> title.startsWith("REVIEW_CASE: Amount mismatch on a payment of 4000.00 IDR"))
                .anyMatch(title -> title.startsWith("BREAK: Missing credit of 4985.00 IDR"))
                .anyMatch(title -> title.startsWith("BREAK: Unmatched credit of 2991.00 IDR"));
        assertThat(worklist.summary().byOwner().get(WorklistService.Owner.TREASURY)).as("two missing credits and the unmatched one")
                .isEqualTo(3);
        assertThat(worklist.summary().overdue()).isZero();

        WorklistService.Item approval = worklist.items().getFirst();
        assertThat(approval.owner()).isEqualTo(WorklistService.Owner.OPERATIONS);
        assertThat(approval.money()).isEqualByComparingTo("60000");
        assertThat(approval.actions()).allSatisfy(action -> {
            assertThat(action.secondOperator()).isTrue();
            assertThat(action.notBy()).as("the proposer may not approve").isEqualTo("operator:ops-ana");
        });

        WorklistService.Item unmatched = worklist.items().stream().filter(item -> item.title().startsWith("Unmatched credit")).findFirst().orElseThrow();
        assertThat(unmatched.heldInSuspense()).isEqualByComparingTo("2991.00");
        assertThat(unmatched.suggestion()).as("one payout expects exactly this amount")
                .contains(reload(referenceless).getPayoutReference()).contains("Confirm with the bank");
        assertThat(unmatched.actions()).singleElement().satisfies(action ->
                assertThat(action.body()).containsEntry("action", "ASSIGN_TO_BATCH").containsEntry("batchId", referenceless.getId()));
        assertThat(unmatched.evidence()).extracting(WorklistService.Link::path).anyMatch(path -> path.startsWith("/api/bank/lines?statementId="));
    }

    @Test
    void shouldHandOutCallsThatActuallyResolveTheItem() throws Exception {
        aBusyMorning();
        WorklistService.Item unmatched = worklistService.worklist(WorklistService.Owner.TREASURY, null).items().stream()
                .filter(item -> item.title().startsWith("Unmatched credit")).findFirst().orElseThrow();
        WorklistService.Action assign = unmatched.actions().getFirst();
        var body = objectMapper.createObjectNode();
        assign.body().forEach((key, value) -> body.putPOJO(key, value));
        body.put("note", "The bank confirmed the transfer belongs to this payout.");

        mockMvc.perform(json(as(OPS_ANA_KEY, post(assign.path())), objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        assertThat(reload(referenceless).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        WorklistService.Worklist after = worklistService.worklist(null, null);
        assertThat(titles(after)).as("the credit and the payout's missing-credit break are both explained")
                .noneMatch(title -> title.contains("Unmatched credit")).filteredOn(title -> title.contains("Missing credit")).hasSize(1);
        assertThat(after.items()).extracting(WorklistService.Item::settlementBatchId).doesNotContain(referenceless.getId());
    }

    @Test
    void shouldFilterByOwnerFlagWhatIsOverdueAndRefuseNonsense() throws Exception {
        aBusyMorning();

        JsonNode treasury = body(mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/worklist").param("owner", "treasury")))
                .andExpect(status().isOk()));
        assertThat(treasury.path("items")).hasSize(3).allSatisfy(item -> assertThat(item.path("owner").asText()).isEqualTo("TREASURY"));
        assertThat(treasury.path("summary").path("items").asInt()).as("the summary still counts everything").isGreaterThan(3);

        clock.advance(Duration.ofHours(5));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/worklist").param("limit", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.summary.overdue").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/worklist").param("owner", "everyone")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/worklist").param("limit", "0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(merchantAKey, get("/api/operations/worklist"))).andExpect(status().isForbidden());
    }

    @Test
    void shouldBeEmptyWhenNothingWaits() {
        paidIntent("WL-CALM", "1000");

        WorklistService.Worklist worklist = worklistService.worklist(null, null);

        assertThat(worklist.items()).isEmpty();
        assertThat(worklist.summary().items()).isZero();
    }
}
