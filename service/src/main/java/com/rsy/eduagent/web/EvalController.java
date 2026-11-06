package com.rsy.eduagent.web;

import com.rsy.eduagent.evaluation.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvalController {
    private final EvaluationService evaluations;
    public EvalController(EvaluationService evaluations) { this.evaluations = evaluations; }
    @PostMapping("/api/eval/run")
    public EvaluationService.Summary run() { return evaluations.run(); }
}
