package id.fatarc.portfolio.payflowreliability.idempotency;

import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.security.ApiErrorWriter;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Optional {@code Idempotency-Key} for every mutating API call: the first response is stored and
 * replayed (with {@code Idempotent-Replayed: true}) for retries of the same request.
 *
 * <p>A retry is recognized by its canonical fingerprint ({@link RequestFingerprint#ofHttp}): the same JSON
 * with keys in another order, other whitespace or {@code 1000} instead of {@code 1000.00} is the same request.
 * A key names one operation; using it for a different method, path, query or body is refused (422).
 *
 * <p>Not applied to payment and refund creation, whose keys are part of the stored records and
 * are required, nor to merchant onboarding, whose responses contain API keys that must not be stored.
 */
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,100}");
    private static final List<Pattern> EXCLUDED = List.of(
            Pattern.compile("/api/payment-intents"),
            Pattern.compile("/api/refunds/payment-intents/[^/]+"),
            Pattern.compile("/api/merchants(/.*)?"));

    private final IdempotencyService service;
    private final ApiErrorWriter errorWriter;

    public IdempotencyFilter(IdempotencyService service, ApiErrorWriter errorWriter) {
        this.service = service;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"POST".equals(request.getMethod())
                || !path.startsWith("/api/")
                || request.getHeader(HEADER) == null
                || EXCLUDED.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Caller caller)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (!VALID_KEY.matcher(key).matches()) {
            errorWriter.write(response, ErrorCode.VALIDATION_FAILED,
                    HEADER + " must be 1-100 printable ASCII characters without spaces.");
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String requestHash = RequestFingerprint.ofHttp(request.getMethod(), request.getRequestURI(), request.getQueryString(), body);

        switch (service.begin(caller.name(), key, request.getMethod(), target, requestHash)) {
            case IdempotencyService.Reject reject -> errorWriter.write(response, reject.code(), reject.message());
            case IdempotencyService.Replay replay -> replay(replay.record(), response);
            case IdempotencyService.Proceed proceed -> execute(new CachedBodyRequest(request, body), response, chain, proceed);
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                         IdempotencyService.Proceed proceed) throws ServletException, IOException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, captured);
        } catch (IOException | ServletException | RuntimeException ex) {
            service.abandon(proceed.recordId());
            throw ex;
        }
        String body = new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (isReplayable(captured.getStatus(), body)) {
            service.complete(proceed.recordId(), captured.getStatus(), captured.getContentType(),
                    captured.getHeader("Location"), body);
        } else {
            service.abandon(proceed.recordId());
        }
        captured.copyBodyToResponse();
    }

    /** Validation errors, retryable conflicts and server errors are not outcomes; the client may fix or retry them. */
    private static boolean isReplayable(int status, String body) {
        return status < 500 && status != 400 && !body.contains("\"retryable\":true");
    }

    private static void replay(IdempotencyRecord record, HttpServletResponse response) throws IOException {
        response.setStatus(record.getResponseStatus());
        response.setHeader(REPLAYED_HEADER, "true");
        if (record.getResponseContentType() != null) {
            response.setContentType(record.getResponseContentType());
        }
        if (record.getResponseLocation() != null) {
            response.setHeader("Location", record.getResponseLocation());
        }
        if (record.getResponseBody() != null) {
            response.getOutputStream().write(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
