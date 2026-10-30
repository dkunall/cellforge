package com.cellforge.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ServiceTokenConfig {

    @Bean
    @ConditionalOnMissingBean(ServiceTokens.class)
    ServiceTokens serviceTokens(@Value("${cellforge.service-token.uri}") URI uri,
                                @Value("${cellforge.service-token.client-id}") String clientId,
                                @Value("${cellforge.service-token.client-secret}") String clientSecret,
                                ObjectMapper json, Clock clock) {
        return new ClientCredentialsTokens(uri, clientId, clientSecret, "cellforge.internal", json, clock);
    }
}
