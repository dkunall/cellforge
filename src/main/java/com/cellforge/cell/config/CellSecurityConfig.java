package com.cellforge.cell.config;

import com.cellforge.shared.web.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Tokens carry no tenant roles: one person can be an owner in one workspace and a guest in another, so roles
 * come from the membership row (see TenantFilter). Scopes only separate people from the control plane.
 */
@Configuration(proxyBeanMethods = false)
class CellSecurityConfig {

    static final String USER = "SCOPE_cellforge.user";
    static final String INTERNAL = "SCOPE_cellforge.internal";

    @Bean
    SecurityFilterChain cellApi(HttpSecurity http, ObjectMapper json) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/internal/**").hasAuthority(INTERNAL)
                .requestMatchers("/v1/**").hasAuthority(USER)
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> { })
                .authenticationEntryPoint((request, response, e) -> ProblemWriter.write(response, json,
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                .accessDeniedHandler((request, response, e) -> ProblemWriter.write(response, json,
                    HttpStatus.FORBIDDEN, "forbidden", "Token lacks the required scope")))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> ProblemWriter.write(response, json,
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                .accessDeniedHandler((request, response, e) -> ProblemWriter.write(response, json,
                    HttpStatus.FORBIDDEN, "forbidden", "Token lacks the required scope")));
        return http.build();
    }
}
