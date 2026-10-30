package com.cellforge.edge.config;

import com.cellforge.shared.web.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The edge verifies tokens before spending a rate-limit token or a cell round trip on them. The cell verifies
 * them again; the edge is not trusted to have done it.
 */
@Configuration(proxyBeanMethods = false)
class EdgeSecurityConfig {

    static final String USER = "SCOPE_cellforge.user";
    static final String INTERNAL = "SCOPE_cellforge.internal";
    static final String ADMIN = "SCOPE_cellforge.admin";

    @Bean
    SecurityFilterChain edgeApi(HttpSecurity http, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) -> ProblemWriter.write(response, json,
            HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required");
        AccessDeniedHandler denied = (request, response, e) -> ProblemWriter.write(response, json,
            HttpStatus.FORBIDDEN, "forbidden", "Token lacks the required scope");
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                // Authenticated by the provider's HMAC signature, not a bearer token.
                .requestMatchers("/webhooks/**").permitAll()
                .requestMatchers("/internal/tenants/*/move", "/internal/billing/**").hasAuthority(ADMIN)
                .requestMatchers("/internal/**").hasAuthority(INTERNAL)
                .requestMatchers("/v1/**").hasAuthority(USER)
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { })
                .authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
        return http.build();
    }
}
