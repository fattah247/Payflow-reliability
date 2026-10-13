package db.migration;

import id.fatarc.portfolio.payflowreliability.audit.AuditHash;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfills data that needs generated ids or hashes, portably across PostgreSQL and H2:
 * <ol>
 *   <li>hash chains for existing audit events (same algorithm as the application), then makes them mandatory;</li>
 *   <li>ledger journals for existing captures, completed refunds and payouts, so the books match history;</li>
 *   <li>outbox commands for refunds and reversals that were in flight before the provider integration.</li>
 * </ol>
 */
public class V3__Backfill_ledger_audit_chains_and_outbox extends BaseJavaMigration {
    private static final String PROVIDER_RECEIVABLE = "provider_receivable";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        backfillAuditChains(connection);
        backfillLedger(connection, now);
        backfillOutbox(connection, now);
    }

    private void backfillAuditChains(Connection connection) throws SQLException {
        Map<String, List<Object[]>> chains = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select id, aggregate_type, aggregate_id, payment_intent_id, event_type, previous_state, new_state,
                            actor, reason, metadata, request_id, created_at
                     from audit_events order by created_at, id""")) {
            while (rows.next()) {
                UUID paymentIntentId = rows.getObject("payment_intent_id", UUID.class);
                String chainKey = AuditHash.chainKey(paymentIntentId, rows.getString("aggregate_type"), rows.getString("aggregate_id"));
                chains.computeIfAbsent(chainKey, key -> new ArrayList<>()).add(new Object[]{
                        rows.getObject("id", UUID.class), rows.getString("aggregate_type"), rows.getString("aggregate_id"),
                        paymentIntentId, rows.getString("event_type"), rows.getString("previous_state"),
                        rows.getString("new_state"), rows.getString("actor"), rows.getString("reason"),
                        rows.getString("metadata"), rows.getString("request_id"),
                        rows.getObject("created_at", OffsetDateTime.class).toInstant()});
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "update audit_events set chain_key = ?, sequence_number = ?, previous_hash = ?, event_hash = ? where id = ?")) {
            for (Map.Entry<String, List<Object[]>> chain : chains.entrySet()) {
                String previousHash = AuditHash.GENESIS;
                long sequence = 1;
                for (Object[] e : chain.getValue()) {
                    String hash = AuditHash.compute(chain.getKey(), sequence, previousHash, (UUID) e[0], (String) e[1],
                            (String) e[2], (UUID) e[3], (String) e[4], (String) e[5], (String) e[6], (String) e[7],
                            (String) e[8], (String) e[9], (String) e[10], (java.time.Instant) e[11]);
                    update.setString(1, chain.getKey());
                    update.setLong(2, sequence);
                    update.setString(3, previousHash);
                    update.setString(4, hash);
                    update.setObject(5, e[0]);
                    update.addBatch();
                    previousHash = hash;
                    sequence++;
                }
            }
            update.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table audit_events alter column chain_key set not null");
            statement.execute("alter table audit_events alter column sequence_number set not null");
            statement.execute("alter table audit_events alter column previous_hash set not null");
            statement.execute("alter table audit_events alter column event_hash set not null");
            statement.execute("alter table audit_events add constraint uk_audit_chain_sequence unique (chain_key, sequence_number)");
        }
    }

    private void backfillLedger(Connection connection, OffsetDateTime now) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select id, merchant_id, currency, amount from payment_intents where captured_at is not null")) {
            while (rows.next()) {
                UUID paymentId = rows.getObject("id", UUID.class);
                journal(connection, now, "CAPTURE", paymentId.toString(), paymentId, rows.getString("currency"),
                        "Backfilled: payment captured before the ledger existed.",
                        PROVIDER_RECEIVABLE, "merchant_payable:" + rows.getString("merchant_id"), rows.getBigDecimal("amount"));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select r.id, r.payment_intent_id, r.amount, p.merchant_id, p.currency
                     from refund_requests r join payment_intents p on p.id = r.payment_intent_id
                     where r.status = 'COMPLETED'""")) {
            while (rows.next()) {
                journal(connection, now, "REFUND", rows.getObject("id", UUID.class).toString(),
                        rows.getObject("payment_intent_id", UUID.class), rows.getString("currency"),
                        "Backfilled: refund completed before the ledger existed.",
                        "merchant_payable:" + rows.getString("merchant_id"), PROVIDER_RECEIVABLE, rows.getBigDecimal("amount"));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select id, merchant_id, currency, total_amount from settlement_batches")) {
            while (rows.next()) {
                journal(connection, now, "PAYOUT", rows.getObject("id", UUID.class).toString(), null,
                        rows.getString("currency"), "Backfilled: payout made before the ledger existed.",
                        "merchant_payable:" + rows.getString("merchant_id"), PROVIDER_RECEIVABLE, rows.getBigDecimal("total_amount"));
            }
        }
    }

    private void journal(Connection connection, OffsetDateTime now, String type, String reference, UUID paymentId,
                         String currency, String description, String debitAccount, String creditAccount,
                         BigDecimal amount) throws SQLException {
        UUID journalId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into ledger_journals (id, journal_type, reference, payment_intent_id, currency, description, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setObject(1, journalId);
            insert.setString(2, type);
            insert.setString(3, reference);
            insert.setObject(4, paymentId);
            insert.setString(5, currency);
            insert.setString(6, description);
            insert.setObject(7, now);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into ledger_postings (id, journal_id, account, currency, amount, created_at)
                values (?, ?, ?, ?, ?, ?)""")) {
            for (Object[] posting : new Object[][]{{debitAccount, amount}, {creditAccount, amount.negate()}}) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, journalId);
                insert.setString(3, (String) posting[0]);
                insert.setString(4, currency);
                insert.setBigDecimal(5, (BigDecimal) posting[1]);
                insert.setObject(6, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void backfillOutbox(Connection connection, OffsetDateTime now) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select id, payment_intent_id from refund_requests where status = 'REQUESTED'")) {
            while (rows.next()) {
                UUID refundId = rows.getObject("id", UUID.class);
                command(connection, now, "REFUND", rows.getObject("payment_intent_id", UUID.class), refundId.toString(),
                        "refund-" + refundId);
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select id from payment_intents where state = 'REVERSAL_REQUIRED'")) {
            while (rows.next()) {
                UUID paymentId = rows.getObject("id", UUID.class);
                command(connection, now, "REVERSAL", paymentId, paymentId.toString(), "reversal-" + paymentId);
            }
        }
    }

    private void command(Connection connection, OffsetDateTime now, String type, UUID paymentId, String referenceId,
                         String idempotencyKey) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into outbox_commands (id, version, command_type, payment_intent_id, reference_id, idempotency_key,
                                             status, attempts, next_attempt_at, created_at, updated_at)
                values (?, 0, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)""")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, type);
            insert.setObject(3, paymentId);
            insert.setString(4, referenceId);
            insert.setString(5, idempotencyKey);
            insert.setObject(6, now);
            insert.setObject(7, now);
            insert.setObject(8, now);
            insert.executeUpdate();
        }
    }
}
