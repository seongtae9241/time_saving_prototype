package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 41판 B-54 [교육 AI]: 첫 활동 연습에서 AI 가 도움 받는 어르신 역할로 다음 말을 하고,
 * 봉사자의 답이 안전 규칙에 어긋나는지 보고 "이렇게 바꿔 말해 보세요" 문장을 만든다.
 * 안전 규칙 위반 판정은 코드 규칙(RuleEngine.rpClassify)이 먼저이고 AI 는 그것을 뒤집지 못한다(호출한 쪽에서 합친다).
 * 키가 없거나 실패하면 빈 값을 돌려주고, 호출한 쪽이 대본(서버 설정)과 규칙 판정으로 대신한다.
 */
@Component
public class RoleplayAi {
    public static final List<String> QUALITIES = List.of("good", "ok", "poor", "bad");
    public static final List<String> FLAGS = List.of("연락처", "금전·선물", "신체 돌봄", "개인정보");

    /** reply: 어르신의 다음 말(마지막 차례면 맺는 말), quality/flag: 방금 답의 판정, better: 바꿔 말하기 예시(없으면 null) */
    public record Turn(String reply, String quality, String flag, String better, String provider) {}

    private final ActivityAi ai;

    public RoleplayAi(ActivityAi ai) { this.ai = ai; }

    /**
     * @param persona    상대 설정 {name, ageLabel, trait, goal}
     * @param situations 서버 설정의 상황 목록 [{key,title,line}] — 아직 꺼내지 않은 상황을 하나씩 던지게 한다
     * @param history    지금까지 대화 [{who: them|me, text}]
     * @param text       봉사자가 방금 한 말
     * @param last       이번이 마지막 차례인지(맺는 말만 하게)
     */
    public Optional<Turn> turn(Map<String, Object> persona, List<Map<String, Object>> situations, List<Map<String, Object>> history, String text, boolean last) {
        StringBuilder talk = new StringBuilder();
        if (history != null) for (Map<String, Object> h : history)
            talk.append("me".equals(h.get("who")) ? "봉사자: " : "어르신: ").append(cut(String.valueOf(h.getOrDefault("text", "")), 200)).append('\n');
        Optional<JsonNode> j = ai.ask("""
                너는 봉사자가 첫 활동 전에 해 보는 연습 대화에서 '도움 받는 어르신' 역할을 맡는다. 동시에 봉사자의 방금 한 말을 평가한다.
                어르신 설정(JSON)의 성격과 말투로, 실제 어르신처럼 짧게(1~2문장) 말한다. 봉사자의 말에 자연스럽게 이어서 답한다.
                상황 목록(JSON)에서 대화에 아직 나오지 않은 상황을 하나 골라 자연스럽게 던진다(연락처 교환, 금전 부탁, 신체 돌봄 요청, 개인 정보 질문 같은 것).
                last 가 true 면 새 상황을 던지지 않고 고맙다는 맺는 말만 한다.
                평가: quality 는 good(상대 말을 받아 주며 규칙을 지키고 대안을 안내) / ok(무난하지만 짧음) / poor(무심하거나 퉁명) / bad(안전 규칙 위반).
                안전 규칙 위반 = 연락처를 주고받음, 돈·선물을 받음, 신체 돌봄(파스·목욕·약 먹이기)을 해 줌, 비밀번호·주소 같은 개인 정보를 묻거나 알려 줌.
                위반이면 flag 를 "연락처"|"금전·선물"|"신체 돌봄"|"개인정보" 중 하나로, 아니면 null.
                quality 가 poor 나 bad 면 better 에 봉사자가 이렇게 바꿔 말하면 좋은 문장 하나를 쓴다(공감 한마디 + 규칙을 지키는 대안). good·ok 면 null.
                실제 사람의 이름·전화번호·주소를 지어내지 않는다.
                출력: {"reply":"어르신의 다음 말","quality":"good|ok|poor|bad","flag":null,"better":null}
                """, "어르신 설정(JSON): " + Views.json(persona) + "\n상황 목록(JSON): " + Views.json(situations) + "\nlast: " + last
                + "\n지금까지 대화:\n" + talk + "봉사자(방금): " + cut(text, 300), 400);
        if (j.isEmpty()) return Optional.empty();
        JsonNode n = j.get();
        String reply = n.path("reply").asText("").trim();
        String q = n.path("quality").asText("");
        if (reply.isBlank() || !QUALITIES.contains(q)) return Optional.empty();
        String flag = n.hasNonNull("flag") && FLAGS.contains(n.path("flag").asText()) ? n.path("flag").asText() : null;
        String better = n.hasNonNull("better") ? n.path("better").asText("").trim() : "";
        return Optional.of(new Turn(cut(reply, 300), q, flag, better.isBlank() ? null : cut(better, 300), ai.providerName()));
    }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }
}
