package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 기능 O-12 편성 대안 추천의 글 부분.
 * 후보 찾기·순서·숫자는 코드(CareScheduleService)가 정하고, 여기서는 후보별 추천 이유와 대안 안내 문장만 다듬는다.
 * 이름·별칭은 AI에 보내지 않는다(근거 문장만 보낸다). 키가 없거나 실패하거나 숫자가 달라지면 코드 문장을 그대로 쓴다(source:'rule').
 */
@Component
public class ScheduleAi {
    private final LlmJson llm;

    public ScheduleAi(LlmJson llm) { this.llm = llm; }

    public record Text(List<String> reasons, String message, String source, String provider) {}

    /**
     * @param slot        "11/17(화) 10:00 동행" 같은 빈 칸 설명
     * @param facts       후보별 근거(코드가 만든 것), 순서 = 추천 순서
     * @param ruleMessage 코드가 만든 안내 문장(대체용)
     */
    public Text explain(String slot, List<List<String>> facts, String ruleMessage) {
        List<String> ruleReasons = facts.stream().map(f -> String.join(" · ", f)).toList();
        StringBuilder in = new StringBuilder("빈 일정: " + slot + "\n");
        for (int i = 0; i < ruleReasons.size(); i++) in.append("후보").append(i + 1).append(" 근거: ").append(ruleReasons.get(i)).append('\n');
        in.append("안내 문장(코드): ").append(ruleMessage);
        Optional<JsonNode> j = llm.ask("""
                너는 기관 담당자의 돌봄 편성을 돕는 도우미다. 코드가 찾은 후보와 근거를 받아 읽기 쉬운 문장으로만 다듬는다.
                규칙:
                - 주어진 근거만 쓴다. 근거에 없는 사실·숫자를 만들지 않는다. 숫자는 그대로 쓴다.
                - reasons: 후보마다 추천 이유 한 줄(40자 안팎, 짧은 존댓말). 후보 수와 순서를 그대로 지킨다.
                - message: 안내 문장을 1~2문장으로 자연스럽게. 요일·날짜·명수는 바꾸지 않는다.
                - 요청을 보내라고 결정하지 않는다(결정은 담당자가 한다). 나이·성별·외모·건강은 쓰지 않는다. 이름을 지어내지 않는다.
                출력: {"reasons":["..."],"message":"..."}
                """, in.toString(), 500);
        if (j.isPresent()) {
            List<String> out = new ArrayList<>();
            boolean ok = j.get().path("reasons").isArray() && j.get().path("reasons").size() == ruleReasons.size();
            if (ok) {
                for (int i = 0; i < ruleReasons.size(); i++) {
                    String t = clean(j.get().path("reasons").get(i).asText(""), 80);
                    // 숫자가 빠지거나 새로 생긴 줄은 코드 문장으로 되돌린다
                    out.add(!t.isEmpty() && MatchReasonAi.sameNumbers(ruleReasons.get(i), t) ? t : ruleReasons.get(i));
                }
                String msg = clean(j.get().path("message").asText(""), 200);
                if (msg.isEmpty() || !MatchReasonAi.sameNumbers(ruleMessage, msg)) msg = ruleMessage;
                return new Text(out, msg, "ai", llm.providerName());
            }
        }
        return new Text(ruleReasons, ruleMessage, "rule", null);
    }

    private static String clean(String s, int max) {
        String t = VoiceSafety.stripHealth(VoiceSafety.scrub(s == null ? "" : s).text());
        t = t == null ? "" : t.trim().replaceAll("[\\r\\n]+", " ");
        return t.length() > max ? t.substring(0, max).trim() : t;
    }
}
