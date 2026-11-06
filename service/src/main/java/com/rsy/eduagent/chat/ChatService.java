package com.rsy.eduagent.chat;

import com.rsy.eduagent.chat.ChatModels.*;
import com.rsy.eduagent.dify.DifyClient;
import com.rsy.eduagent.dify.DifyException;
import com.rsy.eduagent.dify.ReplyAccumulator;
import com.rsy.eduagent.config.DifySettings;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {
    private final DifyClient client;
    private final ChatStore store;
    private final CitationPolicy citations;
    private final DifySettings settings;
    public ChatService(DifyClient client, ChatStore store, CitationPolicy citations, DifySettings settings) {
        this.client = client;
        this.store = store;
        this.citations = citations;
        this.settings = settings;
    }

    public Execution blocking(Request request) {
        // Only the upstream response mode changes; HTTP JSON DTOs and the blocking deadline stay unchanged.
        return aggregateStream(request, ignored -> {}, Mono.never(), settings.blockingTimeout());
    }

    public Execution streaming(Request request, Consumer<StreamChunk> consumer, Mono<?> cancellation) {
        return aggregateStream(request, consumer, cancellation, settings.streamTimeout());
    }

    private Execution aggregateStream(Request request, Consumer<StreamChunk> consumer,
                                      Mono<?> cancellation, Duration timeout) {
        return execute(request, state -> {
            client.streaming(request)
                    .takeUntilOther(cancellation)
                    .takeUntil(node -> "message_end".equals(node.path("event").asText()))
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .doOnNext(node -> state.accept(node, consumer)).then()
                    .timeout(timeout).block();
            state.requireEnd();
        });
    }

    private Execution execute(Request request, Consumer<ReplyAccumulator> action) {
        long started = System.nanoTime();
        ReplyAccumulator state = new ReplyAccumulator(request.conversationId());
        DifyException failure = null;
        try {
            action.accept(state);
        } catch (RuntimeException error) {
            failure = DifyClient.safeError(error);
        }
        Execution result = new Execution(state.answer(), state.conversationId(), state.messageId(),
                state.route(), citations.visibleReferences(state.answer(), state.references()),
                state.retrieverResources(), (System.nanoTime() - started) / 1_000_000,
                failure == null, failure == null ? null : failure.code(), failure == null ? null : failure.getMessage());
        // The Dify call is outside the transaction; a single transaction saves message + citations.
        // A database failure is allowed to propagate, and never produces a successful done event.
        store.save(request, result);
        return result;
    }
}
