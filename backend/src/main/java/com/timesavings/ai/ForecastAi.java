package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.FinanceModel;
import com.timesavings.service.FinanceModel.Adj;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기능명세서 운영자 리포트의 AI(기능 A-02·A-04·A-05·A-06·A-07·A-08).
 * 숫자·통계·예측은 코드가 계산해 넘기고, LLM 은 ① 운영자 문장을 정해진 조정 값 JSON 으로 해석 ② 설명 문장·문구 초안만 쓴다.
 * 키가 없거나 실패하면 규칙 결과(source:'rule').
 */
@Component
public class ForecastAi {
    private final LlmJson llm;

    public ForecastAi(LlmJson llm) { this.llm = llm; }

    private Map<String, Object> mark(Map<String, Object> m, boolean ai) {
        m.put("source", ai ? "ai" : "rule");
        if (ai) m.put("provider", llm.providerName());
        return m;
    }

    private static String clean(String s, int max) {
        String t = VoiceSafety.scrub(s == null ? "" : s.trim()).text();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ===================== A-05 위험 동네 이유 요약 =====================
    /** facts: [{area, risk, reasons[코드 근거]}], rule: {headline, areas:[{area, text}]} */
    public Map<String, Object> riskSummary(List<Map<String, Object>> facts, Map<String, Object> rule) {
        Optional<JsonNode> j = facts.isEmpty() ? Optional.empty() : llm.ask("""
                너는 시간은행 운영자에게 '재정 추계' 결과를 설명하는 보조다. 숫자는 주어진 근거에 있는 것만 그대로 쓰고, 새로 계산하거나 지어내지 않는다.
                headline: 이번 달 전체 상황 1문장. areas: 주어진 동네마다 text(왜 위험·주의인지 근거를 묶어 1~2문장, 쉬운 존댓말).
                사람 이름은 쓰지 않는다. 단정하지 말고 '~로 보여요' 체.
                출력: {"headline":"...","areas":[{"area":"...","text":"..."}]}
                """, "근거(JSON): " + Views.json(facts), 700);
        if (j.isPresent() && !j.get().path("headline").asText("").isBlank()) {
            Map<String, String> texts = new HashMap<>();
            for (JsonNode n : j.get().path("areas")) texts.put(n.path("area").asText(""), n.path("text").asText("").trim());
            List<Map<String, Object>> areas = new ArrayList<>();
            @SuppressWarnings("unchecked") List<Map<String, Object>> ruleAreas = (List<Map<String, Object>>) rule.get("areas");
            for (Map<String, Object> r : ruleAreas) {
                String t = texts.get(String.valueOf(r.get("area")));
                Map<String, Object> m = new LinkedHashMap<>(r);
                if (t != null && !t.isBlank()) m.put("text", clean(t, 400));
                areas.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>(rule);
            out.put("headline", clean(j.get().path("headline").asText(), 300));
            out.put("areas", areas);
            return mark(out, true);
        }
        return mark(new LinkedHashMap<>(rule), false);
    }

    // ===================== A-06 자연어 → 조정 값 =====================
    private static final Pattern BAND = Pattern.compile("([1-8])0\\s*대");
    private static final Pattern NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern UP = Pattern.compile("늘|증가|많아|올라|오르|높아|더\\s");
    private static final Pattern DOWN = Pattern.compile("줄|감소|적어|내려|낮아|떨어|덜\\s");

    private static Double numBefore(String s, String unit) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + unit).matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /** 값의 범위를 정리하고(터무니없는 값 자름) 모르는 나이대·동네는 버린다. 쓸 수 없으면 null. */
    public static Adj valid(String type, String band, String area, Double value, List<String> areas) {
        if (type == null || !FinanceModel.ADJ_TYPES.contains(type) || value == null || value.isNaN() || value.isInfinite()) return null;
        String b = band == null || band.isBlank() || band.equals("null") ? null : band.trim();
        if (b != null) {
            int i = FinanceModel.band(b);
            if (i < 0) return null;
            b = FinanceModel.BANDS.get(i);
        }
        String a = area == null || area.isBlank() || area.equals("null") ? null : area.trim();
        if (a != null && !areas.contains(a)) return null;
        double v = switch (type) {
            case "JOIN_ADD" -> Math.max(-1000, Math.min(1000, value));
            case "CHURN_MULT" -> Math.max(0, Math.min(5, value));
            default -> Math.max(-20, Math.min(20, value));
        };
        if (type.equals("CHURN_MULT") ? v == 1 : v == 0) return null;
        return new Adj(type, b, a, Math.round(v * 100) / 100.0);
    }

    /** 키 없을 때: 흔한 문장 몇 가지를 정규식으로 해석한다. 쉼표·"그리고"로 나눈 마디마다 하나씩. */
    public static List<Adj> parseRule(String text, List<String> areas) {
        List<Adj> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("[,，\\n]|그리고|하고\\s|이고\\s")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            Matcher bm = BAND.matcher(s);
            String band = bm.find() ? bm.group(1) + "0대" : null;
            String area = areas.stream().filter(s::contains).findFirst().orElse(null);
            boolean up = UP.matcher(s).find(), down = DOWN.matcher(s).find();
            int sign = down && !up ? -1 : 1;
            Adj a = null;
            if (s.contains("이탈") || s.contains("탈퇴")) {
                Double pct = numBefore(s, "%"), times = numBefore(s, "배");
                if (s.contains("절반") || s.contains("반으로")) a = valid("CHURN_MULT", band, area, 0.5, areas);
                else if (s.contains("두 배") || s.contains("두배")) a = valid("CHURN_MULT", band, area, 2.0, areas);
                else if (times != null) a = valid("CHURN_MULT", band, area, times, areas);
                else if (pct != null && (up || down)) a = valid("CHURN_MULT", band, area, 1 + sign * pct / 100, areas);
            } else if (s.contains("가입") || s.contains("신규") || s.contains("새 회원")) {
                Double n = numBefore(s, "명");
                if (n != null && (up || down)) a = valid("JOIN_ADD", band, area, sign * n, areas);
            } else if (s.contains("시간")) {
                Double h = numBefore(s, "시간");
                if (h != null && (up || down)) {
                    boolean demand = s.contains("도움 받") || s.contains("도움받") || s.contains("수요") || s.contains("요청");
                    a = valid(demand ? "DEMAND_ADD" : "SUPPLY_ADD", band, area, sign * h, areas);
                }
            }
            if (a != null) out.add(a);
        }
        return out;
    }

