package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * C-13 담당자 메모 정리: 담당자가 적은 대상 특이사항·방문 소감을 읽기 쉬운 짧은 줄로 다듬는다.
 * 원문은 그대로 따로 저장한다(정리본은 보기용). 입력에 있는 사실만 쓰고, 진단·건강 판단은 하지 않는다.
 * 키가 없거나 실패하면 규칙으로 정리(source:'rule'). 연락처·상세 주소는 AI 결과에도 서버가 다시 지운다.
 */
@Component
public class ManagerMemoAi {
    public static final int MAX_LINES = 5;
    private final LlmJson llm;

    public ManagerMemoAi(LlmJson llm) { this.llm = llm; }

    /** 반환 {tidy(줄바꿈으로 나눈 정리본), lines[], source, provider?} */
    public Map<String, Object> tidy(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() > 1000) text = text.substring(0, 1000);
        List<String> lines = ruleLines(text);
        String source = "rule";
        Optional<JsonNode> j = llm.ask("""
                너는 기관 담당자가 도움 대상(독거 어르신)에 대해 적은 메모를 읽기 쉽게 정리하는 도우미다.
                - 입력에 있는 사실만 쓴다. 날짜·사람·사건을 지어내지 않고, 뜻을 바꾸지 않는다.
                - 진단·건강 상태 판단·의학적 추측은 하지 않는다. 병명·약 이름은 옮기지 않는다.
                - 이름·전화번호·주소는 쓰지 않는다.
                - lines: 한 줄에 한 가지씩, 1~5줄, 각 40자 안팎의 '~했어요/~해요' 체. 중요한 것(다음에 챙길 일)을 먼저.
                출력: {"lines":["..."]}
                """, "담당자 메모: " + VoiceSafety.scrub(text).text(), 400);
        if (j.isPresent()) {
            List<String> ai = new ArrayList<>();
            for (JsonNode n : j.get().path("lines")) {
                String s = clean(n.asText(""));
                if (!s.isEmpty() && !ai.contains(s)) ai.add(s);
                if (ai.size() >= MAX_LINES) break;
            }
            if (!ai.isEmpty()) { lines = ai; source = "ai"; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("tidy", String.join("\n", lines));
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", llm.providerName());
        return out;
    }

    private static String clean(String s) {
        String t = VoiceSafety.scrub(s == null ? "" : s).text().replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(?:[-·•*]+|\\d+[.)])\\s+", "").trim();
        return t.length() > 120 ? t.substring(0, 120).trim() + "…" : t;
    }

    /** 규칙 정리: 문장 단위로 나눠 공백을 고르고, 같은 줄은 한 번만. 내용은 바꾸지 않는다. */
    public static List<String> ruleLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("(?<=[.!?。])\\s+|\\n+|\\s*/\\s*|\\s*;\\s*")) {
            String s = clean(part);
            if (s.isEmpty() || out.contains(s)) continue;
            out.add(s);
            if (out.size() >= MAX_LINES) break;
        }
        return out;
    }
}
