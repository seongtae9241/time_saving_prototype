package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.PlannerAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 플래너(2026-11-11 역할별 정리판): 캘린더 · 추천 봉사 · 생애 플랜, 그리고 AI 도움 추천의 단골/가까운 사람 나누기.
 * 숫자·순위·매칭은 여기 코드가 하고, 설명 문장만 PlannerAi(LLM, 없으면 규칙)가 쓴다.
 * 시간은 상속되지 않는다(생애 플랜에 상속 칸이 없다).
 */
@Service
public class PlannerService {
    private static final ObjectMapper OM = new ObjectMapper();
    public static final int MAX_AGE = 95;
    private static final Set<ReqStatus> FIXED = EnumSet.of(ReqStatus.CONFIRMED, ReqStatus.IN_PROGRESS, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED, ReqStatus.DONE);

    private final RequestRepo requests;
    private final PostRepo posts;
    private final ElderRepo elders;
    private final UserRepo users;
    private final LifePlanRepo lifePlans;
    private final PlannerPickRepo picks;
    private final EventService events;
    private final BankService bank;
    private final Views views;
    private final PlannerAi ai;

    public PlannerService(RequestRepo requests, PostRepo posts, ElderRepo elders, UserRepo users, LifePlanRepo lifePlans,
                          PlannerPickRepo picks, EventService events, BankService bank, Views views, PlannerAi ai) {
        this.requests = requests; this.posts = posts; this.elders = elders; this.users = users; this.lifePlans = lifePlans;
        this.picks = picks; this.events = events; this.bank = bank; this.views = views; this.ai = ai;
    }

    // ===================== 생애 플랜 =====================
    public record LifeCmd(Integer age, Integer startAge, Double monthlyHours, Map<String, Double> needs, Boolean save) {}

    /**
     * 생애 플랜 계산(순수 함수 — 테스트 대상).
     * 모으기: 지금 잔액 + 한 달 monthly 시간 × 12 × (startAge − age).
     * 쓰기: 받고 싶은 도움의 한 달 시간 × 가중치(기본 1.0 / 수고 1.2 / 책임 1.5)를 더해 한 해 사용량으로.
     * endAge = startAge + 모은 시간 ÷ 한 해 사용량 (95세까지만 본다).
     */
    public static Map<String, Object> lifeCompute(double balance, int age, int startAge, double monthly, Map<String, Double> needs) {
        age = Math.max(15, Math.min(90, age));
        startAge = Math.max(age + 1, Math.min(MAX_AGE, startAge));
        monthly = Math.max(0, Math.min(60, Rules.round05(monthly)));
        int years = startAge - age;
        double saved = Rules.round05(Math.max(0, balance) + monthly * 12 * years);
        double useMonth = 0;
        List<Map<String, Object>> needRows = new ArrayList<>();
        if (needs != null) for (var e : needs.entrySet()) {
            if (!Rules.isCategory(e.getKey()) || e.getValue() == null || e.getValue() <= 0) continue;
            double h = Math.min(40, Rules.round05(e.getValue()));
            double w = Rules.weight(Rules.categoryLevel(e.getKey()));
            useMonth += h * w;
            needRows.add(Views.map("category", e.getKey(), "hours", h, "weight", w, "cost", Math.round(h * w * 10) / 10.0));
        }
        useMonth = Math.round(useMonth * 10) / 10.0;
        double useYear = Math.round(useMonth * 12 * 10) / 10.0;
        double lastYears = useYear <= 0 ? MAX_AGE - startAge : saved / useYear;
        double endExact = Math.min(MAX_AGE, startAge + lastYears);
        int endAge = (int) Math.floor(endExact + 1e-9);
        boolean ok90 = endExact >= 90 - 1e-9;
        double need90 = Math.max(0, Rules.round05((90 - startAge) * useYear - saved));
        // 90세까지 쓰려면 한 달에 모아야 하는 시간(0.5 단위 올림)
        double need90Monthly = startAge >= 90 ? monthly : Math.max(monthly, Math.ceil(Math.max(0, (90 - startAge) * useYear - Math.max(0, balance)) / (12.0 * years) * 2) / 2.0);
        List<Map<String, Object>> points = new ArrayList<>();
        for (int y = age; y <= MAX_AGE; y++) {
            double v = y <= startAge ? Math.max(0, balance) + monthly * 12 * (y - age) : saved - useYear * (y - startAge);
            points.add(Views.map("age", y, "hours", Math.max(0, Math.round(v * 10) / 10.0)));
        }
        return Views.map("age", age, "startAge", startAge, "monthlyHours", monthly, "balance", Math.max(0, balance),
                "saveYears", years, "saved", saved, "needs", needRows, "useMonthly", useMonth, "useYearly", useYear,
                "lastYears", Math.round(lastYears * 10) / 10.0, "endAge", endAge, "maxAge", MAX_AGE, "okTo90", ok90,
                "need90", need90, "need90Monthly", need90Monthly, "points", points, "inherit", false);
    }

