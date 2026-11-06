package com.rsy.eduagent.dify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels.Request;
import com.rsy.eduagent.config.DifySettings;
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class DifyClient {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {};
    private final WebClient blockingClient;
    private final WebClient streamingClient;
    private final ObjectMapper mapper;
    private final DifySettings settings;

    public DifyClient(DifySettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        HttpClient transport = HttpClient.create().followRedirect(false).disableRetry(true)
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(settings.connectTimeout().toMillis()));
        blockingClient = client(transport.responseTimeout(settings.blockingTimeout()));
        streamingClient = client(transport);
    }

    private WebClient client(HttpClient transport) {
        return WebClient.builder().baseUrl(settings.baseUrl().replaceAll("/+$", ""))
                .defaultHeaders(h -> h.setBearerAuth(settings.apiKey()))
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build(); // No request/response logging or wiretap; Authorization is never printed.
    }

    public JsonNode blocking(Request request) {
        JsonNode result = blockingClient.post().uri("/chat-messages")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .bodyValue(body(request, "blocking"))
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(JsonNode.class) : reject(response))
                .switchIfEmpty(Mono.error(DifyException.protocol()))
                .timeout(settings.blockingTimeout()).onErrorMap(DifyClient::safeError).block();
        if (result == null) throw DifyException.protocol();
        return result;
    }

    public Flux<JsonNode> streaming(Request request) {
        return streamingClient.post().uri("/chat-messages")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body(request, "streaming"))
                .exchangeToFlux(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToFlux(SSE) : this.<ServerSentEvent<String>>reject(response).flux())
                .filter(frame -> frame.data() != null && !frame.data().isBlank())
                .map(frame -> parse(frame.data()))
                // One absolute deadline, not an idle timeout reset by each message or ping.
                .takeUntilOther(Mono.delay(settings.streamTimeout())
                        .flatMap(ignored -> Mono.error(DifyException.timeout())))
                .onErrorMap(DifyClient::safeError);
    }

    private JsonNode parse(String data) {
        try {
            JsonNode node = mapper.readTree(data);
            if (node == null || !node.isObject() || !node.path("event").isTextual()) throw DifyException.protocol();
            return node;
        } catch (Exception ignored) {
            throw DifyException.protocol();
        }
    }

    private <T> Mono<T> reject(ClientResponse response) {
        int upstreamStatus = response.statusCode().value();
        String code = upstreamStatus == 401 ? "DIFY_UNAUTHORIZED" : "DIFY_HTTP_" + upstreamStatus;
        return response.releaseBody().then(Mono.error(new DifyException(code,
                "Dify HTTP request failed (" + upstreamStatus + ")", 502)));
    }

    private Map<String, Object> body(Request request, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", Map.of());
        body.put("query", request.question());
        body.put("user", request.userId());
        body.put("response_mode", mode);
        body.put("auto_generate_name", false);
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            body.put("conversation_id", request.conversationId());
        }
        return body;
    }

    public static DifyException safeError(Throwable error) {
        if (error instanceof DifyException known) return known;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof io.netty.channel.ConnectTimeoutException) return DifyException.timeout();
        }
        return new DifyException("DIFY_CONNECTION_ERROR", "Dify connection or response decoding failed", 502);
    }
}
