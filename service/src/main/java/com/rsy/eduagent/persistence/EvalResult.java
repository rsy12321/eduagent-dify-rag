package com.rsy.eduagent.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "eval_result")
public class EvalResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long runId;
    @Column(nullable = false, length = 32) public String questionId;
    @Column(nullable = false, length = 32) public String type;
    @Column(nullable = false, columnDefinition = "longtext") public String question;
    @Column(nullable = false, length = 16) public String expectedRoute;
    @Column(length = 16) public String actualRoute;
    @Column(nullable = false, columnDefinition = "longtext") public String answer;
    @Column(nullable = false, columnDefinition = "longtext") public String referenceAnswer;
    @Column(nullable = false, columnDefinition = "longtext") public String citationsJson;
    @Column(nullable = false) public long latencyMs;
    @Column(nullable = false, length = 16) public String autoCheck;
    @Column(length = 32) public String manualVerdict;
}
