package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.ForecastAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.FinanceModel.Adj;
import com.timesavings.service.FinanceModel.Inputs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 기능 A-05 시간 은행 재정 추계 · 기능 A-06 정책 시뮬레이션.
 * 지금 회원(나이대·동네·가입일·최근 활동)과 공고·요청 기록에서 모형 입력을 뽑고 {@link FinanceModel} 로 10년을 계산한다.
 * 기록이 적은 값은 기본 가정값을 쓰고 "가정"으로 표시한다. 월간 요약은 ai_results(kind=FIN_MONTHLY, ref_key=연-월)에 저장한다.
 */
@Service
@Transactional
public class FinanceForecastService {
    public static final String KIND = "FIN_MONTHLY";
    private final UserRepo users;
    private final ElderRepo elders;
    private final PostRepo posts;
    private final RequestRepo requests;
    private final AiResultRepo results;
    private final ForecastAi ai;

    public FinanceForecastService(UserRepo users, ElderRepo elders, PostRepo posts, RequestRepo requests, AiResultRepo results, ForecastAi ai) {
        this.users = users; this.elders = elders; this.posts = posts; this.requests = requests; this.results = results; this.ai = ai;
    }

    private static boolean accepted(Request r) {
        return r.status == ReqStatus.VOL_ACCEPTED || r.status.active() || r.status == ReqStatus.DONE || r.status == ReqStatus.NOSHOW;
    }

    private static double postHours(Post p) {
        if (p.estHours != null && p.estHours > 0) return p.estHours;
        return Math.max(0.5, p.endHour - p.startHour);
    }

