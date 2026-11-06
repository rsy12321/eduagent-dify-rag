package com.rsy.eduagent.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reject a missing key before Flyway or the datasource can attempt any connection. */
@Configuration(proxyBeanMethods = false)
public class CredentialsGuard {
    @Bean
    static BeanFactoryPostProcessor requireDifyApiKey(Environment environment) {
        return factory -> {
            String key;
            try {
                key = environment.getProperty("eduagent.dify.api-key");
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                throw missingKey();
            }
            if (key == null || key.isBlank() || key.contains("${")) throw missingKey();
        };
    }
    private static IllegalStateException missingKey() {
        return new IllegalStateException("DIFY_API_KEY is required and must not be blank");
    }
}
