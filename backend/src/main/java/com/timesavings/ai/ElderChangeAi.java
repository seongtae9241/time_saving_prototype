package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * C-16 대상 변화 리포트의 글 부분: 최근 활동 일지를 이전 기록과 비교해 특이사항·변화를 정리한다.
 * 횟수·순위는 코드(ElderChangeService)가 정하고, 여기서는 일지 내용만 정리한다. 진단·건강 판단은 하지 않는다.
 * 키가 없거나 실패하면 호출한 쪽이 넘긴 규칙 결과를 그대로 돌려준다(source:'rule').
 */
@Component
public class ElderChangeAi {
    private final LlmJson llm;

    public ElderChangeAi(LlmJson llm) { this.llm = llm; }

    public boolean available() { return llm.available(); }

    /**
     * @param recent 최근 기간 일지 줄("11-03 동행: …"), 최신순
     * @param before 그 이전 기간 일지 줄
     * @param ruleSummary·ruleChanges 규칙으로 만든 결과(대체용)
     * @return {summary, changes[], source, provider?}
     */
    public Map<String, Object> compare(String label, List<String> recent, List<String> before, String ruleSummary, List<String> ruleChanges) {
        String summary = ruleSummary;
        List<String> changes = ruleChanges;
        String source = "rule";
        if (!recent.isEmpty()) {
            Optional<JsonNode> j = llm.ask("""
                    너는 기관 담당자를 돕는 기록 정리 도우미다. 봉사자가 쓴 활동 일지를 '이전 기록'과 '최근 기록'으로 나눠 받아, 달라진 점을 정리한다.
                    규칙:
                    - 일지에 적힌 내용만 쓴다. 지어내지 않는다. 횟수·날짜 같은 숫자는 새로 세거나 바꾸지 않는다.
                    - 진단·건강 상태 판단·의학적 추측은 하지 않는다. 병명·약 이름은 옮기지 않는다. "~로 보인다" 같은 추측 대신 "일지에 ~라고 적혀 있어요"처럼 쓴다.
                    - 이름·연락처·주소는 쓰지 않는다.
                    - summary: 최근 기록의 특이사항을 1~2문장으로.
                    - changes: 이전 기록과 비교해 달라진 점 0~3개(각 40자 안팎). 이전 기록이 없거나 달라진 점이 없으면 빈 배열.
                    출력: {"summary":"...","changes":["..."]}
                    """, "대상: " + label + "\n[이전 기록]\n" + (before.isEmpty() ? "(없음)" : String.join("\n", before))
                    + "\n[최근 기록]\n" + String.join("\n", recent), 500);
            if (j.isPresent() && !j.get().path("summary").asText("").isBlank()) {
                summary = clean(j.get().path("summary").asText(), 300);
                List<String> ai = new ArrayList<>();
                for (JsonNode n : j.get().path("changes")) {
                    String s = clean(n.asText(""), 120);
                    if (!s.isEmpty() && !ai.contains(s)) ai.add(s);
                    if (ai.size() >= 3) break;
                }
                changes = ai;
                source = "ai";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("changes", changes);
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", llm.providerName());
        return out;
    }

    /** AI 글에도 연락처·병명 지우기를 다시 적용한다. */
    static String clean(String s, int max) {
        String t = VoiceSafety.stripHealth(VoiceSafety.scrub(s == null ? "" : s).text());
        t = t == null ? "" : t.trim();
        return t.length() > max ? t.substring(0, max).trim() + "…" : t;
    }
}