    /** 지금 DB 에서 모형 입력을 만든다. */
    public Inputs inputs() {
        LocalDateTime now = LocalDateTime.now(), since = now.minusMonths(FinanceModel.WINDOW_MONTHS), recent = now.minusDays(90);
        List<User> members = users.findAll().stream().filter(u -> u.role == Role.USER && u.area != null && !u.area.isBlank()).toList();
        List<User> active = members.stream().filter(u -> u.status != UserStatus.WITHDRAWN && !Boolean.TRUE.equals(u.banned)).toList();
        List<Elder> eld = elders.findAll().stream().filter(e -> e.status == ElderStatus.ACTIVE && e.dong != null && !e.dong.isBlank()).toList();
        Inputs in = new Inputs();
        TreeSet<String> areaSet = new TreeSet<>();
        active.forEach(u -> areaSet.add(u.area));
        eld.forEach(e -> areaSet.add(e.dong));
        in.areas = new ArrayList<>(areaSet);
        int A = in.areas.size();
        in.users = new double[A][8]; in.elders = new double[A][8]; in.joins = new double[A][8];
        in.baseYear = now.getYear();
        Map<Long, User> userMap = new HashMap<>();
        users.findAll().forEach(u -> userMap.put(u.id, u));
        Map<Long, Elder> elderMap = new HashMap<>();
        elders.findAll().forEach(e -> elderMap.put(e.id, e));
        double[] uBand = new double[8], pBand = new double[8];
        int joined = 0;
        for (User u : active) {
            int b = FinanceModel.band(u.ageGroup), a = in.areas.indexOf(u.area);
            if (b < 0 || a < 0) continue;
            in.users[a][b]++; uBand[b]++; pBand[b]++;
            if (u.createdAt != null && !u.createdAt.isBefore(since)) { in.joins[a][b] += 1.0 / FinanceModel.WINDOW_MONTHS; joined++; }
        }
        for (Elder e : eld) {
            int b = FinanceModel.band(e.ageGroup), a = in.areas.indexOf(e.dong);
            if (b < 0 || a < 0) continue;
            in.elders[a][b]++; pBand[b]++;
        }
        // ① 나이대별 1인당 월 봉사 시간(공급)·도움 받은 시간(수요): 최근 6개월 공고·요청의 시간
        double[] sup = new double[8], dem = new double[8];
        List<Post> allPosts = posts.findAll();
        Map<Long, Post> postMap = new HashMap<>();
        allPosts.forEach(p -> postMap.put(p.id, p));
        Set<Long> activeIds = new HashSet<>();
        for (Post p : allPosts) {
            if (p.createdAt != null && !p.createdAt.isBefore(recent) && p.ownerId != null) activeIds.add(p.ownerId);
            if (p.status == PostStatus.DELETED || p.createdAt == null || p.createdAt.isBefore(since)) continue;
            String age = p.elderId != null ? Optional.ofNullable(elderMap.get(p.elderId)).map(e -> e.ageGroup).orElse(null)
                    : Optional.ofNullable(userMap.get(p.ownerId)).map(u -> u.ageGroup).orElse(null);
            int b = FinanceModel.band(age);
            if (b < 0) continue;
            if (p.type == PostType.NEED) dem[b] += postHours(p); else sup[b] += postHours(p);
        }
        for (Request r : requests.findAll()) {
            if (r.createdAt == null) continue;
            if (!r.createdAt.isBefore(recent)) { activeIds.add(r.volunteerId); if (r.recipientId != null) activeIds.add(r.recipientId); }
            if (r.createdAt.isBefore(since)) continue;
            Post p = r.postId == null ? null : postMap.get(r.postId);
            if (r.kind != Kind.PROPOSAL && (p == null || p.type == PostType.OFFER)) {
                String age = r.elderId != null ? Optional.ofNullable(elderMap.get(r.elderId)).map(e -> e.ageGroup).orElse(null)
                        : Optional.ofNullable(userMap.get(r.recipientId)).map(u -> u.ageGroup).orElse(null);
                int b = FinanceModel.band(age);
                if (b >= 0) dem[b] += r.hours;
            }
            if (accepted(r) && (p == null || p.type == PostType.NEED)) {
                int b = FinanceModel.band(Optional.ofNullable(userMap.get(r.volunteerId)).map(u -> u.ageGroup).orElse(null));
                if (b >= 0) sup[b] += r.hours;
            }
        }
        for (int b = 0; b < 8; b++) {
            in.supplySample[b] = (int) uBand[b];
            in.demandSample[b] = (int) pBand[b];
            if (uBand[b] >= FinanceModel.MIN_SAMPLE) { in.supplyPer[b] = Math.round(sup[b] / (uBand[b] * FinanceModel.WINDOW_MONTHS) * 100) / 100.0; in.supplyReal[b] = true; }
            if (pBand[b] >= FinanceModel.MIN_SAMPLE) { in.demandPer[b] = Math.round(dem[b] / (pBand[b] * FinanceModel.WINDOW_MONTHS) * 100) / 100.0; in.demandReal[b] = true; }
        }
        // ② 월 가입 수: 최근 6개월 가입 기록이 3명 이상이면 기록, 아니면 가정(회원의 2%)
        in.joinsReal = joined >= FinanceModel.MIN_SAMPLE;
        if (!in.joinsReal) for (int a = 0; a < A; a++) for (int b = 0; b < 8; b++) in.joins[a][b] = in.users[a][b] * FinanceModel.DEFAULT_JOIN_RATE_MONTH;
        // ② 이탈률: 가입 90일이 넘은 회원 가운데 탈퇴했거나 최근 90일 활동(공고·요청)이 없는 비율을 한 해 이탈률로 본다
        List<User> old = members.stream().filter(u -> u.createdAt != null && u.createdAt.isBefore(recent)).toList();
        in.churnSample = old.size();
        if (old.size() >= FinanceModel.MIN_CHURN_SAMPLE) {
            long gone = old.stream().filter(u -> u.status == UserStatus.WITHDRAWN || Boolean.TRUE.equals(u.banned) || !activeIds.contains(u.id)).count();
            in.churnYear = Math.min(0.5, Math.round(gone * 100.0 / old.size()) / 100.0);
            in.churnReal = true;
        }
        return in;
    }

