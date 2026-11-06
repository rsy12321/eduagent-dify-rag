package com.rsy.eduagent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ChatModels {
    private ChatModels() {}

    public record Request(@NotBlank @Size(max = 10000) String question,
                          @Size(max = 128) String conversationId,
                          @NotBlank @Size(max = 128) String userId) {}
    public record Citation(String documentName, Integer segmentPosition, Double score) {}
    public record Reference(String documentName, Integer segmentPosition, Double score,
                            String contentPreview) {
        public Citation citation() { return new Citation(documentName, segmentPosition, score); }
    }
    public record Reply(String answer, String conversationId, String messageId,
                        List<Citation> citations, long latencyMs) {}
    public record Delta(String answer) {}
    public record Done(String messageId, String conversationId, String route,
                       List<Citation> citations, long latencyMs) {}
    public record Error(String code, String message) {}
    public record StreamChunk(String event, String answer) {}
    public record Execution(String answer, String conversationId, String messageId, String route,
                            List<Reference> references, JsonNode retrieverResources, long latencyMs, boolean successful,
                            String errorCode, String errorMessage) {
        public List<Citation> citations() { return references.stream().map(Reference::citation).toList(); }
        public Reply reply() { return new Reply(answer, conversationId, messageId, citations(), latencyMs); }
        public Done done() { return new Done(messageId, conversationId, route, citations(), latencyMs); }
    }
    public record History(Long id, String conversationId, String messageId, String userId,
                          String question, String answer, String route, long latencyMs,
                          String status, String errorMsg, LocalDateTime createdAt,
                          List<Reference> citations) {}

    public static String truncate(String text, int codePoints) {
        if (text == null) return null;
        int length = text.codePointCount(0, text.length());
        return text.substring(0, text.offsetByCodePoints(0, Math.min(length, codePoints)));
    }
}
