package com.rsy.eduagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels;
import com.rsy.eduagent.dify.DifyException;
import com.rsy.eduagent.dify.ReplyAccumulator;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ReplyAccumulatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"node_started", "node_finished"})
    void infersRouteOnlyFromAnswerNode(String event) throws Exception {
        var state = new ReplyAccumulator(null);
        state.accept(mapper.readTree("{\"event\":\"" + event + "\",\"data\":{\"node_type\":\"answer\",\"title\":\"出题回复\"}}"), ignored -> {});
        assertThat(state.route()).isEqualTo("出题");
    }

    @Test void doesNotGuessFromTopLevelTitleOrLlmTitle() throws Exception {
        var state = new ReplyAccumulator(null);
        state.accept(mapper.readTree("""
                {"event":"node_started","title":"答疑回复","data":{"node_type":"llm","title":"答疑回复"}}
                """), ignored -> {});
        assertThat(state.route()).isNull();
    }

    @Test void contradictoryBranchesProduceUnknownRoute() throws Exception {
        var state = new ReplyAccumulator(null);
        for (String title : new String[]{"答疑回复", "出题回复", "出题回复"}) {
            state.accept(mapper.readTree("{\"event\":\"node_started\",\"data\":{\"node_type\":\"answer\",\"title\":\"" + title + "\"}}"), ignored -> {});
        }
        assertThat(state.route()).isNull();
    }

    @Test void replacementOverwritesPreviouslyAccumulatedText() throws Exception {
        var state = new ReplyAccumulator(null);
        var chunks = new ArrayList<ChatModels.StreamChunk>();
        state.accept(mapper.readTree("{\"event\":\"message\",\"answer\":\"初稿\"}"), chunks::add);
        state.accept(mapper.readTree("{\"event\":\"message_replace\",\"answer\":\"替换稿\"}"), chunks::add);
        assertThat(state.answer()).isEqualTo("替换稿");
        assertThat(chunks.get(1).event()).isEqualTo("replace");
    }

    @Test void nodeFailureIsNotSuccessEvenWithoutErrorEvent() throws Exception {
        var state = new ReplyAccumulator(null);
        var event = mapper.readTree("{\"event\":\"node_finished\",\"data\":{\"status\":\"failed\",\"error\":\"do-not-leak\"}}");
        assertThatThrownBy(() -> state.accept(event, ignored -> {})).isInstanceOf(DifyException.class)
                .hasMessage("Dify workflow node failed");
    }

    @Test void truncatesByUnicodeCodePointWithoutSplittingSurrogates() {
        String text = "物😀".repeat(200);
        String preview = ChatModels.truncate(text, 300);
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(300);
        assertThat(preview).isEqualTo("物😀".repeat(150));
    }

    @Test void skipsEmptyMessageDeltasButPreservesWhitespaceAndAggregation() throws Exception {
        var state = new ReplyAccumulator(null);
        var chunks = new ArrayList<ChatModels.StreamChunk>();
        for (String text : new String[]{"", "前", "", " ", "后", ""}) {
            state.accept(mapper.valueToTree(java.util.Map.of("event", "message", "answer", text)), chunks::add);
        }
        assertThat(state.answer()).isEqualTo("前 后");
        assertThat(chunks).extracting(ChatModels.StreamChunk::answer).containsExactly("前", " ", "后");
    }
}
