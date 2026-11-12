package com.timesavings.service;

import com.timesavings.ai.BundleAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 기능 U-12 빈 시간 묶음 추천. 봉사하는 사용자가 날짜와 가능한 시간 범위를 넣으면,
 * 그 시간 안에서 시간이 겹치지 않고 이동 거리가 짧은 도움 요청 공고 2~3건을 묶어 추천한다.
 * - 묶음 고르기(시간 겹침 없음, 사이 이동 시간 여유, 동 사이 거리)는 모두 이 코드가 한다. LLM 은 묶음마다 설명 문장만 쓴다(BundleAi).
 * - 거리는 공고의 동 이름으로 Geo 의 동별 좌표 사이를 잰다(좌표는 저장하지 않는다).
 * - 추천만 한다. 제안을 보내는 것은 사용자가 "한 번에 제안"을 눌렀을 때이고, 수락은 상대가 한다.
 */
@Service
@Transactional(readOnly = true)
public class BundleService {
    /** 걷는 속도(km/h)와 이동 앞뒤 여유(분) */
    static final double WALK_KMH = 4.0;
    static final int BUFFER_MIN = 10;
    /** 두 건 사이 최소 간격(분) */
    static final int MIN_GAP_MIN = 30;
    /** 한 묶음에 넣는 건수, 돌려주는 묶음 수, 살펴보는 후보 수 */
    static final int MIN_ITEMS = 2, MAX_ITEMS = 3, MAX_BUNDLES = 3, MAX_CANDIDATES = 12;

    private final PostRepo posts;
    private final RequestRepo requests;
    private final ElderBlockRepo blocks;
    private final PostService postSvc;
    private final Views views;
    private final BundleAi ai;

    public BundleService(PostRepo posts, RequestRepo requests, ElderBlockRepo blocks, PostService postSvc, Views views, BundleAi ai) {
        this.posts = posts; this.requests = requests; this.blocks = blocks; this.postSvc = postSvc; this.views = views; this.ai = ai;
    }

    /** 묶음 안의 한 건: 공고, 추천 시작 시각, 활동 시간, 앞 건(또는 출발지)에서 오는 거리·이동 시간·기다리는 시간 */
    public record Stop(Post post, double start, double hours, double travelKm, int travelMin, int waitMin) {
        public double end() { return start + hours; }
    }

    /** totalKm: 건 사이 이동 거리 합. startKm: 출발 동에서 첫 건까지 거리(순위에만 쓴다). */
    public record Bundle(List<Stop> stops, double totalKm, double startKm, int idleMin, double totalHours, double totalEarn) {
        Set<Long> ids() { Set<Long> s = new TreeSet<>(); stops.forEach(x -> s.add(x.post().id)); return s; }
        /** 낮을수록 좋다: 이동 거리(km) + 빈 시간(시간) × 0.5, 3건 묶음은 조금 우대 */
        double score() { return totalKm + startKm * 0.5 + idleMin / 60.0 * 0.5 - (stops.size() - MIN_ITEMS) * 0.5; }
    }

