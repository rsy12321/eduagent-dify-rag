package com.rsy.eduagent.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("eduagent.dify")
public record DifySettings(String baseUrl, String apiKey, Duration connectTimeout,
                           Duration blockingTimeout, Duration streamTimeout) {
    public DifySettings {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("${DIFY_API_KEY}")) {
            throw new IllegalArgumentException("DIFY_API_KEY is required and must not be blank");
        }
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("DIFY_BASE_URL must be an HTTP(S) API URL without credentials or query");
        }
        for (Duration timeout : new Duration[]{connectTimeout, blockingTimeout, streamTimeout}) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Dify timeouts must be positive durations");
            }
        }
    }

    @Override
    public String toString() {
        return "DifySettings[apiKey=REDACTED]";
    }
}
