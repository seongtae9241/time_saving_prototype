package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.domain.Enums.FilterLevel;
import com.timesavings.service.Rules;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7.14 담당자 쪽 새 AI: C2 통화 메모 → 공고 초안 여러 개, C3 주간 브리핑 요약.
 * 날짜·시각·반복·카테고리 검사·금지 필터는 코드(KoreanParser·VoiceSafety·RuleEngine)가 하고, LLM 은 나누기·제목·내용·주의 문장만.
 * 키가 없거나 실패하면 규칙 결과(source:'rule'). 등록은 사람이 카드마다 체크해 기존 POST /api/posts 로 한다.
 */
@Component
public class OrgAi {
    private final LlmJson llm;

    public OrgAi(LlmJson llm) { this.llm = llm; }

    public String providerName() { return llm.providerName(); }

    // ===================== C2 통화 메모 → 공고 초안 =====================
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:[,，;\\n]|(?<![가-힣])그리고(?![가-힣])|(?<=[가-힣])\\s*(?:하고|이랑|랑)\\s+(?=(?:다음|이번|매주|매달|격주|[월화수목금토일]요일|\\d|오전|오후|저녁|내일|모레)))\\s*");
    private static final Pattern PERIOD_ONLY = Pattern.compile("(오전|아침|점심|오후|저녁)");

    /** 메모를 요청 단위로 자른다(쉼표·줄바꿈·'그리고'). 대상 이름("이순례 님")은 앞에서 뺀다. */
    public static List<String> split(String memo, String alias) {
        String t = memo == null ? "" : memo.trim();
        if (alias != null && !alias.isBlank()) t = t.replaceAll("^\\s*" + Pattern.quote(alias) + "\\s*(님|씨|어르신)?\\s*(은|는|이|가|께서|이요)?\\s*", "");
        t = t.replaceAll("^\\s*[가-힣]{2,4}\\s*님\\s*(은|는)?\\s+", "");
        List<String> out = new ArrayList<>();
        for (String s : SPLIT.split(t)) {
            String x = s.trim().replaceAll("^(또|그리고|및)\\s+", "").replaceAll("[.。]+$", "").trim();
            if (x.length() >= 2) out.add(x);
        }
        return out;
    }

    static double defaultHours(List<String> cats) {
        if (cats.contains("동행")) return 2.0;
        if (cats.contains("말동무·정서") || cats.contains("디지털 도움")) return 1.0;
        return 1.5;
    }

    /** 한 구절을 공고 초안으로(코드). 날짜·시각·반복·카테고리·금지 필터를 여기서 정한다. */
    public static Map<String, Object> draftFrom(String seg, String area, String elderCaution, LocalDate today) {
        VoiceSafety.Scrubbed sc = VoiceSafety.scrub(seg);
        String s = sc.text();
        KoreanParser.Parsed p = KoreanParser.parse(s, today);
        List<String> cats = new ArrayList<>(p.categories);
        if (cats.size() > 2) cats = cats.subList(0, 2);
        String repeat = VoiceSafety.repeat(s);
        boolean weekly = repeat != null && (repeat.startsWith("매주") || repeat.startsWith("매일"));
        double hours = p.hours != null ? p.hours : defaultHours(cats);
        Double start = p.startHour, end = p.endHour;
        String timeConf = "high";
        if (start == null) {
            Matcher m = PERIOD_ONLY.matcher(s);
            String per = m.find() ? m.group(1) : null;
            timeConf = per == null ? "low" : "medium";
            double[] w = switch (per == null ? "" : per) {
                case "오전", "아침" -> new double[]{9, 12};
                case "점심" -> new double[]{11, 14};
                case "오후" -> new double[]{13, 17};
                case "저녁" -> new double[]{17, 20};
                default -> new double[]{10, 12};
            };
            start = w[0]; end = w[1];
        }
        if (end == null || end <= start) end = start + hours;
        if (end > 24) end = 24.0;
        if (hours > end - start) hours = end - start;
        hours = Math.max(0.5, Math.min(4.0, Math.round(hours * 2) / 2.0));
        String title = TidyAi.ruleTitle(s, cats);
        String detail = VoiceSafety.stripHealth(s);
        if (!detail.matches(".*(요|다)[.!]?$")) detail = detail + " 도와주세요.";
        String caution = VoiceSafety.sanitizeCaution(Optional.ofNullable(VoiceSafety.caution(s)).orElse(elderCaution));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("segment", s);
        d.put("title", title);
        d.put("detail", detail);
        d.put("note", caution);
        d.put("categories", cats);
        d.put("date", p.date == null ? null : p.date.toString());
        d.put("startHour", start);
        d.put("endHour", end);
        d.put("estHours", hours);
        d.put("area", p.area != null ? p.area : area);
        d.put("repeat", Map.of("weekly", weekly, "weeks", weekly ? 4 : 1));
        d.put("repeatLabel", repeat);
        d.put("confidence", Map.of("date", p.date == null ? "low" : p.dateConf, "time", timeConf, "category", cats.isEmpty() ? "low" : "high"));
        d.put("piiRemoved", sc.removed());
        RuleEngine.FilterResult f = RuleEngine.filter(String.join(" ", title, detail, Objects.toString(caution, "")));
        d.put("filter", Map.of("level", f.level().name(), "reason", Objects.toString(f.reason(), "")));
        List<String> missing = new ArrayList<>();
        if (p.date == null) missing.add("date");
        if (cats.isEmpty()) missing.add("category");
        d.put("missing", missing);
        d.put("checked", f.level() != FilterLevel.BLOCK && missing.isEmpty());
        return d;
    }

    /** 메모 → 공고 초안 목록. LLM 은 나누기·제목·내용·주의 문장만, 날짜·시각·반복은 코드가 구절에서 다시 읽는다. */
    public Map<String, Object> memoToPosts(String memo, String alias, String area, String elderCaution, LocalDate today) {
        String clean = VoiceSafety.scrub(memo == null ? "" : memo.trim()).text();
        List<Map<String, Object>> drafts = new ArrayList<>();
        String source = "rule";
        Optional<JsonNode> j = llm.ask("""
                너는 기관 담당자가 어르신과 통화한 메모를 '도움 요청 공고' 여러 개로 나누는 도우미다.
                - 요청 하나마다 segment(메모에서 그 요청에 해당하는 구절을 그대로 복사), title(20자 안 명사형), detail(봉사자가 할 일 1~2문장, '~해 주세요'),
                  caution(방문할 때 주의할 점, 병명 없이 행동으로, 없으면 빈 문자열), categories(다음 중 1~2개만 [말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행]).
                - 메모에 없는 날짜·시간·사실을 지어내지 않는다. 날짜·시간 계산은 하지 않는다(segment 만 정확히).
                - 신체 돌봄(목욕·기저귀·약 먹이기)·돈 관리는 공고로 만들지 않고 빼 둔다.
                출력: {"items":[{"segment":"...","title":"...","detail":"...","caution":"...","categories":["..."]}]}
                """, "대상 별칭: " + Objects.toString(alias, "") + "\n통화 메모: " + clean, 900);
        if (j.isPresent() && j.get().path("items").isArray() && j.get().path("items").size() > 0) {
            for (JsonNode it : j.get().path("items")) {
                String seg = it.path("segment").asText("").trim();
                if (seg.isEmpty()) continue;
                Map<String, Object> d = draftFrom(seg, area, elderCaution, today);
                String t = it.path("title").asText("").trim();
                if (!t.isEmpty()) d.put("title", VoiceSafety.scrub(t).text());
                String det = it.path("detail").asText("").trim();
                if (!det.isEmpty()) d.put("detail", VoiceSafety.stripHealth(VoiceSafety.scrub(det).text()));
                String c = it.path("caution").asText("").trim();
                if (!c.isEmpty()) d.put("note", VoiceSafety.sanitizeCaution(c));
                List<String> ac = new ArrayList<>();
                for (JsonNode x : it.path("categories")) if (Rules.isCategory(x.asText()) && !ac.contains(x.asText())) ac.add(x.asText());
                if (!ac.isEmpty()) {
                    d.put("categories", ac.size() > 2 ? ac.subList(0, 2) : ac);
                    @SuppressWarnings("unchecked") List<String> miss = new ArrayList<>((List<String>) d.get("missing"));
                    miss.remove("category");
                    d.put("missing", miss);
                    d.put("checked", miss.isEmpty() && !"BLOCK".equals(((Map<?, ?>) d.get("filter")).get("level")));
                }
                RuleEngine.FilterResult f = RuleEngine.filter(String.join(" ", (String) d.get("title"), (String) d.get("detail"), Objects.toString(d.get("note"), "")));
                d.put("filter", Map.of("level", f.level().name(), "reason", Objects.toString(f.reason(), "")));
                if (f.level() == FilterLevel.BLOCK) d.put("checked", false);
                drafts.add(d);
                if (drafts.size() >= 8) break;
            }
            if (!drafts.isEmpty()) source = "ai";
        }
        if (drafts.isEmpty()) for (String seg : split(clean, alias)) { drafts.add(draftFrom(seg, area, elderCaution, today)); if (drafts.size() >= 8) break; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drafts", drafts);
        out.put("count", drafts.size());
        out.put("piiRemoved", VoiceSafety.scrub(memo == null ? "" : memo).removed());
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", llm.providerName());
        out.put("notice", "초안이에요. 날짜·시간·내용을 확인하고 체크한 것만 등록돼요.");
        return out;
    }

    // ===================== C3 주간 브리핑 =====================
    /** stats: 코드가 센 숫자. rule: {lines, todo}. LLM 은 3~5줄 요약과 할 일 순서만 쓴다(숫자 바꾸지 않음). */
    public Map<String, Object> briefing(Map<String, Object> stats, List<String> ruleLines, List<String> ruleTodo) {
        Optional<JsonNode> j = llm.ask("""
                너는 돌봄 기관 담당자의 '이번 주 브리핑'을 쓰는 비서다. 숫자는 주어진 통계만 쓰고 바꾸거나 새로 만들지 않는다.
                lines: 이번 주 상황 3~5줄(각 40자 안). todo: 오늘 할 일을 급한 순서로 3~5개(각 30자 안, '~하기' 체).
                급한 순서: 최종 수락 대기(24시간 기한) → 일지 확인(24시간 뒤 자동 확정) → 확인 권장 대상 안부 → 날짜 가까운 미매칭 공고.
                진단·건강 판단은 하지 않는다. '확인 권장'은 안부 확인을 권하는 표시일 뿐이다.
                출력: {"lines":["..."],"todo":["..."]}
                """, "통계(JSON): " + com.timesavings.service.Views.json(stats), 600);
        Map<String, Object> m = new LinkedHashMap<>();
        if (j.isPresent() && j.get().path("lines").isArray() && j.get().path("lines").size() > 0) {
            List<String> lines = texts(j.get().path("lines"), 5), todo = texts(j.get().path("todo"), 5);
            m.put("lines", lines.size() < 3 ? ruleLines : lines);
            m.put("todo", todo.isEmpty() ? ruleTodo : todo);
            m.put("source", "ai");
            m.put("provider", llm.providerName());
            return m;
        }
        m.put("lines", ruleLines);
        m.put("todo", ruleTodo);
        m.put("source", "rule");
        return m;
    }

    static List<String> texts(JsonNode arr, int limit) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String c = CareMemoAi.clean(n.asText(""));
            if (c != null) out.add(c);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
