package id.fatarc.portfolio.payflowreliability.security;

import java.util.Objects;

/**
 * Who is acting. Derived from the authenticated API key, never from the request body, so audit
 * actors and approvals cannot be spoofed.
 *
 * @param name     stable actor name written to the audit trail, for example {@code operator:ops-ana}
 * @param merchantId set only for merchants: the one merchant this caller may act for
 */
public record Caller(String name, Role role, String merchantId) {
    public static final Caller PROVIDER = new Caller("provider", Role.SYSTEM, null);

    public Caller {
        Objects.requireNonNull(name);
        Objects.requireNonNull(role);
    }

    public static Caller merchant(String merchantId) {
        return new Caller("merchant:" + merchantId, Role.MERCHANT, merchantId);
    }

    public static Caller operator(String operatorName) {
        return new Caller("operator:" + operatorName, Role.OPERATOR, null);
    }

    public static Caller system(String jobName) {
        return new Caller("system:" + jobName, Role.SYSTEM, null);
    }

    public boolean isMerchant() {
        return role == Role.MERCHANT;
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    /** Operators and internal jobs see every merchant; a merchant sees only itself. */
    public boolean canAccessMerchant(String otherMerchantId) {
        return role != Role.MERCHANT || merchantId.equals(otherMerchantId);
    }
}