    static String lifeRuleText(Map<String, Object> p) {
        String base = p.get("age") + "~" + ((int) p.get("startAge") - 1) + "세에는 한 달 " + n((double) p.get("monthlyHours")) + "시간씩 모아 "
                + p.get("startAge") + "세에 " + n((double) p.get("saved")) + "시간이 돼요. ";
        if ((double) p.get("useMonthly") <= 0) return base + "받고 싶은 도움을 고르면 몇 살까지 쓸 수 있는지 계산해 드려요.";
        base += p.get("startAge") + "세부터 한 달 " + n((double) p.get("useMonthly")) + "시간을 쓰면 " + p.get("endAge") + "세"
                + ((int) p.get("endAge") >= MAX_AGE ? " 이후" : "") + "까지 도움을 받을 수 있어요. ";
        return Boolean.TRUE.equals(p.get("okTo90"))
                ? base + "지금 속도면 충분해요. 남는 시간은 가족에게 선물할 수 있어요(상속은 안 돼요)."
                : base + "90세까지 쓰려면 " + n((double) p.get("need90")) + "시간이 더 필요해요. 한 달 봉사를 " + n((double) p.get("need90Monthly")) + "시간으로 늘리거나 동행(×1.5)을 섞어 보세요.";
    }

    private static String n(double v) { return Rules.fmtHours(v); }

