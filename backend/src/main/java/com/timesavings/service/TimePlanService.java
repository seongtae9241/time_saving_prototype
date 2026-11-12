package com.timesavings.service;

import com.timesavings.ai.TimePlanAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 기능 U-24 시간 플랜 · U-25 월별 리포트(기능명세서).
 * <p>시간 플랜: 나이·잔액·적립 속도와 "비슷한 사람들의 실제 사용 패턴"으로 앞으로 몇 년 동안 어떤 도움을 받을 수 있는지와 적립 목표를 계산한다.
 * 비슷한 사람들 = ① 나와 같은 나이대 사용자 ② (모자라면) 도움을 주로 받는 60대 이상 사용자. 최근 12개월 완료된 요청에서
 * 카테고리별 한 달 평균 사용 시간을 센다. 기록이 있는 사람이 {@link #MIN_PEERS}명 미만이면 일반적인 가정값을 쓰고 그 사실을 응답에 밝힌다.
 * <p>숫자는 모두 여기 코드가 계산하고, 설명 문장·적립 목표 문구만 TimePlanAi(LLM, 키 없으면 규칙 문장)가 쓴다.
 * 개인을 알아볼 수 있는 값(이름·누가 얼마나)은 응답에 넣지 않는다 — 여러 사람의 평균과 사람 수만.
 */
@Service
@Transactional(readOnly = true)
public class TimePlanService {
    public static final int MIN_PEERS = 5;
    public static final int WINDOW_MONTHS = 12;
    public static final int RATE_MONTHS = 6;
    public static final int TARGET_AGE = 90;
    public static final int MAX_AGE = 95;
    public static final int SENIOR_AGE = 60;
    public static final String DEFAULT_NOTICE = "아직 기록이 적어 일반적인 가정으로 계산했어요";

    private final UserRepo users;
    private final RequestRepo requests;
    private final LedgerRepo ledger;
    private final LifePlanRepo lifePlans;
    private final BankService bank;
    private final TimePlanAi ai;

    public TimePlanService(UserRepo users, RequestRepo requests, LedgerRepo ledger, LifePlanRepo lifePlans, BankService bank, TimePlanAi ai) {
        this.users = users; this.requests = requests; this.ledger = ledger; this.lifePlans = lifePlans; this.bank = bank; this.ai = ai;
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    static double ceil05(double v) { return Math.ceil(v * 2 - 1e-9) / 2.0; }

    /** 3.0 → "3", 2.5 → "2.5" */
    static String n(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(r1(v)); }

    private static double hoursOf(Request r) { return "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours; }

    /** 일반적인 가정(기록이 모자랄 때): 동행 월 2시간 · 장보기 월 2시간 · 말동무 월 1시간. 생애 플랜의 기본값과 같다. */
    public static Map<String, Double> defaultPattern() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("동행", 2.0); m.put("장보기·심부름", 2.0); m.put("말동무·정서", 1.0);
        return m;
    }

    /** basis: SAME_AGE(같은 나이대) · SENIOR(60대 이상) · DEFAULT(일반적인 가정). monthlyHours: 카테고리 → 한 달 평균 시간(가중치 전). */
    public record Pattern(String basis, String label, int peers, Map<String, Double> monthlyHours) {
        public boolean usedDefault() { return "DEFAULT".equals(basis); }
    }

    /**
     * 사람들의 실제 사용 기록 집계(순수 함수 — 테스트 대상).
     * byPeer: 사람(받은 사람 id) → 그 사람이 받은 완료 요청들. 사람마다 "카테고리별 시간 ÷ 쓴 기간(달)"을 구해 사람 수로 평균 낸다.
     * 쓴 기간은 창(최근 12개월) 안의 첫 기록 달부터 이번 달까지(최소 1달).
     */
    public static Map<String, Double> averageMonthly(Map<Long, List<Request>> byPeer, LocalDate today) {
        Map<String, Double> sum = new LinkedHashMap<>();
        int peers = 0;
        for (List<Request> list : byPeer.values()) {
            if (list.isEmpty()) continue;
            peers++;
            LocalDate first = list.stream().map(r -> r.date).min(Comparator.naturalOrder()).orElse(today);
            long months = Math.max(1, ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(today)) + 1);
            for (Request r : list) sum.merge(r.category, hoursOf(r) / months, Double::sum);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        if (peers == 0) return out;
        for (String c : Rules.CATEGORIES) { // 카테고리 순서를 고정
            double v = r1(sum.getOrDefault(c, 0.0) / peers);
            if (v >= 0.1) out.put(c, v);
        }
        return out;
    }

    private Map<Long, List<Request>> usageOf(Set<Long> userIds, LocalDate today) {
        LocalDate since = today.minusMonths(WINDOW_MONTHS);
        Map<Long, List<Request>> by = new LinkedHashMap<>();
        for (Request r : requests.findAll()) {
            if (r.status != ReqStatus.DONE || r.recipientId == null || !userIds.contains(r.recipientId)) continue;
            if (r.date.isBefore(since) || r.date.isAfter(today)) continue;
            by.computeIfAbsent(r.recipientId, k -> new ArrayList<>()).add(r);
        }
        return by;
    }

    /** 비슷한 사람들의 사용 패턴. 같은 나이대 → 60대 이상 → 일반적인 가정 순서로, 기록 있는 사람이 5명 이상인 첫 묶음을 쓴다. */
    public Pattern peerPattern(User me, LocalDate today) {
        List<User> all = users.findAll().stream().filter(u -> u.role == Role.USER && !u.id.equals(me.id)).toList();
        if (me.ageGroup != null && !me.ageGroup.isBlank()) {
            Set<Long> same = new HashSet<>();
            for (User u : all) if (me.ageGroup.equals(u.ageGroup)) same.add(u.id);
            Map<Long, List<Request>> by = usageOf(same, today);
            Map<String, Double> avg = averageMonthly(by, today);
            if (by.size() >= MIN_PEERS && !avg.isEmpty()) return new Pattern("SAME_AGE", "같은 " + me.ageGroup + " 이웃 " + by.size() + "명", by.size(), avg);
        }
        Set<Long> senior = new HashSet<>();
        for (User u : all) { Integer a = ActivityAiService.ageOf(u.ageGroup); if (a != null && a >= SENIOR_AGE) senior.add(u.id); }
        Map<Long, List<Request>> by = usageOf(senior, today);
        Map<String, Double> avg = averageMonthly(by, today);
        if (by.size() >= MIN_PEERS && !avg.isEmpty()) return new Pattern("SENIOR", "도움을 받고 있는 60대 이상 이웃 " + by.size() + "명", by.size(), avg);
        return new Pattern("DEFAULT", "일반적인 가정", by.size(), defaultPattern());
    }

    /** 적립 속도: 최근 6개월(적립을 시작한 지 6개월이 안 됐으면 그 기간) 동안 직접 번 시간의 한 달 평균. [속도, 계산에 쓴 달 수] */
    public double[] earnRate(Long userId, LocalDate today) {
        YearMonth now = YearMonth.from(today), from = now.minusMonths(RATE_MONTHS - 1);
        YearMonth first = null;
        double sum = 0;
        for (Ledger l : ledger.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            boolean earn = l.type == LedgerType.EARN || (l.type == LedgerType.ADJUST && l.requestId != null);
            if (!earn) continue;
            YearMonth ym = YearMonth.from(l.createdAt);
            if (first == null || ym.isBefore(first)) first = ym;
            if (!ym.isBefore(from) && !ym.isAfter(now)) sum += l.amount;
        }
        if (first == null) return new double[]{0, 0};
        long months = Math.max(1, Math.min(RATE_MONTHS, ChronoUnit.MONTHS.between(first, now) + 1));
        return new double[]{r1(Math.max(0, sum) / months), months};
    }

    /**
     * 시간 플랜 계산(순수 함수 — 테스트 대상).
     * 모으기: 잔액 + 적립 속도 × 12 × (도움 받기 시작 나이 − 지금 나이).
     * 쓰기: 비슷한 사람들의 카테고리별 한 달 시간 × 가중치(기본 1.0 · 수고 1.2 · 책임 1.5)의 합.
     * years = 모은 시간 ÷ 한 해 사용량, goalMonthly = 목표 나이(90세)까지 받으려면 한 달에 모아야 하는 시간(0.5 단위 올림).
     */
    public static Map<String, Object> compute(int age, int startAge, double balance, double rate, Map<String, Double> monthlyHours) {
        balance = Math.max(0, balance);
        rate = Math.max(0, rate);
        startAge = Math.max(age, Math.min(MAX_AGE, startAge));
        int saveYears = startAge - age;
        double useMonthly = 0;
        for (var e : monthlyHours.entrySet()) useMonthly += e.getValue() * Rules.weight(Rules.categoryLevel(e.getKey()));
        useMonthly = r1(useMonthly);
        double useYearly = r1(useMonthly * 12);
        double saved = r1(balance + rate * 12 * saveYears);
        int targetAge = Math.min(MAX_AGE, Math.max(TARGET_AGE, startAge + 5));
        int targetYears = targetAge - startAge;
        Double nowYears = useYearly <= 0 ? null : r1(balance / useYearly);
        Double years = useYearly <= 0 ? null : r1(Math.min(MAX_AGE - startAge, saved / useYearly));
        Integer endAge = years == null ? null : (int) Math.floor(startAge + years + 1e-9);
        double needTotal = r1(targetYears * useYearly);
        double shortfall = r1(Math.max(0, needTotal - saved));
        // 모으는 기간이 남았으면 그 기간에, 이미 받는 나이면 받는 기간에 나눠 모은다
        int spread = saveYears > 0 ? saveYears : targetYears;
        double goalMonthly = spread <= 0 ? 0 : ceil05(Math.max(0, needTotal - balance) / (12.0 * spread));
        boolean onTrack = shortfall <= 0 || rate + 1e-9 >= goalMonthly;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : monthlyHours.entrySet()) {
            double w = Rules.weight(Rules.categoryLevel(e.getKey()));
            rows.add(Views.map("category", e.getKey(), "hoursPerMonth", e.getValue(), "weight", w, "costPerMonth", r1(e.getValue() * w),
                    "totalHours", years == null ? null : r1(e.getValue() * 12 * years)));
        }
        return Views.map("age", age, "startAge", startAge, "saveYears", saveYears, "balance", balance, "earnRate", rate,
                "useMonthly", useMonthly, "useYearly", useYearly, "saved", saved, "nowYears", nowYears, "years", years, "endAge", endAge,
                "targetAge", targetAge, "needTotal", needTotal, "shortfall", shortfall, "goalMonthly", goalMonthly,
                "goalExtra", r1(Math.max(0, goalMonthly - rate)), "onTrack", onTrack, "rows", rows);
    }

    @SuppressWarnings("unchecked")
    static String[] ruleTexts(Map<String, Object> p, Pattern pat) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) p.get("rows");
        String helps = String.join(", ", rows.stream().limit(3).map(r -> r.get("category") + " 월 " + n((double) r.get("hoursPerMonth")) + "시간").toList());
        int saveYears = (int) p.get("saveYears");
        double rate = (double) p.get("earnRate");
        StringBuilder sb = new StringBuilder();
        sb.append("지금 통장에 ").append(n((double) p.get("balance"))).append("시간이 있고, ")
                .append(rate > 0 ? "한 달에 " + n(rate) + "시간쯤 모으고 있어요. " : "최근에는 모은 시간이 없어요. ");
        if (saveYears > 0) sb.append("이대로면 ").append(p.get("startAge")).append("세에 ").append(n((double) p.get("saved"))).append("시간이 돼요. ");
        if (p.get("years") == null) sb.append("받고 싶은 도움이 정해지면 얼마나 받을 수 있는지 계산해 드려요.");
        else sb.append(pat.usedDefault() ? "보통 " : "비슷한 분들처럼 ").append(helps).append("씩 받으면 ")
                .append(saveYears > 0 ? p.get("startAge") + "세부터 " : "지금부터 ").append("약 ").append(n((double) p.get("years"))).append("년 동안 도움을 받을 수 있어요.");
        String goal;
        if (p.get("years") == null) goal = "한 달에 1시간부터 모아 보세요.";
        else if (Boolean.TRUE.equals(p.get("onTrack")))
            goal = "지금 속도면 " + p.get("targetAge") + "세까지 충분해요. 남는 시간은 가족에게 선물할 수 있어요.";
        else goal = p.get("targetAge") + "세까지 받으려면 한 달에 " + n((double) p.get("goalMonthly")) + "시간씩 모아 보세요. 지금보다 한 달 "
                    + n((double) p.get("goalExtra")) + "시간 더예요.";
        return new String[]{sb.toString(), goal};
    }

    private static void numbers(Object o, Set<Double> out) {
        if (o instanceof Number x) out.add(x.doubleValue());
        else if (o instanceof Map<?, ?> m) m.values().forEach(v -> numbers(v, out));
        else if (o instanceof Collection<?> c) c.forEach(v -> numbers(v, out));
    }

    /** AI 문장에 쓸 수 있는 숫자: 계산 결과에 있는 숫자와 가중치·고정 기준값만. */
    static Set<Double> allowed(Object facts) {
        Set<Double> s = new HashSet<>(List.of(1.0, 1.2, 1.5, 12.0, (double) MIN_PEERS, (double) TARGET_AGE, (double) SENIOR_AGE));
        numbers(facts, s);
        return s;
    }

    /** age·startAge 를 주면 그 값으로(저장하지 않음), 없으면 생애 플랜에 저장한 값 → 나이대에서 어림한 값 순으로 쓴다. */
    public Map<String, Object> plan(User me, Integer ageIn, Integer startIn) {
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        LocalDate today = LocalDate.now();
        LifePlan lp = lifePlans.findById(me.id).orElse(null);
        Integer guess = ActivityAiService.ageOf(me.ageGroup);
        int age = ageIn != null ? ageIn : lp != null ? lp.age : guess != null ? guess : 50;
        if (age < 15 || age > 90) throw ApiException.bad("INVALID_AGE", "지금 나이는 15~90세로 적어 주세요");
        // 이미 도움을 받는 나이(65세 이상)면 지금부터, 아니면 70세부터 받는 것으로 본다
        int start = startIn != null ? startIn : lp != null && lp.startAge >= age ? lp.startAge : age >= 65 ? age : 70;
        if (start < age || start > MAX_AGE) throw ApiException.bad("INVALID_START_AGE", "도움 받기 시작 나이는 지금 나이 이상, " + MAX_AGE + "세 이하여야 해요");
        BankService.Summary s = bank.summary(me.id);
        double[] rate = earnRate(me.id, today);
        Pattern pat = peerPattern(me, today);
        Map<String, Object> plan = compute(age, start, s.available(), rate[0], pat.monthlyHours());
        Map<String, Object> pattern = Views.map("basis", pat.basis(), "label", pat.label(), "peers", pat.peers(), "minPeers", MIN_PEERS,
                "usedDefault", pat.usedDefault(), "windowMonths", WINDOW_MONTHS);
        Map<String, Object> facts = new LinkedHashMap<>(plan);
        facts.put("pattern", pattern);
        String[] rule = ruleTexts(plan, pat);
        Map<String, Object> text = ai.plan(facts, allowed(facts), rule[0], rule[1]);
        Map<String, Object> out = new LinkedHashMap<>(plan);
        out.put("ageGroup", me.ageGroup);
        out.put("ageFrom", ageIn != null ? "input" : lp != null ? "lifePlan" : guess != null ? "ageGroup" : "default");
        out.put("earnMonths", (int) rate[1]);
        out.put("hold", s.hold());
        out.put("canUseSelf", s.canUseSelf());
        out.put("pattern", pattern);
        out.put("notice", pat.usedDefault() ? DEFAULT_NOTICE : null);
        out.put("text", text.get("text"));
        out.put("goalText", text.get("goal"));
        out.put("source", text.get("source"));
        if (text.get("provider") != null) out.put("provider", text.get("provider"));
        out.put("rules", "시간은 돈으로 바꿀 수 없고, 연결된 가족에게만 선물할 수 있어요. 제도·가중치가 바뀌면 숫자가 달라질 수 있어요.");
        return out;
    }

    // ===================== 기능 U-25 월별 리포트 =====================
    /** 그 달(활동 날짜 기준)에 완료된 활동 집계: 봉사(give)·도움 받음(receive)의 횟수·시간·카테고리별 횟수·가장 많이 한 도움. */
    public Map<String, Object> monthStats(Long userId, YearMonth ym) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (boolean give : new boolean[]{true, false}) {
            Map<String, double[]> cats = new LinkedHashMap<>();
            int count = 0;
            double hours = 0, amount = 0;
            for (Request r : requests.findAll()) {
                if (r.status != ReqStatus.DONE || !YearMonth.from(r.date).equals(ym)) continue;
                if (!userId.equals(give ? r.volunteerId : r.recipientId)) continue;
                count++;
                hours += hoursOf(r);
                amount += r.earned == null ? 0 : r.earned;
                double[] v = cats.computeIfAbsent(r.category, k -> new double[2]);
                v[0]++; v[1] += hoursOf(r);
            }
            List<Map<String, Object>> by = new ArrayList<>();
            cats.forEach((k, v) -> by.add(Views.map("category", k, "count", (int) v[0], "hours", r1(v[1]))));
            by.sort(Comparator.<Map<String, Object>>comparingInt(m -> -(int) m.get("count")).thenComparingDouble(m -> -(double) m.get("hours")));
            out.put(give ? "give" : "receive", Views.map("count", count, "hours", r1(hours), give ? "earned" : "used", r1(amount),
                    "byCategory", by, "top", by.isEmpty() ? null : by.get(0).get("category"), "topCount", by.isEmpty() ? 0 : by.get(0).get("count")));
        }
        return out;
    }

    private static String diffText(String what, int d, String unit) {
        return d == 0 ? what + "는 지난달과 같아요" : what + "가 지난달보다 " + Math.abs(d) + unit + (d > 0 ? " 늘었어요" : " 줄었어요");
    }

    @SuppressWarnings("unchecked")
    static String reportRule(int month, Map<String, Object> cur, Map<String, Object> change) {
        Map<String, Object> g = (Map<String, Object>) cur.get("give"), r = (Map<String, Object>) cur.get("receive");
        int gc = (int) g.get("count"), rc = (int) r.get("count");
        int dg = (int) change.get("giveCount"), dr = (int) change.get("receiveCount");
        StringBuilder sb = new StringBuilder();
        if (gc == 0 && rc == 0) sb.append(month).append("월에는 아직 완료된 활동이 없어요.");
        else {
            sb.append(month).append("월에는 ");
            if (gc > 0) sb.append("봉사를 ").append(gc).append("회(").append(n((double) g.get("hours"))).append("시간) 했");
            if (gc > 0 && rc > 0) sb.append("고, ");
            if (rc > 0) sb.append("도움을 ").append(rc).append("회(").append(n((double) r.get("hours"))).append("시간) 받았");
            sb.append("어요.");
            if (g.get("top") != null) sb.append(" 가장 많이 한 도움은 ").append(g.get("top")).append("(").append(g.get("topCount")).append("회)예요.");
            else if (r.get("top") != null) sb.append(" 가장 많이 받은 도움은 ").append(r.get("top")).append("(").append(r.get("topCount")).append("회)예요.");
        }
        if (gc > 0 || dg != 0) sb.append(" ").append(diffText("봉사", dg, "회")).append(".");
        if (rc > 0 || dr != 0) sb.append(" ").append(diffText("도움 받기", dr, "회")).append(".");
        return sb.toString();
    }

    /** 이번 달(또는 month=YYYY-MM) 리포트. 횟수·시간·변화는 코드가 세고, 요약 문장만 AI(키 없으면 규칙 문장). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> monthReport(User me, String month) {
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        YearMonth ym;
        try { ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month.trim()); }
        catch (Exception e) { throw ApiException.bad("INVALID_MONTH", "달은 2026-11 처럼 적어 주세요"); }
        if (ym.isAfter(YearMonth.now())) throw ApiException.bad("INVALID_MONTH", "아직 오지 않은 달이에요");
        Map<String, Object> cur = monthStats(me.id, ym), prev = monthStats(me.id, ym.minusMonths(1));
        Map<String, Object> cg = (Map<String, Object>) cur.get("give"), cr = (Map<String, Object>) cur.get("receive");
        Map<String, Object> pg = (Map<String, Object>) prev.get("give"), pr = (Map<String, Object>) prev.get("receive");
        Map<String, Object> change = Views.map("giveCount", (int) cg.get("count") - (int) pg.get("count"),
                "receiveCount", (int) cr.get("count") - (int) pr.get("count"),
                "giveHours", r1((double) cg.get("hours") - (double) pg.get("hours")),
                "receiveHours", r1((double) cr.get("hours") - (double) pr.get("hours")));
        Map<String, Object> facts = Views.map("month", ym.getMonthValue(), "prevMonth", ym.minusMonths(1).getMonthValue(), "thisMonth", cur,
                "lastMonth", Views.map("giveCount", pg.get("count"), "giveHours", pg.get("hours"), "receiveCount", pr.get("count"), "receiveHours", pr.get("hours")),
                "change", change);
        Set<Double> ok = allowed(facts);
        for (Object v : change.values()) ok.add(Math.abs(((Number) v).doubleValue())); // "2회 줄었어요"처럼 절댓값으로 쓴다
        ok.add((double) ym.getYear());
        Map<String, Object> text = ai.monthReport(facts, ok, reportRule(ym.getMonthValue(), cur, change));
        Map<String, Object> out = Views.map("month", ym.toString(), "prevMonth", ym.minusMonths(1).toString(), "isCurrent", ym.equals(YearMonth.now()),
                "give", cg, "receive", cr, "prev", Views.map("give", Views.map("count", pg.get("count"), "hours", pg.get("hours")),
                        "receive", Views.map("count", pr.get("count"), "hours", pr.get("hours"))),
                "change", change, "text", text.get("text"), "source", text.get("source"));
        if (text.get("provider") != null) out.put("provider", text.get("provider"));
        return out;
    }
}
