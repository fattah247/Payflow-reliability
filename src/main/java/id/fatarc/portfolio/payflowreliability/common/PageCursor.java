package id.fatarc.portfolio.payflowreliability.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a page ended: the sort time and the id of its last row. Handed to the client as an opaque token
 * ({@code after}); the next page starts strictly after it, so rows written meanwhile never shift a page or repeat one.
 */
public record PageCursor(Instant at, UUID id) {
    public String encode() {
        String raw = at.getEpochSecond() + "." + at.getNano() + "." + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Null for no cursor; a 400 for anything this API did not issue. */
    public static PageCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\.", 3);
            return new PageCursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])), UUID.fromString(parts[2]));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "after is not a cursor from a previous page of this list.");
        }
    }
}
