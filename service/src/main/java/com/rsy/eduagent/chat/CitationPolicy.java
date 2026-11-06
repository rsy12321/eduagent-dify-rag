package com.rsy.eduagent.chat;

import com.rsy.eduagent.chat.ChatModels.Reference;
import com.rsy.eduagent.config.CitationSettings;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CitationPolicy {
    private final CitationSettings settings;

    public CitationPolicy(CitationSettings settings) { this.settings = settings; }

    /** Strip surrounding whitespace for prefix matching only; keep the original answer and diagnostics intact. */
    public List<Reference> visibleReferences(String answer, List<Reference> retrieved) {
        String normalizedAnswer = answer == null ? null : answer.strip();
        if (normalizedAnswer != null && settings.refusalPrefixes().stream().anyMatch(normalizedAnswer::startsWith)) {
            return List.of();
        }
        return List.copyOf(retrieved);
    }
}
