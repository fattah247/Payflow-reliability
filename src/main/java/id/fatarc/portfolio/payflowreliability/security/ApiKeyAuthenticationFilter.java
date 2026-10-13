package id.fatarc.portfolio.payflowreliability.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** {@code Authorization: Bearer <api key>}. An unknown key leaves the request unauthenticated (401 where required). */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyAuthenticator authenticator;

    ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authenticator.authenticate(header.substring(7).trim()).ifPresent(caller -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        caller, null, List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
