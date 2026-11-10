package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12) "왜 이 봉사자?" 한 줄 이유 다듬기.
 * 점수·근거 숫자는 코드(MatchReasonService)가 정하고, LLM 은 그 근거를 자연스러운 한 줄로만 다듬는다.
 * LLM 문장에 코드에 없는 숫자가 있거나 근거 숫자가 빠지면 버리고 코드 문장("동행 12회 · 같은 동네 · 단골")을 쓴다.
 */
@Component
public class MatchReasonAi {
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");
    private final LlmJson llm;

    public MatchReasonAi(LlmJson llm) { this.llm = llm; }

    public record Polished(String text, String source) {}

    public Polished polish(List<String> facts) {
        if (facts == null || facts.isEmpty()) return new Polished(null, "rule");
        String code = String.join(" · ", facts);
        Optional<JsonNode> j = llm.ask("""
                너는 이웃 봉사 연결 앱에서 '왜 이 봉사자를 추천하는지'를 한 줄로 다듬는 도우미다.
                - 주어진 근거만 쓴다. 근거에 없는 사실·숫자를 만들지 않는다. 숫자는 그대로 쓴다.
                - 35자 안의 한 줄, 짧은 존댓말(예: "동행을 12회 해 본 같은 동네 단골이에요").
                - 나이·성별·외모·건강은 쓰지 않는다. 결정(수락하세요 등)을 권하지 않는다.
                출력: {"text":"..."}
                """, "근거: " + code, 120);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim().replaceAll("[\\r\\n]+", " ");
            if (!t.isEmpty() && t.length() <= 60 && sameNumbers(code, t)) return new Polished(t, "ai");
        }
        return new Polished(code, "rule");
    }

    /** LLM 문장의 숫자가 코드 근거의 숫자와 똑같은지(빠지거나 새로 생기면 false). */
    static boolean sameNumbers(String code, String text) {
        return nums(code).equals(nums(text));
    }

    private static List<String> nums(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = DIGITS.matcher(s);
        while (m.find()) out.add(m.group());
        Collections.sort(out);
        return out;
    }
}
