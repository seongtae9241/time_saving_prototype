package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 회의반영 4~6장의 새 AI 글쓰기(인수인계 요약, 운영자 인사이트, 개인 분석, 봉사 플랜 설명).
 * AiClient 가 고른 공급자(LlmProvider: gemini/anthropic)를 그대로 쓰고, 키가 없거나 실패하면 호출한 쪽이 넘긴 규칙 문장을 돌려준다.
 * 숫자는 언제나 코드가 계산해 넘기고, AI 는 설명·제안 글만 쓴다. 결과에는 source: "ai" | "rule" (+ ai 면 provider).
 */
@Component
public class InsightAi {
    private static final Logger log = LoggerFactory.getLogger(InsightAi.class);
    private final ObjectMapper om = new ObjectMapper();
    private final AiClient ai;

    public InsightAi(AiClient ai) { this.ai = ai; }

    public boolean enabled() { return ai.enabled(); }

    Optional<JsonNode> ask(String system, String user, int maxTokens) {
        if (!ai.enabled()) return Optional.empty();
        LlmProvider p = ai.provider();
        if (p == null) return Optional.empty();
        try {
            String t = p.completeJson(system + "\n\n반드시 JSON 객체 하나만 출력한다. 설명 문장·코드 블록 표시는 쓰지 않는다.", user, maxTokens);
            Optional<JsonNode> j = AiClient.parseJsonObject(om, t);
            if (j.isEmpty()) log.warn("AI 응답 형식 이상({}), 규칙 문장으로 대체", p.name());
            return j;
        } catch (Exception e) {
            log.warn("AI 호출 실패({}, {}), 규칙 문장으로 대체", p.name(), e.getClass().getSimpleName());
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

    private static List<String> strings(JsonNode arr, int limit) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) { String s = n.asText("").trim(); if (!s.isEmpty()) out.add(s); if (out.size() >= limit) break; }
        return out;
    }

    // ===================== 4장 인수인계 요약 =====================
    /** 건강·의료 낱말. 규칙 요약은 이런 메모를 그대로 옮기지 않고 "병원·약 안내 있음"으로 줄인다. */
    public static final Pattern HEALTH = Pattern.compile("병원|진료|재진|약국|처방|복용|약을|약 |혈압|혈당|당뇨|수술|검사|의사|간호|통증|치료|입원|퇴원|주사|정형외과|내과|치과|안과|보건소");

