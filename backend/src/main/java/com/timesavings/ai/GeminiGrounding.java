package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기관 클래스 찾기용 Gemini 구글 검색 그라운딩(generateContent + tools:[{google_search:{}}]).
 * 그라운딩과 JSON 응답 모드(responseMimeType)는 같이 쓸 수 없을 수 있어 텍스트로 받고, 호출한 쪽이 글 안의 JSON 부분을 꺼낸다.
 * 키는 GeminiProvider 와 같은 GEMINI_API_KEY(새 환경변수 없음), x-goog-api-key 헤더로만 보낸다. 키·프롬프트는 로그에 남기지 않는다.
 * AiClient 는 고치지 않는다 — 켜짐 여부는 PublicClassAi 가 AiClient.provider() 가 Gemini 인지로 판단한다.
 */
@Component
public class GeminiGrounding {
    private final ObjectMapper om = new ObjectMapper();
    private final RestClient http;
    private final String key;
    private final String model;
    private final String baseUrl;

    @Autowired
    public GeminiGrounding(@Value("${GEMINI_API_KEY:}") String key,
                           @Value("${GEMINI_MODEL:gemini-flash-latest}") String model,
                           @Value("${app.ai.gemini-url:https://generativelanguage.googleapis.com/v1beta/models}") String baseUrl,
                           @Value("${app.ai.timeout-seconds:20}") int timeout) {
        this(RestClient.builder().requestFactory(AnthropicProvider.factory(Math.max(timeout, 40))), key, model, baseUrl);
    }

    /** 테스트용: MockRestServiceServer 를 묶은 builder 를 받는다. */
    GeminiGrounding(RestClient.Builder builder, String key, String model, String baseUrl) {
        this.http = builder.build();
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model == null || model.isBlank() ? "gemini-flash-latest" : model.trim();
        this.baseUrl = baseUrl;
    }

    public boolean available() { return key != null; }

    /** 그라운딩 결과: 모델이 쓴 글, 근거로 쓴 웹 주소들(groundingChunks[].web.uri), 검색어들. */
    public record Result(String text, List<Source> sources, List<String> queries) {}
    public record Source(String uri, String title) {}

    public Result search(String system, String user, int maxTokens) throws Exception {
        ObjectNode body = om.createObjectNode();
        if (system != null && !system.isBlank()) body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", user);
        body.putArray("tools").addObject().putObject("google_search");
        ObjectNode gc = body.putObject("generationConfig");
        gc.put("maxOutputTokens", Math.max(1024, maxTokens * 2));
        gc.put("temperature", 0.2);
        String res = http.post().uri(baseUrl + "/" + model + ":generateContent")
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(om.writeValueAsString(body))
                .retrieve().body(String.class);
        return parse(om.readTree(res));
    }

    /** 응답 파싱(네트워크 없음, 테스트용으로 공개). */
    public static Result parse(JsonNode root) {
        String text = GeminiProvider.text(root);
        JsonNode gm = root.path("candidates").path(0).path("groundingMetadata");
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode c : gm.path("groundingChunks")) {
            String uri = c.path("web").path("uri").asText("");
            if (!uri.isBlank() && seen.add(uri)) sources.add(new Source(uri, c.path("web").path("title").asText(null)));
        }
        List<String> queries = new ArrayList<>();
        for (JsonNode q : gm.path("webSearchQueries")) if (q.isTextual()) queries.add(q.asText());
        return new Result(text, sources, queries);
    }
}
