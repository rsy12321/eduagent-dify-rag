package com.rsy.eduagent;

import com.rsy.eduagent.config.DifySettings;
import com.rsy.eduagent.config.CredentialsGuard;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DifySettings.class)
    static class PropertiesOnly {}

    @Test void missingApiKeyFailsWithClearMessageBeforeAnyNetworkCall() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("eduagent.dify.base-url=http://127.0.0.1:1/v1",
                        "eduagent.dify.connect-timeout=5s", "eduagent.dify.blocking-timeout=120s",
                        "eduagent.dify.stream-timeout=180s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("DIFY_API_KEY is required and must not be blank");
                });
    }

    @Test void settingsToStringRedactsAuthorizationSecret() {
        var settings = new DifySettings("http://127.0.0.1:1/v1", "synthetic-secret-placeholder",
                Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ofSeconds(180));
        assertThat(settings.toString()).contains("REDACTED").doesNotContain("synthetic-secret-placeholder");
    }

    @Test void unresolvedKeyPlaceholderFailsInBeanFactoryPostProcessor() {
        new ApplicationContextRunner().withUserConfiguration(CredentialsGuard.class)
                .withPropertyValues("eduagent.dify.api-key=${EDUAGENT_TEST_INTENTIONALLY_UNSET_KEY}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("DIFY_API_KEY is required");
                });
    }
}
