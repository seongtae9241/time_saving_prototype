package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Claude Messages API. 키는 환경변수 ANTHROPIC_API_KEY 에서만 읽는다. */
@Component
public class AnthropicProvider implements LlmProvider {
    private final ObjectMapper om = new ObjectMapper();
    private final RestClient http;
    private final String key;
    private final String model;
    private final String url;

    @Autowired
    public AnthropicProvider(@Value("${ANTHROPIC_API_KEY:}") String key,
                             @Value("${app.ai.model:claude-haiku-5-5}") String model,
                             @Value("${app.ai.url:https://api.anthropic.com/v1/messages}") String url,
                             @Value("${app.ai.timeout-seconds:20}") int timeout) {
        this(RestClient.builder().requestFactory(factory(timeout)), key, model, url);
    }

    /** 테스트용: MockRestServiceServer 를 묶은 builder 를 받는다. */
    AnthropicProvider(RestClient.Builder builder, String key, String model, String url) {
        this.http = builder.build();
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model;
        this.url = url;
    }

    static SimpleClientHttpRequestFactory factory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutSeconds * 1000);
        f.setReadTimeout(timeoutSeconds * 1000);
        return f;
    }

    @Override public String name() { return "anthropic"; }
    @Override public String model() { return model; }
    @Override public boolean available() { return key != null; }

    @Override
    public String completeJson(String system, String user, int maxTokens) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        ObjectNode m = body.putArray("messages").addObject();
        m.put("role", "user");
        m.put("content", user);
        String res = http.post().uri(url)
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(om.writeValueAsString(body))
                .retrieve().body(String.class);
        JsonNode root = om.readTree(res);
        StringBuilder text = new StringBuilder();
        for (JsonNode c : root.path("content")) if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
        return text.toString();
    }
}
