package com.timesavings.service;

import com.timesavings.ai.ScheduleAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 기능 O-11 돌봄 편성표 · O-12 편성 대안 추천 · O-13 공고 한꺼번에 올리기.
 * <ul>
 *   <li>편성표: 담당 대상(세로) × 요일(가로) 한 주. 칸에는 확정·진행 중 요청, 응답 대기, 열려 있는 공고, 빈 칸이 구분돼 담긴다.</li>
 *   <li>빈 일정(gap): 지난 4주 같은 요일에 {@link #PATTERN_MIN}번 이상 방문이 있었는데 이번에는 아무 일정이 없는 칸. 계산은 모두 코드가 한다.</li>
 *   <li>대안 추천: 후보 찾기(단골·같은 동·그 시간 봉사 가능 공고·받지 않기 제외·시간 겹침 제외)와 순서는 코드, 글만 ScheduleAi. 요청은 보내지 않는다.</li>
 *   <li>일괄 등록: 칸마다 PostService.create 를 따로 불러(칸마다 따로 저장) 실패한 칸은 이유와 함께 돌려준다. 그래서 이 클래스에는 @Transactional 을 붙이지 않는다.</li>
 * </ul>
 */
@Service
public class CareScheduleService {
    public static final int PATTERN_WEEKS = 4;
    public static final int PATTERN_MIN = 2;
    public static final int MAX_CANDIDATES = 5;
    public static final int MAX_BULK = 20;
    public static final String NOTICE = "후보와 순서는 시스템이 찾고, AI는 추천 이유 문장만 다듬어요. 요청은 담당자가 눌러야 보내져요.";
    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private final ElderRepo elders;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final UserRepo users;
    private final ElderBlockRepo blocks;
    private final Views views;
    private final PostService postSvc;
    private final ScheduleAi ai;

    public CareScheduleService(ElderRepo elders, RequestRepo requests, PostRepo posts, UserRepo users, ElderBlockRepo blocks,
                               Views views, PostService postSvc, ScheduleAi ai) {
        this.elders = elders; this.requests = requests; this.posts = posts; this.users = users; this.blocks = blocks;
        this.views = views; this.postSvc = postSvc; this.ai = ai;
    }

    public record SlotCmd(Long elderId, LocalDate date, Double startHour, Double hours, String category) {}
    public record BulkItem(Long elderId, LocalDate date, Double startHour, Double endHour, String category, String detail) {}
    /** 지난 4주 같은 요일의 방문 흐름: 몇 주 방문했는지, 가장 잦은 도움 종류·시작 시각·시간. */
    public record Pattern(int weeks, String category, double startHour, double hours) {}

    public static String dow(LocalDate d) { return DOW[d.getDayOfWeek().getValue() - 1]; }
    public static LocalDate monday(LocalDate d) { return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    private static String hhmm(double h) { int H = (int) Math.floor(h); return H + ":" + (h - H >= 0.5 ? "30" : "00"); }
    private static String hoursText(double h) { return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h)) + "시간"; }

    /** 편성표에 일정으로 올리는 요청인가(거절·취소·만료·노쇼는 일정이 아니다). */
    static boolean onBoard(Request r) { return r.status.pending() || r.status.active() || r.status == ReqStatus.DONE; }

    static String stateOf(Request r) {
        if (r.status == ReqStatus.DONE) return "DONE";
        if (r.status.pending()) return "PENDING";
        return r.status == ReqStatus.CONFIRMED ? "CONFIRMED" : "PROGRESS";
    }

    private Elder mine(User mgr, Long elderId) {
        if (elderId == null) throw ApiException.bad("ELDER_REQUIRED", "어느 대상의 일정인지 골라 주세요");
        Elder e = elders.findById(elderId).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        if (!mgr.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상이 아니에요");
        return e;
    }

    /**
     * 지난 4주 방문 흐름(코드 계산). date 와 같은 요일 가운데 오늘보다 앞선 가장 가까운 4번을 본다.
     * 그날 일정에 오른 요청(완료·확정·진행)이 있던 주가 PATTERN_MIN 번 이상이면 흐름이 있다고 본다.
     */
    static Pattern pattern(List<Request> elderReqs, LocalDate date, LocalDate today) {
        LocalDate d = date.minusDays(7);
        while (!d.isBefore(today)) d = d.minusDays(7);
        int weeks = 0;
        List<Request> hits = new ArrayList<>();
        for (int i = 0; i < PATTERN_WEEKS; i++, d = d.minusDays(7)) {
            LocalDate day = d;
            List<Request> on = elderReqs.stream().filter(r -> r.date.equals(day) && (r.status.active() || r.status == ReqStatus.DONE)).toList();
            if (!on.isEmpty()) { weeks++; hits.addAll(on); }
        }
        if (weeks < PATTERN_MIN) return null;
        String cat = hits.stream().collect(Collectors.groupingBy(r -> r.category, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        Request typical = hits.stream().filter(r -> Objects.equals(r.category, cat)).findFirst().orElse(hits.get(0));
        return new Pattern(weeks, cat, typical.startHour, Math.min(4, Math.max(0.5, typical.hours)));
    }

    // ======================= O-11 편성표 =======================
    public Map<String, Object> week(User mgr, LocalDate any, Long elderId) {
        LocalDate today = LocalDate.now();
        LocalDate mon = monday(any == null ? today : any);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) dates.add(mon.plusDays(i));
        List<Elder> all = elders.findByManagerId(mgr.id).stream().filter(e -> e.status == ElderStatus.ACTIVE)
                .sorted(Comparator.comparing((Elder e) -> e.id)).toList();
        if (elderId != null) mine(mgr, elderId);
        List<Elder> shown = elderId == null ? all : all.stream().filter(e -> e.id.equals(elderId)).toList();

        int nFixed = 0, nPending = 0, nOpen = 0, nGap = 0, nEmpty = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Elder e : shown) {
            List<Request> rs = requests.findByElderId(e.id);
            List<Post> ps = posts.findByElderId(e.id).stream().filter(p -> p.type == PostType.NEED && p.status == PostStatus.OPEN).toList();
            Set<Long> openPostIds = ps.stream().map(p -> p.id).collect(Collectors.toSet());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (LocalDate d : dates) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Request r : rs) {
                    if (!r.date.equals(d) || !onBoard(r)) continue;
                    // 열려 있는 공고에 온 응답 대기 건은 공고 칸의 "제안 n건"으로 센다(같은 일정을 두 번 그리지 않음)
                    if (r.status.pending() && r.postId != null && openPostIds.contains(r.postId)) continue;
                    String st = stateOf(r);
                    if (st.equals("PENDING")) nPending++; else if (!st.equals("DONE")) nFixed++;
                    items.add(Views.map("kind", "REQUEST", "state", st, "requestId", r.id, "status", r.status, "startHour", r.startHour, "hours", r.hours,
                            "category", r.category, "volunteerName", users.findById(r.volunteerId).map(u -> u.name).orElse(null)));
                }
                for (Post p : ps) {
                    if (!p.date.equals(d)) continue;
                    nOpen++;
                    long waiting = rs.stream().filter(r -> p.id.equals(r.postId) && r.status.pending()).count();
                    items.add(Views.map("kind", "POST", "state", "OPEN", "postId", p.id, "startHour", p.startHour, "endHour", p.endHour,
                            "category", p.categories.isEmpty() ? null : p.categories.get(0), "title", p.title, "pendingCount", waiting));
                }
                items.sort(Comparator.comparingDouble(m -> ((Number) m.get("startHour")).doubleValue()));
                boolean past = d.isBefore(today);
                boolean empty = items.isEmpty();
                Map<String, Object> gap = null;
                if (empty && !past) {
                    Pattern pt = pattern(rs, d, today);
                    if (pt != null) {
                        nGap++;
                        gap = Views.map("weeks", pt.weeks(), "category", pt.category(), "startHour", pt.startHour(), "hours", pt.hours(),
                                "reason", "지난 " + PATTERN_WEEKS + "주 중 " + pt.weeks() + "주 " + dow(d) + "요일에 " + pt.category() + " 방문이 있었어요");
                    }
                    nEmpty++;
                }
                cells.add(Views.map("date", d.toString(), "items", items, "empty", empty, "past", past, "selectable", empty && !past, "gap", gap));
            }
            rows.add(Views.map("elderId", e.id, "alias", e.alias, "displayName", e.alias + " 님", "dong", e.dong, "needs", e.needs, "cells", cells));
        }
        List<Map<String, Object>> days = dates.stream().map(d -> Views.map("date", d.toString(), "dow", dow(d),
                "label", d.getMonthValue() + "/" + d.getDayOfMonth(), "today", d.equals(today), "past", d.isBefore(today))).toList();
        return Views.map("weekStart", mon.toString(), "weekEnd", mon.plusDays(6).toString(), "prevWeek", mon.minusDays(7).toString(),
                "nextWeek", mon.plusDays(7).toString(), "today", today.toString(), "thisWeek", mon.equals(monday(today)),
                "days", days, "elders", rows, "elderId", elderId,
                "filter", all.stream().map(e -> Views.map("elderId", e.id, "displayName", e.alias + " 님")).toList(),
                "counts", Views.map("fixed", nFixed, "pending", nPending, "open", nOpen, "gaps", nGap, "empty", nEmpty));
    }

    // ======================= O-12 편성 대안 추천 =======================
    private static boolean overlaps(double s1, double h1, double s2, double h2) { return s1 < s2 + h2 - 1e-9 && s2 < s1 + h1 - 1e-9; }

    /** 그 칸에 넣을 수 있는 후보(코드). 점수 높은 순. */
    List<Map<String, Object>> candidates(Elder e, LocalDate date, double start, double hours, String category, List<Request> allReqs) {
        return candidates(e, date, start, hours, category, allReqs, true);
    }

    /** 봉사 자격(신분증 + 첫 활동 교육 필수 설정) — 생성자를 바꾸지 않으려고 필드 주입 */
    @org.springframework.beans.factory.annotation.Autowired private VolunteerGate gate;

    /**
     * 순서: 단골 → 그 시간에 봉사 가능 공고를 올린 봉사자 → 같은 동 봉사자(neighbors 가 true 일 때). 같은 묶음 안에서는 점수 높은 순.
     * 같은 동 봉사자는 공고도 단골도 아니라 바로 요청할 수 없어 action 이 "POST"(공고를 올려 기다리기)다.
     */
    List<Map<String, Object>> candidates(Elder e, LocalDate date, double start, double hours, String category, List<Request> allReqs, boolean neighbors) {
        Map<Long, Post> offers = new LinkedHashMap<>(); // 그 요일·시간에 열려 있는 봉사 가능 공고(봉사자별 한 건)
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (p.type != PostType.OFFER || p.ownerId == null || !p.date.equals(date)) continue;
            if (category != null && !p.categories.contains(category)) continue;
            if (p.startHour > start + 1e-9 || start + hours > p.endHour + 1e-9) continue;
            offers.putIfAbsent(p.ownerId, p);
        }
        Set<Long> regulars = views.regularsOf(null, e.id).stream().map(m -> (Long) m.get("id")).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> pool = new LinkedHashSet<>(regulars);
        pool.addAll(offers.keySet());
        if (neighbors && e.dong != null) for (User n : users.findAll()) if (e.dong.equals(n.area) && n.role == Role.USER) pool.add(n.id); // 기능 O-12: 같은 동 봉사자
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long vid : pool) {
            User v = users.findById(vid).orElse(null);
            if (v == null || v.status != UserStatus.ACTIVE || v.role != Role.USER || v.idStatus != VerifyStatus.APPROVED) continue;
            if (gate.block(v, false) != null) continue; // 봉사 자격 없음(첫 활동 교육 필수인데 미이수 등)
            if (blocks.existsByElderIdAndVolunteerId(e.id, vid)) continue; // 받지 않기 목록
            boolean busy = allReqs.stream().anyMatch(r -> vid.equals(r.volunteerId) && !r.status.terminal() && r.date.equals(date)
                    && overlaps(r.startHour, r.hours, start, hours));
            if (busy) continue; // 같은 시간에 다른 일정
            long doneWith = allReqs.stream().filter(r -> vid.equals(r.volunteerId) && e.id.equals(r.elderId) && r.status == ReqStatus.DONE).count();
            boolean regular = regulars.contains(vid);
            boolean sameDong = e.dong != null && e.dong.equals(v.area);
            Post offer = offers.get(vid);
            double[] rt = views.rating(vid);
            List<String> facts = new ArrayList<>();
            if (regular) facts.add("단골(이 대상을 " + doneWith + "회 도움)");
            else if (doneWith > 0) facts.add("이 대상을 " + doneWith + "회 도움");
            if (sameDong) facts.add("같은 동(" + e.dong + ")");
            if (offer != null) facts.add("그 시간에 봉사 가능 공고(" + hhmm(offer.startHour) + "~" + hhmm(offer.endHour) + ")");
            else if (regular) facts.add("그 시간 공고는 없지만 단골이라 직접 요청할 수 있어요");
            else facts.add("그 시간 공고는 없어요. 이 칸에 공고를 올리면 볼 수 있어요");
            int tier = regular ? 0 : offer != null ? 1 : 2;
            if (rt[1] > 0) facts.add("평가 " + rt[0]);
            double score = (regular ? 50 : 0) + Math.min(15, doneWith * 3) + (sameDong ? 20 : 0) + (offer != null ? 30 : 0) + rt[0] * 2;
            out.add(Views.map("volunteerId", vid, "name", v.name, "area", v.area, "ratingAvg", rt[0], "ratingCount", (int) rt[1],
                    "regular", regular, "doneCount", doneWith, "sameDong", sameDong, "timeFit", offer != null,
                    "offerPostId", offer == null ? null : offer.id, "offerStartHour", offer == null ? null : offer.startHour,
                    "offerEndHour", offer == null ? null : offer.endHour,
                    "action", offer != null ? "REQUEST" : regular ? "REGULAR" : "POST", "tier", tier, "facts", facts, "score", Math.round(score * 10) / 10.0));
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("tier"))
                .thenComparingDouble(m -> -((Number) m.get("score")).doubleValue())
                .thenComparing(m -> (Long) m.get("volunteerId")));
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }

    /**
     * 빈 칸 하나에 대한 추천. 후보가 없으면 같은 주(앞뒤 6일) 다른 요일과 공고 올리기를 제안한다.
     * 아무것도 보내거나 만들지 않는다(담당자가 눌러서 요청 화면·일괄 등록으로 간다).
     */
    public Map<String, Object> suggest(User mgr, SlotCmd c) {
        Elder e = mine(mgr, c.elderId());
        if (e.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        LocalDate today = LocalDate.now();
        if (c.date() == null || c.date().isBefore(today)) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜의 칸을 골라 주세요");
        List<Request> all = requests.findAll();
        List<Request> mineReqs = all.stream().filter(r -> e.id.equals(r.elderId)).toList();
        Pattern pt = pattern(mineReqs, c.date(), today);
        String cat = c.category() != null && !c.category().isBlank() ? c.category()
                : pt != null ? pt.category() : e.needs == null || e.needs.isEmpty() ? null : e.needs.get(0);
        if (cat == null || !Rules.isCategory(cat)) throw ApiException.bad("INVALID_CATEGORY", "도움 종류(카테고리)를 골라 주세요");
        double start = c.startHour() != null ? c.startHour() : pt != null ? pt.startHour() : 10;
        double hours = c.hours() != null ? c.hours() : pt != null ? pt.hours() : 1;
        Rules.checkHours(start, hours);

        List<Map<String, Object>> cands = candidates(e, c.date(), start, hours, cat, all);
        String slot = c.date().getMonthValue() + "/" + c.date().getDayOfMonth() + "(" + dow(c.date()) + ") " + hhmm(start) + " " + cat + " " + hoursText(hours);

        // 다른 요일 대안: 그날 이 대상에게 겹치는 일정이 없고 후보가 있는 날(가까운 날 먼저, 최대 3일)
        List<Map<String, Object>> alts = new ArrayList<>();
        long direct = cands.stream().filter(m -> !"POST".equals(m.get("action"))).count(); // 바로 요청할 수 있는 후보(단골·그 시간 공고)
        long nearby = cands.size() - direct;                                                // 같은 동이지만 공고를 올려야 하는 후보
        if (direct == 0) {
            List<LocalDate> near = new ArrayList<>();
            for (int k = 1; k <= 6; k++) { near.add(c.date().plusDays(k)); near.add(c.date().minusDays(k)); }
            for (LocalDate d : near) {
                if (d.isBefore(today) || alts.size() >= 3) continue;
                boolean taken = mineReqs.stream().anyMatch(r -> r.date.equals(d) && onBoard(r) && overlaps(r.startHour, r.hours, start, hours));
                if (taken) continue;
                List<Map<String, Object>> cs = candidates(e, d, start, hours, cat, all, false); // 다른 요일은 바로 요청할 수 있는 후보만
                if (cs.isEmpty()) continue;
                alts.add(Views.map("date", d.toString(), "dow", dow(d), "label", d.getMonthValue() + "/" + d.getDayOfMonth(),
                        "count", cs.size(), "candidates", cs));
            }
            alts.sort(Comparator.comparing(m -> (String) m.get("date")));
        }
        Map<String, Object> postDraft = Views.map("elderId", e.id, "date", c.date().toString(), "startHour", start,
                "endHour", Math.min(24, start + hours), "category", cat);

        String rule;
        String nearText = nearby > 0 ? " 같은 동 봉사자 " + nearby + "명은 이 칸에 공고를 올리면 볼 수 있어요." : "";
        if (direct > 0) rule = slot + "에 맞는 봉사자 " + direct + "명을 찾았어요. 요청은 골라서 직접 보내 주세요." + nearText;
        else if (!alts.isEmpty()) rule = slot + "에는 바로 요청할 수 있는 봉사자가 없어요. " + alts.stream().map(a -> a.get("dow") + "요일(" + a.get("label") + ") " + a.get("count") + "명")
                .collect(Collectors.joining(", ")) + "은 가능해요. 요일을 바꾸거나 공고를 올려 보세요." + nearText;
        else rule = slot + "에는 바로 요청할 수 있는 봉사자가 없고 가까운 다른 요일에도 없어요. 공고를 올려 봉사자를 기다려 보세요." + nearText;

        List<List<String>> facts = new ArrayList<>();
        for (Map<String, Object> m : cands) { @SuppressWarnings("unchecked") List<String> f = (List<String>) m.get("facts"); facts.add(f); }
        ScheduleAi.Text t = ai.explain(slot, facts, rule);
        for (int i = 0; i < cands.size(); i++) cands.get(i).put("reason", t.reasons().get(i));
        for (Map<String, Object> a : alts) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> cs = (List<Map<String, Object>>) a.get("candidates");
            for (Map<String, Object> m : cs) { @SuppressWarnings("unchecked") List<String> f = (List<String>) m.get("facts"); m.put("reason", String.join(" · ", f)); }
        }
        Map<String, Object> out = Views.map("elderId", e.id, "displayName", e.alias + " 님", "dong", e.dong,
                "slot", Views.map("date", c.date().toString(), "dow", dow(c.date()), "startHour", start, "hours", hours, "category", cat, "label", slot),
                "pattern", pt == null ? null : Views.map("weeks", pt.weeks(), "category", pt.category(), "startHour", pt.startHour(), "hours", pt.hours()),
                "candidates", cands, "alternatives", alts, "postDraft", postDraft, "message", t.message(),
                "source", t.source(), "notice", NOTICE, "sent", false);
        if (t.provider() != null) out.put("provider", t.provider());
        return out;
    }

    // ======================= O-13 공고 한꺼번에 올리기 =======================
    /** 칸마다 대리 공고 1건. 한 칸이 실패해도 다른 칸은 올라간다. */
    public Map<String, Object> bulkPosts(User mgr, List<BulkItem> items) {
        if (items == null || items.isEmpty()) throw ApiException.bad("ITEMS_REQUIRED", "공고를 올릴 빈 칸을 골라 주세요");
        if (items.size() > MAX_BULK) throw ApiException.bad("TOO_MANY", "한 번에 " + MAX_BULK + "칸까지 올릴 수 있어요");
        List<Map<String, Object>> created = new ArrayList<>(), failed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BulkItem it = items.get(i);
            Map<String, Object> row = Views.map("index", i, "elderId", it.elderId(), "date", it.date() == null ? null : it.date().toString(),
                    "startHour", it.startHour(), "endHour", it.endHour(), "category", it.category());
            try {
                Elder e = mine(mgr, it.elderId());
                row.put("displayName", e.alias + " 님");
                if (it.category() == null || it.category().isBlank()) throw ApiException.bad("INVALID_CATEGORY", "도움 종류(카테고리)를 골라 주세요");
                if (it.date() == null) throw ApiException.bad("INVALID_DATE", "날짜를 골라 주세요");
                if (it.startHour() == null) throw ApiException.bad("INVALID_TIME", "시작 시각을 정해 주세요");
                double end = it.endHour() != null ? it.endHour() : Math.min(24, it.startHour() + 1);
                // 같은 대상·같은 시간에 이미 일정이나 열린 공고가 있으면 겹쳐 올리지 않는다
                boolean taken = requests.findByElderId(e.id).stream().anyMatch(r -> r.date.equals(it.date()) && onBoard(r)
                        && overlaps(r.startHour, r.hours, it.startHour(), end - it.startHour()))
                        || posts.findByElderId(e.id).stream().anyMatch(p -> p.type == PostType.NEED && p.status == PostStatus.OPEN && p.date.equals(it.date())
                        && overlaps(p.startHour, p.endHour - p.startHour, it.startHour(), end - it.startHour()));
                if (taken) throw ApiException.conflict("SLOT_TAKEN", "그 시간에 이미 일정이나 열려 있는 공고가 있어요");
                String detail = it.detail() == null || it.detail().isBlank() ? null : it.detail().trim();
                // 금지 요청 판정·시간·카테고리 검사는 공고 등록과 같은 길(PostService.create)을 지난다
                Post p = postSvc.create(mgr, new PostService.PostCmd(PostType.NEED, it.date(), it.startHour(), end, e.dong,
                        List.of(it.category()), null, detail, null, null, false, 1, e.id, null, null)).get(0);
                row.put("postId", p.id);
                row.put("title", p.title);
                created.add(row);
            } catch (ApiException ex) {
                row.put("code", ex.code);
                row.put("reason", ex.getMessage());
                failed.add(row);
            }
        }
        return Views.map("created", created, "failed", failed, "createdCount", created.size(), "failedCount", failed.size());
    }
}
