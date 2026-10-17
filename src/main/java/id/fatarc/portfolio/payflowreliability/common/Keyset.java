package id.fatarc.portfolio.payflowreliability.common;

import jakarta.persistence.criteria.Path;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Keyset paging over a time column with the id as tie-breaker: each page asks for the rows after the last one it
 * showed, in a stable order, and reads one row more than it returns to learn whether another page exists. No offset,
 * so deep pages cost the same as the first, and no count query.
 */
public final class Keyset {
    private Keyset() {
    }

    public static <T> Specification<T> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static <T> PageSlice<T> page(JpaSpecificationExecutor<T> repository, Specification<T> filter, String timeField,
                                        boolean ascending, PageCursor after, Integer limit, Function<T, PageCursor> cursorOf) {
        int size = ListLimit.of(limit);
        Specification<T> query = after == null ? filter : filter.and(after(timeField, after, ascending));
        Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort order = Sort.by(direction, timeField).and(Sort.by(direction, "id"));
        List<T> rows = repository.findBy(query, q -> q.sortBy(order).limit(size + 1).all());
        if (rows.size() <= size) {
            return new PageSlice<>(rows, null);
        }
        List<T> shown = rows.subList(0, size);
        return new PageSlice<>(List.copyOf(shown), cursorOf.apply(shown.getLast()));
    }

    private static <T> Specification<T> after(String timeField, PageCursor cursor, boolean ascending) {
        return (root, query, cb) -> {
            Path<Instant> time = root.get(timeField);
            Path<UUID> id = root.get("id");
            return ascending
                    ? cb.or(cb.greaterThan(time, cursor.at()), cb.and(cb.equal(time, cursor.at()), cb.greaterThan(id, cursor.id())))
                    : cb.or(cb.lessThan(time, cursor.at()), cb.and(cb.equal(time, cursor.at()), cb.lessThan(id, cursor.id())));
        };
    }
}
