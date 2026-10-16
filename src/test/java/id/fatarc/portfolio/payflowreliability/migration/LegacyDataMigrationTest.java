package id.fatarc.portfolio.payflowreliability.migration;

import id.fatarc.portfolio.payflowreliability.audit.AuditHash;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrading a database that already holds V1 data: amounts, states, refunds, settlements and audit events
 * written by the previous version must come out consistent with the new rules (ledger matches history,
 * audit chains verify, in-flight refunds and reversals are queued for the provider). Runs against the
 * same database engine as the rest of the suite (H2, or PostgreSQL via SPRING_DATASOURCE_URL).
 */
class LegacyDataMigrationTest {
    private static final String MERCHANT = "legacy-merchant";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC);

    private final String baseUrl = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "");
    private final boolean postgres = baseUrl.startsWith("jdbc:postgresql");
    private final String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    private final UUID settled = UUID.randomUUID();
    private final UUID refundRequested = UUID.randomUUID();
    private final UUID refunded = UUID.randomUUID();
    private final UUID reversalAfterCapture = UUID.randomUUID();
    private final UUID reversalBeforeCapture = UUID.randomUUID();
    private final UUID held = UUID.randomUUID();
    private final UUID pendingRefund = UUID.randomUUID();
    private final UUID completedRefund = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();

    @BeforeEach
    void createDatabase() {
        if (postgres) {
            String separator = baseUrl.contains("?") ? "&" : "?";
            dataSource = new DriverManagerDataSource(baseUrl + separator + "currentSchema=" + schema,
                    System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        } else {
            dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + schema
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void dropDatabase() {
        jdbc.execute(postgres ? "drop schema " + schema + " cascade" : "drop all objects");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/vendor/" + (postgres ? "postgresql" : "h2"))
                .target(target);
        if (postgres) {
            configuration.schemas(schema).createSchemas(true);
        }
        return configuration.load();
    }

    // ---- V1 data ----------------------------------------------------------------------------------

    private void payment(UUID id, String state, String stateBeforeReview, String amount, OffsetDateTime updatedAt) {
        jdbc.update("""
                insert into payment_intents (id, version, merchant_id, merchant_reference, amount, currency, idempotency_key,
                                             state, state_before_review, created_at, updated_at)
                values (?, 0, ?, ?, ?, 'IDR', ?, ?, ?, ?, ?)""",
                id, MERCHANT, "ORDER-" + id, new BigDecimal(amount), "key-" + id, state, stateBeforeReview, T0, updatedAt);
    }

    private UUID transaction(UUID paymentId, String providerStatus, String amount, boolean settledFlag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payment_transactions (id, version, payment_intent_id, merchant_id, provider_transaction_id,
                                                  provider_status, amount, currency, settled, settled_on, created_at, updated_at)
                values (?, 0, ?, ?, ?, ?, ?, 'IDR', ?, ?, ?, ?)""",
                id, paymentId, MERCHANT, "tx-" + paymentId, providerStatus, new BigDecimal(amount), settledFlag,
                settledFlag ? T0.toLocalDate() : null, T0, T0);
        return id;
    }

    private int audited;

    private void transition(UUID paymentId, String from, String to) {
        audited++;
        jdbc.update("""
                insert into audit_events (id, aggregate_type, aggregate_id, payment_intent_id, event_type, previous_state,
                                          new_state, actor, reason, metadata, request_id, created_at)
                values (?, 'PAYMENT_INTENT', ?, ?, 'TRANSACTION_STATE_CHANGED', ?, ?, 'provider', 'legacy', '', null, ?)""",
                UUID.randomUUID(), paymentId.toString(), paymentId, from, to, T0.plusMinutes(audited));
    }

    private void history(UUID paymentId, String... states) {
        for (int i = 1; i < states.length; i++) {
            transition(paymentId, states[i - 1], states[i]);
        }
    }

    private void legacyData() {
        payment(settled, "SETTLED", null, "100.00", T0.plusHours(1));
        UUID settledTx = transaction(settled, "SUCCESS", "100.00", true);
        history(settled, "CREATED", "PENDING", "SUCCESS", "SETTLED");
        jdbc.update("insert into settlement_batches (id, merchant_id, transaction_count, total_amount, currency, created_at)"
                + " values (?, ?, 1, 100.00, 'IDR', ?)", batch, MERCHANT, T0.plusHours(1));
        jdbc.update("insert into settlement_batch_items (id, batch_id, transaction_id, amount, created_at) values (?, ?, ?, 100.00, ?)",
                UUID.randomUUID(), batch, settledTx, T0.plusHours(1));

        payment(refundRequested, "REFUND_REQUESTED", null, "50.00", T0.plusHours(2));
        transaction(refundRequested, "SUCCESS", "50.00", false);
        history(refundRequested, "CREATED", "PENDING", "SUCCESS", "REFUND_REQUESTED");
        jdbc.update("insert into refund_requests (id, version, payment_intent_id, amount, currency, reason, status, created_at, updated_at)"
                + " values (?, 0, ?, 50.00, 'IDR', 'customer', 'REQUESTED', ?, ?)", pendingRefund, refundRequested, T0, T0);

        payment(refunded, "REFUNDED", null, "30.00", T0.plusHours(3));
        transaction(refunded, "SUCCESS", "30.00", false);
        history(refunded, "CREATED", "PENDING", "SUCCESS", "REFUND_REQUESTED", "REFUNDED");
        jdbc.update("insert into refund_requests (id, version, payment_intent_id, amount, currency, reason, status, created_at, updated_at)"
                + " values (?, 0, ?, 30.00, 'IDR', 'customer', 'COMPLETED', ?, ?)", completedRefund, refunded, T0, T0.plusHours(3));

        payment(reversalAfterCapture, "REVERSAL_REQUIRED", null, "40.00", T0.plusHours(4));
        transaction(reversalAfterCapture, "REVERSAL_REQUIRED", "40.00", false);
        history(reversalAfterCapture, "CREATED", "PENDING", "SUCCESS", "REVERSAL_REQUIRED");

        payment(reversalBeforeCapture, "REVERSAL_REQUIRED", null, "20.00", T0.plusHours(5));
        transaction(reversalBeforeCapture, "REVERSAL_REQUIRED", "20.00", false);
        history(reversalBeforeCapture, "CREATED", "PENDING", "AUTHORIZED", "REVERSAL_REQUIRED");

        payment(held, "MANUAL_REVIEW", "SUCCESS", "60.00", T0.plusHours(6));
        transaction(held, "SUCCESS", "60.00", false);
        history(held, "CREATED", "PENDING", "SUCCESS", "MANUAL_REVIEW");
        // The previous version deduplicated open cases by lookup only; two concurrent writers left two.
        for (int duplicate = 0; duplicate < 2; duplicate++) {
            jdbc.update("""
                    insert into review_cases (id, version, reason, status, reference_type, reference_id, payment_intent_id,
                                              details, created_by, created_at)
                    values (?, 0, 'INVALID_STATE_TRANSITION', 'OPEN', 'PAYMENT_INTENT', ?, ?, 'legacy hold', 'provider', ?)""",
                    UUID.randomUUID(), held.toString(), held, T0.plusSeconds(duplicate));
        }
    }

    // ---- the upgrade --------------------------------------------------------------------------------

    @Test
    void shouldCarryLegacyDataForwardConsistently() {
        flyway("1").migrate();
        legacyData();

        var result = flyway("latest").migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo(postgres ? "8" : "7");

        assertThat(jdbc.queryForObject("select state from payment_intents where id = ?", String.class, refundRequested))
                .as("REFUND_REQUESTED is no longer a payment state; the refund itself stays in flight").isEqualTo("SUCCESS");
        assertThat(capturedAt(reversalAfterCapture)).as("captured before the reversal request").isNotNull();
        assertThat(capturedAt(reversalBeforeCapture)).as("only authorized: nothing captured").isNull();
        assertThat(capturedAt(held)).isNotNull();
        assertThat(capturedAt(settled)).isEqualTo(T0.plusMinutes(2).toInstant());
        assertThat(jdbc.queryForObject("select refunded_amount from payment_intents where id = ?", BigDecimal.class, refunded))
                .isEqualByComparingTo("30");

        assertThat(jdbc.queryForList("select command_type || ':' || reference_id from outbox_commands where status = 'PENDING'", String.class))
                .containsExactlyInAnyOrder("REFUND:" + pendingRefund, "REVERSAL:" + reversalAfterCapture, "REVERSAL:" + reversalBeforeCapture);

        assertThat(jdbc.queryForList("select journal_type from ledger_journals", String.class))
                .containsExactlyInAnyOrder("CAPTURE", "CAPTURE", "CAPTURE", "CAPTURE", "CAPTURE", "REFUND", "PAYOUT");
        assertThat(jdbc.queryForList("select journal_id from ledger_postings group by journal_id having sum(amount) <> 0", UUID.class))
                .isEmpty();
        // Owed = captures 280 - completed refunds 30 - payouts 100, exactly what the invariant check expects.
        assertThat(jdbc.queryForObject("select -sum(amount) from ledger_postings where account = ?", BigDecimal.class,
                "merchant_payable:" + MERCHANT)).isEqualByComparingTo("150");

        assertThat(jdbc.queryForObject("select item_type || ':' || currency || ':' || cast(payment_intent_id as varchar(36))"
                + " from settlement_batch_items", String.class)).isEqualTo("CAPTURE:IDR:" + settled);
        assertThat(jdbc.queryForObject("select capture_amount from settlement_batches", BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select payout_status from settlement_batches", String.class))
                .as("paid out before bank statements were reconciled: no bank evidence to wait for").isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("select status from merchants where merchant_id = ?", String.class, MERCHANT)).isEqualTo("ACTIVE");

        assertThat(jdbc.queryForList("select status from review_cases where reference_id = ? order by created_at",
                String.class, held.toString())).as("the newer duplicate is closed so the unique index can be built")
                .containsExactly("OPEN", "RESOLVED");

        assertThat(jdbc.queryForObject("select request_fingerprint from payment_intents where id = ?", String.class, settled))
                .as("a client retrying a pre-upgrade request is still recognized")
                .isEqualTo(PaymentIntent.fingerprintOf("ORDER-" + settled, new BigDecimal("100.00"), "IDR"));
        assertThat(jdbc.queryForObject("select request_fingerprint from refund_requests where id = ?", String.class, pendingRefund))
                .isEqualTo(RefundRequest.fingerprintOf(refundRequested, new BigDecimal("50.00"), "customer"));

        assertAuditChainsVerify();
    }

    private Instant capturedAt(UUID paymentId) {
        OffsetDateTime value = jdbc.queryForObject("select captured_at from payment_intents where id = ?", OffsetDateTime.class, paymentId);
        return value == null ? null : value.toInstant();
    }

    private void assertAuditChainsVerify() {
        List<Map<String, Object>> events = jdbc.queryForList("select * from audit_events order by chain_key, sequence_number");
        assertThat(events).hasSize(audited);
        String chain = null;
        String previous = null;
        long expectedSequence = 0;
        for (Map<String, Object> event : events) {
            String chainKey = (String) event.get("chain_key");
            if (!chainKey.equals(chain)) {
                chain = chainKey;
                previous = AuditHash.GENESIS;
                expectedSequence = 1;
            }
            assertThat(((Number) event.get("sequence_number")).longValue()).isEqualTo(expectedSequence++);
            assertThat(event.get("previous_hash")).isEqualTo(previous);
            UUID paymentId = (UUID) event.get("payment_intent_id");
            String expectedHash = AuditHash.compute(chainKey, ((Number) event.get("sequence_number")).longValue(), previous,
                    (UUID) event.get("id"), (String) event.get("aggregate_type"), (String) event.get("aggregate_id"),
                    paymentId, (String) event.get("event_type"), (String) event.get("previous_state"),
                    (String) event.get("new_state"), (String) event.get("actor"), (String) event.get("reason"),
                    (String) event.get("metadata"), (String) event.get("request_id"),
                    jdbc.queryForObject("select created_at from audit_events where id = ?", OffsetDateTime.class, event.get("id")).toInstant());
            assertThat(event.get("event_hash")).isEqualTo(expectedHash);
            previous = expectedHash;
        }
    }
}
