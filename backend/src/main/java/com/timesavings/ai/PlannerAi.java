package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 플래너·생애 플랜·AI 도움 추천의 설명 문장(2026-11-11 역할별 정리판).
 * 숫자·순위는 모두 코드가 계산해 facts 로 넘기고, AI 는 그 숫자만 써서 쉬운 문장으로 풀어 쓴다.
 * 키가 없거나 실패하면 코드가 만든 ruleText 를 그대로 돌려준다. 결과에 source: "ai" | "rule".
 */
@Component
public class PlannerAi {
    private final ActivityAi ai;

    public PlannerAi(ActivityAi ai) { this.ai = ai; }

    public Map<String, Object> explain(String role, Map<String, Object> facts, String ruleText) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<JsonNode> j = ai.ask("""
                시간은행(봉사 시간을 저축해 나중에 도움을 받는 서비스)의 안내 도우미다. 역할: %s
                주어진 계산 결과(JSON)의 숫자만 쓰고 새로 계산하거나 지어내지 않는다. 시간은 상속되지 않고 가족에게만 선물할 수 있다.
                쉬운 존댓말 2~3문장. 출력: {"text":"..."}
                """.formatted(role), "계산 결과(JSON): " + Views.json(facts) + "\n기본 안내문: " + ruleText, 400);
        String t = j.map(n -> n.path("text").asText("").trim()).orElse("");
        if (!t.isBlank()) {
            m.put("text", t.length() > 400 ? t.substring(0, 400) : t);
            m.put("source", "ai");
            m.put("provider", ai.providerName());
        } else {
            m.put("text", ruleText);
            m.put("source", "rule");
        }
        return m;
    }
}
