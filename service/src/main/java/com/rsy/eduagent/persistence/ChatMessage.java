package com.rsy.eduagent.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(length = 128) public String conversationId;
    @Column(length = 128) public String difyMessageId;
    @Column(nullable = false, length = 128) public String userId;
    @Column(nullable = false, columnDefinition = "longtext") public String question;
    @Column(nullable = false, columnDefinition = "longtext") public String answer;
    @Column(length = 16) public String route;
    @Column(nullable = false) public long latencyMs;
    @Column(nullable = false, length = 16) public String status;
    @Column(length = 512) public String errorMsg;
    @Column(nullable = false) public LocalDateTime createdAt;
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    public List<ChatCitation> citations = new ArrayList<>();
}
