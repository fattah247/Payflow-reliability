package id.fatarc.portfolio.payflowreliability.common;

import java.sql.SQLException;

public final class DatabaseErrors {
    /** ANSI SQLState for unique_violation; reported by both PostgreSQL and H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private DatabaseErrors() {
    }

    /**
     * True when the failure is a unique-constraint violation. In this service that means a
     * concurrent request inserted the same natural key first, so re-reading (or retrying)
     * resolves it. Other integrity failures indicate a bug and must not be treated as retryable.
     *
     * <p>Checks the SQLState rather than Hibernate's constraint classification, which is not
     * populated for every dialect (PostgreSQL reports UNIQUE violations as kind OTHER).
     */
    public static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
