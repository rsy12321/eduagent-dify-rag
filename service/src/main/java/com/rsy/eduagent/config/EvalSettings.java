package com.rsy.eduagent.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("eduagent.evaluation")
public record EvalSettings(String questionsPath, String reportsDir, Duration interval) {
    public EvalSettings {
        if (questionsPath == null || questionsPath.isBlank() || reportsDir == null || reportsDir.isBlank()
                || interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("Evaluation paths and a nonnegative interval are required");
        }
    }

    public Path resolvedQuestionsPath() {
        return Paths.get(questionsPath).toAbsolutePath().normalize();
    }

    public Path resolvedReportsDir() {
        return Paths.get(reportsDir).toAbsolutePath().normalize();
    }
}
