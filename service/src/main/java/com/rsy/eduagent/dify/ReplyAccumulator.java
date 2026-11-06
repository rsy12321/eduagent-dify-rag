package com.rsy.eduagent.dify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rsy.eduagent.chat.ChatModels;
import com.rsy.eduagent.chat.ChatModels.Reference;
import com.rsy.eduagent.chat.ChatModels.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Per-request state. Only the consuming worker mutates it. */
public final class ReplyAccumulator {
    private final StringBuilder answer = new StringBuilder();
    private String conversationId;
    private String messageId;
    private String route;
    private boolean ambiguousRoute;
    private boolean ended;
    private List<Reference> references = List.of();
    private JsonNode retrieverResources = JsonNodeFactory.instance.arrayNode();

    public ReplyAccumulator(String conversationId) { this.conversationId = conversationId; }

    public void accept(JsonNode event, Consumer<StreamChunk> consumer) {
        updateIds(event);
        String type = event.path("event").asText();
        switch (type) {
            case "message" -> {
                String delta = requiredText(event, "answer");
                append(delta);
                if (!delta.isEmpty()) consumer.accept(new StreamChunk("message", delta));
            }
            case "message_replace" -> {
                answer.setLength(0);
                append(requiredText(event, "answer"));
                consumer.accept(new StreamChunk("replace", answer.toString()));
            }
            case "node_started", "node_finished" -> {
                JsonNode data = event.path("data");
                checkFailure(data);
                if ("answer".equals(data.path("node_type").asText())) {
                    String candidate = switch (data.path("title").asText()) {
                        case "答疑回复" -> "答疑";
                        case "出题回复" -> "出题";
                        default -> null;
                    };
                    if (candidate != null) {
                        if (route != null && !route.equals(candidate)) ambiguousRoute = true;
                        route = candidate;
                    }
                }
            }
            case "message_end" -> {
                captureReferences(event);
                ended = true;
                requireIds();
            }
            case "workflow_finished" -> checkFailure(event.path("data"));
            case "error" -> throw new DifyException("DIFY_STREAM_ERROR", "Dify reported a stream error", 502);
            default -> { /* Workflow progress, ping and unrelated payloads are not answer text. */ }
        }
    }

    public void blocking(JsonNode node) {
        if ("error".equals(node.path("event").asText())) {
            throw new DifyException("DIFY_STREAM_ERROR", "Dify reported an error", 502);
        }
        updateIds(node);
        append(requiredText(node, "answer"));
        captureReferences(node);
        requireIds();
        ended = true;
    }

    private void append(String text) {
        if ((long) answer.length() + text.length() > 1_000_000) throw DifyException.protocol();
        answer.append(text);
    }
    private void captureReferences(JsonNode node) {
        references = references(node);
        JsonNode raw = node.path("metadata").path("retriever_resources");
        retrieverResources = raw.isArray() ? raw.deepCopy() : JsonNodeFactory.instance.arrayNode();
    }
    private void updateIds(JsonNode node) {
        if (node.path("conversation_id").isTextual()) conversationId = node.path("conversation_id").asText();
        if (node.path("message_id").isTextual()) messageId = node.path("message_id").asText();
    }
    private void requireIds() {
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 128
                || messageId == null || messageId.isBlank() || messageId.length() > 128) throw DifyException.protocol();
    }
    private static String requiredText(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw DifyException.protocol();
        return node.path(key).asText();
    }
    private static void checkFailure(JsonNode data) {
        if (List.of("failed", "exception").contains(data.path("status").asText())) {
            throw new DifyException("DIFY_WORKFLOW_FAILED", "Dify workflow node failed", 502);
        }
    }
    public static List<Reference> references(JsonNode node) {
        JsonNode resources = node.path("metadata").path("retriever_resources");
        if (resources.isMissingNode() || resources.isNull()) return List.of();
        if (!resources.isArray()) throw DifyException.protocol();
        List<Reference> result = new ArrayList<>();
        for (JsonNode resource : resources) {
            JsonNode position = resource.path("segment_position");
            result.add(new Reference(ChatModels.truncate(resource.path("document_name").asText(""), 512),
                    position.isIntegralNumber() ? position.asInt() : null,
                    resource.path("score").isNumber() ? resource.path("score").asDouble() : null,
                    ChatModels.truncate(resource.path("content").asText(""), 300)));
        }
        return List.copyOf(result);
    }
    public void requireEnd() { if (!ended) throw DifyException.protocol(); }
    public String answer() { return answer.toString(); }
    public String conversationId() { return conversationId; }
    public String messageId() { return messageId; }
    public String route() { return ambiguousRoute ? null : route; }
    public List<Reference> references() { return references; }
    public JsonNode retrieverResources() { return retrieverResources.deepCopy(); }
}
