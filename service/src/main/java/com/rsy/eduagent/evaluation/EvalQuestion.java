package com.rsy.eduagent.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalQuestion(String id, String type, String question,
                           @JsonProperty("expected_route") String expectedRoute,
                           @JsonProperty("reference_answer") String referenceAnswer) {}
