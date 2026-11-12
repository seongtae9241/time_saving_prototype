package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기능 U-12 빈 시간 묶음 추천의 "왜 이 묶음인지" 설명 문장.
 * 묶음 고르기(시간 겹침·이동 거리·여유)는 모두 코드(BundleService)가 하고, LLM 은 코드가 준 사실로 한두 문장만 쓴다.
 * 키가 없거나, LLM 문장에 코드에 없는 숫자가 있으면 규칙 문장을 쓴다(source: ai | rule).
 */
@Component
public class BundleAi {
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");
    private final LlmJson llm;

    public BundleAi(LlmJson llm) { this.llm = llm; }

    public record Explained(String text, String source) {}

    /** facts: 코드가 계산한 사실 문장들(시간표·이동 거리·여유·적립). ruleText: 키가 없을 때 쓸 규칙 문장. */
    public Explained explain(List<String> facts, String ruleText) {
        if (facts == null || facts.isEmpty() || !llm.available()) return new Explained(ruleText, "rule");
        String joined = String.join("\n", facts);
        Optional<JsonNode> j = llm.ask("""
                너는 이웃 봉사 연결 앱에서 '하루에 이어서 할 수 있는 봉사 묶음'을 왜 추천하는지 설명하는 도우미다.
                - 주어진 사실만 쓴다. 사실에 없는 숫자·장소·사람을 만들지 않는다. 숫자는 그대로 쓴다.
                - 80자 안의 한두 문장, 쉬운 존댓말. 이동 거리와 시간이 겹치지 않는다는 점을 중심으로 쓴다.
                - 건강·나이·성별은 쓰지 않는다. 수락·신청을 재촉하지 않는다(결정은 사용자가 한다).
                출력: {"text":"..."}
                """, "사실:\n" + joined, 200);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim().replaceAll("[\\r\\n]+", " ");
            if (!t.isEmpty() && t.length() <= 160 && numbersWithin(joined, t)) return new Explained(t, "ai");
        }
        return new Explained(ruleText, "rule");
    }

    /** LLM 문장의 숫자가 모두 코드 사실에 있는 숫자인지(새 숫자가 생기면 false). */
    public static boolean numbersWithin(String facts, String text) {
        Set<String> ok = new HashSet<>();
        Matcher m = DIGITS.matcher(facts);
        while (m.find()) ok.add(m.group());
        Matcher t = DIGITS.matcher(text);
        while (t.find()) if (!ok.contains(t.group())) return false;
        return true;
    }
}
