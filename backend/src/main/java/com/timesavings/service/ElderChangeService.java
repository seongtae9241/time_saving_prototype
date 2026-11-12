package com.timesavings.service;

import com.timesavings.ai.ElderChangeAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C-16 대상 변화 리포트(오늘·최근).
 * 담당 대상마다 최근 30일 활동 일지를 그 앞 30일과 비교해 특이사항·변화를 정리하고, 먼저 살펴볼 순서를 알려 준다.
 * <ul>
 *   <li>횟수·날짜·점수·순위는 모두 코드가 계산한다(score 규칙은 {@link #score}).</li>
 *   <li>AI(ElderChangeAi)는 일지 내용만 정리한다. 키가 없으면 규칙 문장. 진단·건강 판단은 하지 않는다.</li>
 *   <li>AI 호출은 점수 높은 대상 AI_TOP 명까지만, 일지가 바뀌지 않으면 메모리 캐시를 쓴다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ElderChangeService {
    public static final int WINDOW_DAYS = 30;
    public static final int QUIET_DAYS = 21;
    public static final int AI_TOP = 6;
    public static final int LOG_LINES = 6;
    public static final String NOTICE = "신청·활동 횟수와 안부 확인 순서는 시스템이 계산하고, AI는 봉사자 일지 내용만 정리해요. 진단·건강 판단이 아니에요.";

    private final ElderRepo elders;
    private final RequestRepo requests;
    private final LifeReportRepo reports;
    private final CareSignalService careSignals;
    private final ElderChangeAi ai;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(String finger, Map<String, Object> text) {}

    public ElderChangeService(ElderRepo elders, RequestRepo requests, LifeReportRepo reports, CareSignalService careSignals, ElderChangeAi ai) {
        this.elders = elders; this.requests = requests; this.reports = reports; this.careSignals = careSignals; this.ai = ai;
    }

    /** 코드가 센 숫자 묶음. */
    public record Stats(int recentCount, int prevCount, int todayCount, Integer daysSinceLast, LocalDate lastDate, int signalCount, int prevSignalCount,
                        List<String> signals, List<String> newSignals, List<String> newCategories, boolean flagged, boolean monthlyBig, boolean everActive) {}

    public record Scored(int score, String level, List<String> reasons) {}

    private static boolean reported(Request r) {
        return (r.status == ReqStatus.DONE || r.status == ReqStatus.LOG_SUBMITTED) && r.logSubmittedAt != null;
    }

    /**
     * 살펴볼 순서 점수(코드 규칙). 확인 권장 40 · 이전에 없던 생활 변화 표현 15씩(최대 30) · 표현이 늘어남 10 ·
     * 활동이 2회 이상 줄어듦 20 · 21일 넘게 활동 없음 20 · 아직 활동 없음 10 · 지난달 월간 리포트 변화 큼 10 ·
     * 새 도움 종류 5 · 활동이 2회 이상 늘어남 5 · 오늘 일지 들어옴 5.
     */
    public static Scored score(Stats s) {
        int sc = 0;
        List<String> why = new ArrayList<>();
        if (s.flagged()) { sc += 40; why.add("최근 메모에 생활 변화 표현(" + String.join("·", s.signals()) + ")이 " + s.signalCount() + "번 나와 확인을 권해요"); }
        if (!s.newSignals().isEmpty()) { sc += Math.min(30, 15 * s.newSignals().size()); why.add("이전 기록에 없던 표현: " + String.join("·", s.newSignals())); }
        else if (s.signalCount() > s.prevSignalCount()) { sc += 10; why.add("생활 변화 표현이 " + s.prevSignalCount() + "번 → " + s.signalCount() + "번으로 늘었어요"); }
        if (s.prevCount() - s.recentCount() >= 2) { sc += 20; why.add("활동이 " + s.prevCount() + "회 → " + s.recentCount() + "회로 줄었어요"); }
        else if (s.recentCount() - s.prevCount() >= 2) { sc += 5; why.add("활동이 " + s.prevCount() + "회 → " + s.recentCount() + "회로 늘었어요"); }
        if (!s.everActive()) { sc += 10; why.add("아직 활동 기록이 없어요"); }
        else if (s.daysSinceLast() != null && s.daysSinceLast() >= QUIET_DAYS) { sc += 20; why.add("마지막 활동 뒤 " + s.daysSinceLast() + "일이 지났어요"); }
        if (s.monthlyBig()) { sc += 10; why.add("지난 월간 리포트에서 변화가 컸어요"); }
        if (!s.newCategories().isEmpty()) { sc += 5; why.add("새로 받은 도움: " + String.join("·", s.newCategories())); }
        if (s.todayCount() > 0) { sc += 5; why.add("오늘 일지 " + s.todayCount() + "건이 들어왔어요"); }
        String level = sc >= 40 ? "HIGH" : sc >= 15 ? "MID" : "LOW";
        return new Scored(sc, level, why);
    }

    /**
     * 기능 O-10: 봉사 신청 내역 집계(코드 계산). 최근 WINDOW_DAYS 일 동안 이 대상으로 만들어진 요청·제안 전체를
     * 성사(확정~완료)·응답 대기·거절(봉사자 거절 + 담당자 거부)·만료·취소·노쇼로 나누고, 봉사자를 못 구하고 마감된 공고 수를 센다.
     */
    public record Apply(int total, int matched, int waiting, int rejected, int expired, int cancelled, int noshow, int unmatchedPosts) {
        public static final Apply NONE = new Apply(0, 0, 0, 0, 0, 0, 0, 0);
        /** 맞춰지지 않은 신청(거절·만료·노쇼). 담당자가 스스로 거둔 취소는 넣지 않는다. */
        public int failed() { return rejected + expired + noshow; }
    }

    /**
     * 안부 확인 우선순위 점수 = 일지 기준 점수({@link #score(Stats)}) + 신청 내역 점수.
     * 맞춰지지 않은 신청 2건 이상 15(1건 5) · 노쇼 10 · 봉사자를 못 구하고 마감된 공고 10.
     */
    public static Scored score(Stats s, Apply a) {
        Scored base = score(s);
        if (a == null) return base;
        int sc = base.score();
        List<String> why = new ArrayList<>(base.reasons());
        if (a.failed() >= 2) { sc += 15; why.add("최근 신청 " + a.total() + "건 중 " + a.failed() + "건이 맞춰지지 않았어요(거절 " + a.rejected() + " · 만료 " + a.expired() + " · 노쇼 " + a.noshow() + ")"); }
        else if (a.failed() == 1) { sc += 5; why.add("최근 신청 " + a.total() + "건 중 1건이 맞춰지지 않았어요"); }
        if (a.noshow() > 0) { sc += 10; why.add("약속한 봉사자가 오지 않은 일이 " + a.noshow() + "번 있었어요"); }
        if (a.unmatchedPosts() > 0) { sc += 10; why.add("봉사자를 못 구하고 마감된 공고가 " + a.unmatchedPosts() + "건 있어요"); }
        return new Scored(sc, sc >= 40 ? "HIGH" : sc >= 15 ? "MID" : "LOW", why);
    }

    /** 신청 내역 한 줄(숫자는 Apply 그대로). */
    public static String applyText(Apply a) {
        if (a == null || (a.total() == 0 && a.unmatchedPosts() == 0)) return "최근 " + WINDOW_DAYS + "일 동안 봉사 신청이 없었어요.";
        StringBuilder sb = new StringBuilder("최근 " + WINDOW_DAYS + "일 신청 " + a.total() + "건: 성사 " + a.matched() + " · 응답 대기 " + a.waiting()
                + " · 거절 " + a.rejected() + " · 만료 " + a.expired() + " · 취소 " + a.cancelled());
        if (a.noshow() > 0) sb.append(" · 노쇼 ").append(a.noshow());
        if (a.unmatchedPosts() > 0) sb.append(" / 못 구하고 마감된 공고 ").append(a.unmatchedPosts()).append("건");
        return sb.toString();
    }

    /** 생성자 인자를 늘리지 않으려고 필드 주입(신청 내역의 마감된 공고를 센다) */
    @org.springframework.beans.factory.annotation.Autowired private PostRepo posts;

    private Apply apply(Elder e, List<Request> all, LocalDate today) {
        LocalDate from = today.minusDays(WINDOW_DAYS - 1L);
        List<Request> mine = all.stream().filter(r -> e.id.equals(r.elderId) && r.createdAt != null && !r.createdAt.toLocalDate().isBefore(from)).toList();
        int matched = 0, waiting = 0, rejected = 0, expired = 0, cancelled = 0, noshow = 0;
        for (Request r : mine) {
            switch (r.status) {
                case SENT, VOL_ACCEPTED -> waiting++;
                case REJECTED, DECLINED -> rejected++;
                case EXPIRED -> expired++;
                case CANCELLED -> cancelled++;
                case NOSHOW -> noshow++;
                default -> matched++;
            }
        }
        int unmatched = 0;
        if (posts != null) {
            for (Post p : posts.findByElderId(e.id)) {
                if (p.type != PostType.NEED || p.status != PostStatus.CLOSED || p.date.isBefore(from) || !p.date.isBefore(today)) continue;
                boolean got = all.stream().anyMatch(r -> p.id.equals(r.postId) && (r.status.active() || r.status == ReqStatus.DONE));
                if (!got) unmatched++;
            }
        }
        return new Apply(mine.size(), matched, waiting, rejected, expired, cancelled, noshow, unmatched);
    }

    /** 규칙 문장(키 없을 때·AI 대상이 아닐 때). 숫자는 Stats 그대로. */
    public static String ruleSummary(String label, Stats s) {
        if (!s.everActive()) return label + "은 아직 활동 기록이 없어요.";
        StringBuilder sb = new StringBuilder(label + "은 최근 " + WINDOW_DAYS + "일 동안 활동 " + s.recentCount() + "회(그 앞 " + WINDOW_DAYS + "일은 " + s.prevCount() + "회)예요.");
        if (s.signalCount() > 0) sb.append(" 최근 메모에 '").append(String.join("·", s.signals())).append("' 표현이 ").append(s.signalCount()).append("번 적혀 있어요.");
        else if (s.recentCount() > 0) sb.append(" 최근 메모에 눈에 띄는 생활 변화 표현은 없어요.");
        if (s.recentCount() == 0 && s.daysSinceLast() != null) sb.append(" 마지막 활동은 ").append(s.daysSinceLast()).append("일 전이에요.");
        return sb.toString();
    }

    public static List<String> ruleChanges(Stats s) {
        List<String> out = new ArrayList<>();
        if (!s.newSignals().isEmpty()) out.add("이전 기록에 없던 표현이 생겼어요: " + String.join("·", s.newSignals()));
        if (s.prevCount() != s.recentCount() && (s.prevCount() > 0 || s.recentCount() > 0)) out.add("활동 횟수 " + s.prevCount() + "회 → " + s.recentCount() + "회");
        if (!s.newCategories().isEmpty()) out.add("새로 받은 도움 종류: " + String.join("·", s.newCategories()));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static String line(Request r) {
        String log = r.logText == null ? "" : r.logText.replace("\n", " ").trim();
        String memo = r.careMemo == null || r.careMemo.isBlank() ? "" : " · 전달 메모: " + r.careMemo.replace("\n", " ").trim();
        String t = r.date.toString().substring(5) + " " + r.category + ": " + log + memo;
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    private record Row(Elder e, Stats stats, Scored scored, List<Request> recent, List<Request> before, Apply apply) {}

    private Row row(Elder e, List<Request> all, LocalDate today) {
        LocalDate recentFrom = today.minusDays(WINDOW_DAYS - 1L), prevFrom = recentFrom.minusDays(WINDOW_DAYS);
        List<Request> mine = all.stream().filter(r -> e.id.equals(r.elderId) && reported(r))
                .sorted(Comparator.comparing((Request r) -> r.date).reversed().thenComparing(r -> r.id, Comparator.reverseOrder())).toList();
        List<Request> recent = mine.stream().filter(r -> !r.date.isBefore(recentFrom)).toList();
        List<Request> before = mine.stream().filter(r -> !r.date.isBefore(prevFrom) && r.date.isBefore(recentFrom)).toList();
        Map<Long, CareNote> notes = careSignals.byRequest(java.util.stream.Stream.concat(recent.stream(), before.stream()).map(r -> r.id).toList());
        LinkedHashSet<String> sig = new LinkedHashSet<>(), prevSig = new LinkedHashSet<>();
        int sigN = 0, prevSigN = 0;
        for (Request r : recent) { CareNote n = notes.get(r.id); if (n != null && n.signals != null && !n.signals.isEmpty()) { sigN++; sig.addAll(n.signals); } }
        for (Request r : before) { CareNote n = notes.get(r.id); if (n != null && n.signals != null && !n.signals.isEmpty()) { prevSigN++; prevSig.addAll(n.signals); } }
        List<String> newSig = sig.stream().filter(x -> !prevSig.contains(x)).toList();
        Set<String> prevCats = new HashSet<>();
        before.forEach(r -> prevCats.add(r.category));
        List<String> newCats = before.isEmpty() ? List.of()
                : recent.stream().map(r -> r.category).filter(c -> c != null && !prevCats.contains(c)).distinct().toList();
        LocalDate last = mine.isEmpty() ? null : mine.get(0).date;
        Integer days = last == null ? null : (int) Math.max(0, ChronoUnit.DAYS.between(last, today));
        int todayN = (int) mine.stream().filter(r -> r.logSubmittedAt.toLocalDate().equals(today)).count();
        boolean flagged = careSignals.status(SubjectType.ELDER, e.id, null).flagged();
        boolean big = reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(SubjectType.ELDER, e.id).stream().findFirst().map(r -> r.bigChange).orElse(false);
        Stats st = new Stats(recent.size(), before.size(), todayN, days, last, sigN, prevSigN, new ArrayList<>(sig),
                before.isEmpty() ? List.of() : newSig, newCats, flagged, big, !mine.isEmpty());
        Apply ap = apply(e, all, today);
        return new Row(e, st, score(st, ap), recent, before, ap);
    }

    public Map<String, Object> report(User mgr, boolean todayOnly) {
        LocalDate today = LocalDate.now();
        List<Request> all = requests.findAll();
        List<Row> rows = new ArrayList<>();
        for (Elder e : elders.findByManagerId(mgr.id)) {
            if (e.status != ElderStatus.ACTIVE) continue;
            rows.add(row(e, all, today));
        }
        rows.sort(Comparator.comparingInt((Row r) -> -r.scored().score())
                .thenComparing(r -> r.stats().lastDate() == null ? LocalDate.MIN : r.stats().lastDate())
                .thenComparing(r -> r.e().id));
        List<Map<String, Object>> items = new ArrayList<>();
        boolean anyAi = false;
        int rank = 0, aiUsed = 0;
        for (Row r : rows) {
            rank++;
            if (todayOnly && r.stats().todayCount() == 0) continue;
            String label = r.e().alias + " 님";
            boolean useAi = ai.available() && !r.recent().isEmpty() && aiUsed < AI_TOP;
            if (useAi) aiUsed++;
            Map<String, Object> text = text(r, label, useAi);
            if ("ai".equals(text.get("source"))) anyAi = true;
            Stats s = r.stats();
            items.add(Views.map("elderId", r.e().id, "alias", r.e().alias, "displayName", label, "dong", r.e().dong,
                    "rank", rank, "score", r.scored().score(), "level", r.scored().level(), "reasons", r.scored().reasons(),
                    "stats", Views.map("recentCount", s.recentCount(), "prevCount", s.prevCount(), "todayCount", s.todayCount(),
                            "daysSinceLast", s.daysSinceLast(), "lastDate", s.lastDate() == null ? null : s.lastDate().toString(),
                            "signalCount", s.signalCount(), "signals", s.signals(), "newSignals", s.newSignals(), "flagged", s.flagged()),
                    "apply", Views.map("total", r.apply().total(), "matched", r.apply().matched(), "waiting", r.apply().waiting(),
                            "rejected", r.apply().rejected(), "expired", r.apply().expired(), "cancelled", r.apply().cancelled(),
                            "noshow", r.apply().noshow(), "unmatchedPosts", r.apply().unmatchedPosts(), "failed", r.apply().failed()),
                    "applyText", applyText(r.apply()),
                    "checkFirst", !"LOW".equals(r.scored().level()),
                    "summary", text.get("summary"), "changes", text.get("changes"), "source", text.get("source"), "provider", text.get("provider")));
        }
        return Views.map("date", today.toString(), "range", todayOnly ? "today" : "recent", "windowDays", WINDOW_DAYS,
                "items", items, "total", rows.size(), "source", anyAi ? "ai" : "rule", "notice", NOTICE);
    }

    private Map<String, Object> text(Row r, String label, boolean useAi) {
        String rs = ruleSummary(label, r.stats());
        List<String> rc = ruleChanges(r.stats());
        if (!useAi) return ai.compare(label, List.of(), List.of(), rs, rc);
        String finger = r.recent().stream().map(x -> x.id + ":" + x.logSubmittedAt).reduce("", String::concat) + "|"
                + r.before().stream().map(x -> String.valueOf(x.id)).reduce("", String::concat);
        Cached c = cache.get(r.e().id);
        if (c != null && c.finger().equals(finger)) return c.text();
        Map<String, Object> t = ai.compare(label, r.recent().stream().limit(LOG_LINES).map(ElderChangeService::line).toList(),
                r.before().stream().limit(LOG_LINES).map(ElderChangeService::line).toList(), rs, rc);
        if ("ai".equals(t.get("source"))) cache.put(r.e().id, new Cached(finger, t));
        return t;
    }
}
