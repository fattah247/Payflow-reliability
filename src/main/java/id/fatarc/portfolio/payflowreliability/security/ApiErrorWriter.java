package id.fatarc.portfolio.payflowreliability.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.common.ApiError;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Writes the standard error body from filters, where controller advice does not apply. */
@Component
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }
}
