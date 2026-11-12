package com.timesavings.service;

import com.timesavings.ai.ForecastAi;
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
 * 기능명세서 운영자 리포트: 기능 A-02 봉사 유형 리포트 · 기능 A-04 계절별 리포트(다음 계절 예상 수요) · 기능 A-07 대응 추천(모집 문구 초안).
 * 숫자는 모두 여기서 세고, 해석 문장·문구 초안만 {@link ForecastAi}(키 없으면 규칙 문장).
 */
@Service
@Transactional(readOnly = true)
public class AdminReportService {
    public static final int SHORT_DAYS = 180;
    private final UserRepo users;
    private final ElderRepo elders;
    private final PostRepo posts;
    private final RequestRepo requests;
    private final ForecastAi ai;

    public AdminReportService(UserRepo users, ElderRepo elders, PostRepo posts, RequestRepo requests, ForecastAi ai) {
        this.users = users; this.elders = elders; this.posts = posts; this.requests = requests; this.ai = ai;
    }

    private static long pct(long a, long total) { return total == 0 ? 0 : Math.round(a * 100.0 / total); }

    // ===================== A-02 봉사 유형 리포트 =====================
    public Map<String, Object> types() {
        LocalDate from = YearMonth.now().minusMonths(11).atDay(1);
        List<Request> done = requests.findAll().stream().filter(r -> r.status == ReqStatus.DONE && r.date != null && !r.date.isBefore(from) && r.category != null).toList();
        Map<String, Set<Long>> givers = new LinkedHashMap<>();
        Map<String, Set<String>> receivers = new LinkedHashMap<>();
        Map<String, double[]> stat = new LinkedHashMap<>(); // 건수, 시간
        for (String c : Rules.CATEGORIES) { givers.put(c, new HashSet<>()); receivers.put(c, new HashSet<>()); stat.put(c, new double[2]); }
        Set<Long> gaveUsers = new HashSet<>(), gotUsers = new HashSet<>();
        for (Request r : done) {
            gaveUsers.add(r.volunteerId);
            if (r.elderId == null && r.recipientId != null) gotUsers.add(r.recipientId);
            if (!givers.containsKey(r.category)) { givers.put(r.category, new HashSet<>()); receivers.put(r.category, new HashSet<>()); stat.put(r.category, new double[2]); }
            givers.get(r.category).add(r.volunteerId);
            receivers.get(r.category).add(r.elderId != null ? "E" + r.elderId : "U" + r.recipientId);
            stat.get(r.category)[0]++;
            stat.get(r.category)[1] += r.hours;
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String c : givers.keySet()) {
            long g = givers.get(c).size(), rc = receivers.get(c).size();
            cats.add(Views.map("category", c, "givers", g, "receivers", rc, "giverPct", pct(g, g + rc), "receiverPct", g + rc == 0 ? 0 : 100 - pct(g, g + rc),
                    "count", (long) stat.get(c)[0], "hours", Math.round(stat.get(c)[1] * 10) / 10.0));
        }
        List<User> members = users.findAll().stream().filter(u -> u.role == Role.USER && u.status != UserStatus.WITHDRAWN).toList();
        long both = 0, give = 0, get = 0, none = 0;
        for (User u : members) {
            boolean a = gaveUsers.contains(u.id), b = gotUsers.contains(u.id);
            if (a && b) both++; else if (a) give++; else if (b) get++; else none++;
        }
        long elderCount = elders.findAll().stream().filter(e -> e.status == ElderStatus.ACTIVE).count();
        long total = members.size();
        List<Map<String, Object>> typesList = List.of(
                Views.map("key", "GIVE", "label", "봉사만 하는 회원", "count", give, "pct", pct(give, total)),
                Views.map("key", "BOTH", "label", "주고받는 회원", "count", both, "pct", pct(both, total)),
                Views.map("key", "GET", "label", "도움만 받는 회원", "count", get, "pct", pct(get, total)),
                Views.map("key", "NONE", "label", "아직 활동 없는 회원", "count", none, "pct", pct(none, total)));
        // 규칙 해석 문장
        Map<String, Object> few = cats.stream().filter(c -> (Long) c.get("count") > 0).min(Comparator.comparingLong(c -> (Long) c.get("giverPct"))).orElse(null);
        StringBuilder sb = new StringBuilder();
        if (few == null) sb.append("최근 12개월에 끝난 활동이 아직 없어 해석할 자료가 부족해요.");
        else {
            sb.append("주는 쪽이 가장 얇은 종류는 '").append(few.get("category")).append("'(주는 사람 ").append(few.get("givers")).append("명 · 받는 사람 ").append(few.get("receivers")).append("명)이에요. ");
            sb.append("회원 ").append(total).append("명 가운데 주고받는 회원은 ").append(both).append("명(").append(pct(both, total)).append("%), 아직 활동이 없는 회원은 ").append(none).append("명이에요. ");
            if (none > both) sb.append("짐작: 가입만 하고 첫 활동을 못 한 회원이 많아 보여요. 첫 활동 안내를 살펴보세요.");
            else if (get > give) sb.append("짐작: 도움만 받는 회원이 많아 봉사 쪽 참여를 권하는 안내가 필요해 보여요.");
            else sb.append("짐작: 주는 쪽과 받는 쪽이 고르게 섞여 있어요.");
        }
        Map<String, Object> facts = Views.map("categories", cats, "userTypes", typesList, "elders", elderCount, "members", total);
        Map<String, Object> out = new LinkedHashMap<>(facts);
        out.put("periodMonths", 12);
        out.put("doneCount", done.size());
        out.put("interpretation", ai.interpret("카테고리별 주는 쪽·받는 쪽 비율과 사용자 유형 분포", facts, sb.toString().trim()));
        return out;
    }

