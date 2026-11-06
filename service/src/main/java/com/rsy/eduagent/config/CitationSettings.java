package com.rsy.eduagent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("eduagent.citations")
public record CitationSettings(List<String> refusalPrefixes) {
    public CitationSettings {
        if (refusalPrefixes == null || refusalPrefixes.isEmpty()
                || refusalPrefixes.stream().anyMatch(prefix -> prefix == null || prefix.isBlank())) {
            throw new IllegalArgumentException("Citation refusal prefixes must be nonempty strings");
        }
        refusalPrefixes = List.copyOf(refusalPrefixes);
    }
}
