package com.timesavings.service;

import com.timesavings.ai.InsightAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 6장 LLM 개인 분석: 내 활동 분석, 내 평가 습관, 내 봉사 플랜.
 * 횟수·비율·평균·기간(가중치 포함)은 모두 코드가 계산하고, InsightAi 는 설명·추천 문장만 쓴다(키 없으면 규칙 문장).
 */
@Service
@Transactional(readOnly = true)
public class PersonalInsightService {
    public static final double WEEKS_PER_MONTH = 4.345;
    private final RequestRepo requests;
    private final RatingRepo ratings;
    private final BankService bank;
    private final Views views;
    private final InsightAi insight;

    public PersonalInsightService(RequestRepo requests, RatingRepo ratings, BankService bank, Views views, InsightAi insight) {
        this.requests = requests; this.ratings = ratings; this.bank = bank; this.views = views; this.insight = insight;
    }

    static String slot(double startHour) { return startHour < 12 ? "오전" : startHour < 18 ? "오후" : "저녁"; }

    private static double hoursOf(Request r) { return "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours; }

    private static Map<String, Object> side(List<Request> list) {
        Map<String, Integer> cats = new LinkedHashMap<>(), slots = new LinkedHashMap<>();
        for (String s : List.of("오전", "오후", "저녁")) slots.put(s, 0);
        double h = 0;
        for (Request r : list) {
            cats.merge(r.category, 1, Integer::sum);
            slots.merge(slot(r.startHour), 1, Integer::sum);
            h += hoursOf(r);
        }
        List<Map<String, Object>> byCat = cats.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> Views.map("category", e.getKey(), "count", e.getValue(), "pct", list.isEmpty() ? 0 : Math.round(e.getValue() * 100.0 / list.size()))).toList();
        String topSlot = list.isEmpty() ? null : slots.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return Views.map("count", list.size(), "hours", BankService.r2(h), "byCategory", byCat, "bySlot", slots, "topSlot", topSlot);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> insights(User me) {
        List<Request> all = requests.findAll();
        List<Request> given = all.stream().filter(r -> me.id.equals(r.volunteerId) && r.status == ReqStatus.DONE).toList();
        List<Request> received = all.stream().filter(r -> me.id.equals(r.recipientId) && r.status == ReqStatus.DONE).toList();
        Map<String, Object> g = side(given), rc = side(received);
        double[] rt = views.rating(me.id);
        g.put("ratingAvg", rt[0]);
        g.put("ratingCount", (int) rt[1]);
        g.put("topTags", views.topTags(me.id, 3));

        // 내가 남긴 평가
        List<Rating> mine = ratings.findByRaterId(me.id);
        int[] dist = new int[5];
        Map<String, Integer> tags = new HashMap<>();
        List<String> comments = new ArrayList<>();
        for (Rating x : mine.stream().sorted(Comparator.comparing((Rating x) -> x.createdAt).reversed()).toList()) {
            if (x.stars >= 1 && x.stars <= 5) dist[x.stars - 1]++;
            x.tags.forEach(t -> tags.merge(t, 1, Integer::sum));
            if (x.comment != null && !x.comment.isBlank() && comments.size() < 3) comments.add(x.comment.trim());
        }
        double avg = mine.isEmpty() ? 0 : Math.round(mine.stream().mapToInt(x -> x.stars).average().orElse(0) * 10) / 10.0;
        List<String> topTags = tags.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3).map(Map.Entry::getKey).toList();
        String style = mine.isEmpty() ? null : avg >= 4.7 ? "후한 편" : avg <= 3.5 ? "꼼꼼한 편" : "균형 잡힌 편";
        Map<String, Object> rating = Views.map("count", mine.size(), "avg", avg, "distribution", List.of(dist[0], dist[1], dist[2], dist[3], dist[4]),
                "topTags", topTags, "recentComments", comments, "style", style,
                "tagRate", mine.isEmpty() ? 0 : Math.round(mine.stream().filter(x -> !x.tags.isEmpty()).count() * 100.0 / mine.size()));

        Map<String, Object> rule = Views.map("activity", activityRule(me.name, g, rc), "rating", ratingRule(rating));
        Map<String, Object> text = insight.personal(me.name + " 님", Views.map("given", g, "received", rc, "myRatings", rating), rule);
        return Views.map("given", g, "received", rc, "rating", rating,
                "activityText", text.get("activity"), "ratingText", text.get("rating"), "source", text.get("source"), "provider", text.get("provider"));
    }