    private static Map<String, Double> defaultNeeds() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("동행", 2.0); m.put("장보기·심부름", 2.0); m.put("말동무·정서", 1.0);
        return m;
    }

    private Map<String, Double> readNeeds(String json) {
        if (json == null || json.isBlank()) return defaultNeeds();
        try { return OM.readValue(json, new TypeReference<LinkedHashMap<String, Double>>() {}); } catch (Exception e) { return defaultNeeds(); }
    }

    /** 저장된 조건(없으면 기본값)으로 계산. cmd 가 있으면 그 값으로 계산하고 save=true 면 저장. */
    @Transactional
    public Map<String, Object> lifePlan(User u, LifeCmd c) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        LifePlan lp = lifePlans.findById(u.id).orElse(null);
        Integer guess = ActivityAiService.ageOf(u.ageGroup);
        int age = c != null && c.age() != null ? c.age() : lp != null ? lp.age : guess != null ? guess : 50;
        int start = c != null && c.startAge() != null ? c.startAge() : lp != null ? lp.startAge : Math.max(age + 5, 72);
        double monthly = c != null && c.monthlyHours() != null ? c.monthlyHours() : lp != null ? lp.monthlyHours : 4;
        Map<String, Double> needs = c != null && c.needs() != null ? c.needs() : lp != null ? readNeeds(lp.needsJson) : defaultNeeds();
        if (age < 15 || age > 90) throw ApiException.bad("INVALID_AGE", "지금 나이는 15~90세로 적어 주세요");
        if (start <= age || start > MAX_AGE) throw ApiException.bad("INVALID_START_AGE", "도움 받기 시작 나이는 지금 나이보다 많고 " + MAX_AGE + "세 이하여야 해요");
        if (monthly < 0 || monthly > 60) throw ApiException.bad("INVALID_MONTHLY", "한 달 봉사 시간은 0~60시간으로 적어 주세요");
        BankService.Summary s = bank.summary(u.id);
        Map<String, Object> plan = lifeCompute(s.available(), age, start, monthly, needs);
        boolean saved = lp != null;
        if (c != null && Boolean.TRUE.equals(c.save())) {
            if (lp == null) { lp = new LifePlan(); lp.userId = u.id; }
            lp.age = (int) plan.get("age"); lp.startAge = (int) plan.get("startAge"); lp.monthlyHours = (double) plan.get("monthlyHours");
            Map<String, Double> keep = new LinkedHashMap<>();
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) plan.get("needs");
            for (Map<String, Object> r : rows) keep.put((String) r.get("category"), (Double) r.get("hours"));
            lp.needsJson = Views.json(keep);
            lp.updatedAt = LocalDateTime.now();
            lifePlans.save(lp);
            saved = true;
        }
        Map<String, Object> facts = new LinkedHashMap<>(plan);
        facts.remove("points");
        Map<String, Object> advice = ai.explain("생애 플랜 조언", facts, lifeRuleText(plan));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("saved", saved);
        out.put("text", advice.get("text"));
        out.put("source", advice.get("source"));
        if (advice.get("provider") != null) out.put("provider", advice.get("provider"));
        out.put("canUseSelf", s.canUseSelf());
        return out;
    }

    // ===================== 캘린더 · 추천 봉사 =====================
    private static String slotOf(LocalDate d, double startHour) {
        boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
        return (weekend ? "주말 " : "평일 ") + (startHour < 12 ? "오전" : startHour < 18 || weekend ? "오후" : "저녁");
    }

    private String whoOf(Request r, User me) {
        if (me.id.equals(r.volunteerId)) {
            if (r.elderId != null) return elders.findById(r.elderId).map(e -> Rules.maskAlias(e.alias)).orElse("기관 대상");
            return r.recipientId == null ? "" : users.findById(r.recipientId).map(x -> x.name + " 님").orElse("");
        }
        return users.findById(r.volunteerId).map(x -> x.name + " 님").orElse("");
    }

    private static boolean overlaps(double s1, double e1, double s2, double e2) { return s1 < e2 && s2 < e1; }

    /** 추천 봉사 순위 점수(코드): 같은 동 3 · 잘하는 카테고리 2 · 가능 시간대 2 · 빈 시간 필수. */
    static int recScore(boolean sameArea, boolean catMatch, boolean slotMatch) {
        return (sameArea ? 3 : 0) + (catMatch ? 2 : 0) + (slotMatch ? 2 : 0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> planner(User u, String month) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        YearMonth ym;
        try { ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month); }
        catch (Exception e) { throw ApiException.bad("INVALID_MONTH", "달은 2026-11 처럼 적어 주세요"); }
        LocalDate today = LocalDate.now();
        List<Request> mine = requests.findAll().stream()
                .filter(r -> u.id.equals(r.volunteerId) || u.id.equals(r.recipientId)).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        double confirmed = 0;
        for (Request r : mine) {
            if (!FIXED.contains(r.status) || !YearMonth.from(r.date).equals(ym)) continue;
            boolean give = u.id.equals(r.volunteerId);
            double earn = r.status == ReqStatus.DONE && r.earned != null ? r.earned : r.estEarn;
            if (give) confirmed += earn;
            items.add(Views.map("kind", give ? "give" : "recv", "date", r.date.toString(), "startHour", r.startHour, "hours", r.hours,
                    "category", r.category, "title", r.content, "who", whoOf(r, u), "amount", give ? earn : r.hours,
                    "requestId", r.id, "status", r.status));
        }
        for (Map<String, Object> e : events.list(u)) {
            if (!Boolean.TRUE.equals(e.get("myApplied")) || e.get("date") == null) continue;
            LocalDate d = LocalDate.parse((String) e.get("date"));
            if (!YearMonth.from(d).equals(ym)) continue;
            items.add(Views.map("kind", "event", "date", d.toString(), "startHour", e.get("startHour"), "hours", 0,
                    "category", null, "title", e.get("title"), "who", e.get("orgName"), "amount", 0, "eventId", e.get("id")));
        }
        // 내 계획(추천 봉사에서 고른 공고) — 열려 있고 날짜가 안 지난 것만
        List<PlannerPick> myPicks = picks.findByUserId(u.id);
        Set<Long> pickIds = new HashSet<>();
        double planned = 0;
        List<Map<String, Object>> planRows = new ArrayList<>();
        for (PlannerPick pk : myPicks) {
            Post p = posts.findById(pk.postId).orElse(null);
            if (p == null || p.status != PostStatus.OPEN || p.date.isBefore(today)) continue;
            pickIds.add(p.id);
            Map<String, Object> card = views.postCard(p, u);
            double earn = card.get("estEarn") == null ? 0 : ((Number) card.get("estEarn")).doubleValue();
            double ph = pk.hours != null ? pk.hours : planHours(p);
            double ps = pk.startHour != null ? pk.startHour : p.startHour;
            Map<String, Object> row = Views.map("kind", "plan", "date", p.date.toString(), "startHour", ps, "endHour", ps + ph, "hours", ph,
                    "category", p.categories.isEmpty() ? null : p.categories.get(0), "title", p.title, "who", card.get("ownerName"),
                    "amount", earn, "postId", p.id, "postStartHour", p.startHour, "postEndHour", p.endHour, "planSource", pk.planSource);
            planRows.add(row);
            if (YearMonth.from(p.date).equals(ym)) { items.add(row); planned += earn; }
        }
        items.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("date")).thenComparing(m -> ((Number) (m.get("startHour") == null ? 0 : m.get("startHour"))).doubleValue()));

        // 추천 봉사: 열린 봉사 요청 공고 중 내 일정과 겹치지 않는 것(오늘 이후 30일)
        String topCat = topCategory(u, mine);
        List<Map<String, Object>> recs = recommend(u, mine, myPicks, today, topCat);

        LifePlan lp = lifePlans.findById(u.id).orElse(null);
        double target = lp != null && lp.monthlyHours > 0 ? lp.monthlyHours : 4;
        confirmed = Math.round(confirmed * 10) / 10.0;
        planned = Math.round(planned * 10) / 10.0;
        double left = Math.max(0, Math.round((target - confirmed - planned) * 10) / 10.0);
        Map<String, Object> goal = Views.map("target", target, "confirmed", confirmed, "planned", planned, "left", left, "fromLifePlan", lp != null);
        String rule = planRows.isEmpty()
                ? (left <= 0 ? "이번 달 목표 " + n(target) + "시간을 이미 채웠어요."
                    : "이번 달은 " + n(left) + "시간이 더 필요해요. " + (recs.isEmpty() ? "지금은 빈 시간에 맞는 봉사 요청이 없어요. 관심 알림을 켜 두면 새 공고를 알려 드려요."
                    : "추천 봉사 중 마음에 드는 것을 고르고 계획 만들기를 누르면 빈 시간에 맞춰 날짜와 시간을 정해 드릴게요."))
                : "고른 봉사 " + planRows.size() + "개를 빈 시간에 맞춰 달력에 넣었어요. 모두 하면 이번 달 " + n(confirmed + planned) + "시간으로 목표를 "
                    + (left <= 0 ? "채워요." : n(left) + "시간 남겨요.") + " 제안을 보내고 상대가 수락해야 확정돼요.";
        Map<String, Object> note = ai.explain("이번 달 봉사 계획 안내", Views.map("goal", goal, "plannedCount", planRows.size(), "recommendCount", recs.size(), "topCategory", topCat), rule);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("today", today.toString());
        out.put("items", items);
        out.put("goal", goal);
        out.put("plans", planRows);
        out.put("recommends", recs);
        out.put("text", note.get("text"));
        out.put("source", note.get("source"));
        return out;
    }

    // ===================== 추천 봉사 (41판 B-60) =====================
    private static String topCategory(User u, List<Request> mine) {
        return mine.stream().filter(r -> u.id.equals(r.volunteerId) && r.status == ReqStatus.DONE && r.category != null)
                .collect(java.util.stream.Collectors.groupingBy(r -> r.category, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** 계획에 넣을 활동 시간: 공고의 예정 시간(없으면 1시간), 공고 범위보다 길 수 없다. */
    static double planHours(Post p) {
        double h = p.estHours == null || p.estHours <= 0 ? 1.0 : p.estHours;
        return Math.max(0.5, Math.min(h, p.endHour - p.startHour));
    }

    /** [시작, 끝) 목록: 그 날짜에 이미 잡힌 내 일정(끝나지 않은 요청)과 이미 세운 계획. except = 계산에서 뺄 계획(공고 id). */
    private List<double[]> busyOn(User u, LocalDate date, List<Request> mine, List<PlannerPick> myPicks, Long except) {
        List<double[]> out = new ArrayList<>();
        for (Request r : mine) if (!r.status.terminal() && r.date.equals(date)) out.add(new double[]{r.startHour, r.startHour + r.hours});
        for (PlannerPick pk : myPicks) {
            if (pk.postId.equals(except)) continue;
            Post p = posts.findById(pk.postId).orElse(null);
            if (p == null || p.status != PostStatus.OPEN || !p.date.equals(date)) continue;
            double st = pk.startHour != null ? pk.startHour : p.startHour;
            out.add(new double[]{st, st + (pk.hours != null ? pk.hours : planHours(p))});
        }
        return out;
    }

    /** 공고 시간 범위 안에서 hours 만큼 들어가고 busy 와 겹치지 않는 시작 시각(30분 단위) — 순수 함수, 테스트 대상. */
    public static List<Double> freeStarts(double postStart, double postEnd, double hours, List<double[]> busy) {
        List<Double> out = new ArrayList<>();
        for (double st = Math.ceil(postStart * 2) / 2.0; st + hours <= postEnd + 1e-9; st += 0.5) {
            final double s0 = st;
            if (busy.stream().noneMatch(b -> overlaps(s0, s0 + hours, b[0], b[1]))) out.add(st);
        }
        return out;
    }

    private List<Map<String, Object>> recommend(User u, List<Request> mine, List<PlannerPick> myPicks, LocalDate today, String topCat) {
        Set<Long> asked = new HashSet<>(), pickIds = new HashSet<>();
        for (Request r : mine) if (r.postId != null && !r.status.terminal()) asked.add(r.postId);
        for (PlannerPick pk : myPicks) pickIds.add(pk.postId);
        List<Object[]> scored = new ArrayList<>();
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (p.type != PostType.NEED || p.date.isBefore(today) || p.date.isAfter(today.plusDays(30))) continue;
            if (u.id.equals(p.ownerId) || asked.contains(p.id) || pickIds.contains(p.id)) continue;
            // 빈 시간 필수: 공고 범위 안에 예정 시간만큼 들어갈 자리가 있어야 한다
            if (freeStarts(p.startHour, p.endHour, planHours(p), busyOn(u, p.date, mine, myPicks, null)).isEmpty()) continue;
            boolean sameArea = u.area != null && u.area.equals(p.area);
            boolean catMatch = p.categories.stream().anyMatch(c -> c.equals(topCat) || (u.categories != null && u.categories.contains(c)));
            boolean slotMatch = u.timeSlots != null && u.timeSlots.contains(slotOf(p.date, p.startHour));
            scored.add(new Object[]{p, recScore(sameArea, catMatch, slotMatch), sameArea, catMatch, slotMatch});
        }
        scored.sort(Comparator.<Object[]>comparingInt(o -> -(int) o[1]).thenComparing(o -> ((Post) o[0]).date).thenComparing(o -> ((Post) o[0]).id));
        List<Map<String, Object>> recs = new ArrayList<>();
        for (Object[] o : scored.subList(0, Math.min(5, scored.size()))) {
            Post p = (Post) o[0];
            Map<String, Object> card = views.postCard(p, u);
            List<String> why = new ArrayList<>();
            why.add("그 시간에 다른 일정이 없어요");
            if ((boolean) o[4]) why.add("가능 시간(" + slotOf(p.date, p.startHour) + ")과 맞아요");
            if ((boolean) o[2]) why.add("같은 " + p.area + "이에요");
            if ((boolean) o[3]) why.add("잘하시는 도움이에요");
            double w = ((Number) card.get("weight")).doubleValue();
            if (w > 1.0) why.add((w >= 1.5 ? "책임 " : "수고 ") + w + "배");
            card.put("why", String.join(" · ", why));
            card.put("score", o[1]);
            recs.add(card);
        }
        return recs;
    }

    /** GET /api/me/recommends — 홈 화면용(설명 문장 AI 를 부르지 않는다). {recommends[], planCount, basis} */
    @Transactional(readOnly = true)
    public Map<String, Object> recommends(User u) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        LocalDate today = LocalDate.now();
        List<Request> mine = requests.findAll().stream().filter(r -> u.id.equals(r.volunteerId) || u.id.equals(r.recipientId)).toList();
        List<PlannerPick> myPicks = picks.findByUserId(u.id);
        long planCount = myPicks.stream().map(pk -> posts.findById(pk.postId).orElse(null))
                .filter(p -> p != null && p.status == PostStatus.OPEN && !p.date.isBefore(today)).count();
        return Views.map("recommends", recommend(u, mine, myPicks, today, topCategory(u, mine)), "planCount", planCount,
                "basis", Views.map("area", u.area, "timeSlots", u.timeSlots, "categories", u.categories));
    }

    /**
     * 계획 만들기(41판 B-61): 고른 추천 봉사마다 내 빈 시간 후보를 코드가 만들고, 그 가운데 AI 가 시작 시각을 고른다.
     * AI 가 고른 값이 후보에 없거나(범위 밖·겹침) 키가 없으면 코드가 고른다(가능 시간대와 맞는 것 → 공고 시작 시각에 가까운 것).
     * 빈 자리가 없는 공고는 넣지 않고 skipped 로 알려 준다. 날짜는 공고의 날짜 그대로다.
     * 반환 {made:[{postId,date,startHour,endHour,hours,source}], skipped:[{postId,title,reason}], source}
     */
    @Transactional
    public Map<String, Object> addPicks(User u, List<Long> postIds) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 쓸 수 있어요");
        if (postIds == null || postIds.isEmpty()) throw ApiException.bad("POSTS_REQUIRED", "추천 봉사를 골라 주세요");
        List<Request> mine = requests.findAll().stream().filter(r -> u.id.equals(r.volunteerId) || u.id.equals(r.recipientId)).toList();
        List<PlannerPick> myPicks = new ArrayList<>(picks.findByUserId(u.id));
        List<Post> todo = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(postIds)) {
            Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("공고를 찾을 수 없어요"));
            if (p.type != PostType.NEED || p.status != PostStatus.OPEN || u.id.equals(p.ownerId)) throw ApiException.bad("INVALID_POST", "열려 있는 봉사 요청 공고만 계획에 넣을 수 있어요");
            if (picks.findByUserIdAndPostId(u.id, id).isPresent()) continue;
            todo.add(p);
        }
        todo.sort(Comparator.comparing((Post p) -> p.date).thenComparing(p -> p.startHour));
        // AI 에게 줄 재료: 공고마다 지금 일정 기준의 후보 시각(서로 겹치는지는 아래에서 다시 검사한다)
        List<Map<String, Object>> options = new ArrayList<>();
        for (Post p : todo) {
            List<Double> c = freeStarts(p.startHour, p.endHour, planHours(p), busyOn(u, p.date, mine, myPicks, null));
            if (!c.isEmpty()) options.add(Views.map("postId", p.id, "date", p.date.toString(), "title", p.title, "hours", planHours(p),
                    "range", p.startHour + "~" + p.endHour, "candidates", c));
        }
        List<Map<String, Object>> busyFacts = mine.stream().filter(r -> !r.status.terminal() && !r.date.isBefore(LocalDate.now()))
                .map(r -> Views.map("date", r.date.toString(), "startHour", r.startHour, "endHour", r.startHour + r.hours)).toList();
        Map<Long, Double> aiPick = ai.pickTimes(Views.map("timeSlots", u.timeSlots, "busy", busyFacts), options);
        List<Map<String, Object>> made = new ArrayList<>(), skipped = new ArrayList<>();
        boolean anyAi = false;
        for (Post p : todo) {
            double h = planHours(p);
            List<Double> cand = freeStarts(p.startHour, p.endHour, h, busyOn(u, p.date, mine, myPicks, null)); // 방금 넣은 계획까지 반영
            if (cand.isEmpty()) {
                skipped.add(Views.map("postId", p.id, "title", p.title, "reason", "그 시간에 이미 다른 일정이 있어요"));
                continue;
            }
            Double want = aiPick.get(p.id);
            boolean byAi = want != null && cand.stream().anyMatch(c -> Math.abs(c - want) < 1e-9);
            double st = byAi ? want : ruleStart(u, p, cand);
            PlannerPick pk = new PlannerPick();
            pk.userId = u.id; pk.postId = p.id; pk.startHour = st; pk.hours = h; pk.planSource = byAi ? "ai" : "rule";
            picks.save(pk);
            myPicks.add(pk);
            anyAi |= byAi;
            made.add(Views.map("postId", p.id, "title", p.title, "date", p.date.toString(), "startHour", st, "endHour", st + h, "hours", h, "source", pk.planSource));
        }
        return Views.map("made", made, "skipped", skipped, "source", anyAi ? "ai" : "rule");
    }

    /** 규칙으로 고르는 시작 시각: 내 가능 시간대에 드는 후보가 있으면 그 가운데 가장 이른 것, 없으면 가장 이른 후보. */
    private static double ruleStart(User u, Post p, List<Double> cand) {
        if (u.timeSlots != null) for (Double c : cand) if (u.timeSlots.contains(slotOf(p.date, c))) return c;
        return cand.get(0);
    }

    @Transactional
    public void removePick(User u, Long postId) {
        picks.findByUserIdAndPostId(u.id, postId).ifPresent(picks::delete);
    }

    // ===================== AI 도움 추천: 단골 / 가까운 사람 =====================
    /**
     * 공고 카드(views.postCard)를 단골이 올린 것과 그 밖의 가까운 사람 것으로 나눈다(순위·매칭은 코드).
     * 단골 = 받는 쪽이면 내 단골 봉사자가 올린 도움 가능 공고, 돕는 쪽이면 내가 3번 이상 도운 이웃의 봉사 요청 공고.
     * 순위: 같은 동 → 가능 시간대 → 가까운 거리.
     */
    public Map<String, List<Map<String, Object>>> splitByRegular(User u, List<Map<String, Object>> cards, boolean helper, int limit) {
        List<Map<String, Object>> reg = new ArrayList<>(), near = new ArrayList<>();
        Map<Long, Boolean> cache = new HashMap<>();
        Set<Object> seenSeries = new HashSet<>();
        for (Map<String, Object> c : cards) {
            // 매주 반복 공고는 가장 가까운 날짜 하나만(같은 사람이 목록을 채우지 않게)
            if (c.get("seriesId") != null && !seenSeries.add(c.get("seriesId"))) continue;
            Long ownerId = (Long) c.get("ownerId");
            Long elderId = (Long) c.get("elderId");
            boolean regular;
            if (helper) regular = elderId != null ? views.isRegular(u.id, null, elderId) : ownerId != null && cache.computeIfAbsent(ownerId, id -> views.isRegular(u.id, id, null));
            else regular = ownerId != null && cache.computeIfAbsent(ownerId, id -> views.isRegular(id, u.id, null));
            boolean sameArea = u.area != null && u.area.equals(c.get("area"));
            LocalDate d = LocalDate.parse((String) c.get("date"));
            boolean time = u.timeSlots != null && u.timeSlots.contains(slotOf(d, ((Number) c.get("startHour")).doubleValue()));
            List<String> fit = new ArrayList<>();
            if (regular) fit.add("단골");
            if (sameArea) fit.add("같은 동");
            if (time) fit.add("가능 시간과 맞음");
            c.put("fit", Views.map("regular", regular, "area", sameArea, "time", time, "score", (sameArea ? 2 : 0) + (time ? 1 : 0)));
            c.put("fitText", fit.isEmpty() ? "거리 " + c.get("distanceKm") + "km" : String.join(" · ", fit));
            (regular ? reg : near).add(c);
        }
        Comparator<Map<String, Object>> order = Comparator.<Map<String, Object>>comparingInt(c -> -(int) ((Map<?, ?>) c.get("fit")).get("score"))
                .thenComparingDouble(c -> ((Number) c.get("distanceKm")).doubleValue());
        reg.sort(order); near.sort(order);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("regular", reg.subList(0, Math.min(limit, reg.size())));
        out.put("nearby", near.subList(0, Math.min(limit, near.size())));
        return out;
    }
}
