package db.migration;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * Computes the request fingerprint of every existing payment and refund with the same code the application
 * uses for new ones (so a client retrying an old request after the upgrade is still recognized), then makes
 * the column mandatory.
 */
public class V6__Backfill_request_fingerprints extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("select id, merchant_reference, amount, currency from payment_intents");
             PreparedStatement update = connection.prepareStatement("update payment_intents set request_fingerprint = ? where id = ?")) {
            while (rows.next()) {
                update.setString(1, PaymentIntent.fingerprintOf(rows.getString("merchant_reference"),
                        rows.getBigDecimal("amount"), rows.getString("currency")));
                update.setObject(2, rows.getObject("id", UUID.class));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("select id, payment_intent_id, amount, reason from refund_requests");
             PreparedStatement update = connection.prepareStatement("update refund_requests set request_fingerprint = ? where id = ?")) {
            while (rows.next()) {
                BigDecimal amount = rows.getBigDecimal("amount");
                update.setString(1, RefundRequest.fingerprintOf(rows.getObject("payment_intent_id", UUID.class), amount,
                        rows.getString("reason")));
                update.setObject(2, rows.getObject("id", UUID.class));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table payment_intents alter column request_fingerprint set not null");
            statement.execute("alter table refund_requests alter column request_fingerprint set not null");
        }
    }
}