    @SuppressWarnings("unchecked")
    static String activityRule(String name, Map<String, Object> g, Map<String, Object> rc) {
        int gn = (Integer) g.get("count"), rn = (Integer) rc.get("count");
        if (gn == 0 && rn == 0) return "아직 끝난 활동이 없어요. 가까운 공고에서 가벼운 말동무부터 시작해 보세요.";
        StringBuilder sb = new StringBuilder();
        if (gn > 0) {
            Map<String, Object> top = ((List<Map<String, Object>>) g.get("byCategory")).get(0);
            sb.append(name).append(" 님은 지금까지 ").append(gn).append("번 도왔고, 그중 ").append(top.get("category")).append(com.timesavings.ai.InsightAi.josa((String) top.get("category"), "이", "가")).append(" ")
                    .append(top.get("pct")).append("%로 가장 많아요. 주로 ").append(g.get("topSlot")).append("에 활동해요. ");
            if ((Integer) g.get("ratingCount") > 0)
                sb.append("받은 평가는 평균 ★").append(g.get("ratingAvg"))
                        .append(((List<String>) g.get("topTags")).isEmpty() ? "" : "이고 '" + String.join("', '", (List<String>) g.get("topTags")) + "'라는 말을 자주 들어요")
                        .append(". ");
        }
        if (rn > 0) {
            Map<String, Object> top = ((List<Map<String, Object>>) rc.get("byCategory")).get(0);
            sb.append("도움은 ").append(rn).append("번 받았고 ").append(top.get("category")).append(" 도움을 가장 자주 받아요.");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    static String ratingRule(Map<String, Object> rating) {
        int n = (Integer) rating.get("count");
        if (n == 0) return "아직 남긴 평가가 없어요. 활동이 끝나면 짧은 칭찬 한마디가 큰 힘이 돼요.";
        List<String> tags = (List<String>) rating.get("topTags");
        return n + "번 평가를 남겼고 평균 ★" + rating.get("avg") + "으로 " + rating.get("style") + "이에요."
                + (tags.isEmpty() ? "" : " 자주 쓰는 칭찬은 '" + String.join("', '", tags) + "'예요.");
    }

    // ---------------------------------------------------------------- 봉사 플랜
    public record PlanItem(String category, Double perWeek, Double hours) {}

    static void validate(List<PlanItem> plans) {
        if (plans == null || plans.isEmpty()) throw ApiException.bad("PLAN_REQUIRED", "받고 싶은 도움을 하나 이상 골라 주세요");
        if (plans.size() > 6) throw ApiException.bad("TOO_MANY_PLANS", "한 번에 6개까지 계산할 수 있어요");
        for (PlanItem p : plans) {
            if (p.category() == null || !Rules.isCategory(p.category())) throw ApiException.bad("INVALID_CATEGORY", "도움 종류를 골라 주세요");
            if (p.perWeek() == null || p.perWeek() < 0.25 - 1e-9 || p.perWeek() > 7 + 1e-9 || Math.abs(p.perWeek() * 4 - Math.round(p.perWeek() * 4)) > 1e-9)
                throw ApiException.bad("INVALID_FREQUENCY", "횟수는 월 1회(0.25)부터 주 7회까지예요");
            if (p.hours() == null || p.hours() < 0.5 || p.hours() > 8 || !Rules.halfStep(p.hours()))
                throw ApiException.bad("INVALID_HOURS", "1회 시간은 0.5시간 단위로 0.5~8시간이에요");
        }
    }

    public static String freqText(double perWeek) {
        if (perWeek < 1 - 1e-9) return "월 " + Math.round(perWeek * 4) + "회";
        return "주 " + (perWeek == Math.floor(perWeek) ? String.valueOf((int) perWeek) : String.valueOf(perWeek)) + "회";
    }

    public static String spanText(Integer weeks) {
        if (weeks == null) return "-";
        if (weeks == 0) return "1주도 어려워요";
        if (weeks < 8) return "약 " + weeks + "주";
        return "약 " + Math.round(weeks / WEEKS_PER_MONTH * 10) / 10.0 + "개월(" + weeks + "주)";
    }

    /** 가중치(기본 1.0·수고 1.2·책임 1.5)를 반영한 기간 계산. usable 은 지금 쓸 수 있는 시간. */
    public static Map<String, Object> compute(double usable, List<PlanItem> plans) {
        double weekly = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlanItem p : plans) {
            double w = Rules.weight(Rules.categoryLevel(p.category()));
            double cost = Rules.earn(p.hours(), w);
            double wk = cost * p.perWeek();
            weekly += wk;
            Integer alone = wk <= 0 ? null : (int) Math.floor(usable / wk + 1e-9);
            rows.add(Views.map("category", p.category(), "perWeek", p.perWeek(), "freq", freqText(p.perWeek()), "hours", p.hours(), "weight", w,
                    "costPerSession", cost, "weeklyCost", BankService.r2(wk), "sessionsAffordable", cost <= 0 ? 0 : (int) Math.floor(usable / cost + 1e-9),
                    "weeksAlone", alone, "spanAlone", spanText(alone)));
        }
        weekly = BankService.r2(weekly);
        Integer weeks = weekly <= 0 ? null : (int) Math.floor(usable / weekly + 1e-9);
        Double months = weeks == null ? null : Math.round(weeks / WEEKS_PER_MONTH * 10) / 10.0;
        double monthlyCost = BankService.r2(weekly * WEEKS_PER_MONTH);
        return Views.map("usable", usable, "plans", rows, "weeklyCost", weekly, "monthlyCost", monthlyCost, "weeks", weeks, "months", months, "span", spanText(weeks));
    }

    static String describe(List<PlanItem> plans) {
        return String.join(" + ", plans.stream().map(p -> p.category() + " " + freqText(p.perWeek()) + " " + Rules.fmtHours(p.hours()) + "시간").toList());
    }

    private static final Map<String, Double> DEFAULT_HOURS = Map.of("동행", 2.0, "장보기·심부름", 1.5, "가벼운 집안일", 1.5);

    public Map<String, Object> carePlan(User me, List<PlanItem> plans) {
        validate(plans);
        BankService.Summary s = bank.summary(me.id);
        Map<String, Object> result = compute(s.usable(), plans);
        Map<String, Object> ifUnlocked = s.canUseSelf() ? null : compute(s.available(), plans);
        double need = Math.max(0, BankService.r2(BankService.SELF_USE_MIN - s.earnedSelf()));

        // 추천 후보(코드 계산): 자주 받은 도움 1순위 주 1회 / 말동무 주 2회 / 동행 월 2회 + 말동무 주 1회
        List<String> top = requests.findAll().stream().filter(r -> me.id.equals(r.recipientId) && r.status == ReqStatus.DONE)
                .collect(java.util.stream.Collectors.groupingBy(r -> r.category, java.util.stream.Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        String first = top.isEmpty() ? "말동무·정서" : top.get(0);
        List<List<PlanItem>> combos = new ArrayList<>();
        combos.add(List.of(new PlanItem(first, 1.0, DEFAULT_HOURS.getOrDefault(first, 1.0))));
        combos.add(List.of(new PlanItem("말동무·정서", 2.0, 1.0)));
        combos.add(List.of(new PlanItem("동행", 0.5, 2.0), new PlanItem("말동무·정서", 1.0, 1.0)));
        List<Map<String, Object>> candidates = new ArrayList<>();
        double base = s.canUseSelf() ? s.usable() : s.available();
        for (List<PlanItem> c : combos) {
            Map<String, Object> r = compute(base, c);
            candidates.add(Views.map("label", describe(c), "plans", c.stream().map(p -> Views.map("category", p.category(), "perWeek", p.perWeek(), "hours", p.hours())).toList(),
                    "weeklyCost", r.get("weeklyCost"), "weeks", r.get("weeks"), "span", r.get("span")));
        }

        String ruleText = (s.canUseSelf() ? "" : "직접 적립한 시간이 " + Rules.fmtHours(BankService.SELF_USE_MIN) + "시간이 되어야 내 시간을 쓸 수 있어요(" + Rules.fmtHours(need) + "시간 더 필요). ")
                + "지금 쓸 수 있는 " + Rules.fmtHours(s.usable()) + "시간으로 " + describe(plans) + "이면 한 주에 " + result.get("weeklyCost") + "시간씩 줄어 "
                + result.get("span") + " 받을 수 있어요."
                + (plans.stream().anyMatch(p -> "동행".equals(p.category())) ? " 동행은 책임 등급이라 1시간에 1.5시간씩 빠져요." : "")
                + (ifUnlocked != null && ifUnlocked.get("weeks") != null ? " 조건을 채우면 잔액 " + Rules.fmtHours(s.available()) + "시간으로 " + ifUnlocked.get("span") + "이에요." : "");
        Map<String, Object> summary = Views.map("usable", s.usable(), "available", s.available(), "balance", s.balance(), "hold", s.hold(),
                "earnedSelf", s.earnedSelf(), "canUseSelf", s.canUseSelf(), "selfUseMin", BankService.SELF_USE_MIN, "needToUnlock", need,
                "chosen", describe(plans), "chosenWeeks", result.get("weeks"), "chosenSpan", result.get("span"));
        Map<String, Object> text = insight.carePlan(summary, candidates, ruleText);
        return Views.map("summary", summary, "result", result, "ifUnlocked", ifUnlocked, "candidates", candidates,
                "text", text.get("text"), "pick", text.get("pick"), "source", text.get("source"), "provider", text.get("provider"),
                "weights", Views.map("BASIC", 1.0, "EFFORT", 1.2, "RESPONSIBLE", 1.5));
    }
}
