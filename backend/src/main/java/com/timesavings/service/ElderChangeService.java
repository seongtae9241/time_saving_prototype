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
    public static final String NOTICE = "횟수와 순서는 시스템이 계산하고, AI는 봉사자 일지 내용만 정리해요. 진단·건강 판단이 아니에요.";

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

    private record Row(Elder e, Stats stats, Scored scored, List<Request> recent, List<Request> before) {}

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
        return new Row(e, st, score(st), recent, before);
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
