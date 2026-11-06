package com.rsy.eduagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.*;
import com.rsy.eduagent.chat.ChatModels.Request;
import com.rsy.eduagent.config.CitationSettings;
import com.rsy.eduagent.config.DifySettings;
import com.rsy.eduagent.dify.DifyClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTimeoutTest {
    @Test void blockingAggregationKeepsItsOwnDeadlineAndPersistsFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"event\":\"message\",\"answer\":\"迟到\"}\n\n")
                    .setBodyDelay(1, TimeUnit.SECONDS));
            var settings = new DifySettings(server.url("/v1").toString(), "test-placeholder",
                    Duration.ofSeconds(1), Duration.ofMillis(150), Duration.ofSeconds(5));
            ChatStore store = mock(ChatStore.class);
            ChatService service = new ChatService(new DifyClient(settings, new ObjectMapper()), store,
                    new CitationPolicy(new CitationSettings(List.of("知识库中没有找到相关内容"))), settings);
            Request request = new Request("问题", null, "timeout-test");
            var result = service.blocking(request);
            assertThat(result.successful()).isFalse();
            assertThat(result.errorCode()).isEqualTo("DIFY_TIMEOUT");
            assertThat(result.latencyMs()).isLessThan(1000);
            verify(store).save(request, result);
        }
    }
}
