package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 2026-11-09 새 AI 기능(위치 공공 서비스·공고 정리)이 쓰는 작은 도우미.
 * AiClient 가 고른 공급자(LlmProvider: Gemini/Anthropic)를 그대로 쓰고, 키가 없거나 실패하면 Optional.empty() → 호출한 쪽이 규칙 결과를 쓴다.
 * 키·위치 좌표·입력 문장은 로그에 남기지 않는다.
 */
@Component
public class LlmJson {
    private static final Logger log = LoggerFactory.getLogger(LlmJson.class);
    private final ObjectMapper om = new ObjectMapper();
    private final AiClient ai;

    public LlmJson(AiClient ai) { this.ai = ai; }

    public boolean available() { return ai.enabled(); }

    public String providerName() { return ai.providerName(); }

    public Optional<JsonNode> ask(String system, String user, int maxTokens) {
        if (!ai.enabled()) return Optional.empty();
        LlmProvider p = ai.provider();
        if (p == null) return Optional.empty();
        try {
            String t = p.completeJson(system + "\n\n반드시 JSON 객체 하나만 출력한다. 설명 문장·코드 블록 표시는 쓰지 않는다.", user, maxTokens);
            Optional<JsonNode> j = AiClient.parseJsonObject(om, t);
            if (j.isEmpty()) log.warn("AI 응답 형식 이상({}), 규칙 모드로 대체", p.name());
            return j;
        } catch (Exception e) {
            log.warn("AI 호출 실패({}, {}), 규칙 모드로 대체", p.name(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
