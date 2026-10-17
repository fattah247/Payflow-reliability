package db.migration;

import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Gives every review case the due time the application now stores (the worklist pages by it): a proposal awaiting
 * approval is due an hour after it was proposed, an unknown provider outcome five minutes after the case opened, any
 * other case four hours after. Same constants as {@link ReviewCase}, so old and new cases agree.
 */
public class V12__Review_case_due_times extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table review_cases add column due_at timestamp(6) with time zone");
        }
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("select id, reason, status, created_at, proposed_at from review_cases");
             PreparedStatement update = connection.prepareStatement("update review_cases set due_at = ? where id = ?")) {
            while (rows.next()) {
                OffsetDateTime created = rows.getObject("created_at", OffsetDateTime.class);
                OffsetDateTime proposed = rows.getObject("proposed_at", OffsetDateTime.class);
                OffsetDateTime due = "AWAITING_APPROVAL".equals(rows.getString("status")) && proposed != null
                        ? proposed.plus(ReviewCase.APPROVE_WITHIN)
                        : "PROVIDER_OUTCOME_UNKNOWN".equals(rows.getString("reason"))
                        ? created.plus(ReviewCase.UNKNOWN_OUTCOME_WITHIN)
                        : created.plus(ReviewCase.DECIDE_WITHIN);
                update.setObject(1, due);
                update.setObject(2, rows.getObject("id", UUID.class));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table review_cases alter column due_at set not null");
            statement.execute("create index idx_review_case_status_due on review_cases (status, due_at)");
        }
    }
}
