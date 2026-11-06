package com.rsy.eduagent.web;

import com.rsy.eduagent.chat.ChatModels;
import com.rsy.eduagent.chat.ChatModels.*;
import com.rsy.eduagent.chat.ChatService;
import com.rsy.eduagent.chat.ChatStore;
import com.rsy.eduagent.config.DifySettings;
import com.rsy.eduagent.dify.DifyException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService service;
    private final ChatStore store;
    private final ThreadPoolTaskExecutor executor;
    private final DifySettings settings;
    public ChatController(ChatService service, ChatStore store, ThreadPoolTaskExecutor executor, DifySettings settings) {
        this.service = service;
        this.store = store;
        this.executor = executor;
        this.settings = settings;
    }

    @PostMapping("/chat")
    public Reply chat(@Valid @RequestBody Request request) {
        Execution result = service.blocking(request);
        if (!result.successful()) throw new ApiException("DIFY_TIMEOUT".equals(result.errorCode()) ? 504 : 502,
                result.errorCode(), result.errorMessage());
        return result.reply();
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody Request request) {
        SseEmitter emitter = new SseEmitter(settings.streamTimeout().toMillis() + 10_000);
        Sinks.One<Void> cancellation = Sinks.one();
        AtomicBoolean terminal = new AtomicBoolean();
        Runnable disconnect = () -> {
            terminal.set(true);
            cancellation.tryEmitError(DifyException.disconnected());
        };
        emitter.onTimeout(disconnect);
        emitter.onError(ignored -> disconnect.run());
        emitter.onCompletion(() -> { if (!terminal.get()) disconnect.run(); });
        try {
            executor.execute(() -> {
                try {
                    Execution result = service.streaming(request, chunk -> {
                        if (terminal.get()) throw DifyException.disconnected();
                        send(emitter, chunk.event(), new Delta(chunk.answer()));
                    }, cancellation.asMono());
                    if (terminal.compareAndSet(false, true)) {
                        if (result.successful()) send(emitter, "done", result.done());
                        else send(emitter, "error", new ChatModels.Error(result.errorCode(), result.errorMessage()));
                    }
                } catch (RuntimeException ignored) {
                    if (terminal.compareAndSet(false, true)) {
                        try { send(emitter, "error", new ChatModels.Error("PERSISTENCE_ERROR", "Could not persist chat result")); }
                        catch (RuntimeException disconnected) { /* The client may already be gone. */ }
                    }
                } finally {
                    emitter.complete();
                }
            });
        } catch (TaskRejectedException overloaded) {
            throw new ApiException(503, "STREAM_CAPACITY", "All stream workers are busy; retry later");
        }
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON)); }
        catch (IOException | IllegalStateException ignored) { throw DifyException.disconnected(); }
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<History> history(@PathVariable String conversationId) {
        if (conversationId.isBlank() || conversationId.length() > 128) {
            throw new ApiException(400, "INVALID_REQUEST", "Invalid conversationId");
        }
        return store.history(conversationId);
    }
}
