package com.timesavings.service;

import com.timesavings.ai.InsightAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 5장 운영자 대시보드 분석: 나이대별 인기 카테고리, 월(계절)별 추이, 카테고리별 수요·공급 차이(숫자는 모두 코드),
 * 그리고 LLM 인사이트(공급 부족 이유 짐작, 새 카테고리 후보 — 1시간 캐시).
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {
    public static final int SD_DAYS = 180;
    public static final long CACHE_MS = 60 * 60 * 1000L;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final UserRepo users;
    private final ElderRepo elders;
    private final InsightAi insight;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public AnalyticsService(RequestRepo requests, PostRepo posts, UserRepo users, ElderRepo elders, InsightAi insight) {
        this.requests = requests; this.posts = posts; this.users = users; this.elders = elders; this.insight = insight;
    }

    // ---------------------------------------------------------------- 집계
    private static List<Map<String, Object>> topCats(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n).map(e -> Views.map("category", e.getKey(), "count", e.getValue())).toList();
    }

    private static int ageOrder(String a) {
        if (a == null) return 99;
        String d = a.replaceAll("[^0-9]", "");
        return d.isEmpty() ? 98 : Integer.parseInt(d);
    }

    static String season(int month) {
        return switch (month) { case 3, 4, 5 -> "봄"; case 6, 7, 8 -> "여름"; case 9, 10, 11 -> "가을"; default -> "겨울"; };
    }

    private List<Map<String, Object>> byAge(Map<String, Map<String, Integer>> m) {
        List<Map<String, Object>> out = new ArrayList<>();
        m.entrySet().stream().sorted(Comparator.comparingInt(e -> ageOrder(e.getKey()))).forEach(e -> {
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            out.add(Views.map("ageGroup", e.getKey(), "total", total, "top", topCats(e.getValue(), 3), "counts", new TreeMap<>(e.getValue())));
        });
        return out;
    }

    /**
     * 카테고리별 수요·공급(since 이후 생성분).
     * 수요 = 도움 요청(NEED) 공고 + 받는 쪽이 보낸 요청(REQUEST·REGULAR), 공급 = 봉사 가능(OFFER) 공고 + 수락된 건(VOL_ACCEPTED 이후로 간 요청·제안).
     */
    public static List<Map<String, Object>> supplyDemand(List<Post> posts, List<Request> reqs, LocalDateTime since) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (String c : Rules.CATEGORIES) m.put(c, new int[4]); // needPosts, requests, offerPosts, accepted
        for (Post p : posts) {
            if (p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(since)) continue;
            for (String c : p.categories) {
                int[] v = statRow(m, c);
                if (v == null) continue;
                if (p.type == PostType.NEED) v[0]++; else v[2]++;
            }
        }
        for (Request r : reqs) {
            if (r.createdAt == null || r.createdAt.isBefore(since)) continue;
            int[] v = statRow(m, r.category);
            if (v == null) continue;
            if (r.kind != Kind.PROPOSAL) v[1]++;
            boolean accepted = r.status == ReqStatus.VOL_ACCEPTED || r.status.active() || r.status == ReqStatus.DONE || r.status == ReqStatus.NOSHOW;
            if (accepted) v[3]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        m.forEach((c, v) -> {
            int demand = v[0] + v[1], supply = v[2] + v[3];
            out.add(Views.map("category", c, "demand", demand, "supply", supply, "gap", demand - supply,
                    "needPosts", v[0], "requests", v[1], "offerPosts", v[2], "accepted", v[3],
                    "ratio", demand == 0 ? null : Math.round(supply * 100.0 / demand)));
        });
        out.sort((a, b) -> Integer.compare((Integer) b.get("gap"), (Integer) a.get("gap")));
        return out;
    }

    /** 기능 A-15: 운영자가 승인해 더한 카테고리도 자기 이름으로 집계한다(기본 6종 뒤에 줄이 생긴다). 카테고리가 아닌 값은 세지 않는다. */
    private static int[] statRow(Map<String, int[]> m, String cat) {
        if (cat == null) return null;
        int[] v = m.get(cat);
        if (v == null && Rules.isCategory(cat)) { v = new int[4]; m.put(cat, v); }
        return v;
    }

    private static boolean accepted(Request r) {
        return r.status == ReqStatus.VOL_ACCEPTED || r.status.active() || r.status == ReqStatus.DONE || r.status == ReqStatus.NOSHOW;
    }

    /** 묶음 키(지역·계절) → 카테고리 → [수요, 공급]. 수요·공급 뜻은 {@link #supplyDemand} 와 같다. */
    private static void add(Map<String, Map<String, int[]>> m, String key, String cat, int demand, int supply) {
        if (key == null || key.isBlank() || cat == null || !Rules.isCategory(cat)) return;
        // 기능 A-15: 더한 카테고리는 기본 6종 뒤에 자기 줄을 만든다(예전에는 여기서 null 이 나와 통계 화면이 깨졌다)
        int[] v = m.computeIfAbsent(key, k -> { Map<String, int[]> c = new LinkedHashMap<>(); for (String x : Rules.CATEGORIES) c.put(x, new int[2]); return c; })
                .computeIfAbsent(cat, k -> new int[2]);
        v[0] += demand; v[1] += supply;
    }

    private static List<Map<String, Object>> catRows(Map<String, int[]> cats) {
        List<Map<String, Object>> rows = new ArrayList<>();
        cats.forEach((c, v) -> rows.add(Views.map("category", c, "demand", v[0], "supply", v[1], "gap", v[0] - v[1])));
        return rows;
    }

    /**
     * D-04 지역(동)별로 어떤 도움이 부족한지: since 이후 지역 × 카테고리 수요·공급.
     * 공고는 공고의 동, 요청은 areaOf(공고의 동 → 도움 대상의 동 → 받는 사람의 동)로 묶는다. 부족(gap) 큰 지역이 위.
     */
    public static List<Map<String, Object>> areaShortage(List<Post> posts, List<Request> reqs, LocalDateTime since,
                                                         java.util.function.Function<Request, String> areaOf) {
        Map<String, Map<String, int[]>> m = new TreeMap<>();
        for (Post p : posts) {
            if (p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(since)) continue;
            for (String c : p.categories) add(m, p.area, c, p.type == PostType.NEED ? 1 : 0, p.type == PostType.NEED ? 0 : 1);
        }
        for (Request r : reqs) {
            if (r.createdAt == null || r.createdAt.isBefore(since)) continue;
            add(m, areaOf.apply(r), r.category, r.kind != Kind.PROPOSAL ? 1 : 0, accepted(r) ? 1 : 0);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        m.forEach((area, cats) -> {
            List<Map<String, Object>> rows = catRows(cats);
            int demand = rows.stream().mapToInt(x -> (Integer) x.get("demand")).sum(), supply = rows.stream().mapToInt(x -> (Integer) x.get("supply")).sum();
            List<Map<String, Object>> shortList = rows.stream().filter(x -> (Integer) x.get("gap") > 0)
                    .sorted((a, b) -> Integer.compare((Integer) b.get("gap"), (Integer) a.get("gap"))).toList();
            out.add(Views.map("area", area, "demand", demand, "supply", supply, "gap", demand - supply,
                    "shortTotal", shortList.stream().mapToInt(x -> (Integer) x.get("gap")).sum(), "short", shortList, "categories", rows));
        });
        out.sort((a, b) -> Integer.compare((Integer) b.get("shortTotal"), (Integer) a.get("shortTotal")));
        return out;
    }

    /** D-11 계절별 봉사 요청(수요)·봉사 가능량(공급): from 이후 만들어진 공고·요청을 만든 달의 계절 × 카테고리로 센다. 봄·여름·가을·겨울 순. */
    public static List<Map<String, Object>> seasonSupplyDemand(List<Post> posts, List<Request> reqs, LocalDateTime from) {
        Map<String, Map<String, int[]>> m = new LinkedHashMap<>();
        for (String s : List.of("봄", "여름", "가을", "겨울")) add(m, s, Rules.CATEGORIES.get(0), 0, 0);
        for (Post p : posts) {
            if (p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(from)) continue;
            for (String c : p.categories) add(m, season(p.createdAt.getMonthValue()), c, p.type == PostType.NEED ? 1 : 0, p.type == PostType.NEED ? 0 : 1);
        }
        for (Request r : reqs) {
            if (r.createdAt == null || r.createdAt.isBefore(from)) continue;
            add(m, season(r.createdAt.getMonthValue()), r.category, r.kind != Kind.PROPOSAL ? 1 : 0, accepted(r) ? 1 : 0);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        m.forEach((s, cats) -> {
            List<Map<String, Object>> rows = catRows(cats);
            int demand = rows.stream().mapToInt(x -> (Integer) x.get("demand")).sum(), supply = rows.stream().mapToInt(x -> (Integer) x.get("supply")).sum();
            out.add(Views.map("season", s, "demand", demand, "supply", supply, "gap", demand - supply, "categories", rows));
        });
        return out;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false) private GroupService groups;

    public Map<String, Object> analytics() {
        List<Request> all = requests.findAll();
        Map<Long, User> userMap = new HashMap<>();
        users.findAll().forEach(u -> userMap.put(u.id, u));
        Map<Long, Elder> elderMap = new HashMap<>();
        elders.findAll().forEach(e -> elderMap.put(e.id, e));
        LocalDate from = YearMonth.now().minusMonths(11).atDay(1);
        List<Request> done = all.stream().filter(r -> r.status == ReqStatus.DONE && !r.date.isBefore(from) && r.category != null).toList();

        Map<String, Map<String, Integer>> recAge = new HashMap<>(), volAge = new HashMap<>();
        for (Request r : done) {
            String ra = r.elderId != null ? Optional.ofNullable(elderMap.get(r.elderId)).map(e -> e.ageGroup).orElse(null)
                    : Optional.ofNullable(userMap.get(r.recipientId)).map(u -> u.ageGroup).orElse(null);
            String va = Optional.ofNullable(userMap.get(r.volunteerId)).map(u -> u.ageGroup).orElse(null);
            if (ra != null) recAge.computeIfAbsent(ra, k -> new HashMap<>()).merge(r.category, 1, Integer::sum);
            if (va != null) volAge.computeIfAbsent(va, k -> new HashMap<>()).merge(r.category, 1, Integer::sum);
        }

        // 단체 봉사(기능 U-10): 참석이 확인된 건도 봉사자 나이대별·월별 봉사 횟수에 넣는다
        List<GroupService.Attended> grp = groups == null ? List.of()
                : groups.attendedAll().stream().filter(a -> a.date() != null && !a.date().isBefore(from) && a.category() != null).toList();
        for (GroupService.Attended a : grp) {
            String va = Optional.ofNullable(userMap.get(a.userId())).map(u -> u.ageGroup).orElse(null);
            if (va != null) volAge.computeIfAbsent(va, k -> new HashMap<>()).merge(a.category(), 1, Integer::sum);
        }
        List<Map<String, Object>> monthly = new ArrayList<>();
        Map<String, Map<String, Integer>> seasons = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = YearMonth.now().minusMonths(i);
            Map<String, Integer> cnt = new LinkedHashMap<>();
            for (String c : Rules.CATEGORIES) cnt.put(c, 0);
            done.stream().filter(r -> YearMonth.from(r.date).equals(ym)).forEach(r -> cnt.merge(r.category, 1, Integer::sum));
            grp.stream().filter(a -> YearMonth.from(a.date()).equals(ym)).forEach(a -> cnt.merge(a.category(), 1, Integer::sum));
            int total = cnt.values().stream().mapToInt(Integer::intValue).sum();
            List<Map<String, Object>> top = topCats(cnt, 1);
            monthly.add(Views.map("month", ym.toString(), "season", season(ym.getMonthValue()), "total", total, "counts", cnt,
                    "top", total == 0 || top.isEmpty() ? null : top.get(0).get("category")));
            Map<String, Integer> s = seasons.computeIfAbsent(season(ym.getMonthValue()), k -> new HashMap<>());
            cnt.forEach((k, v) -> s.merge(k, v, Integer::sum));
        }
        List<Map<String, Object>> seasonTop = new ArrayList<>();
        seasons.forEach((k, v) -> {
            int total = v.values().stream().mapToInt(Integer::intValue).sum();
            seasonTop.add(Views.map("season", k, "total", total, "top", total == 0 ? List.of() : topCats(v, 2)));
        });

        List<Post> allPosts = posts.findAll();
        Map<Long, Post> postMap = new HashMap<>();
        allPosts.forEach(p -> postMap.put(p.id, p));
        java.util.function.Function<Request, String> areaOf = r -> {
            Post p = r.postId == null ? null : postMap.get(r.postId);
            if (p != null && p.area != null) return p.area;
            if (r.elderId != null) return Optional.ofNullable(elderMap.get(r.elderId)).map(e -> e.dong).orElse(null);
            return r.recipientId == null ? null : Optional.ofNullable(userMap.get(r.recipientId)).map(u -> u.area).orElse(null);
        };
        LocalDateTime sdSince = LocalDateTime.now().minusDays(SD_DAYS);
        List<Map<String, Object>> sd = supplyDemand(allPosts, all, sdSince);
        return Views.map("areaShortage", areaShortage(allPosts, all, sdSince, areaOf),
                "seasonSupplyDemand", seasonSupplyDemand(allPosts, all, from.atStartOfDay()),
                "periodMonths", 12, "byRecipientAge", byAge(recAge), "byVolunteerAge", byAge(volAge),
                "monthlyCategory", monthly, "seasons", seasonTop, "supplyDemand", sd, "supplyDemandDays", SD_DAYS);
    }

    /**
     * 이용 리포트의 도움 요청 / 봉사 가능 토글(2026-11-13 대표 지시).
     * 고른 종류의 공고(최근 12개월)만 세어 나이별 인기 · 계절별 · 지역별 통계를 낸다. 숫자는 모두 코드가 센다.
     * 나이대는 공고를 올린 사람(관리 대상 대리 공고는 그 관리 대상) 기준.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> byPostType(PostType type) {
        Map<Long, User> userMap = new HashMap<>();
        users.findAll().forEach(u -> userMap.put(u.id, u));
        Map<Long, Elder> elderMap = new HashMap<>();
        elders.findAll().forEach(e -> elderMap.put(e.id, e));
        LocalDate from = YearMonth.now().minusMonths(11).atDay(1);
        List<Post> list = posts.findAll().stream().filter(p -> p.type == type && p.date != null && !p.date.isBefore(from)
                && p.categories != null && !p.categories.isEmpty()).toList();
        Map<String, Map<String, Integer>> age = new HashMap<>(), area = new TreeMap<>();
        for (Post p : list) {
            String ag = p.elderId != null ? Optional.ofNullable(elderMap.get(p.elderId)).map(e -> e.ageGroup).orElse(null)
                    : Optional.ofNullable(userMap.get(p.ownerId)).map(u -> u.ageGroup).orElse(null);
            for (String c : p.categories) {
                if (ag != null && !ag.isBlank()) age.computeIfAbsent(ag, k -> new HashMap<>()).merge(c, 1, Integer::sum);
                if (p.area != null && !p.area.isBlank()) area.computeIfAbsent(p.area, k -> new HashMap<>()).merge(c, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> monthly = new ArrayList<>();
        Map<String, Map<String, Integer>> seasons = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = YearMonth.now().minusMonths(i);
            Map<String, Integer> cnt = new LinkedHashMap<>();
            for (String c : Rules.CATEGORIES) cnt.put(c, 0);
            list.stream().filter(p -> YearMonth.from(p.date).equals(ym)).forEach(p -> p.categories.forEach(c -> cnt.merge(c, 1, Integer::sum)));
            int total = cnt.values().stream().mapToInt(Integer::intValue).sum();
            List<Map<String, Object>> top = topCats(cnt, 1);
            monthly.add(Views.map("month", ym.toString(), "season", season(ym.getMonthValue()), "total", total, "counts", cnt,
                    "top", total == 0 || top.isEmpty() ? null : top.get(0).get("category")));
            Map<String, Integer> sm = seasons.computeIfAbsent(season(ym.getMonthValue()), k -> new HashMap<>());
            cnt.forEach((k, v) -> sm.merge(k, v, Integer::sum));
        }
        List<Map<String, Object>> seasonTop = new ArrayList<>();
        seasons.forEach((k, v) -> {
            int total = v.values().stream().mapToInt(Integer::intValue).sum();
            seasonTop.add(Views.map("season", k, "total", total, "top", total == 0 ? List.of() : topCats(v, 3)));
        });
        List<Map<String, Object>> areas = new ArrayList<>();
        area.forEach((k, v) -> {
            int total = v.values().stream().mapToInt(Integer::intValue).sum();
            areas.add(Views.map("area", k, "total", total, "top", topCats(v, 3)));
        });
        areas.sort((a, b) -> Integer.compare((Integer) b.get("total"), (Integer) a.get("total")));
        return Views.map("type", type.name(), "label", type == PostType.NEED ? "도움 요청" : "봉사 가능", "periodMonths", 12,
                "postCount", list.size(), "byAge", byAge(age), "monthly", monthly, "seasons", seasonTop, "byArea", areas);
    }

    // ---------------------------------------------------------------- 카테고리 밖 문장
    /** 6개 카테고리의 대표 낱말. 요청 문장에 하나도 없으면 "카테고리에 안 맞는 문장"으로 본다. */
    static final Map<String, Pattern> CAT_WORDS = Map.of(
            "동행", Pattern.compile("동행|병원|진료|보건소|약국|은행|외출|같이\\s*가"),
            "장보기·심부름", Pattern.compile("장보기|장 보|마트|시장|심부름|사\\s*오|사다|구입|우유|반찬거리"),
            "말동무·정서", Pattern.compile("말벗|말동무|안부|산책|이야기|대화|말씀|외로"),
            "디지털 도움", Pattern.compile("폰|휴대폰|스마트폰|앱|키오스크|영상통화|카톡|카카오|문자|인터넷|컴퓨터|설정|태블릿"),
            "생활 정리", Pattern.compile("정리|서류|우편|옷장|냉장고|분리수거"),
            "가벼운 집안일", Pattern.compile("청소|빨래|설거지|집안일|이불|걸레|쓸고|닦"));

    public static boolean fitsCategory(String text) {
        if (text == null || text.isBlank()) return true;
        for (Pattern p : CAT_WORDS.values()) if (p.matcher(text).find()) return true;
        return false;
    }

    /** 공고(NEED 제목·내용)와 요청 내용 중 어느 카테고리 낱말에도 안 맞는 문장(중복 제거). */
    public List<String> unmatchedTexts() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        LocalDateTime since = LocalDateTime.now().minusDays(365);
        for (Post p : posts.findAll()) {
            if (p.type != PostType.NEED || p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(since)) continue;
            String t = (Objects.toString(p.title, "") + " " + Objects.toString(p.detail, "")).trim();
            if (!fitsCategory(t)) out.add(Objects.toString(p.detail, Objects.toString(p.title, "")).trim());
        }
        for (Request r : requests.findAll()) {
            if (r.createdAt == null || r.createdAt.isBefore(since)) continue;
            String t = Objects.toString(r.content, "") + " " + Objects.toString(r.memo, "");
            if (!fitsCategory(t)) out.add(Objects.toString(r.content, "").trim());
        }
        return out.stream().filter(s -> !s.isBlank()).limit(40).toList();
    }

    /** 규칙으로 묶는 새 카테고리 후보(키 없을 때). */
    static final Map<String, Pattern> CANDIDATES = new LinkedHashMap<>();
    static {
        CANDIDATES.put("간단한 수리", Pattern.compile("형광등|전구|방충망|고장|수리|선반|문고리|건전지|못\\s*박"));
        CANDIDATES.put("반려동물 돌봄", Pattern.compile("강아지|고양이|반려|애완|개\\s*밥|사료"));
        CANDIDATES.put("화분·텃밭", Pattern.compile("화분|분갈이|텃밭|화초|모종"));
        CANDIDATES.put("행정·서류 대행", Pattern.compile("주민센터|민원|신청서|등본|서류\\s*떼"));
        CANDIDATES.put("음식 나눔", Pattern.compile("김장|반찬\\s*만들|도시락|요리"));
    }

    static final Map<String, String> SHORT_REASON = Map.of(
            "동행", "책임 등급(1.5배)이라 부담을 느끼는 봉사자가 많고, 평일 낮 병원 시간에 움직일 수 있는 사람이 적을 수 있어요",
            "가벼운 집안일", "몸을 쓰는 일이라 5060 봉사자가 망설이고, 주말 오전에 몰릴 수 있어요",
            "장보기·심부름", "무거운 짐과 이동이 필요해 차가 없는 봉사자가 꺼릴 수 있어요",
            "생활 정리", "한 번에 끝나지 않고 오래 걸릴 수 있어 신청이 적을 수 있어요",
            "말동무·정서", "꾸준히 같은 분을 만나야 해서 시간을 정해 두기 어려울 수 있어요",
            "디지털 도움", "설명할 자신이 없다고 느끼는 5060 봉사자가 많을 수 있어요");

    public static Map<String, Object> insightRule(List<Map<String, Object>> sd, List<String> unmatched) {
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (Map<String, Object> s : sd) {
            int gap = (Integer) s.get("gap");
            if (gap <= 0) continue;
            String c = (String) s.get("category");
            shortages.add(Views.map("category", c, "demand", s.get("demand"), "supply", s.get("supply"), "gap", gap,
                    "reason", "수요가 공급보다 " + gap + "건 많아요. 짐작: " + SHORT_REASON.getOrDefault(c, "봉사자 모집이 더 필요해요")));
            if (shortages.size() >= 3) break;
        }
        List<Map<String, Object>> cands = new ArrayList<>();
        CANDIDATES.forEach((name, p) -> {
            List<String> ex = unmatched.stream().filter(t -> p.matcher(t).find()).limit(3).toList();
            if (!ex.isEmpty()) cands.add(Views.map("name", name, "reason", "6개 카테고리에 맞지 않는 요청 " + ex.size() + "건이 이 주제예요", "examples", ex));
        });
        String summary = shortages.isEmpty() ? "지금은 모든 카테고리에서 공급이 수요를 따라가고 있어요."
                : shortages.get(0).get("category") + " 봉사자 모집이 가장 급해요(" + shortages.get(0).get("gap") + "건 부족). "
                + (cands.isEmpty() ? "" : "새 카테고리 후보 '" + cands.get(0).get("name") + "'도 검토해 보세요.");
        return Views.map("shortages", shortages, "newCategories", cands, "summary", summary.trim());
    }

    /** refresh=false 면 1시간 동안 같은 결과를 돌려준다. */
    public synchronized Map<String, Object> insights(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && cached != null && now - cachedAt < CACHE_MS) {
            Map<String, Object> m = new LinkedHashMap<>(cached);
            m.put("cached", true);
            return m;
        }
        Map<String, Object> a = analytics();
        @SuppressWarnings("unchecked") List<Map<String, Object>> sd = (List<Map<String, Object>>) a.get("supplyDemand");
        List<String> unmatched = unmatchedTexts();
        Map<String, Object> stats = Views.map("supplyDemand", sd, "seasons", a.get("seasons"),
                "byRecipientAge", a.get("byRecipientAge"), "byVolunteerAge", a.get("byVolunteerAge"));
        Map<String, Object> res = new LinkedHashMap<>(insight.adminInsights(stats, unmatched, insightRule(sd, unmatched)));
        res.put("unmatched", unmatched.stream().limit(10).toList());
        res.put("unmatchedCount", unmatched.size());
        res.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        cached = res;
        cachedAt = now;
        Map<String, Object> m = new LinkedHashMap<>(res);
        m.put("cached", false);
        return m;
    }
}
