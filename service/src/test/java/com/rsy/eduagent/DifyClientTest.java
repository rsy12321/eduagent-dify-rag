package com.rsy.eduagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels.Request;
import com.rsy.eduagent.config.DifySettings;
import com.rsy.eduagent.dify.DifyClient;
import com.rsy.eduagent.dify.DifyException;
import com.rsy.eduagent.dify.ReplyAccumulator;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DifyClientTest {
    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Request request = new Request("什么是惯性？", null, "test-user");

    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.shutdown(); }
    private DifyClient client(Duration blocking, Duration streaming) {
        return new DifyClient(new DifySettings(server.url("/v1").toString(), "test-placeholder-not-a-real-key",
                Duration.ofSeconds(1), blocking, streaming), mapper);
    }
    private DifyClient client() { return client(Duration.ofSeconds(3), Duration.ofSeconds(3)); }

    @Test void parsesBlockingAnswerAndRetrieverResourcesAndSendsNewConversation() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"event":"message","mode":"advanced-chat","conversation_id":"c1","message_id":"m1",
                 "answer":"惯性是物体的性质。","metadata":{"retriever_resources":[
                 {"document_name":"03-motion-and-force.md","segment_position":3,"score":0.91,"content":"知识片段"}]}}
                """));
        JsonNode response = client().blocking(request);
        ReplyAccumulator reply = new ReplyAccumulator(null);
        reply.blocking(response);
        assertThat(reply.answer()).isEqualTo("惯性是物体的性质。");
        assertThat(reply.conversationId()).isEqualTo("c1");
        assertThat(reply.messageId()).isEqualTo("m1");
        assertThat(reply.references()).hasSize(1);
        assertThat(reply.references().get(0).documentName()).isEqualTo("03-motion-and-force.md");
        assertThat(reply.references().get(0).segmentPosition()).isEqualTo(3);
        assertThat(reply.references().get(0).score()).isEqualTo(0.91);
        assertThat(reply.references().get(0).contentPreview()).isEqualTo("知识片段");
        var sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getPath()).isEqualTo("/v1/chat-messages");
        JsonNode body = mapper.readTree(sent.getBody().readUtf8());
        assertThat(body.has("conversation_id")).isFalse();
        assertThat(body.path("response_mode").asText()).isEqualTo("blocking");
        assertThat(body.path("inputs").isObject()).isTrue();
        assertThat(body.path("user").asText()).isEqualTo("test-user");
    }

    @ParameterizedTest @ValueSource(ints = {401, 500})
    void mapsHttpErrorsWithoutLeakingUpstreamBody(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("sensitive-upstream-placeholder"));
        assertThatThrownBy(() -> client().blocking(request)).isInstanceOf(DifyException.class)
                .satisfies(error -> {
                    DifyException failure = (DifyException) error;
                    assertThat(failure.code()).isEqualTo(status == 401 ? "DIFY_UNAUTHORIZED" : "DIFY_HTTP_500");
                    assertThat(failure.status()).isEqualTo(502);
                    assertThat(failure.toString()).doesNotContain("sensitive-upstream-placeholder");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test void timesOutBlockingRead() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{}").setBodyDelay(1, TimeUnit.SECONDS));
        assertThatThrownBy(() -> client(Duration.ofMillis(150), Duration.ofSeconds(3)).blocking(request))
                .isInstanceOf(DifyException.class).satisfies(error ->
                        assertThat(((DifyException) error).code()).isEqualTo("DIFY_TIMEOUT"));
    }

    @Test void streamDeadlineIsNotResetByIncomingFrames() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: ping\n\ndata: {\"event\":\"ping\"}\n\n".repeat(100))
                .throttleBody(40, 30, TimeUnit.MILLISECONDS));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client(Duration.ofSeconds(3), Duration.ofMillis(220)).streaming(request).collectList().block())
                .isInstanceOf(DifyException.class).satisfies(error ->
                        assertThat(((DifyException) error).code()).isEqualTo("DIFY_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void malformedStreamJsonIsProtocolFailure() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: not-json\n\n"));
        assertThatThrownBy(() -> client().streaming(request).collectList().block())
                .isInstanceOf(DifyException.class).satisfies(error ->
                        assertThat(((DifyException) error).code()).isEqualTo("DIFY_PROTOCOL_ERROR"));
    }

    @Test void connectionFailureIsMapped() throws Exception {
        DifyClient client = client();
        server.shutdown();
        assertThatThrownBy(() -> client.blocking(request)).isInstanceOf(DifyException.class)
                .satisfies(error -> assertThat(((DifyException) error).code()).isEqualTo("DIFY_CONNECTION_ERROR"));
    }

    @Test void doesNotResendGenerationWhenConnectionBreaksAfterRequest() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThatThrownBy(() -> client().blocking(request)).isInstanceOf(DifyException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test void emptySuccessfulResponseIsProtocolFailure() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json"));
        assertThatThrownBy(() -> client().blocking(request)).isInstanceOf(DifyException.class)
                .satisfies(error -> assertThat(((DifyException) error).code()).isEqualTo("DIFY_PROTOCOL_ERROR"));
    }
}
