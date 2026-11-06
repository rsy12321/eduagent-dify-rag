package com.rsy.eduagent.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @EntityGraph(attributePaths = "citations")
    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);
}
