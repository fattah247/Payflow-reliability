package id.fatarc.portfolio.payflowreliability.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * A page as a plain JSON array (so existing clients keep working), with the way to the next page in headers:
 * {@code Link: <...&after=...>; rel="next"} and {@code X-Next-After}. Neither is present on the last page.
 */
public final class PageResponses {
    public static final String NEXT_AFTER_HEADER = "X-Next-After";

    private PageResponses() {
    }

    public static <T> ResponseEntity<List<T>> of(PageSlice<T> page) {
        return withNext(page.items(), page.next() == null ? null : page.next().encode());
    }

    /** Any body, with the next-page headers when {@code cursor} is not null. */
    public static <B> ResponseEntity<B> withNext(B body, String cursor) {
        if (cursor == null) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.ok().header(NEXT_AFTER_HEADER, cursor).header("Link", "<" + nextLink(cursor) + ">; rel=\"next\"").body(body);
    }

    /** This request again, with every parameter it was called with, and {@code after} moved on. */
    private static String nextLink(String cursor) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        UriComponentsBuilder next = ServletUriComponentsBuilder.fromCurrentRequestUri();
        request.getParameterMap().forEach((name, values) -> {
            if (!"after".equals(name)) {
                next.queryParam(name, (Object[]) values);
            }
        });
        return next.queryParam("after", cursor).encode().build().toUriString();
    }
}
