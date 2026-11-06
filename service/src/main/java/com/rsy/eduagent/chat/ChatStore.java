package com.rsy.eduagent.chat;

import com.rsy.eduagent.chat.ChatModels.*;
import com.rsy.eduagent.persistence.ChatCitation;
import com.rsy.eduagent.persistence.ChatMessage;
import com.rsy.eduagent.persistence.ChatMessageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatStore {
    private final ChatMessageRepository repository;
    private final Clock clock;
    public ChatStore(ChatMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void save(Request request, Execution result) {
        ChatMessage message = new ChatMessage();
        message.conversationId = ChatModels.truncate(result.conversationId(), 128);
        message.difyMessageId = ChatModels.truncate(result.messageId(), 128);
        message.userId = request.userId();
        message.question = request.question();
        message.answer = result.answer();
        message.route = result.route();
        message.latencyMs = result.latencyMs();
        message.status = result.successful() ? "SUCCESS" : "FAILED";
        message.errorMsg = result.successful() ? null : result.errorCode() + ": " + result.errorMessage();
        message.createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (Reference ref : result.references()) {
            ChatCitation citation = new ChatCitation();
            citation.message = message;
            citation.documentName = ref.documentName();
            citation.segmentPosition = ref.segmentPosition();
            citation.score = ref.score();
            citation.contentPreview = ref.contentPreview();
            message.citations.add(citation);
        }
        repository.saveAndFlush(message);
    }

    @Transactional(readOnly = true)
    public List<History> history(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
                .map(m -> new History(m.id, m.conversationId, m.difyMessageId, m.userId, m.question,
                        m.answer, m.route, m.latencyMs, m.status, m.errorMsg, m.createdAt,
                        m.citations.stream().map(c -> new Reference(c.documentName, c.segmentPosition,
                                c.score, c.contentPreview)).toList())).toList();
    }
}
