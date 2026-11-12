package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.AiClient;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

/** 생활 변화 리포트(P-03~P-07). 숫자는 코드가 집계하고, 서술만 AiClient(AI 또는 규칙)가 쓴다. */
@Service
@Transactional
public class LifeReportService {
    private final LifeReportRepo reports;
    private final RequestRepo requests;
    private final ElderRepo elders;
    private final UserRepo users;
    private final FamilyLinkRepo family;
    private final NotificationService noti;
    private final AiClient ai;

    public LifeReportService(LifeReportRepo reports, RequestRepo requests, ElderRepo elders, UserRepo users, FamilyLinkRepo family,
                             NotificationService noti, AiClient ai) {
        this.reports = reports; this.requests = requests; this.elders = elders; this.users = users; this.family = family;
        this.noti = noti; this.ai = ai;
    }

    private static boolean in(Request r, YearMonth m) {
        return r.status == ReqStatus.DONE && YearMonth.from(r.date).equals(m);
    }

    private LifeReport build(SubjectType type, Long id, String label, List<Request> received, List<Request> given, YearMonth month) {
        YearMonth prev = month.minusMonths(1);
        List<Request> cur = received.stream().filter(r -> in(r, month)).toList();
        List<Request> before = received.stream().filter(r -> in(r, prev)).toList();
        int helpCur = (int) given.stream().filter(r -> in(r, month)).count();
        int helpPrev = (int) given.stream().filter(r -> in(r, prev)).count();
        Map<String, Integer> byCat = new TreeMap<>(), prevByCat = new TreeMap<>();
        cur.forEach(r -> byCat.merge(r.category, 1, Integer::sum));
        before.forEach(r -> prevByCat.merge(r.category, 1, Integer::sum));
        List<String> logs = new ArrayList<>();
        for (Request r : cur) if (r.logText != null) logs.add(r.date + " " + r.category + ": " + r.logText.replace("\n", " "));
        int count = cur.size() + helpCur, prevCount = before.size() + helpPrev;
        double hours = cur.stream().mapToDouble(r -> r.hours).sum();
        boolean newCat = byCat.keySet().stream().anyMatch(k -> !prevByCat.containsKey(k)) && !prevByCat.isEmpty();
        boolean big = Math.abs(count - prevCount) >= 2 || newCat;
        // C-16: 이번 달 일지를 지난달 일지와 내용으로 비교할 수 있게 지난달 일지도 함께 준다(표시를 붙여 구분). 숫자는 위에서 코드가 센 값만 쓴다.
        List<String> prevLogs = new ArrayList<>();
        for (Request r : before) if (r.logText != null) prevLogs.add("[지난달 기록] " + r.date + " " + r.category + ": " + r.logText.replace("\n", " "));
        List<String> forAi = new ArrayList<>(logs);
        if (!logs.isEmpty() && !prevLogs.isEmpty()) {
            forAi = new ArrayList<>(logs.stream().limit(6).toList());
            forAi.addAll(prevLogs.stream().limit(4).toList());
        }
        Map<String, Object> narr = ai.lifeNarrative(label, count, prevCount, byCat, forAi);
        LifeReport lr = reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(type, id).stream()
                .filter(x -> x.month.equals(month.toString())).findFirst().orElseGet(LifeReport::new);
        lr.subjectType = type;
        lr.subjectId = id;
        lr.month = month.toString();
        lr.statsJson = Views.json(Views.map("count", count, "prevCount", prevCount, "receivedCount", cur.size(), "helpCount", helpCur,
                "hours", hours, "byCategory", byCat, "prevByCategory", prevByCat, "logCount", logs.size(), "prevLogCount", prevLogs.size(), "newCategory", newCat));
        lr.narrative = (String) narr.get("narrative");
        lr.source = (String) narr.get("source");
        lr.bigChange = big;
        return reports.save(lr);
    }

    /** 한 달치 리포트를 만든다(이미 있으면 다시 쓴다). 반환: 만든 개수 */
    public int generate(YearMonth month) {
        List<Request> all = requests.findAll();
        int n = 0;
        for (Elder e : elders.findAll()) {
            if (e.status != ElderStatus.ACTIVE) continue;
            List<Request> rec = all.stream().filter(r -> e.id.equals(r.elderId)).toList();
            LifeReport lr = build(SubjectType.ELDER, e.id, e.alias + " 님", rec, List.of(), month);
            n++;
            if (lr.bigChange && e.managerId != null)
                noti.send(e.managerId, "LIFE_REPORT", month.getMonthValue() + "월 생활 변화 리포트가 나왔어요", e.alias + " 님 · 변화가 큰 사례", "/org/reports");
        }
        Set<Long> linked = new HashSet<>();
        for (FamilyLink f : family.findAll()) if (f.status == LinkStatus.ACCEPTED) { linked.add(f.requesterId); linked.add(f.targetId); }
        for (Long uid : linked) {
            User u = users.findById(uid).orElse(null);
            if (u == null || u.status != UserStatus.ACTIVE) continue;
            List<Request> rec = all.stream().filter(r -> uid.equals(r.recipientId)).toList();
            List<Request> given = all.stream().filter(r -> uid.equals(r.volunteerId)).toList();
            build(SubjectType.USER, uid, u.name + " 님", rec, given, month);
            n++;
            for (FamilyLink f : family.findAll()) {
                if (f.status != LinkStatus.ACCEPTED) continue;
                Long other = shareTarget(f, uid);
                if (other != null) noti.send(other, "LIFE_REPORT", u.name + " 님의 " + month.getMonthValue() + "월 생활 변화 리포트가 나왔어요", "가족 연결에서 볼 수 있어요", "/family/" + uid + "/report");
            }
        }
        return n;
    }

    /** f 에서 subject 가 공유에 동의했으면 상대 사용자 id. */
    public static Long shareTarget(FamilyLink f, Long subject) {
        if (f.requesterId.equals(subject) && f.shareReport) return f.targetId;
        if (f.targetId.equals(subject) && f.targetShareReport) return f.requesterId;
        return null;
    }

    public boolean existsFor(YearMonth m) { return !reports.findByMonth(m.toString()).isEmpty(); }

    public Map<String, Object> view(LifeReport r, String label) {
        return Views.map("id", r.id, "subjectType", r.subjectType, "subjectId", r.subjectId, "subjectLabel", label, "month", r.month,
                "stats", Views.parse(r.statsJson, new TypeReference<Map<String, Object>>() {}, Map.of()),
                "narrative", r.narrative, "bigChange", r.bigChange, "source", r.source, "createdAt", r.createdAt);
    }
}
