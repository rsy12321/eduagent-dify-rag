package com.rsy.eduagent.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "eval_run")
public class EvalRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    @Column(nullable = false) public int total;
    @Column(nullable = false) public int routeCorrect;
    @Column(nullable = false) public int refusalCorrect;
    @Column(nullable = false) public double avgLatencyMs;
    @Column(columnDefinition = "text") public String note;
}
