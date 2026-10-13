package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audit events form a hash chain per payment: changing, removing or reordering one is detectable. */
class AuditChainTest extends IntegrationTest {

    private String chainOf(PaymentIntent intent) {
        return AuditHash.chainKey(intent.getId(), null, null);
    }

    /** Runs SQL as a maintenance session would: on PostgreSQL the append-only triggers need an explicit opt-in. */
    private void tamper(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (isPostgres()) {
                jdbcTemplate.execute("set local payflow.evidence_maintenance = 'on'");
            }
            jdbcTemplate.update(sql, args);
        });
    }

    private PaymentIntent paymentWithHistory() {
        PaymentIntent intent = paidIntent("ORDER-A-1", "50000");
        requestRefund(intent, "10000", "refund-a-1");
        drainOutbox();
        return intent;
    }

    @Test
    void shouldChainEveryEventOfAPayment() throws Exception {
        PaymentIntent intent = paymentWithHistory();
        List<AuditEvent> events = auditFor(intent);

        assertThat(events).extracting(AuditEvent::getSequenceNumber)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(events.getFirst().getPreviousHash()).isEqualTo(AuditHash.GENESIS);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getPreviousHash()).isEqualTo(events.get(i - 1).getEventHash());
        }
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/audit-events/verify").param("paymentIntentId", intent.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.events").value(events.size()))
                .andExpect(jsonPath("$.headHash").value(events.getLast().getEventHash()));
    }

    @Test
    void shouldDetectAModifiedEvent() {
        PaymentIntent intent = paymentWithHistory();
        AuditEvent third = auditFor(intent).get(2);

        tamper("update audit_events set reason = 'nothing to see here' where id = ?", third.getId());

        AuditChainVerifier.Verification verification = auditChainVerifier.verify(chainOf(intent));
        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstBrokenSequence()).isEqualTo(third.getSequenceNumber());
        assertThat(verification.problem()).contains("modified");
    }

    @Test
    void shouldDetectARemovedEvent() {
        PaymentIntent intent = paymentWithHistory();
        AuditEvent second = auditFor(intent).get(1);

        tamper("delete from audit_events where id = ?", second.getId());

        AuditChainVerifier.Verification verification = auditChainVerifier.verify(chainOf(intent));
        assertThat(verification.valid()).isFalse();
        assertThat(verification.problem()).contains("missing");
    }

    @Test
    void shouldDetectARewrittenHistoryWhoseHashesWereRecomputedForOneEvent() {
        PaymentIntent intent = paymentWithHistory();
        AuditEvent second = auditFor(intent).get(1);
        String forgedHash = AuditHash.compute(second.getChainKey(), second.getSequenceNumber(), second.getPreviousHash(),
                second.getId(), second.getAggregateType(), second.getAggregateId(), second.getPaymentIntentId(),
                second.getEventType(), second.getPreviousState(), second.getNewState(), "someone-else", second.getReason(),
                second.getMetadata(), second.getRequestId(), second.getCreatedAt());

        tamper("update audit_events set actor = 'someone-else', event_hash = ? where id = ?", forgedHash, second.getId());

        AuditChainVerifier.Verification verification = auditChainVerifier.verify(chainOf(intent));
        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstBrokenSequence()).as("the next event still points at the original hash")
                .isEqualTo(second.getSequenceNumber() + 1);
    }

    @Test
    void shouldKeepTheChainIntactUnderConcurrentWriters() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-A-2", "42000");

        race(6, () -> webhook(intent.getId(), "evt-a-2", "tx-a-2", ProviderStatus.SUCCESS, "42000"));

        assertThat(auditChainVerifier.verify(chainOf(intent)).valid()).isTrue();
    }

    @Test
    void shouldRefuseToChangeEvidenceInPostgres() {
        Assumptions.assumeTrue(isPostgres(), "append-only triggers are PostgreSQL-specific");
        PaymentIntent intent = capturedIntent("ORDER-A-3", "1000");

        assertThatThrownBy(() -> jdbcTemplate.update("update audit_events set reason = 'x' where payment_intent_id = ?", intent.getId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from ledger_postings"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.execute("truncate table ledger_journals cascade"))
                .hasMessageContaining("append-only");
        assertThat(auditChainVerifier.verify(chainOf(intent)).valid()).isTrue();
    }
}