    // ---------------- 거리·이동 시간 (코드 계산) ----------------
    /** 두 동 중심 사이 거리(km, 0.1 단위). 표에 없는 동이면 같은 동 0km, 다른 동 2.5km 로 본다. */
    public static double kmBetween(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.trim().equals(b.trim())) return 0;
        double[] x = Geo.DONG.get(a.trim()), y = Geo.DONG.get(b.trim());
        if (x == null || y == null) return 2.5;
        return Math.round(Geo.km(x[0], x[1], y[0], y[1]) * 10) / 10.0;
    }

    /** 걸어서 가는 시간(분, 올림) */
    public static int travelMinutes(double km) { return (int) Math.ceil(km / WALK_KMH * 60); }

    /** 두 건 사이에 꼭 비워 둘 시간(시간 단위, 30분 단위로 올림): 이동 시간 + 여유 10분, 최소 30분 */
    public static double gapHours(double km) {
        int need = Math.max(MIN_GAP_MIN, travelMinutes(km) + BUFFER_MIN);
        return Math.ceil(need / 30.0) * 0.5;
    }

    private static double ceilHalf(double h) { return Math.ceil(h * 2 - 1e-9) / 2.0; }

    // ---------------- 후보 ----------------
    private record Cand(Post post, double winStart, double winEnd, double hours) {}

    private List<double[]> busyOf(User u, LocalDate date) {
        List<double[]> busy = new ArrayList<>();
        for (Request r : requests.findAll())
            if (u.id.equals(r.volunteerId) && date.equals(r.date) && (r.status.pending() || r.status.active()))
                busy.add(new double[]{r.startHour, r.startHour + r.hours});
        return busy;
    }

    private static boolean clashes(List<double[]> busy, double s, double e) {
        double pad = MIN_GAP_MIN / 60.0;
        for (double[] b : busy) if (s < b[1] + pad - 1e-9 && e > b[0] - pad + 1e-9) return true;
        return false;
    }

    private List<Cand> candidates(User u, LocalDate date, double from, double to, List<double[]> busy) {
        List<Cand> out = new ArrayList<>();
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (p.type != PostType.NEED || !date.equals(p.date) || p.estHours == null) continue;
            if (u.id.equals(p.ownerId) || u.id.equals(p.createdBy)) continue; // 내 공고·내가 대신 올린 공고
            if (p.elderId != null && blocks.existsByElderIdAndVolunteerId(p.elderId, u.id)) continue; // 받지 않기로 한 대상
            if (requests.findByPostId(p.id).stream().anyMatch(r -> u.id.equals(r.senderId) && r.status.pending())) continue; // 이미 제안함
            double ws = ceilHalf(Math.max(p.startHour, from)), we = Math.min(p.endHour, to);
            if (ws + p.estHours > we + 1e-9) continue; // 가능한 시간 안에 들어가지 않음
            out.add(new Cand(p, ws, we, p.estHours));
        }
        return out;
    }

    // ---------------- 묶음 만들기 ----------------
    /**
     * 날짜·가능한 시간 범위(from~to)·출발 동(없어도 됨)으로 묶음을 고른다. 후보 2건 이상이어야 묶음이 나온다.
     * 순서를 바꿔 가며 "앞 건이 끝나고 이동·여유 시간을 둔 뒤 다음 건을 시작"할 수 있는 조합만 남기고 점수순으로 최대 3개.
     */
    public List<Bundle> pick(User u, LocalDate date, double from, double to, String startDong) {
        List<double[]> busy = busyOf(u, date);
        List<Cand> cands = new ArrayList<>(candidates(u, date, from, to, busy));
        // 출발 동에서 가까운 순으로 후보를 줄인다(조합 수 제한)
        cands.sort(Comparator.<Cand>comparingDouble(c -> startDong == null ? 0 : kmBetween(startDong, c.post().area))
                .thenComparingDouble(Cand::winStart).thenComparing(c -> c.post().id));
        if (cands.size() > MAX_CANDIDATES) cands = cands.subList(0, MAX_CANDIDATES);
        Map<Set<Long>, Bundle> best = new LinkedHashMap<>();
        permute(cands, new ArrayList<>(), new boolean[cands.size()], startDong, busy, best);
        List<Bundle> all = new ArrayList<>(best.values());
        all.sort(Comparator.comparingDouble(Bundle::score).thenComparing(b -> b.stops().get(0).start()).thenComparing(b -> b.ids().toString()));
        // 서로 다른 선택지가 되게: 이미 고른 묶음과 2건 이상 겹치는 것은 뒤로 미룬다
        List<Bundle> out = new ArrayList<>(), later = new ArrayList<>();
        for (Bundle b : all) {
            boolean similar = out.stream().anyMatch(o -> { Set<Long> x = new HashSet<>(o.ids()); x.retainAll(b.ids()); return x.size() >= 2; });
            (similar ? later : out).add(b);
            if (out.size() >= MAX_BUNDLES) break;
        }
        for (Bundle b : later) { if (out.size() >= MAX_BUNDLES) break; out.add(b); }
        return out;
    }

    private void permute(List<Cand> cands, List<Cand> cur, boolean[] used, String startDong, List<double[]> busy, Map<Set<Long>, Bundle> best) {
        if (cur.size() >= MIN_ITEMS) {
            Bundle b = schedule(cur, startDong, busy);
            if (b != null) best.merge(b.ids(), b, (x, y) -> y.score() < x.score() - 1e-9 ? y : x);
            else return; // 이 순서로 안 되면 뒤에 더 붙여도 안 된다
        }
        if (cur.size() >= MAX_ITEMS) return;
        for (int i = 0; i < cands.size(); i++) {
            if (used[i]) continue;
            used[i] = true; cur.add(cands.get(i));
            permute(cands, cur, used, startDong, busy, best);
            cur.remove(cur.size() - 1); used[i] = false;
        }
    }

    /** 이 순서대로 가장 이른 시각에 차례로 넣어 본다. 들어가지 않으면 null. */
    private Bundle schedule(List<Cand> order, String startDong, List<double[]> busy) {
        List<Stop> stops = new ArrayList<>();
        double prevEnd = -1, km = 0, startKm = 0, hours = 0, earn = 0;
        int idle = 0;
        String prevDong = startDong;
        for (Cand c : order) {
            double d = prevDong == null ? 0 : kmBetween(prevDong, c.post().area);
            double start = c.winStart();
            if (prevEnd >= 0) start = Math.max(start, ceilHalf(prevEnd + gapHours(d)));
            // 내 다른 일정과 겹치면 30분씩 뒤로 미뤄 본다
            while (start + c.hours() <= c.winEnd() + 1e-9 && clashes(busy, start, start + c.hours())) start += 0.5;
            if (start + c.hours() > c.winEnd() + 1e-9) return null;
            int travel = travelMinutes(d);
            int wait = prevEnd < 0 ? 0 : (int) Math.round((start - prevEnd) * 60);
            stops.add(new Stop(c.post(), start, c.hours(), d, travel, wait));
            if (prevEnd >= 0) { idle += wait; km += d; } else startKm = d;
            hours += c.hours();
            double w = c.post().weight != null ? c.post().weight : Rules.weight(c.post().difficulty != null ? c.post().difficulty : Rules.maxLevel(c.post().categories));
            earn += Rules.earn(c.hours(), w);
            prevEnd = start + c.hours();
            prevDong = c.post().area;
        }
        return new Bundle(stops, Math.round(km * 10) / 10.0, startKm, idle, hours, earn);
    }

    // ---------------- 화면용 ----------------
    private static String hhmm(double h) {
        int H = (int) Math.floor(h);
        return H + ":" + (h - H >= 0.5 ? "30" : "00");
    }

    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    /** 코드가 계산한 사실 문장(LLM 에 주는 근거이자 화면의 근거 목록) */
    static List<String> facts(Bundle b) {
        List<String> f = new ArrayList<>();
        StringBuilder t = new StringBuilder("시간표: ");
        for (int i = 0; i < b.stops().size(); i++) {
            Stop s = b.stops().get(i);
            if (i > 0) t.append(" → ");
            t.append(s.post().area).append(" ").append(hhmm(s.start())).append("~").append(hhmm(s.end())).append(" ").append(String.join("·", s.post().categories));
        }
        f.add(t.toString());
        f.add("시간이 겹치지 않아요. 건 사이 빈 시간은 모두 " + b.idleMin() + "분이에요");
        long dongs = b.stops().stream().map(s -> s.post().area).distinct().count();
        f.add(dongs == 1 ? "모두 " + b.stops().get(0).post().area + " 안이라 건 사이 이동이 거의 없어요"
                : "건 사이 이동 거리는 모두 " + num(b.totalKm()) + "km(걸어서 약 " + travelMinutes(b.totalKm()) + "분)예요");
        f.add("활동 시간 합계 " + num(b.totalHours()) + "시간, 예상 적립 합계 " + num(b.totalEarn()) + "시간");
        return f;
    }

    /** 키가 없을 때 쓰는 규칙 문장 */
    static String ruleReason(Bundle b) {
        Stop a = b.stops().get(0), z = b.stops().get(b.stops().size() - 1);
        long dongs = b.stops().stream().map(s -> s.post().area).distinct().count();
        String move = dongs == 1 ? "모두 " + a.post().area + " 안이라 이동이 거의 없고" : "이동은 모두 " + num(b.totalKm()) + "km(걸어서 약 " + travelMinutes(b.totalKm()) + "분)이고";
        return hhmm(a.start()) + "부터 " + hhmm(z.end()) + "까지 " + b.stops().size() + "건이 겹치지 않고 이어져요. " + move
                + " 건 사이에 " + b.stops().stream().skip(1).mapToInt(Stop::waitMin).min().orElse(0) + "분 이상 여유가 있어요.";
    }

    /**
     * 추천 응답. startDong: 출발 동(프로필 동네 또는 화면이 보낸 좌표에서 가장 가까운 동). 좌표는 여기서만 쓰고 저장하지 않는다.
     */
    public Map<String, Object> recommend(User u, LocalDate date, Double fromHour, Double toHour, Double lat, Double lng) {
        if (u.role != Role.USER) throw ApiException.forbidden("봉사하는 사용자만 쓸 수 있어요");
        if (date == null || date.isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
        if (fromHour == null || toHour == null || !Rules.halfStep(fromHour) || !Rules.halfStep(toHour) || fromHour < 0 || toHour > 24)
            throw ApiException.bad("INVALID_TIME", "가능한 시간을 30분 단위로 정해 주세요");
        if (toHour - fromHour < 1.5) throw ApiException.bad("RANGE_TOO_SHORT", "두 건 이상 이어서 하려면 가능한 시간을 1시간 30분 이상으로 잡아 주세요");
        if ((lat != null || lng != null) && !Geo.valid(lat, lng)) throw ApiException.bad("INVALID_LOCATION", "위치 값이 맞지 않아요");
        String startDong = u.area;
        String startBy = "profile";
        if (Geo.valid(lat, lng)) {
            String near = null; double bestKm = Double.MAX_VALUE;
            for (var e : Geo.DONG.entrySet()) {
                double k = Geo.km(lat, lng, e.getValue()[0], e.getValue()[1]);
                if (k < bestKm) { bestKm = k; near = e.getKey(); }
            }
            if (near != null) { startDong = near; startBy = "location"; }
        }
        List<double[]> busy = busyOf(u, date);
        int candCount = candidates(u, date, fromHour, toHour, busy).size();
        List<Bundle> picked = pick(u, date, fromHour, toHour, startDong);
        List<Map<String, Object>> out = new ArrayList<>();
        boolean anyAi = false;
        for (Bundle b : picked) {
            List<String> facts = facts(b);
            BundleAi.Explained ex = ai.explain(facts, ruleReason(b));
            if ("ai".equals(ex.source())) anyAi = true;
            List<Map<String, Object>> items = new ArrayList<>();
            for (Stop s : b.stops()) {
                Post p = s.post();
                double w = p.weight != null ? p.weight : Rules.weight(p.difficulty != null ? p.difficulty : Rules.maxLevel(p.categories));
                items.add(Views.map("postId", p.id, "post", postSvc.decorate(views.postCard(p, u), p, u), "startHour", s.start(), "endHour", s.end(),
                        "hours", s.hours(), "estEarn", Rules.earn(s.hours(), w), "travelKm", s.travelKm(), "travelMin", s.travelMin(), "waitMin", s.waitMin()));
            }
            out.add(Views.map("key", b.ids().toString(), "items", items, "count", items.size(), "totalKm", b.totalKm(), "travelMin", travelMinutes(b.totalKm()),
                    "idleMin", b.idleMin(), "totalHours", b.totalHours(), "totalEarn", b.totalEarn(),
                    "startHour", b.stops().get(0).start(), "endHour", b.stops().get(b.stops().size() - 1).end(),
                    "reason", ex.text(), "facts", facts, "source", ex.source()));
        }
        String message = !out.isEmpty() ? null
                : candCount == 0 ? "이 시간에 할 수 있는 도움 요청 공고가 아직 없어요. 날짜나 시간을 넓혀 보세요."
                : candCount == 1 ? "이 시간에 맞는 공고가 1건뿐이라 묶을 수 없어요. 공고 목록에서 따로 제안해 보세요."
                : "맞는 공고는 있지만 시간이 겹치거나 이동할 여유가 없어 묶을 수 없어요. 가능한 시간을 넓혀 보세요.";
        return Views.map("date", date.toString(), "fromHour", fromHour, "toHour", toHour, "startDong", startDong, "startBy", startBy,
                "candidateCount", candCount, "bundles", out, "message", message, "source", anyAi ? "ai" : "rule",
                "rule", "건 사이에는 이동 시간(걸어서 시속 " + num(WALK_KMH) + "km)에 " + BUFFER_MIN + "분을 더해 최소 " + MIN_GAP_MIN + "분을 비워요. 묶음은 코드가 고르고 AI는 설명만 써요.");
    }
}
