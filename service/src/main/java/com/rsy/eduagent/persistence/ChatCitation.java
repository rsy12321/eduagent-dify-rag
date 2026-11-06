package com.rsy.eduagent.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_citation")
public class ChatCitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_message_id", nullable = false) public ChatMessage message;
    @Column(length = 512) public String documentName;
    public Integer segmentPosition;
    public Double score;
    @Column(columnDefinition = "text") public String contentPreview;
}
