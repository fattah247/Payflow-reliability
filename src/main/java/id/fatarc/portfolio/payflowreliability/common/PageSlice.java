package id.fatarc.portfolio.payflowreliability.common;

import java.util.List;
import java.util.function.Function;

/** One page of a list, and where the next one starts (null on the last page). */
public record PageSlice<T>(List<T> items, PageCursor next) {
    public <R> PageSlice<R> map(Function<List<T>, List<R>> mapper) {
        return new PageSlice<>(mapper.apply(items), next);
    }
}
