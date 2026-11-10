package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.service.Rules;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 7.14 운영자 쪽 새 AI: C4 역할극 상대 초안, C5 신고 요약, C6 이상 적립 설명, C7 문의 답변 초안.
 * 집계·심각도·근거는 코드가 만들어 넘기고, LLM 은 글(요약·설명·초안)만 쓴다. 결정(등록·제재·보류·발송)은 운영자가 버튼으로.
 * 키가 없거나 실패하면 호출한 쪽이 넘긴 규칙 결과(source:'rule').
 */
@Component
public class AdminAi {
    private final LlmJson llm;
    private final ObjectMapper om = new ObjectMapper();

    public AdminAi(LlmJson llm) { this.llm = llm; }

    private Map<String, Object> mark(Map<String, Object> m, boolean ai) {
        m.put("source", ai ? "ai" : "rule");
        if (ai) m.put("provider", llm.providerName());
        return m;
    }

    // ===================== C4 역할극 상대 =====================
    public static final List<String> QUALITIES = List.of("good", "ok", "poor", "bad");
    public static final List<String> FLAGS = List.of("연락처", "금전·선물", "신체 돌봄", "개인정보");

    /** 가명 강제: 실명처럼 보이면 첫 글자 + ○ + 끝 글자. */
    public static String pseudonym(String name) {
        String n = name == null ? "" : name.replaceAll("\\s*님$", "").trim();
        if (n.isEmpty()) return "김○자 님";
        if (!n.contains("○")) n = n.length() >= 2 ? n.charAt(0) + "○" + n.charAt(n.length() - 1) : n + "○";
        return n + " 님";
    }

