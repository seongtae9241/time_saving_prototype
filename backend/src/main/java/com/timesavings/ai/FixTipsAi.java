package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12) 안 맞춰지는 공고 고치기 제안: 근거 숫자와 후보 제안(행동·바꿀 값)은 코드(UnmatchedPostService)가 만들고,
 * LLM 은 후보 중 1~3개를 골라 쉬운 문장으로 다듬기만 한다. 후보에 없는 행동이나 근거에 없는 숫자가 나오면 그 항목은 버린다.
 */
@Component
public class FixTipsAi {
    public static final Set<String> ACTIONS = Set.of("TIME", "SPLIT", "DATE", "EDIT");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private final LlmJson llm;

    public FixTipsAi(LlmJson llm) { this.llm = llm; }

    public record Result(List<Map<String, Object>> tips, String source) {}

    /** candidates: [{action, text, suggest}] (코드 순서 = 중요한 순), evidenceText: 근거 요약(숫자 포함) */
    public Result polish(List<Map<String, Object>> candidates, String postText, String evidenceText) {
        if (candidates.isEmpty()) return new Result(List.of(), "rule");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++)
            sb.append(i + 1).append(". action=").append(candidates.get(i).get("action")).append(" : ").append(candidates.get(i).get("text")).append('\n');
        Optional<JsonNode> j = llm.ask("""
                너는 동네 봉사 앱에서 24시간 동안 요청이 없는 공고의 주인에게 '이렇게 바꿔 보세요'를 알려 주는 도우미다.
                - 아래 후보 제안 가운데 도움이 될 1~3개를 골라, 각각 한 문장(40자 안, 쉬운 존댓말)으로 다듬는다.
                - action 은 후보에 있는 값만 쓴다(TIME, SPLIT, DATE, EDIT). 근거에 없는 숫자·사실을 만들지 않는다.
                - 탓하는 말투를 쓰지 않는다. 공고를 올리라고 강요하지 않는다.
                출력: {"tips":[{"action":"TIME","text":"..."}]}
                """, "공고: " + clip(postText, 300) + "\n근거: " + evidenceText + "\n후보 제안:\n" + sb, 400);
        if (j.isPresent()) {
            List<Map<String, Object>> out = new ArrayList<>();
            Set<String> used = new HashSet<>();
            String allowedNums = evidenceText + " " + sb;
            for (JsonNode n : j.get().path("tips")) {
                if (out.size() >= 3) break;
                String a = n.path("action").asText("").trim().toUpperCase();
                String t = n.path("text").asText("").trim().replaceAll("[\\r\\n]+", " ");
                if (!ACTIONS.contains(a) || t.isEmpty() || t.length() > 80 || used.contains(a) || !numbersAllowed(allowedNums, t)) continue;
                Map<String, Object> cand = candidates.stream().filter(c -> a.equals(c.get("action"))).findFirst().orElse(null);
                if (cand == null) continue;
                Map<String, Object> tip = new LinkedHashMap<>(cand);
                tip.put("text", t);
                out.add(tip);
                used.add(a);
            }
            if (!out.isEmpty()) return new Result(out, "ai");
        }
        return new Result(candidates.subList(0, Math.min(3, candidates.size())), "rule");
    }

    /** text 의 숫자가 모두 allowed 안에 있는지 */
    static boolean numbersAllowed(String allowed, String text) {
        Set<String> ok = new HashSet<>();
        Matcher m = DIGITS.matcher(allowed);
        while (m.find()) ok.add(m.group());
        m = DIGITS.matcher(text);
        while (m.find()) if (!ok.contains(m.group())) return false;
        return true;
    }

    private static String clip(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }
}
