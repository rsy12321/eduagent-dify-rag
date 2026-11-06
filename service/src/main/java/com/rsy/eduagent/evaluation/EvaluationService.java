package com.rsy.eduagent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels.Execution;
import com.rsy.eduagent.chat.ChatModels.Request;
import com.rsy.eduagent.chat.ChatService;
import com.rsy.eduagent.config.EvalSettings;
import com.rsy.eduagent.persistence.EvalResult;
import com.rsy.eduagent.web.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class EvaluationService {
    public record Summary(Long runId, int total, int routeCorrect, int refusalCorrect, int refusalTotal,
                          double routeAccuracy, Double refusalAccuracy, double avgLatencyMs,
                          long maxLatencyMs, int failedRequests, String report) {}
    private final ChatService chat;
    private final EvalStore store;
    private final EvalReportWriter reports;
    private final EvalSettings settings;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public EvaluationService(ChatService chat, EvalStore store, EvalReportWriter reports,
                             EvalSettings settings, ObjectMapper mapper) {
        this.chat = chat;
        this.store = store;
        this.reports = reports;
        this.settings = settings;
        this.mapper = mapper;
    }

    public Summary run() {
        if (!running.compareAndSet(false, true)) throw new ApiException(409, "EVAL_BUSY", "An evaluation is already running");
        Long runId = null;
        List<EvalResult> rows = new ArrayList<>();
        int routeCorrect = 0;
        int refusalCorrect = 0;
        int refusalTotal = 0;
        int failed = 0;
        long sum = 0;
        long maximum = 0;
        boolean finalized = false;
        try {
            List<EvalQuestion> questions = load();
            runId = store.begin(questions.size());
            for (int index = 0; index < questions.size(); index++) {
                if (index > 0) Thread.sleep(settings.interval().toMillis());
                EvalQuestion question = questions.get(index);
                // Every item omits conversation_id, including two items from the same run.
                Execution result = chat.streaming(new Request(question.question(), null, "eval-" + runId),
                        ignored -> {}, Mono.never());
                EvalResult row = new EvalResult();
                row.runId = runId;
                row.questionId = question.id();
                row.type = question.type();
                row.question = question.question();
                row.expectedRoute = question.expectedRoute();
                row.actualRoute = result.route();
                row.answer = result.answer();
                row.referenceAnswer = question.referenceAnswer();
                row.citationsJson = mapper.writeValueAsString(result.retrieverResources());
                row.latencyMs = result.latencyMs();
                row.autoCheck = autoCheck(question.type(), result.answer());
                row.manualVerdict = null;
                store.save(row);
                rows.add(row);
                if (Objects.equals(row.expectedRoute, row.actualRoute)) routeCorrect++;
                if ("out_of_scope".equals(row.type)) {
                    refusalTotal++;
                    if ("PASS".equals(row.autoCheck)) refusalCorrect++;
                }
                if (!result.successful()) failed++;
                sum += row.latencyMs;
                maximum = Math.max(maximum, row.latencyMs);
            }
            double average = sum / (double) rows.size();
            store.finish(runId, routeCorrect, refusalCorrect, average, "COMPLETED; Dify failures=" + failed
                    + "; qa/generate=MANUAL; refusal uses literal phrase only");
            finalized = true;
            Path report;
            try {
                report = reports.write(runId, rows, routeCorrect, refusalCorrect, refusalTotal, average, maximum, failed);
            } catch (IOException error) {
                store.finish(runId, routeCorrect, refusalCorrect, average, "COMPLETED; REPORT_WRITE_FAILED; Dify failures=" + failed);
                throw new ApiException(500, "REPORT_WRITE_FAILED", "Evaluation saved but report write failed; runId=" + runId);
            }
            return new Summary(runId, rows.size(), routeCorrect, refusalCorrect, refusalTotal,
                    routeCorrect / (double) rows.size(), refusalTotal == 0 ? null : refusalCorrect / (double) refusalTotal,
                    average, maximum, failed, report.toString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "EVAL_INTERRUPTED", "Evaluation was interrupted");
        } catch (IOException error) {
            throw new ApiException(500, "EVAL_FILE_ERROR", "Could not read evaluation data or serialize results");
        } finally {
            try {
                if (runId != null && !finalized) {
                    store.finish(runId, routeCorrect, refusalCorrect, rows.isEmpty() ? 0 : sum / (double) rows.size(),
                            "INCOMPLETE; persisted " + rows.size() + " results; no report generated");
                }
            } finally {
                running.set(false);
            }
        }
    }

    public static String autoCheck(String type, String answer) {
        if (!"out_of_scope".equals(type)) return "MANUAL";
        return answer != null && answer.contains("知识库中没有") ? "PASS" : "FAIL";
    }

    private List<EvalQuestion> load() throws IOException {
        List<EvalQuestion> questions = mapper.readValue(settings.resolvedQuestionsPath().toFile(), new TypeReference<>() {});
        if (questions == null || questions.isEmpty()) throw new ApiException(400, "INVALID_EVAL_DATA", "Evaluation dataset must not be empty");
        Set<String> ids = new HashSet<>();
        for (EvalQuestion q : questions) {
            if (q == null || blank(q.id()) || q.id().length() > 32 || !ids.add(q.id())
                    || blank(q.question()) || q.question().length() > 10000 || blank(q.referenceAnswer())
                    || q.type() == null || !List.of("qa", "generate", "out_of_scope").contains(q.type())
                    || !("generate".equals(q.type()) ? "出题" : "答疑").equals(q.expectedRoute())) {
                throw new ApiException(400, "INVALID_EVAL_DATA", "Evaluation IDs, fields, types or routes are invalid");
            }
        }
        return questions;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