    /** LLM 응답(JSON)을 조정 값으로. 형식이 다르면 그 줄은 버린다. LLM 이 준 숫자는 '조건'일 뿐이고 결과 계산은 코드가 한다. */
    public static List<Adj> fromJson(JsonNode root, List<String> areas) {
        List<Adj> out = new ArrayList<>();
        if (root == null) return out;
        for (JsonNode n : root.path("adjustments")) {
            if (!n.path("value").isNumber()) continue;
            Adj a = valid(n.path("type").asText(null), n.path("ageBand").isNull() ? null : n.path("ageBand").asText(null),
                    n.path("area").isNull() ? null : n.path("area").asText(null), n.path("value").asDouble(), areas);
            if (a != null) out.add(a);
            if (out.size() >= 6) break;
        }
        return out;
    }

    /** {adjustments, source}. 못 알아들으면 adjustments 가 빈다(화면이 직접 넣는 칸을 보여 준다). */
    public Map<String, Object> parsePolicy(String text, List<String> areas) {
        Optional<JsonNode> j = text == null || text.isBlank() ? Optional.empty() : llm.ask("""
                너는 시간은행 운영자의 '정책 가정' 문장을 정해진 조정 값으로 바꾸는 해석기다. 계산·예측·설명은 하지 않는다. 문장에 있는 조건만 옮긴다.
                type 은 넷 중 하나:
                - JOIN_ADD: 한 달 가입 수 변화(명, 줄면 음수)
                - CHURN_MULT: 이탈률에 곱할 배수(절반이면 0.5, 20% 줄면 0.8, 두 배면 2)
                - SUPPLY_ADD: 1인당 월 봉사 시간 변화(시간, 줄면 음수)
                - DEMAND_ADD: 1인당 월 도움 받는 시간 변화(시간, 줄면 음수)
                ageBand 는 "10대","20대","30대","40대","50대","60대","70대","80대 이상" 중 하나 또는 null(전체).
                area 는 주어진 동네 이름 중 하나 또는 null(전체). 문장에 없는 조건은 만들지 않는다. 해석할 수 없으면 adjustments 를 빈 배열로.
                출력: {"adjustments":[{"type":"JOIN_ADD","ageBand":"20대","area":null,"value":30}]}
                """, "동네 목록: " + Views.json(areas) + "\n운영자 문장: " + clean(text, 300), 400);
        if (j.isPresent()) {
            List<Adj> a = fromJson(j.get(), areas);
            if (!a.isEmpty()) return mark(Views.map("adjustments", a), true);
        }
        return mark(Views.map("adjustments", parseRule(text, areas)), false);
    }

