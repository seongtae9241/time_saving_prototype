package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 41판 B-56: 봉사 확인서의 "자기소개용 활동 요약 문장"([기록 정리 AI]).
 * 횟수·시간·주요 활동은 코드가 집계해 facts 로 넘기고, AI 는 그 숫자만 써서 문장으로 풀어 쓴다.
 * 키가 없거나 실패하거나 숫자를 바꿔 쓰면 코드가 만든 규칙 문장을 그대로 돌려준다. 결과에 source: "ai" | "rule".
 */
@Component
public class CertAi {
    private final ActivityAi ai;

    public CertAi(ActivityAi ai) { this.ai = ai; }

    public Map<String, Object> summary(Map<String, Object> facts, String ruleText) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<JsonNode> j = ai.ask("""
                봉사 활동 확인서에 넣을 '자기소개용 활동 요약'을 쓴다. 자기소개서·지원서에 그대로 붙여 쓸 수 있는 1인칭 문장이다.
                주어진 집계(JSON)의 숫자만 쓰고 새로 계산하거나 지어내지 않는다. 받는 사람의 이름·연락처·주소·건강 상태는 쓰지 않는다.
                과장하지 않는 담담한 '~했습니다' 체 2~3문장. 출력: {"text":"..."}
                """, "집계(JSON): " + Views.json(facts) + "\n기본 문장: " + ruleText, 400);
        String t = j.map(n -> n.path("text").asText("").trim()).orElse("");
        if (!t.isBlank() && keepsNumbers(t, facts)) {
            m.put("text", t.length() > 400 ? t.substring(0, 400) : t);
            m.put("source", "ai");
            m.put("provider", ai.providerName());
        } else {
            m.put("text", ruleText);
            m.put("source", "rule");
        }
        return m;
    }

    /** AI 문장에 활동 횟수가 그대로 들어 있어야 쓴다(숫자를 바꿔 쓴 문장은 버린다). */
    static boolean keepsNumbers(String text, Map<String, Object> facts) {
        Object c = facts.get("count");
        return c == null || text.contains(String.valueOf(c));
    }
}