    /** 초안 형식 검사: 3턴, 각 4개 선택지(good/ok/poor/bad 하나씩), bad 에는 위반 유형. 맞지 않으면 null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validPersona(Map<String, Object> p) {
        if (p == null) return null;
        Object tv = p.get("turns");
        if (!(tv instanceof List<?> turns) || turns.size() != 3) return null;
        List<Map<String, Object>> outTurns = new ArrayList<>();
        for (Object t : turns) {
            if (!(t instanceof Map<?, ?> tm)) return null;
            Object ov = tm.get("options");
            if (!(ov instanceof List<?> opts) || opts.size() < 3) return null;
            List<Map<String, Object>> outOpts = new ArrayList<>();
            Set<String> qs = new HashSet<>();
            for (Object o : opts) {
                if (!(o instanceof Map<?, ?> om)) return null;
                String text = Objects.toString(om.get("t"), "").trim(), q = Objects.toString(om.get("q"), "").trim();
                if (text.isEmpty() || !QUALITIES.contains(q)) return null;
                Map<String, Object> x = Views.map("t", text.replaceAll("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}", "010-0000-0000"), "q", q);
                if ("bad".equals(q)) {
                    String f = Objects.toString(om.get("f"), "");
                    x.put("f", FLAGS.contains(f) ? f : RuleEngine.rpClassify(text).flag() != null ? RuleEngine.rpClassify(text).flag() : "금전·선물");
                }
                qs.add(q);
                outOpts.add(x);
            }
            if (!qs.contains("good") || !qs.contains("bad")) return null;
            outTurns.add(Views.map("options", outOpts, "next", Objects.toString(tm.get("next"), "").trim()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String name = pseudonym(Objects.toString(p.get("name"), ""));
        out.put("name", name);
        String core = name.replaceAll("\\s*님$", "");
        out.put("initial", core.substring(core.length() - 1));
        out.put("ageLabel", Objects.toString(p.get("ageLabel"), "80대 · 어르신"));
        String fit = Objects.toString(p.get("fitCategory"), "");
        out.put("fitCategory", Rules.isCategory(fit) ? fit : "말동무·정서");
        out.put("trait", Objects.toString(p.get("trait"), ""));
        out.put("goal", Objects.toString(p.get("goal"), ""));
        out.put("opening", VoiceSafety.scrub(Objects.toString(p.get("opening"), "")).text());
        out.put("turns", outTurns);
        out.put("active", false);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> persona(List<Map<String, Object>> cases, Map<String, Object> focus, Map<String, Object> ruleDraft) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 봉사자 안전 교육용 '역할극 가상 상대'를 만드는 교육 설계자다.
                주어진 상황 통계(실제 사례에서 이름·연락처를 지운 것)에 맞는 가상 상대 한 명을 만든다. 실제 사람을 떠올릴 수 있는 정보는 쓰지 않는다.
                name 은 가명으로 "박○자 님"처럼 가운데를 ○로. ageLabel(예 "84세 · 어르신"), fitCategory 는 [말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행] 중 하나.
                trait(성향 1문장), goal(교육 목표 1문장), opening(상대의 첫마디).
                turns 는 정확히 3개. 각 턴: options 4개(봉사자가 고를 대답) — q 는 good/ok/poor/bad 하나씩, bad 에는 f(위반 유형: 연락처|금전·선물|신체 돌봄|개인정보),
                next 는 봉사자가 대답한 뒤 상대가 하는 다음 말.
                출력: {"name":"...","ageLabel":"...","fitCategory":"...","trait":"...","goal":"...","opening":"...","turns":[{"options":[{"t":"...","q":"good"},{"t":"...","q":"ok"},{"t":"...","q":"poor"},{"t":"...","q":"bad","f":"..."}],"next":"..."}]}
                """, "상황 통계(JSON): " + Views.json(cases) + "\n이번에 다룰 상황: " + Views.json(focus), 1400);
        if (j.isPresent()) {
            try {
                Map<String, Object> raw = om.convertValue(j.get(), Map.class);
                Map<String, Object> v = validPersona(raw);
                if (v != null) return mark(v, true);
            } catch (IllegalArgumentException ignore) { /* 형식 이상 → 규칙 */ }
        }
        return mark(new LinkedHashMap<>(ruleDraft), false);
    }

    // ===================== C5 신고 요약 =====================
    /** ctx: 코드가 모은 맥락(이름 대신 역할). rule: {summary, suggestion}. LLM 은 요약·참고 조치·심각도 '제안'만(코드 규칙이 바닥). */
    public Map<String, Object> triage(Map<String, Object> ctx, Map<String, Object> rule) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 운영자의 신고 검토를 돕는 보조다. 주어진 신고 내용과 맥락만 쓴다. 사람 이름·연락처는 쓰지 않고 '신고자', '대상'이라고 쓴다.
                summary: 무슨 일인지 2문장. severity: high|medium|low 중 제안(신체 접촉·금전 요구·위협은 high).
                suggestion: 운영자가 참고할 조치 1~2문장(결정은 운영자가 한다는 전제로 '~를 검토해 보세요' 체).
                출력: {"summary":"...","severity":"...","suggestion":"..."}
                """, "신고(JSON): " + Views.json(ctx), 500);
        if (j.isPresent() && !j.get().path("summary").asText("").isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("summary", VoiceSafety.scrub(j.get().path("summary").asText().trim()).text());
            String sev = j.get().path("severity").asText("").toLowerCase();
            m.put("aiSeverity", List.of("high", "medium", "low").contains(sev) ? sev : null);
            String sg = j.get().path("suggestion").asText("").trim();
            m.put("suggestion", sg.isEmpty() ? rule.get("suggestion") : VoiceSafety.scrub(sg).text());
            return mark(m, true);
        }
        return mark(new LinkedHashMap<>(rule), false);
    }

    // ===================== C6 이상 적립 설명 =====================
    public Map<String, Object> anomaly(Map<String, Object> evidence, Map<String, Object> rule) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 운영자에게 '이상 적립 패턴'을 설명하는 보조다. 숫자는 주어진 근거만 쓰고 바꾸지 않는다.
                부정행위라고 단정하지 않는다(정상일 수도 있음을 함께 쓴다). 사람 이름은 쓰지 않는다.
                text: 코드가 잡은 근거를 읽기 쉽게 2~3문장. checks: 운영자가 확인할 점 2~4개(각 30자 안).
                출력: {"text":"...","checks":["..."]}
                """, "근거(JSON): " + Views.json(evidence), 500);
        if (j.isPresent() && !j.get().path("text").asText("").isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", j.get().path("text").asText().trim());
            List<String> c = OrgAi.texts(j.get().path("checks"), 4);
            m.put("checks", c.isEmpty() ? rule.get("checks") : c);
            return mark(m, true);
        }
        return mark(new LinkedHashMap<>(rule), false);
    }

    // ===================== C7 문의 답변 초안 =====================
    public Map<String, Object> inquiry(Map<String, Object> q, List<Map<String, Object>> basis, String ruleDraft) {
        Optional<JsonNode> j = llm.ask("""
                너는 시간은행 고객센터 운영자의 1:1 문의 답변 '초안'을 쓴다. 운영자가 고쳐서 직접 등록한다.
                근거(basis)에 있는 규칙·공지·사용자 데이터만 쓴다. 모르는 것은 "확인 후 다시 안내드릴게요"라고 쓴다. 약속·보상·예외를 지어내지 않는다.
                쉬운 존댓말 3~6문장. 연락처·계좌는 쓰지 않는다. used: 쓴 근거 번호(0부터).
                출력: {"draft":"...","used":[0]}
                """, "문의(JSON): " + Views.json(q) + "\n근거(JSON): " + Views.json(basis), 700);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent() && !j.get().path("draft").asText("").isBlank()) {
            m.put("draft", VoiceSafety.scrub(j.get().path("draft").asText().trim()).text());
            List<Integer> used = new ArrayList<>();
            for (JsonNode n : j.get().path("used")) if (n.canConvertToInt() && n.asInt() >= 0 && n.asInt() < basis.size()) used.add(n.asInt());
            m.put("used", used);
            return mark(m, true);
        }
        m.put("draft", ruleDraft);
        m.put("used", List.of());
        return mark(m, false);
    }
}