    private static String src(boolean real) { return real ? "기록" : "가정"; }

    /** 화면에 보여 줄 가정·기록 구분 표. */
    public static Map<String, Object> assumptions(Inputs in) {
        List<Map<String, Object>> bands = new ArrayList<>();
        for (int b = 0; b < 8; b++) {
            double u = 0, e = 0;
            for (int a = 0; a < in.areas.size(); a++) { u += in.users[a][b]; e += in.elders[a][b]; }
            bands.add(Views.map("band", FinanceModel.BANDS.get(b), "users", (int) u, "elders", (int) e,
                    "supplyPer", in.supplyPer[b], "supplySource", src(in.supplyReal[b]), "demandPer", in.demandPer[b], "demandSource", src(in.demandReal[b])));
        }
        double joins = 0;
        for (double[] row : in.joins) for (double v : row) joins += v;
        return Views.map("bands", bands,
                "joinsPerMonth", Math.round(joins * 10) / 10.0, "joinsSource", src(in.joinsReal),
                "churnYear", Math.round(in.churnYear * 100), "churnSource", src(in.churnReal), "churnSample", in.churnSample,
                "windowMonths", FinanceModel.WINDOW_MONTHS, "minSample", FinanceModel.MIN_SAMPLE,
                "notes", List.of(
                        "해마다 각 나이대의 10%가 다음 나이대로 올라가요(단순 코호트 모형).",
                        "공급 = 회원 수 × 1인당 월 봉사 시간 × 12, 수요 = (회원 + 관리 대상) × 1인당 월 도움 받는 시간 × 12.",
                        "한 나이대에 " + FinanceModel.MIN_SAMPLE + "명이 안 되면 그 나이대의 시간은 기본 가정값을 써요.",
                        "관리 대상(어르신)은 도움만 받고, 수는 그대로 두고 나이만 올려요.",
                        "이탈률은 가입 90일이 넘은 회원 가운데 탈퇴했거나 최근 90일 활동이 없는 비율이에요(" + FinanceModel.MIN_CHURN_SAMPLE + "명이 안 되면 가정 15%)."));
    }

    /** A-05: 지금 추세 그대로의 10년 추계. */
    @Transactional(readOnly = true)
    public Map<String, Object> forecast() {
        Inputs in = inputs();
        Map<String, Object> out = FinanceModel.project(in, List.of());
        out.put("assumptions", assumptions(in));
        out.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        return out;
    }

    /** A-06: text(자연어) 또는 adjustments(직접 넣은 값)로 다시 계산해 전후를 돌려준다. */
    @Transactional(readOnly = true)
    public Map<String, Object> simulate(String text, List<Adj> manual) {
        Inputs in = inputs();
        List<Adj> adjs = new ArrayList<>();
        String source = "manual";
        Object provider = null;
        if (manual != null && !manual.isEmpty()) {
            for (Adj a : manual) {
                Adj v = ForecastAi.valid(a.type(), a.ageBand(), a.area(), a.value(), in.areas);
                if (v != null) adjs.add(v);
            }
        } else {
            Map<String, Object> p = ai.parsePolicy(text, in.areas);
            @SuppressWarnings("unchecked") List<Adj> l = (List<Adj>) p.get("adjustments");
            adjs.addAll(l);
            source = (String) p.get("source");
            provider = p.get("provider");
        }
        Map<String, Object> before = FinanceModel.project(in, List.of());
        Map<String, Object> out = Views.map("text", text, "adjustments", adjs.stream().map(FinanceForecastService::adjView).toList(),
                "understood", !adjs.isEmpty(), "source", source, "provider", provider, "areas", in.areas, "bands", FinanceModel.BANDS,
                "before", before);
        if (!adjs.isEmpty()) out.put("after", FinanceModel.project(in, adjs));
        out.put("assumptions", assumptions(in));
        return out;
    }

