package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 기능 U-24 시간 플랜 · U-25 월별 리포트의 설명 문장.
 * 숫자는 모두 코드가 계산해 facts 로 넘기고, AI 는 그 숫자만 써서 설명 문장과 적립 목표 문구를 쓴다.
 * 주어진 숫자가 아닌 숫자가 섞이거나, 키가 없거나, 실패하면 코드가 만든 규칙 문장을 그대로 돌려준다. 결과에 source: "ai" | "rule".
 */
@Component
public class TimePlanAi {
    private final ActivityAi ai;

    public TimePlanAi(ActivityAi ai) { this.ai = ai; }

    private Map<String, Object> out(String text, String goal, boolean fromAi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        if (goal != null) m.put("goal", goal);
        m.put("source", fromAi ? "ai" : "rule");
        if (fromAi) m.put("provider", ai.providerName());
        return m;
    }

    private static boolean fine(String s, int max, Collection<Double> allowed) {
        return s != null && !s.isBlank() && s.length() <= max && ActivityAi.numbersMatch(s, allowed);
    }

    /** 시간 플랜: {text(앞으로 몇 년간 어떤 도움을 받을 수 있는지), goal(적립 목표 문구), source}. */
    public Map<String, Object> plan(Map<String, Object> facts, Collection<Double> allowed, String ruleText, String ruleGoal) {
        Optional<JsonNode> j = ai.ask("""
                시간은행(봉사 시간을 저축해 나중에 도움을 받는 서비스)의 시간 플랜 안내 도우미다.
                주어진 계산 결과(JSON)의 숫자만 그대로 쓰고 새로 계산·반올림하거나 지어내지 않는다. 의료·건강 판단은 하지 않는다.
                시간은 돈으로 바꿀 수 없고 상속되지 않으며 가족에게만 선물할 수 있다.
                text: 앞으로 몇 년 동안 어떤 도움을 받을 수 있는지 쉬운 존댓말 2~3문장. goal: 적립 목표를 한 문장으로(부담 없게).
                출력: {"text":"...","goal":"..."}
                """, "계산 결과(JSON): " + Views.json(facts) + "\n기본 안내문: " + ruleText + "\n기본 목표 문구: " + ruleGoal, 500);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim(), g = j.get().path("goal").asText("").trim();
            if (fine(t, 400, allowed)) return out(t, fine(g, 160, allowed) ? g : ruleGoal, true);
        }
        return out(ruleText, ruleGoal, false);
    }

    /** 월별 리포트: {text(이번 달 요약과 지난달 대비 변화), source}. */
    public Map<String, Object> monthReport(Map<String, Object> facts, Collection<Double> allowed, String ruleText) {
        Optional<JsonNode> j = ai.ask("""
                시간은행 사용자의 이번 달 활동 리포트를 쓴다. 주어진 집계(JSON)의 숫자만 그대로 쓰고 계산하거나 지어내지 않는다.
                봉사·도움 받은 횟수, 가장 많이 한 도움, 지난달과 달라진 점을 쉬운 존댓말 2~3문장으로. 평가·훈계는 하지 않는다.
                출력: {"text":"..."}
                """, "집계(JSON): " + Views.json(facts) + "\n기본 문장: " + ruleText, 400);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim();
            if (fine(t, 400, allowed)) return out(t, null, true);
        }
        return out(ruleText, null, false);
    }
}
