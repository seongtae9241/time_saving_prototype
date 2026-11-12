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

    /**
     * 41판 B-61 [플래너 AI]: 고른 추천 봉사마다 내 빈 시간 후보 가운데 시작 시각을 고른다.
     * 후보(candidates)는 서버가 공고 범위·겹침을 따져 만든 것이고, AI 는 그 가운데 하나를 고르기만 한다.
     * 돌려주는 값: {postId → startHour}. 후보에 없는 값·빠진 공고는 호출한 쪽(서버)이 규칙으로 정한다. 키가 없으면 빈 Map.
     */
    public Map<Long, Double> pickTimes(Map<String, Object> me, java.util.List<Map<String, Object>> options) {
        Map<Long, Double> out = new LinkedHashMap<>();
        if (options == null || options.isEmpty()) return out;
        Optional<JsonNode> j = ai.ask("""
                시간은행 봉사자의 이번 달 계획을 세우는 도우미다. 봉사 공고마다 candidates(시작할 수 있는 시각, 24시간제 숫자) 가운데 하나를 고른다.
                내 정보(JSON)의 가능한 시간대와 이미 잡힌 일정을 보고, 같은 날 일정 사이에 이동할 여유가 있는 시각을 고른다.
                candidates 에 없는 숫자는 쓰지 않는다. 날짜는 바꾸지 않는다.
                출력: {"plans":[{"postId":1,"startHour":14.0}]}
                """, "내 정보(JSON): " + Views.json(me) + "\n고를 공고(JSON): " + Views.json(options), 400);
        if (j.isEmpty()) return out;
        for (JsonNode n : j.get().path("plans")) {
            if (!n.path("postId").canConvertToLong() || !n.path("startHour").isNumber()) continue;
            out.put(n.path("postId").asLong(), n.path("startHour").asDouble());
        }
        return out;
    }

    public String providerName() { return ai.providerName(); }

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