    // ===================== 설명 문장(A-02·A-04) =====================
    /** topic: 무엇에 대한 해석인지, facts: 코드 집계, ruleText: 규칙 문장 */
    public Map<String, Object> interpret(String topic, Object facts, String ruleText) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 운영자에게 통계를 풀이해 주는 보조다. 주제: %s
                주어진 집계 숫자만 그대로 쓰고 새 숫자를 계산하거나 지어내지 않는다. 사람 이름은 쓰지 않는다.
                text: 눈에 띄는 점과 운영자가 살펴볼 점을 쉬운 존댓말 2~3문장으로. 이유는 '짐작'이라고 밝힌다.
                출력: {"text":"..."}
                """.formatted(topic), "집계(JSON): " + Views.json(facts), 500);
        if (j.isPresent() && !j.get().path("text").asText("").isBlank())
            return mark(Views.map("text", clean(j.get().path("text").asText(), 600)), true);
        return mark(Views.map("text", ruleText), false);
    }

    // ===================== A-07 모집 문구 초안 =====================
    /** items: [{area, category, timeSlot, gap}], rule: 같은 순서의 [{title, body}] */
    public Map<String, Object> recruit(List<Map<String, Object>> items, List<Map<String, Object>> rule) {
        Optional<JsonNode> j = items.isEmpty() ? Optional.empty() : llm.ask("""
                너는 시간은행(이웃끼리 시간을 주고받는 봉사 서비스)의 봉사자 모집 공지 '초안'을 쓴다. 운영자가 고쳐서 직접 올린다.
                주어진 항목(동네·도움 종류·시간대·부족 건수)마다 title(30자 안)과 body(쉬운 존댓말 3~4문장)를 쓴다.
                돈·상품·보상을 약속하지 않는다(봉사한 시간이 시간 통장에 쌓인다는 말만 쓸 수 있다). 사람 이름·연락처를 쓰지 않는다. 주어진 숫자만 쓴다.
                출력: {"drafts":[{"title":"...","body":"..."}]}  (항목과 같은 순서, 같은 개수)
                """, "항목(JSON): " + Views.json(items), 1200);
        List<Map<String, Object>> out = new ArrayList<>();
        boolean ai = false;
        if (j.isPresent() && j.get().path("drafts").isArray() && j.get().path("drafts").size() == items.size()) {
            ai = true;
            int i = 0;
            for (JsonNode n : j.get().path("drafts")) {
                String t = clean(n.path("title").asText(""), 100), b = clean(n.path("body").asText(""), 1000);
                if (t.isBlank() || b.isBlank()) { ai = false; break; }
                Map<String, Object> m = new LinkedHashMap<>(items.get(i++));
                m.put("title", t); m.put("body", b);
                out.add(m);
            }
        }
        if (!ai) {
            out.clear();
            for (int i = 0; i < items.size(); i++) { Map<String, Object> m = new LinkedHashMap<>(items.get(i)); m.putAll(rule.get(i)); out.add(m); }
        }
        return mark(Views.map("drafts", out), ai);
    }

    // ===================== A-08 특보 리포트 문장 =====================
    /** facts: 코드가 센 위험 지역·대상 수, rule: {summary, actions[]} */
    public Map<String, Object> weather(Map<String, Object> facts, Map<String, Object> rule) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 운영자에게 '기상 특보 대응 리포트'를 써 주는 보조다. 주어진 특보와 집계(지역별 관리 대상 수·담당 기관·예정 활동 수)만 쓴다.
                숫자를 바꾸거나 새로 만들지 않는다. 사람 이름을 쓰지 않는다. 의료 진단·건강 판단은 하지 않고, 안부 확인·일정 조정 같은 일반 행동 요령만 쓴다.
                summary: 어느 지역에 어떤 대상이 얼마나 있는지 2문장. actions: 담당자에게 권할 일 2~4개(각 40자 안).
                출력: {"summary":"...","actions":["..."]}
                """, "특보·집계(JSON): " + Views.json(facts), 600);
        if (j.isPresent() && !j.get().path("summary").asText("").isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("summary", clean(j.get().path("summary").asText(), 500));
            List<String> acts = OrgAi.texts(j.get().path("actions"), 4);
            m.put("actions", acts.isEmpty() ? rule.get("actions") : acts);
            return mark(m, true);
        }
        return mark(new LinkedHashMap<>(rule), false);
    }
}
