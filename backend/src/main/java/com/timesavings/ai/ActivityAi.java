package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7.13 LLM 추가 B(활동·메시지·시간 통장)의 AI 글쓰기.
 * AiClient 가 고른 공급자(LlmProvider)를 그대로 쓰고(AiClient 는 고치지 않음), 키가 없거나 실패하면 호출한 쪽이 넘긴 규칙 결과를 돌려준다.
 * 숫자·규칙 판정은 코드(ActivityRules·서비스)가 하고, AI 는 정리·설명·제안 문장만 쓴다. 수락·제재·적립 조정 같은 결정은 하지 않는다.
 * 결과에는 source: "ai" | "rule" (+ ai 면 provider). 입력 문장·키는 로그에 남기지 않는다.
 */
@Component
public class ActivityAi {
    private static final Logger log = LoggerFactory.getLogger(ActivityAi.class);
    private final ObjectMapper om = new ObjectMapper();
    private final AiClient ai;

    public ActivityAi(AiClient ai) { this.ai = ai; }

    public boolean enabled() { return ai.enabled(); }

    public String providerName() { return ai.providerName(); }

    Optional<JsonNode> ask(String system, String user, int maxTokens) {
        if (!ai.enabled()) return Optional.empty();
        LlmProvider p = ai.provider();
        if (p == null) return Optional.empty();
        try {
            String t = p.completeJson(system + "\n\n반드시 JSON 객체 하나만 출력한다. 설명 문장·코드 블록 표시는 쓰지 않는다.", user, maxTokens);
            Optional<JsonNode> j = AiClient.parseJsonObject(om, t);
            if (j.isEmpty()) log.warn("AI 응답 형식 이상({}), 규칙 결과로 대체", p.name());
            return j;
        } catch (Exception e) {
            log.warn("AI 호출 실패({}, {}), 규칙 결과로 대체", p.name(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<String, Object> out(Map<String, Object> m, boolean fromAi) {
        m.put("source", fromAi ? "ai" : "rule");
        if (fromAi) m.put("provider", ai.providerName());
        return m;
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    static List<String> strings(JsonNode arr, int limit, int maxLen) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (s.isEmpty() || s.length() > maxLen) continue;
            out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ===================== 1. 방문 전 브리핑 =====================
    private static final Pattern PII = Pattern.compile("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}|\\d{2,6}-\\d{2,6}-\\d{2,6}|\\d+동\\s*\\d+호|번지");

    /** basics: 별칭·동·도움 내용·주의사항·일시만(지난 전달 메모·연락처 없음). 반환 {lines[], source}. */
    public Map<String, Object> briefing(Map<String, Object> basics, String category, List<String> ruleLines) {
        Optional<JsonNode> j = ask("""
                너는 이웃 돌봄 봉사자의 '방문 전 체크리스트'를 쓴다. 주어진 재료(기본 정보·카테고리 규칙·교육 규칙)에 있는 내용만 쓴다.
                규칙: 3~6줄, 한 줄에 한 가지, 줄마다 40자 안팎의 쉬운 존댓말('~해요' 체). 첫 줄은 누구에게(표시 이름·동) 무엇을 하는지.
                진단·건강 상태 추측·병명은 쓰지 않는다. 이름·연락처·주소는 쓰지 않는다. 재료에 없는 새 사실을 지어내지 않는다.
                출력: {"lines":["...","..."]}
                """, "기본 정보(JSON): " + json(basics) + "\n카테고리: " + category
                + "\n카테고리 규칙: " + json(ActivityRules.categoryRules(category, basics.get("senior")))
                + "\n교육 규칙: " + json(ActivityRules.EDU_RULES), 500);
        if (j.isPresent()) {
            List<String> lines = new ArrayList<>();
            for (String s : strings(j.get().path("lines"), 6, 80)) if (!PII.matcher(s).find()) lines.add(s);
            if (lines.size() >= 3) return out(new LinkedHashMap<>(Map.of("lines", lines)), true);
        }
        return out(new LinkedHashMap<>(Map.of("lines", ruleLines)), false);
    }

    // ===================== 2·3. 메시지 안전 감지 + 빠른 답장 + 일정 변경 (한 메시지당 1회) =====================
    /**
     * rule*: 코드 결과. AI 는 돌려 말하는 연락처·금전·앱 밖 만남·부적절 언행을 더 찾을 수 있지만, 코드가 잡은 수준을 낮추지는 못한다.
     * 반환 {level, reasons[], replies[], schedule|null, source}. 자동 차단·제재는 하지 않는다(표시만).
     */
    public Map<String, Object> message(String text, String context, ActivityRules.Safety ruleSafety,
                                       ActivityRules.Schedule ruleSchedule, List<String> ruleReplies, Double startHour) {
        Optional<JsonNode> j = ask("""
                너는 이웃 돌봄 플랫폼 메시지의 안전 도우미다. 받은 메시지 한 개를 읽고 세 가지를 한다.
                1) 안전 분류: level none|caution|warn. reasons 는 "연락처 교환"(돌려 말하는 번호·SNS 요구 포함) / "금전·선물 요구" / "앱 밖 만남 유도" / "부적절한 말" 중에서만.
                   애매하면 caution. 평범한 인사·약속 이야기는 none. 차단·제재를 권하지 않는다.
                2) replies: 받은 사람(어르신일 수 있음)이 누르기 쉬운 짧은 답장 2~3개(각 20자 안팎, 존댓말). 연락처·돈·앱 밖 약속을 받아 주는 답장은 쓰지 않는다.
                3) schedule: 메시지가 약속 시간·날짜를 바꾸자는 뜻인지. 바꾸자는 뜻이면 {"change":true,"kind":"DELAY|EARLY|TIME|DATE|CANCEL","text":"한 줄 설명"}, 아니면 {"change":false}.
                   30분 미만 지각 알림은 change=false.
                출력: {"level":"none","reasons":[],"replies":["..."],"schedule":{"change":false}}
                """, "상황: " + context + "\n받은 메시지: " + text, 400);
        String level = ruleSafety.level();
        LinkedHashSet<String> reasons = new LinkedHashSet<>(ruleSafety.reasons());
        List<String> replies = ruleReplies;
        Map<String, Object> schedule = scheduleMap(ruleSchedule);
        boolean fromAi = false;
        if (j.isPresent()) {
            JsonNode n = j.get();
            String al = n.path("level").asText("");
            if (ActivityRules.levelRank(al) > ActivityRules.levelRank(level) && List.of("caution", "warn").contains(al)) level = al;
            for (JsonNode r : n.path("reasons")) if (ActivityRules.REASONS.contains(r.asText())) reasons.add(r.asText());
            if (!"none".equals(level) && reasons.isEmpty()) reasons.add("부적절한 말");
            if ("none".equals(level)) reasons.clear();
            List<String> rs = ActivityRules.cleanReplies(strings(n.path("replies"), 5, 40));
            if (rs.size() >= 2 && !"warn".equals(level)) replies = rs;
            JsonNode sc = n.path("schedule");
            if (schedule == null && sc.path("change").asBoolean(false) && ActivityRules.SCHEDULE_KINDS.contains(sc.path("kind").asText())) {
                String t = sc.path("text").asText("").trim();
                schedule = new LinkedHashMap<>();
                schedule.put("kind", sc.path("kind").asText());
                schedule.put("text", t.isEmpty() || t.length() > 60 ? "약속 시간을 바꾸자는 메시지예요" : t);
                schedule.put("deltaMin", null);
                schedule.put("newStartHour", null);
            }
            fromAi = true;
        }
        if ("warn".equals(level) && !"warn".equals(ruleSafety.level())) replies = ActivityRules.replies(text, new ActivityRules.Safety(level, new ArrayList<>(reasons)), null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("reasons", new ArrayList<>(reasons));
        m.put("warnText", ActivityRules.warnText(new ActivityRules.Safety(level, new ArrayList<>(reasons))));
        m.put("replies", replies);
        m.put("schedule", schedule);
        return out(m, fromAi);
    }

    public static Map<String, Object> scheduleMap(ActivityRules.Schedule s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", s.kind());
        m.put("text", s.text());
        m.put("deltaMin", s.deltaMin());
        m.put("newStartHour", s.newStartHour());
        return m;
    }

    // ===================== 4. 시간 조정·난이도 이의 판단 보조 =====================
    /** table: 코드가 비교한 표. rulePro/ruleCon: 규칙 근거. 반환 {pro[], con[], summary, source}. 결정은 하지 않는다. */
    public Map<String, Object> judge(String kind, List<Map<String, Object>> table, String reason, String logText,
                                     List<String> rulePro, List<String> ruleCon, String ruleSummary) {
        Optional<JsonNode> j = ask("""
                너는 시간은행 운영자의 판단 보조다. '%s' 신청을 보고 근거만 정리한다. 수락·거절을 정하지 않고, 결정은 사람이 한다.
                숫자는 주어진 비교표에 있는 것만 쓴다. 일지·신청 사유에 없는 사실을 지어내지 않는다. 건강·진단 추측은 하지 않는다.
                pro: 타당해 보이는 이유 1~3개, con: 다시 확인할 점(타당하지 않아 보이는 이유) 1~3개, 각 40자 안팎. summary: 한 문장(결론 대신 '확인할 점' 중심).
                출력: {"pro":["..."],"con":["..."],"summary":"..."}
                """.formatted("ADJUST".equals(kind) ? "활동 시간 조정" : "난이도 이의"),
                "비교표(JSON): " + json(table) + "\n신청 사유: " + Objects.toString(reason, "(없음)") + "\n활동 일지: " + Objects.toString(logText, "(없음)"), 500);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent()) {
            List<String> pro = strings(j.get().path("pro"), 3, 80), con = strings(j.get().path("con"), 3, 80);
            String sum = j.get().path("summary").asText("").trim();
            if (!pro.isEmpty() || !con.isEmpty()) {
                m.put("pro", pro);
                m.put("con", con);
                m.put("summary", sum.isEmpty() || sum.length() > 120 ? ruleSummary : sum);
                return out(m, true);
            }
        }
        m.put("pro", rulePro);
        m.put("con", ruleCon);
        m.put("summary", ruleSummary);
        return out(m, false);
    }

    // ===================== 5. 적립 목표 코치 =====================
    /** plan: 코드 계산(필요 총 시간·월 목표). activity: 잘하는 카테고리·시간대. posts: 코드가 고른 근처 공고 후보. 반환 {text, tips[], pick[], source}. */
    public Map<String, Object> goalCoach(Map<String, Object> plan, Map<String, Object> activity, List<Map<String, Object>> posts,
                                         String ruleText, List<String> ruleTips) {
        Optional<JsonNode> j = ask("""
                시간은행(봉사 시간을 저축해 나중에 도움을 받는 서비스) 사용자의 '거꾸로 적립 플랜' 코치다.
                숫자(필요 총 시간·월 목표·가중치)는 주어진 계산 결과만 쓰고 새로 계산하지 않는다.
                text: 이번 달 이렇게 쌓아 보라는 추천 2~3문장(잘하는 카테고리·시간대에 맞춰, 쉬운 존댓말). tips: 실천 팁 1~3개(각 30자 안팎).
                pick: 후보 공고(posts) 중 이 사람에게 맞는 2~3개의 번호(0부터). 공고에 없는 일정·장소를 지어내지 않는다.
                출력: {"text":"...","tips":["..."],"pick":[0,1]}
                """, "플랜(JSON): " + json(plan) + "\n활동 기록(JSON): " + json(activity) + "\n후보 공고(JSON): " + json(posts), 500);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent() && !j.get().path("text").asText("").isBlank()) {
            List<Integer> pick = new ArrayList<>();
            for (JsonNode n : j.get().path("pick")) if (n.canConvertToInt() && n.asInt() >= 0 && n.asInt() < posts.size() && !pick.contains(n.asInt())) pick.add(n.asInt());
            List<String> tips = strings(j.get().path("tips"), 3, 60);
            m.put("text", j.get().path("text").asText().trim());
            m.put("tips", tips.isEmpty() ? ruleTips : tips);
            m.put("pick", pick.isEmpty() ? defaultPick(posts.size()) : pick.subList(0, Math.min(3, pick.size())));
            return out(m, true);
        }
        m.put("text", ruleText);
        m.put("tips", ruleTips);
        m.put("pick", defaultPick(posts.size()));
        return out(m, false);
    }

    private static List<Integer> defaultPick(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, n); i++) out.add(i);
        return out;
    }

    // ===================== 6. 월별 결산 한 줄 설명 =====================
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** AI 문장에 나온 숫자가 모두 코드가 준 숫자인지(아니면 규칙 문장을 쓴다). */
    public static boolean numbersMatch(String text, Collection<Double> allowed) {
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            double v = Double.parseDouble(m.group());
            boolean ok = false;
            for (Double a : allowed) if (a != null && Math.abs(a - v) < 1e-6) { ok = true; break; }
            if (!ok) return false;
        }
        return true;
    }

    /** numbers: 코드 집계(적립·사용·카테고리별·가중치 덕분에 더 쌓인 시간). 반환 {text, suggestion, source}. */
    public Map<String, Object> monthNote(Map<String, Object> numbers, Collection<Double> allowed, String ruleText, String ruleSuggestion) {
        Optional<JsonNode> j = ask("""
                시간은행 사용자의 이번 달 시간 통장 결산을 한 줄로 설명한다. 숫자는 주어진 집계에 있는 것만 그대로 쓰고 계산·반올림하지 않는다.
                text: 한 문장(예: "이번 달 6.5시간 쌓았어요. 동행이 1.5배라 많이 늘었어요"). suggestion: 다음 달 한 가지 제안 한 문장(부담 없게).
                출력: {"text":"...","suggestion":"..."}
                """, "집계(JSON): " + json(numbers), 300);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim(), s = j.get().path("suggestion").asText("").trim();
            if (!t.isEmpty() && t.length() <= 120 && numbersMatch(t, allowed) && numbersMatch(s, allowed)) {
                m.put("text", t);
                m.put("suggestion", s.isEmpty() || s.length() > 120 ? ruleSuggestion : s);
                return out(m, true);
            }
        }
        m.put("text", ruleText);
        m.put("suggestion", ruleSuggestion);
        return out(m, false);
    }
}