    /**
     * items: 최신순 [{date, category, volunteer, logText, careMemo}] (코드가 이미 최근 5~10건으로 자름).
     * 반환 {text, points[], source}.
     */
    public Map<String, Object> handover(String subjectLabel, List<Map<String, Object>> items) {
        Map<String, Object> rule = handoverRule(subjectLabel, items);
        if (items.isEmpty()) return out(rule, false);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> it : items)
            sb.append("- ").append(it.get("date")).append(" ").append(it.get("category")).append(" · 일지: ")
                    .append(oneLine(it.get("logText"))).append(it.get("careMemo") != null ? " · 전달 메모: " + oneLine(it.get("careMemo")) : "").append("\n");
        Optional<JsonNode> j = ask("""
                너는 돌봄 봉사 인수인계 도우미다. 새 담당자나 새 봉사자가 읽을 '인수인계 요약'을 쓴다.
                규칙: 주어진 기록에 있는 사실만 쓴다. 진단·건강 상태 판단·의학적 추측은 하지 않는다.
                병명·약 이름은 옮기지 말고 "병원 안내 사항 있음(○/○ 메모 확인)"처럼 줄인다. 이름·연락처·주소는 쓰지 않는다.
                text 는 2~3문장(어떤 도움을 얼마나 받았는지, 요즘 흐름). points 는 다음에 챙길 것 2~4개(각 25자 안팎, '~하기' 체).
                출력: {"text":"...","points":["..."]}
                """, "대상: " + subjectLabel + "\n최근 기록(최신순):\n" + sb, 600);
        if (j.isPresent() && !j.get().path("text").asText().isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", j.get().path("text").asText().trim());
            List<String> pts = strings(j.get().path("points"), 4);
            m.put("points", pts.isEmpty() ? rule.get("points") : pts);
            m.put("count", items.size());
            return out(m, true);
        }
        return out(rule, false);
    }

    /** 끝 글자 받침에 맞는 조사(은/는, 이/가, 을/를). 한글이 아니면 받침 없는 쪽. */
    public static String josa(String word, String withBatchim, String without) {
        if (word == null || word.isEmpty()) return without;
        char c = word.charAt(word.length() - 1);
        boolean has = c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
        return has ? withBatchim : without;
    }

    private static String oneLine(Object o) { return o == null ? "" : o.toString().replace("\n", " ").trim(); }

    static String md(Object date) {
        String s = String.valueOf(date);
        if (s.length() >= 10) return Integer.parseInt(s.substring(5, 7)) + "/" + Integer.parseInt(s.substring(8, 10));
        return s;
    }

    /** 키 없을 때: 횟수·카테고리 요약 + 메모의 "다음에 챙길 것"(건강 내용은 줄여서). */
    public static Map<String, Object> handoverRule(String subjectLabel, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", items.size());
        if (items.isEmpty()) {
            m.put("text", subjectLabel + "의 최근 활동 기록이 아직 없어요.");
            m.put("points", List.of());
            return m;
        }
        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (Map<String, Object> it : items) byCat.merge(String.valueOf(it.get("category")), 1, Integer::sum);
        List<String> cats = byCat.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + " " + e.getValue() + "회").toList();
        Object last = items.get(0).get("date");
        String text = subjectLabel + josa(subjectLabel, "은", "는") + " 최근 " + items.size() + "번 도움을 받았어요(" + String.join(", ", cats) + "). 마지막 활동은 " + md(last) + "이에요.";
        List<String> points = new ArrayList<>();
        boolean healthNoted = false;
        for (Map<String, Object> it : items) {
            Object cm = it.get("careMemo");
            if (cm == null || cm.toString().isBlank()) continue;
            String memo = oneLine(cm);
            if (HEALTH.matcher(memo).find()) {
                if (!healthNoted) points.add("병원 안내 사항 있음 (" + md(it.get("date")) + " 메모 확인)");
                healthNoted = true;
                continue;
            }
            points.add(memo.length() > 40 ? memo.substring(0, 40) + "…" : memo);
            if (points.size() >= 4) break;
        }
        if (points.isEmpty()) points.add("전달 메모가 없어요. 최근 일지를 확인해 주세요");
        m.put("text", text);
        m.put("points", points);
        return m;
    }

    // ===================== 5장 운영자 인사이트 =====================
    /**
     * stats: 코드가 집계한 수요·공급 차이·나이대·월별 숫자. unmatched: 6개 카테고리에 안 맞는 요청 문장.
     * rule: 키가 없을 때 쓸 결과({shortages, newCategories, summary}). AI 는 같은 모양으로 설명만 바꾼다.
     */
    public Map<String, Object> adminInsights(Map<String, Object> stats, List<String> unmatched, Map<String, Object> rule) {
        Optional<JsonNode> j = ask("""
                너는 지역 시간은행(봉사 시간을 저축하는 서비스) 운영 분석가다. 숫자는 주어진 통계만 쓰고 새로 만들지 않는다.
                1) shortages: 수요가 공급보다 많은 카테고리마다 짐작되는 이유 1~2문장(예: 시간대·체력·책임 부담). 확실하지 않으면 '짐작'임을 드러낸다.
                2) newCategories: 기존 6개 카테고리(말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행)에 맞지 않는 요청 문장을 묶어 새 카테고리 후보 1~3개.
                   각 후보: name(10자 안팎), reason(1문장), examples(주어진 문장에서 그대로 1~3개). 위험하거나 전문 자격이 필요한 일(전기 공사·의료 등)은 후보로 내지 말고 reason 에 주의만 적는다.
                3) summary: 운영자가 이번 달 할 일 2문장.
                출력: {"shortages":[{"category":"...","reason":"..."}],"newCategories":[{"name":"...","reason":"...","examples":["..."]}],"summary":"..."}
                """, "통계(JSON): " + json(stats) + "\n카테고리에 안 맞는 요청 문장:\n- " + String.join("\n- ", unmatched.stream().limit(30).toList()), 900);
        if (j.isPresent() && (j.get().path("shortages").isArray() || j.get().path("newCategories").isArray())) {
            Map<String, Object> m = new LinkedHashMap<>(rule);
            List<Map<String, Object>> sh = new ArrayList<>();
            for (JsonNode n : j.get().path("shortages")) {
                String c = n.path("category").asText(""), r = n.path("reason").asText("");
                if (!c.isBlank() && !r.isBlank()) sh.add(new LinkedHashMap<>(Map.of("category", c, "reason", r)));
            }
            // 숫자(gap)는 규칙 결과에서 다시 붙인다
            for (Map<String, Object> s : sh)
                for (Object o : (List<?>) rule.getOrDefault("shortages", List.of()))
                    if (o instanceof Map<?, ?> rm && s.get("category").equals(rm.get("category"))) { s.put("demand", rm.get("demand")); s.put("supply", rm.get("supply")); s.put("gap", rm.get("gap")); }
            if (!sh.isEmpty()) m.put("shortages", sh);
            List<Map<String, Object>> nc = new ArrayList<>();
            for (JsonNode n : j.get().path("newCategories")) {
                String name = n.path("name").asText("");
                if (name.isBlank()) continue;
                nc.add(new LinkedHashMap<>(Map.of("name", name, "reason", n.path("reason").asText(""), "examples", strings(n.path("examples"), 3))));
            }
            if (!nc.isEmpty()) m.put("newCategories", nc);
            if (!j.get().path("summary").asText("").isBlank()) m.put("summary", j.get().path("summary").asText());
            return out(m, true);
        }
        return out(new LinkedHashMap<>(rule), false);
    }

    // ===================== 6장 개인 분석 =====================
    /** stats: 코드 집계(활동·평가 습관). rule: {activity, rating} 규칙 문장. */
    public Map<String, Object> personal(String name, Map<String, Object> stats, Map<String, Object> rule) {
        Optional<JsonNode> j = ask("""
                시간은행 사용자의 개인 활동 분석을 쓴다. 숫자는 주어진 통계만 쓰고 바꾸지 않는다. 따뜻하고 쉬운 존댓말, 평가·훈계는 하지 않는다.
                activity: 어떤 도움을 잘 주고 어떤 도움을 자주 받는지, 주로 어느 시간대인지, 받은 평가의 특징 2~3문장.
                rating: 내가 남긴 별점·태그·한마디로 본 평가 성향(후한 편/꼼꼼한 편/균형) 1~2문장과 자주 쓰는 칭찬.
                기록이 거의 없으면 그렇다고 말하고 첫 활동을 권한다.
                출력: {"activity":"...","rating":"..."}
                """, "이름: " + name + "\n통계(JSON): " + json(stats), 600);
        if (j.isPresent() && !j.get().path("activity").asText("").isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", j.get().path("activity").asText().trim());
            String rt = j.get().path("rating").asText("").trim();
            m.put("rating", rt.isEmpty() ? rule.get("rating") : rt);
            return out(m, true);
        }
        return out(new LinkedHashMap<>(rule), false);
    }

    /** 봉사 플랜: 코드가 계산한 기간·후보 조합을 받아 추천 설명만 쓴다. */
    public Map<String, Object> carePlan(Map<String, Object> summary, List<Map<String, Object>> candidates, String ruleText) {
        Optional<JsonNode> j = ask("""
                시간은행 사용자가 남은 시간으로 앞으로 도움을 어떻게 받을 수 있는지 설명한다. 기간·횟수 숫자는 주어진 값만 쓰고 계산하지 않는다.
                후보 조합(candidates) 중 사용자 상황에 맞는 1~2개를 골라 이유를 쉬운 존댓말 2~3문장으로 쓴다. 가중치(동행 1.5배 등) 때문에 빨리 줄어드는 점을 알려 준다.
                본인 사용 조건(직접 적립 10시간 이상)이 아직이면 그 점을 먼저 말한다.
                출력: {"text":"...","pick":[후보 번호(0부터)]}
                """, "상황(JSON): " + json(summary) + "\n후보(JSON): " + json(candidates), 500);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent() && !j.get().path("text").asText("").isBlank()) {
            m.put("text", j.get().path("text").asText().trim());
            List<Integer> pick = new ArrayList<>();
            for (JsonNode n : j.get().path("pick")) if (n.canConvertToInt() && n.asInt() >= 0 && n.asInt() < candidates.size()) pick.add(n.asInt());
            m.put("pick", pick);
            return out(m, true);
        }
        m.put("text", ruleText);
        m.put("pick", candidates.isEmpty() ? List.of() : List.of(0));
        return out(m, false);
    }
}
