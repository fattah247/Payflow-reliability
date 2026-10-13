package id.fatarc.portfolio.payflowreliability.security;

import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.idempotency.IdempotencyFilter;
import id.fatarc.portfolio.payflowreliability.idempotency.IdempotencyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Stateless API-key authentication. Merchants may only create and read their own payments and
 * refunds; everything operational needs an operator. The provider webhook and the provider
 * simulator authenticate themselves (HMAC signature, simulator key), so they bypass API keys.
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {
    private static final String MERCHANT = Role.MERCHANT.name();
    private static final String OPERATOR = Role.OPERATOR.name();

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyAuthenticator authenticator,
                                           ApiErrorWriter errorWriter, IdempotencyService idempotencyService)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(authenticator), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new IdempotencyFilter(idempotencyService, errorWriter), AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            errorWriter.write(response, ErrorCode.UNAUTHENTICATED,
                                    "Send a valid API key as 'Authorization: Bearer <key>'.");
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.FORBIDDEN, "This API key may not call this endpoint.")))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/fake-provider/webhook").permitAll()
                        .requestMatchers("/provider-sim/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payment-intents").hasRole(MERCHANT)
                        .requestMatchers(HttpMethod.POST, "/api/payment-intents/*/start").hasAnyRole(MERCHANT, OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/payment-intents/*").hasAnyRole(MERCHANT, OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/refunds/payment-intents/*").hasAnyRole(MERCHANT, OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/refunds/**").hasAnyRole(MERCHANT, OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/refunds/*/cancel").hasAnyRole(MERCHANT, OPERATOR)
                        .anyRequest().hasRole(OPERATOR));
        return http.build();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }
}
