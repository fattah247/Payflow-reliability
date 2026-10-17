package id.fatarc.portfolio.payflowreliability.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** How many rows a list endpoint returns: {@value #DEFAULT} unless the caller asks, never more than {@value #MAX}. */
public final class ListLimit {
    public static final int DEFAULT = 100;
    public static final int MAX = 1000;

    private ListLimit() {
    }

    public static int of(Integer requested) {
        if (requested == null) {
            return DEFAULT;
        }
        if (requested < 1 || requested > MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX + ".");
        }
        return requested;
    }

    public static Pageable first(Integer requested, Sort sort) {
        return PageRequest.of(0, of(requested), sort);
    }

    public static Pageable first(Integer requested) {
        return PageRequest.of(0, of(requested));
    }
}