    // ===================== A-04 계절별 리포트 · 다음 계절 예상 수요 =====================
    static final List<String> SEASONS = List.of("봄", "여름", "가을", "겨울");

    /** 수요 한 건 = 도움 요청(NEED) 공고의 카테고리마다 1 + 받는 쪽이 보낸 요청 1. [from, to) 사이에 만든 것. */
    static Map<String, Integer> demand(List<Post> posts, List<Request> reqs, LocalDateTime from, LocalDateTime to, String season) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : Rules.CATEGORIES) m.put(c, 0);
        for (Post p : posts) {
            if (p.type != PostType.NEED || p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(from) || !p.createdAt.isBefore(to)) continue;
            if (season != null && !season.equals(AnalyticsService.season(p.createdAt.getMonthValue()))) continue;
            for (String c : p.categories) if (Rules.isCategory(c)) m.merge(c, 1, Integer::sum);
        }
        for (Request r : reqs) {
            if (r.kind == Kind.PROPOSAL || r.createdAt == null || r.createdAt.isBefore(from) || !r.createdAt.isBefore(to) || r.category == null) continue;
            if (season != null && !season.equals(AnalyticsService.season(r.createdAt.getMonthValue()))) continue;
            if (Rules.isCategory(r.category)) m.merge(r.category, 1, Integer::sum);
        }
        return m;
    }

    private static int sum(Map<String, Integer> m) { return m.values().stream().mapToInt(Integer::intValue).sum(); }

    /**
     * 다음 계절 예상 수요(코드 계산): 기준 = 지난 12개월 가운데 그 계절의 카테고리별 수요(그 계절 기록이 없으면 12개월 수요 ÷ 4),
     * 추세 = 최근 90일 수요 ÷ 그 앞 90일 수요(0.5~2.0 으로 자름, 앞 기간 기록이 없으면 1.0). 예상 = 기준 × 추세(반올림).
     */
    public static Map<String, Object> nextSeason(List<Post> posts, List<Request> reqs, LocalDateTime now) {
        String cur = AnalyticsService.season(now.getMonthValue());
        String next = SEASONS.get((SEASONS.indexOf(cur) + 1) % 4);
        LocalDateTime yearAgo = now.minusMonths(12);
        Map<String, Integer> same = demand(posts, reqs, yearAgo, now, next), all = demand(posts, reqs, yearAgo, now, null);
        boolean hasSeason = sum(same) > 0;
        int recent = sum(demand(posts, reqs, now.minusDays(90), now, null)), prev = sum(demand(posts, reqs, now.minusDays(180), now.minusDays(90), null));
        double growth = prev == 0 ? 1.0 : Math.max(0.5, Math.min(2.0, (double) recent / prev));
        growth = Math.round(growth * 100) / 100.0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String c : Rules.CATEGORIES) {
            double base = hasSeason ? same.get(c) : all.get(c) / 4.0;
            rows.add(Views.map("category", c, "base", Math.round(base * 10) / 10.0, "expected", Math.round(base * growth)));
        }
        rows.sort((a, b) -> Long.compare((Long) b.get("expected"), (Long) a.get("expected")));
        return Views.map("season", next, "currentSeason", cur, "growth", growth, "recent90", recent, "prev90", prev,
                "basis", hasSeason ? "지난 " + next + " 기록" : "지난 12개월 평균(지난 " + next + " 기록 없음)", "categories", rows,
                "total", rows.stream().mapToLong(r -> (Long) r.get("expected")).sum());
    }

    public Map<String, Object> season() {
        List<Post> allPosts = posts.findAll();
        List<Request> all = requests.findAll();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = YearMonth.now().minusMonths(11).atDay(1).atStartOfDay();
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = YearMonth.now().minusMonths(i);
            Map<String, Integer> d = demand(allPosts, all, ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay(), null);
            String top = sum(d) == 0 ? null : d.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            monthly.add(Views.map("month", ym.toString(), "season", AnalyticsService.season(ym.getMonthValue()), "total", sum(d), "counts", d, "top", top));
        }
        Map<String, Object> next = nextSeason(allPosts, all, now);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) next.get("categories");
        String rule = (Long) next.get("total") == 0 ? "아직 수요 기록이 적어 다음 계절 예상을 내기 어려워요."
                : "다음 계절(" + next.get("season") + ")에는 " + rows.get(0).get("category") + " 요청이 가장 많을 것으로 예상돼요(약 " + rows.get(0).get("expected") + "건). "
                + "전체 예상은 약 " + next.get("total") + "건이고, " + next.get("basis") + "에 최근 석 달 추세(×" + next.get("growth") + ")를 곱한 값이에요. "
                + "짐작: 계절이 바뀌면 바깥 활동과 집 안 도움의 비중이 달라질 수 있으니 그 종류의 봉사자를 미리 모아 두면 좋아요.";
        Map<String, Object> facts = Views.map("nextSeason", next, "monthly", monthly);
        Map<String, Object> out = Views.map("periodMonths", 12, "monthly", monthly,
                "seasons", AnalyticsService.seasonSupplyDemand(allPosts, all, from), "nextSeason", next);
        out.put("interpretation", ai.interpret("계절·월별 카테고리 변화와 다음 계절 예상 수요(예상 수요 숫자는 코드가 계산한 값)", facts, rule));
        return out;
    }

    // ===================== A-07 대응 추천: 모집 문구 초안 =====================
    static String slot(LocalDate date, double startHour) {
        boolean weekend = date != null && (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY);
        return (weekend ? "주말 " : "평일 ") + (startHour < 12 ? "오전" : startHour < 18 ? "오후" : "저녁");
    }

    public Map<String, Object> recruit() {
        LocalDateTime since = LocalDateTime.now().minusDays(SHORT_DAYS);
        Map<Long, Post> postMap = new HashMap<>();
        List<Post> allPosts = posts.findAll();
        allPosts.forEach(p -> postMap.put(p.id, p));
        Map<Long, User> userMap = new HashMap<>();
        users.findAll().forEach(u -> userMap.put(u.id, u));
        Map<Long, Elder> elderMap = new HashMap<>();
        elders.findAll().forEach(e -> elderMap.put(e.id, e));
        Map<String, int[]> cnt = new LinkedHashMap<>();              // 동네|카테고리 → [수요, 공급]
        Map<String, Map<String, Integer>> slots = new HashMap<>();   // 동네|카테고리 → 시간대 → 건수(수요)
        for (Post p : allPosts) {
            if (p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(since) || p.area == null) continue;
            for (String c : p.categories) {
                String k = p.area + "|" + c;
                int[] v = cnt.computeIfAbsent(k, x -> new int[2]);
                if (p.type == PostType.NEED) { v[0]++; slots.computeIfAbsent(k, x -> new HashMap<>()).merge(slot(p.date, p.startHour), 1, Integer::sum); } else v[1]++;
            }
        }
        for (Request r : requests.findAll()) {
            if (r.createdAt == null || r.createdAt.isBefore(since) || r.category == null) continue;
            Post p = r.postId == null ? null : postMap.get(r.postId);
            String area = p != null && p.area != null ? p.area : r.elderId != null ? Optional.ofNullable(elderMap.get(r.elderId)).map(e -> e.dong).orElse(null)
                    : Optional.ofNullable(userMap.get(r.recipientId)).map(u -> u.area).orElse(null);
            if (area == null) continue;
            String k = area + "|" + r.category;
            int[] v = cnt.computeIfAbsent(k, x -> new int[2]);
            if (r.kind != Kind.PROPOSAL) { v[0]++; slots.computeIfAbsent(k, x -> new HashMap<>()).merge(slot(r.date, r.startHour), 1, Integer::sum); }
            if (r.status == ReqStatus.VOL_ACCEPTED || r.status.active() || r.status == ReqStatus.DONE || r.status == ReqStatus.NOSHOW) v[1]++;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        cnt.forEach((k, v) -> {
            if (v[0] - v[1] <= 0) return;
            String[] ac = k.split("\\|", 2);
            String ts = slots.getOrDefault(k, Map.of()).entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse("평일 오후");
            items.add(Views.map("area", ac[0], "category", ac[1], "timeSlot", ts, "demand", v[0], "supply", v[1], "gap", v[0] - v[1]));
        });
        items.sort((a, b) -> Integer.compare((Integer) b.get("gap"), (Integer) a.get("gap")));
        List<Map<String, Object>> top = items.stream().limit(5).toList();
        List<Map<String, Object>> rule = new ArrayList<>();
        for (Map<String, Object> i : top) rule.add(ruleDraft(i));
        Map<String, Object> out = new LinkedHashMap<>(ai.recruit(top, rule));
        out.put("days", SHORT_DAYS);
        out.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        return out;
    }

    static Map<String, Object> ruleDraft(Map<String, Object> i) {
        String area = (String) i.get("area"), cat = (String) i.get("category"), ts = (String) i.get("timeSlot");
        double w = Rules.isCategory(cat) ? Rules.weight(Rules.categoryLevel(cat)) : 1.0;
        String title = "[" + area + "] " + ts + " " + cat + " 봉사자를 찾아요";
        String body = area + "에서 최근 " + SHORT_DAYS + "일 동안 " + cat + " 도움 요청이 " + i.get("demand") + "건 있었는데, 도울 수 있다는 이웃은 " + i.get("supply") + "건이었어요. "
                + ts + "에 시간을 낼 수 있는 이웃을 기다려요. "
                + "봉사한 시간은 시간 통장에 쌓여요(" + Rules.josa(cat, "은", "는") + " 1시간에 " + w + "시간). "
                + "앱의 봉사 요청 게시판에서 " + area + " " + cat + " 공고를 확인해 주세요.";
        return Views.map("title", title, "body", body);
    }
}
