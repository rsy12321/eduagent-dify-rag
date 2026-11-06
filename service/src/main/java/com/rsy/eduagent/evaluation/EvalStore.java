package com.rsy.eduagent.evaluation;

import com.rsy.eduagent.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvalStore {
    private final EvalRunRepository runs;
    private final EvalResultRepository results;
    private final Clock clock;
    public EvalStore(EvalRunRepository runs, EvalResultRepository results, Clock clock) {
        this.runs = runs;
        this.results = results;
        this.clock = clock;
    }
    @Transactional
    public Long begin(int total) {
        EvalRun run = new EvalRun();
        run.startedAt = now();
        run.total = total;
        run.note = "RUNNING; content checks for qa/generate require manual review";
        return runs.saveAndFlush(run).id;
    }
    @Transactional
    public void save(EvalResult result) { results.saveAndFlush(result); }
    @Transactional
    public void finish(Long id, int routeCorrect, int refusalCorrect, double avgLatency, String note) {
        EvalRun run = runs.findById(id).orElseThrow();
        run.finishedAt = now();
        run.routeCorrect = routeCorrect;
        run.refusalCorrect = refusalCorrect;
        run.avgLatencyMs = avgLatency;
        run.note = note;
        runs.saveAndFlush(run);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
