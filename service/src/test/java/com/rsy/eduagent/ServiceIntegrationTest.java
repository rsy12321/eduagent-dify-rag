package com.rsy.eduagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels.Request;
import com.rsy.eduagent.chat.ChatService;
import com.rsy.eduagent.chat.ChatStore;
import com.rsy.eduagent.dify.DifyException;
import com.rsy.eduagent.evaluation.EvaluationService;
import com.rsy.eduagent.persistence.*;
import com.rsy.eduagent.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceIntegrationTest {
    private static final MockWebServer SERVER = new MockWebServer();
    private static final Path FIXTURE_DIR;
    static {
        try {
            SERVER.start();
            Files.createDirectories(Path.of("target"));
            FIXTURE_DIR = Files.createTempDirectory(Path.of("target"), "eval-test-").toAbsolutePath();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("eduagent.dify.base-url", () -> SERVER.url("/v1").toString());
        registry.add("eduagent.evaluation.questions-path", () -> FIXTURE_DIR.resolve("questions.json").toString());
        registry.add("eduagent.evaluation.reports-dir", () -> FIXTURE_DIR.resolve("reports").toString());
    }
    @AfterAll static void stopServer() throws Exception { SERVER.shutdown(); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatMessageRepository messages;
    @Autowired EvalResultRepository results;
    @Autowired EvalRunRepository runs;
    @Autowired ChatStore store;
    @Autowired ChatService chat;
    @Autowired EvaluationService evaluations;

    @BeforeEach void reset() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        jdbc.update("DELETE FROM chat_citation");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM eval_result");
        jdbc.update("DELETE FROM eval_run");
        while (SERVER.takeRequest(10, TimeUnit.MILLISECONDS) != null) { /* drain only test requests */ }
        Path reports = FIXTURE_DIR.resolve("reports");
        if (Files.exists(reports)) {
            try (var files = Files.list(reports)) {
                for (Path file : files.toList()) Files.delete(file);
            }
        }
    }

    @Test void blockingAggregatesStreamPreservingJsonContractAndConversation() throws Exception {
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("第一次", "回答"), "c1", "m1")));
        var response = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("第一问", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("第一次回答"))
                .andExpect(jsonPath("$.conversationId").value("c1"))
                .andExpect(jsonPath("$.messageId").value("m1"))
                .andExpect(jsonPath("$.citations[0].segmentPosition").value(3)).andReturn();
        Set<String> fields = new java.util.HashSet<>();
        mapper.readTree(response.getResponse().getContentAsString()).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("answer", "conversationId", "messageId", "citations", "latencyMs");
        JsonNode firstRequest = mapper.readTree(SERVER.takeRequest().getBody().readUtf8());
        assertThat(firstRequest.has("conversation_id")).isFalse();
        assertThat(firstRequest.path("response_mode").asText()).isEqualTo("streaming");
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("第二次回答"), "c1", "m2")));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(requestJson("追问", "c1")))
                .andExpect(status().isOk());
        JsonNode sent = mapper.readTree(SERVER.takeRequest().getBody().readUtf8());
        assertThat(sent.path("conversation_id").asText()).isEqualTo("c1");
        assertThat(sent.path("user").asText()).isEqualTo("integration-user");
        mvc.perform(get("/api/conversations/c1/messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].citations[0].documentName").value("03-motion-and-force.md"));
        var history = store.history("c1");
        assertThat(history.get(0).route()).isEqualTo("答疑");
        assertThat(history.get(0).citations().get(0).contentPreview()).hasSize(300);
    }

    @Test void streamingDoesNotForwardEmptyMessageEvents() throws Exception {
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("", "有效回答", "", ""), "c-empty", "m-empty")));
        String response = streamRequest();
        assertThat(response).doesNotContain("\"answer\":\"\"");
        assertThat(response.lines().filter("event:message"::equals).count()).isEqualTo(1);
        assertThat(response).contains("event:done");
        assertThat(store.history("c-empty").get(0).answer()).isEqualTo("有效回答");
    }

    @ParameterizedTest
    @ValueSource(strings = {"知识库中没有找到相关内容", "知识库中没有足够的相关内容"})
    void blockingRefusalHidesCitationsAndDoesNotPersistThem(String prefix) throws Exception {
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of(prefix, "，请补充资料。"), "c-refuse", "m-refuse")));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(requestJson("范围外问题", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.citations").isEmpty());
        assertThat(store.history("c-refuse").get(0).citations()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_citation", Integer.class)).isZero();
    }

    @Test void streamingFinalReplacementRefusalHidesCitationsInDoneAndHistory() throws Exception {
        String body = frame(Map.of("event", "message", "answer", "初始答案", "conversation_id", "c-replace", "message_id", "m-replace"))
                + frame(Map.of("event", "message_replace", "answer", "知识库中没有足够的相关内容，请补充资料。"))
                + frame(Map.of("event", "message_end", "metadata", metadata()));
        SERVER.enqueue(sseResponse(body));
        assertThat(streamRequest()).contains("event:replace", "event:done", "\"citations\":[]")
                .doesNotContain("03-motion-and-force.md");
        var history = store.history("c-replace").get(0);
        assertThat(history.answer()).startsWith("知识库中没有足够的相关内容");
        assertThat(history.citations()).isEmpty();
    }

    @Test void evaluationRetainsRawRetrievalOnRefusalWhileChatCitationsStayEmpty() throws Exception {
        writeQuestions(List.of(question("Q01", "out_of_scope")));
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("知识库中没有找到相关内容，请补充资料。"), "c-eval-refuse", "m-eval-refuse")));
        var summary = evaluations.run();
        var row = results.findByRunIdOrderByIdAsc(summary.runId()).get(0);
        JsonNode raw = mapper.readTree(row.citationsJson);
        assertThat(raw).isEqualTo(mapper.valueToTree(metadata().get("retriever_resources")));
        assertThat(raw.get(0).path("content").asText()).hasSize(320);
        assertThat(raw.get(0).path("document_name").asText()).isEqualTo("03-motion-and-force.md");
        assertThat(store.history("c-eval-refuse").get(0).citations()).isEmpty();
        assertThat(Files.readString(Path.of(summary.report())))
                .contains("检索命中（拒答时不展示给用户）", "03-motion-and-force.md");
    }

    @Test void validationRejectsBlankQuestionWithoutCallingDify() throws Exception {
        int before = SERVER.getRequestCount();
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(requestJson(" ", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(SERVER.getRequestCount()).isEqualTo(before);
        assertThat(messages.count()).isZero();
    }

    @Test void blockingHttpFailureIsSavedAndMappedToBadGateway() throws Exception {
        SERVER.enqueue(new MockResponse().setResponseCode(401).setBody("sensitive-placeholder"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(requestJson("问题", null)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DIFY_UNAUTHORIZED"));
        var message = messages.findAll().get(0);
        assertThat(message.status).isEqualTo("FAILED");
        assertThat(message.errorMsg).contains("DIFY_UNAUTHORIZED").doesNotContain("sensitive-placeholder");
    }

    @Test void sseForwardsEachDeltaThenPersistsRouteAndCitationsBeforeDone() throws Exception {
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("惯性", "是性质。"), "c-stream", "m-stream")));
        String response = streamRequest();
        assertThat(response).contains("event:message", "\"answer\":\"惯性\"", "\"answer\":\"是性质。\"",
                "event:done", "\"route\":\"答疑\"", "\"messageId\":\"m-stream\"");
        assertThat(response.indexOf("惯性")).isLessThan(response.indexOf("是性质。"));
        assertThat(response.indexOf("是性质。")).isLessThan(response.indexOf("event:done"));
        var history = store.history("c-stream");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).answer()).isEqualTo("惯性是性质。");
        assertThat(history.get(0).route()).isEqualTo("答疑");
        assertThat(history.get(0).status()).isEqualTo("SUCCESS");
        assertThat(history.get(0).citations()).hasSize(1);
        assertThat(mapper.readTree(SERVER.takeRequest().getBody().readUtf8()).path("response_mode").asText()).isEqualTo("streaming");
    }

    @Test void streamingErrorSavesPartialAnswerAndSendsOnlyErrorTerminalEvent() throws Exception {
        String body = frame(Map.of("event", "message", "answer", "部分回答", "conversation_id", "cf", "message_id", "mf"))
                + frame(Map.of("event", "error", "status", 500, "message", "sensitive-upstream-placeholder"));
        SERVER.enqueue(sseResponse(body));
        String response = streamRequest();
        assertThat(response).contains("部分回答", "event:error", "DIFY_STREAM_ERROR")
                .doesNotContain("event:done", "sensitive-upstream-placeholder");
        var saved = store.history("cf").get(0);
        assertThat(saved.status()).isEqualTo("FAILED");
        assertThat(saved.answer()).isEqualTo("部分回答");
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test void streamWithoutMessageEndIsRecordedAsFailure() throws Exception {
        SERVER.enqueue(sseResponse(frame(Map.of("event", "message", "answer", "截断文本"))));
        assertThat(streamRequest()).contains("event:error", "DIFY_PROTOCOL_ERROR").doesNotContain("event:done");
        assertThat(messages.findAll().get(0).status).isEqualTo("FAILED");
    }

    @Test void streamingHttpErrorIsPersisted() throws Exception {
        SERVER.enqueue(new MockResponse().setResponseCode(500));
        assertThat(streamRequest()).contains("event:error", "DIFY_HTTP_500").doesNotContain("event:done");
        assertThat(messages.findAll().get(0).status).isEqualTo("FAILED");
    }

    @Test void cancellationProducesOneFailedLocalRecord() {
        var execution = chat.streaming(new Request("取消", null, "cancel-test"), ignored -> {},
                Mono.error(DifyException.disconnected()));
        assertThat(execution.successful()).isFalse();
        assertThat(execution.errorCode()).isEqualTo("CLIENT_DISCONNECTED");
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test void evaluationComputesSeparateRatesAndWritesEscapedReportWithFreshConversations() throws Exception {
        writeQuestions(List.of(question("Q01", "qa"), question("Q02", "generate"),
                question("Q03", "out_of_scope"), question("Q04", "out_of_scope")));
        for (String answer : List.of("参考<b>|内容\n第二行", "生成题目", "知识库中没有这部分内容", "直接给出范围外答案")) {
            SERVER.enqueue(sseResponse(successStream("答疑回复", List.of(answer), "eval-conversation", "eval-message")));
        }
        var http = mvc.perform(post("/api/eval/run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.routeCorrect").value(3))
                .andExpect(jsonPath("$.refusalCorrect").value(1))
                .andExpect(jsonPath("$.refusalTotal").value(2))
                .andExpect(jsonPath("$.routeAccuracy").value(0.75))
                .andExpect(jsonPath("$.refusalAccuracy").value(0.5)).andReturn();
        JsonNode summary = mapper.readTree(http.getResponse().getContentAsString());
        long runId = summary.path("runId").asLong();
        List<EvalResult> saved = results.findByRunIdOrderByIdAsc(runId);
        assertThat(saved).extracting(row -> row.autoCheck).containsExactly("MANUAL", "MANUAL", "PASS", "FAIL");
        assertThat(saved).allMatch(row -> row.manualVerdict == null);
        assertThat(saved).allMatch(row -> row.referenceAnswer.equals("模拟参考标准"));
        assertThat(saved).allMatch(row -> row.citationsJson.contains("03-motion-and-force.md"));
        var run = runs.findById(runId).orElseThrow();
        assertThat(run.finishedAt).isNotNull();
        assertThat(run.routeCorrect).isEqualTo(3);
        assertThat(run.refusalCorrect).isEqualTo(1);
        double expectedMean = saved.stream().mapToLong(row -> row.latencyMs).average().orElseThrow();
        assertThat(run.avgLatencyMs).isEqualTo(expectedMean);
        assertThat(summary.path("maxLatencyMs").asLong()).isEqualTo(saved.stream().mapToLong(row -> row.latencyMs).max().orElseThrow());
        Path report = Path.of(summary.path("report").asText());
        assertThat(report.getFileName().toString()).matches("eval-\\d{8}-\\d{6}\\.md");
        assertThat(Files.readString(report)).contains("3/4（75.00%）", "1/2（50.00%）", "MANUAL",
                "&lt;b&gt;\\|内容<br>第二行", "03-motion-and-force.md");
        for (int i = 0; i < 4; i++) {
            JsonNode body = mapper.readTree(SERVER.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
            assertThat(body.has("conversation_id")).isFalse();
            assertThat(body.path("response_mode").asText()).isEqualTo("streaming");
        }
    }

    @Test void evaluationPreservesManualStatusOnFailedQaAndUsesNullForEmptyRefusalDenominator() throws Exception {
        writeQuestions(List.of(question("Q01", "qa")));
        SERVER.enqueue(sseResponse(frame(Map.of("event", "error", "message", "test failure"))));
        var summary = evaluations.run();
        assertThat(summary.failedRequests()).isEqualTo(1);
        assertThat(summary.routeCorrect()).isZero();
        assertThat(summary.refusalAccuracy()).isNull();
        assertThat(results.findByRunIdOrderByIdAsc(summary.runId()).get(0).autoCheck).isEqualTo("MANUAL");
        assertThat(runs.findById(summary.runId()).orElseThrow().note).contains("Dify failures=1");
    }

    @Test void concurrentEvaluationIsRejectedInsteadOfStartingAnotherBatch() throws Exception {
        writeQuestions(List.of(question("Q01", "qa")));
        SERVER.enqueue(sseResponse(successStream("答疑回复", List.of("答案"), "c", "m"))
                .setBodyDelay(700, TimeUnit.MILLISECONDS));
        var first = CompletableFuture.supplyAsync(evaluations::run);
        assertThat(SERVER.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThatThrownBy(evaluations::run).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(409));
        assertThat(first.get(5, TimeUnit.SECONDS).total()).isEqualTo(1);
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test void invalidDatasetFailsBeforeAnyDifyRequestOrDatabaseRun() throws Exception {
        writeQuestions(List.of(question("Q01", "unknown")));
        int before = SERVER.getRequestCount();
        assertThatThrownBy(evaluations::run).isInstanceOf(ApiException.class);
        assertThat(SERVER.getRequestCount()).isEqualTo(before);
        assertThat(runs.count()).isZero();
    }

    private String streamRequest() throws Exception {
        var pending = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("问题", null))).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(8000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private String requestJson(String question, String conversation) throws Exception {
        return mapper.writeValueAsString(new Request(question, conversation, "integration-user"));
    }
    private String blocking(String answer, String conversation, String message) throws Exception {
        return mapper.writeValueAsString(Map.of("event", "message", "answer", answer,
                "conversation_id", conversation, "message_id", message, "metadata", metadata()));
    }
    private Map<String, Object> metadata() {
        return Map.of("retriever_resources", List.of(Map.of("document_name", "03-motion-and-force.md",
                "segment_position", 3, "score", 0.9, "content", "知".repeat(320))));
    }
    private String successStream(String title, List<String> chunks, String conversation, String message) throws Exception {
        StringBuilder text = new StringBuilder("event: ping\n\n");
        text.append(frame(Map.of("event", "node_started", "data", Map.of("node_type", "answer", "title", title))));
        for (String chunk : chunks) text.append(frame(Map.of("event", "message", "answer", chunk,
                "conversation_id", conversation, "message_id", message)));
        text.append(frame(Map.of("event", "node_finished", "data", Map.of("node_type", "answer", "title", title, "status", "succeeded"))));
        text.append(frame(Map.of("event", "message_end", "conversation_id", conversation,
                "message_id", message, "metadata", metadata())));
        return text.toString();
    }
    private String frame(Object value) throws Exception { return "data: " + mapper.writeValueAsString(value) + "\n\n"; }
    private MockResponse sseResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body);
    }
    private MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
    private Map<String, String> question(String id, String type) {
        return Map.of("id", id, "type", type, "question", "模拟问题 " + id,
                "expected_route", "generate".equals(type) ? "出题" : "答疑", "reference_answer", "模拟参考标准");
    }
    private void writeQuestions(List<Map<String, String>> questions) throws Exception {
        mapper.writeValue(FIXTURE_DIR.resolve("questions.json").toFile(), questions);
    }
}
