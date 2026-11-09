package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Google Gemini generateContent REST.
 * 키는 환경변수 GEMINI_API_KEY 에서만 읽고 x-goog-api-key 헤더로만 보낸다(주소에 넣지 않아 로그에 남지 않게).
 */
@Component
public class GeminiProvider implements LlmProvider {
    private final ObjectMapper om = new ObjectMapper();
    private final RestClient http;
    private final String key;
    private final String model;
    private final String baseUrl;

    @Autowired
    public GeminiProvider(@Value("${GEMINI_API_KEY:}") String key,
                          @Value("${GEMINI_MODEL:gemini-flash-latest}") String model,
                          @Value("${app.ai.gemini-url:https://generativelanguage.googleapis.com/v1beta/models}") String baseUrl,
                          @Value("${app.ai.timeout-seconds:20}") int timeout) {
        this(RestClient.builder().requestFactory(AnthropicProvider.factory(Math.max(timeout, 30))), key, model, baseUrl);
    }

    GeminiProvider(RestClient.Builder builder, String key, String model, String baseUrl) {
        this.http = builder.build();
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model == null || model.isBlank() ? "gemini-flash-latest" : model.trim();
        this.baseUrl = baseUrl;
    }

    @Override public String name() { return "gemini"; }
    @Override public String model() { return model; }
    @Override public boolean available() { return key != null; }

    @Override
    public String completeJson(String system, String user, int maxTokens) throws Exception {
        ArrayNode parts = om.createArrayNode();
        parts.addObject().put("text", user);
        return text(generate(system, parts, true, maxTokens));
    }

    /**
     * generateContent 한 번. parts 는 user 차례의 내용(text, inline_data 등).
     * json=true 면 generationConfig.responseMimeType 을 application/json 으로 둔다.
     */
    public JsonNode generate(String system, ArrayNode parts, boolean json, int maxTokens) throws Exception {
        ObjectNode body = om.createObjectNode();
        if (system != null && !system.isBlank()) body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.set("parts", parts);
        ObjectNode gc = body.putObject("generationConfig");
        if (json) gc.put("responseMimeType", "application/json");
        gc.put("maxOutputTokens", Math.max(256, maxTokens * 2)); // 생각(thinking) 토큰 몫을 조금 더 둔다
        gc.put("temperature", 0.3);
        String res = http.post().uri(baseUrl + "/" + model + ":generateContent")
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(om.writeValueAsString(body))
                .retrieve().body(String.class);
        return om.readTree(res);
    }

    /** candidates[0].content.parts[*].text 를 이어 붙인다(생각 요약 part 는 뺀다). 없으면 빈 문자열. */
    public static String text(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        for (JsonNode p : parts) {
            if (p.path("thought").asBoolean(false)) continue;
            if (p.has("text")) sb.append(p.path("text").asText());
        }
        return sb.toString();
    }

    ObjectMapper mapper() { return om; }
}