    static Map<String, Object> adjView(Adj a) {
        String who = (a.area() == null ? "전체 동네" : a.area()) + " · " + (a.ageBand() == null ? "모든 나이대" : a.ageBand());
        String what = switch (a.type()) {
            case "JOIN_ADD" -> "한 달 가입 " + sign(a.value()) + "명";
            case "CHURN_MULT" -> "이탈률 × " + a.value();
            case "SUPPLY_ADD" -> "1인당 월 봉사 시간 " + sign(a.value()) + "시간";
            default -> "1인당 월 도움 받는 시간 " + sign(a.value()) + "시간";
        };
        return Views.map("type", a.type(), "ageBand", a.ageBand(), "area", a.area(), "value", a.value(), "label", who + " · " + what);
    }

    private static String sign(double v) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return v > 0 ? "+" + s : s;
    }

    // ---------------- 월간 요약(저장) ----------------
    private Map<String, Object> view(AiResult r) {
        Map<String, Object> m = Views.parse(r.payload, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>());
        m.put("month", r.refKey);
        m.put("savedAt", r.createdAt == null ? null : r.createdAt.withNano(0).toString());
        return m;
    }

    /** 이번 달 요약. 저장된 것이 있으면 그대로, 없거나 refresh 면 지금 추계로 다시 만들어 저장한다. */
    public Map<String, Object> monthly(boolean refresh) {
        String month = YearMonth.now().toString();
        Optional<AiResult> saved = results.findByKindAndRefKey(KIND, month);
        if (saved.isPresent() && !refresh) return view(saved.get());
        Map<String, Object> f = forecast();
        @SuppressWarnings("unchecked") List<Map<String, Object>> areas = (List<Map<String, Object>>) f.get("areas");
        List<Map<String, Object>> facts = new ArrayList<>(), ruleAreas = new ArrayList<>();
        for (Map<String, Object> a : areas) {
            if ("안정".equals(a.get("risk"))) continue;
            @SuppressWarnings("unchecked") List<String> reasons = (List<String>) a.get("reasons");
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
            facts.add(Views.map("area", a.get("area"), "risk", a.get("risk"), "reasons", reasons));
            ruleAreas.add(Views.map("area", a.get("area"), "risk", a.get("risk"), "reasons", reasons, "text", String.join(". ", reasons) + ".",
                    "ratioNow", rows.get(0).get("ratio"), "ratioEnd", rows.get(rows.size() - 1).get("ratio"), "endYear", rows.get(rows.size() - 1).get("year")));
        }
        long danger = ruleAreas.stream().filter(a -> "위험".equals(a.get("risk"))).count();
        String headline = ruleAreas.isEmpty() ? "지금 추세로는 10년 안에 공급이 수요에 못 미치는 동네가 없어요."
                : "10년 안에 공급이 수요에 못 미칠 것으로 보이는 동네가 " + ruleAreas.size() + "곳이에요" + (danger > 0 ? "(위험 " + danger + "곳)." : ".")
                + " 가장 먼저 살필 곳은 " + ruleAreas.get(0).get("area") + "이에요.";
        Map<String, Object> payload = ai.riskSummary(facts, Views.map("headline", headline, "areas", ruleAreas));
        payload.put("total", f.get("total"));
        AiResult r = saved.orElseGet(AiResult::new);
        r.kind = KIND; r.refKey = month; r.payload = Views.json(payload);
        r.source = (String) payload.get("source"); r.provider = (String) payload.get("provider"); r.createdAt = LocalDateTime.now();
        results.save(r);
        return view(r);
    }

    /** 저장해 둔 월간 요약 목록(최근 달 먼저). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summaries() {
        return results.findByKind(KIND).stream().sorted(Comparator.comparing((AiResult r) -> r.refKey).reversed()).map(this::view).toList();
    }
}
