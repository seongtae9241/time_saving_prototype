package com.timesavings.service;

import com.timesavings.domain.Elder;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.repo.RequestRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 홈 "봉사자 현황"(2026-11-16, 프로토타입 O-HOME-01 오른쪽 아래). 새로 저장하는 것 없이 기존 요청 자료에서 계산한다.
 * 대상: 내 관리 대상과 엮인 봉사자 = 지금 대기·확정·진행 중인 요청이 있거나, 최근 RECENT_DAYS 일 안에 활동을 마쳤거나, 내 대상의 단골(완료 3회 이상)인 사람.
 * 상태(위에서부터 먼저 맞는 것 하나):
 *   ACTIVE_NOW 활동 중(시작 QR 을 찍고 아직 안 끝남) → TODAY 오늘 예정(오늘 확정) → SCHEDULED 예정(뒤 날짜 확정) → PENDING 응답 대기(요청·제안 답 전) → IDLE 대기.
 * 프로토타입의 "쉬는 날"은 봉사자가 쉬는 날을 저장하지 않아 만들지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class OrgVolunteerService {
    public static final int RECENT_DAYS = 60;
    public static final int REGULAR_MIN = 3;
    private static final List<String> ORDER = List.of("ACTIVE_NOW", "TODAY", "SCHEDULED", "PENDING", "IDLE");

    private final ElderRepo elders;
    private final RequestRepo requests;
    private final UserRepo users;

    public OrgVolunteerService(ElderRepo elders, RequestRepo requests, UserRepo users) {
        this.elders = elders; this.requests = requests; this.users = users;
    }

    static String label(String state) {
        return switch (state) {
            case "ACTIVE_NOW" -> "활동 중";
            case "TODAY" -> "오늘 예정";
            case "SCHEDULED" -> "예정";
            case "PENDING" -> "응답 대기";
            default -> "대기";
        };
    }

    private static String hhmm(double h) { int H = (int) Math.floor(h); return String.format("%02d:%s", H, h - H >= 0.5 ? "30" : "00"); }

    private static String stateOf(Request r, LocalDate today) {
        if (r.status == ReqStatus.IN_PROGRESS) return "ACTIVE_NOW";
        if (r.status == ReqStatus.CONFIRMED && r.date.equals(today)) return "TODAY";
        if (r.status == ReqStatus.CONFIRMED && r.date.isAfter(today)) return "SCHEDULED";
        if (r.status.pending() && !r.date.isBefore(today)) return "PENDING";
        return null;
    }

    public Map<String, Object> status(User mgr, Integer limit) {
        LocalDate today = LocalDate.now();
        Map<Long, Elder> mine = new LinkedHashMap<>();
        elders.findByManagerId(mgr.id).stream().filter(e -> e.status == ElderStatus.ACTIVE).forEach(e -> mine.put(e.id, e));
        Map<Long, List<Request>> byVol = new LinkedHashMap<>();
        for (Request r : requests.findAll()) {
            if (r.elderId == null || !mine.containsKey(r.elderId) || r.volunteerId == null) continue;
            byVol.computeIfAbsent(r.volunteerId, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        ORDER.forEach(s -> counts.put(s, 0));
        int regularVols = 0;
        for (var en : byVol.entrySet()) {
            User v = users.findById(en.getKey()).orElse(null);
            if (v == null || v.status != UserStatus.ACTIVE || v.role != Role.USER) continue;
            List<Request> rs = en.getValue();
            // 지금 상태를 정하는 요청: 상태 순서가 앞선 것, 같으면 가까운 날짜
            Request next = null; String state = "IDLE";
            for (Request r : rs) {
                String s = stateOf(r, today);
                if (s == null) continue;
                int c = Integer.compare(ORDER.indexOf(s), ORDER.indexOf(state));
                if (next == null || c < 0 || (c == 0 && (r.date.isBefore(next.date) || (r.date.equals(next.date) && r.startHour < next.startHour)))) { next = r; state = s; }
            }
            List<Request> done = rs.stream().filter(r -> r.status == ReqStatus.DONE).toList();
            Map<Long, Long> perElder = new LinkedHashMap<>();
            done.forEach(r -> perElder.merge(r.elderId, 1L, Long::sum));
            List<String> regularOf = perElder.entrySet().stream().filter(x -> x.getValue() >= REGULAR_MIN).map(x -> mine.get(x.getKey()).alias + " 님").toList();
            LocalDate last = done.stream().map(r -> r.date).max(Comparator.naturalOrder()).orElse(null);
            boolean recent = last != null && !last.isBefore(today.minusDays(RECENT_DAYS));
            if (next == null && !recent && regularOf.isEmpty()) continue; // 지금 엮인 일이 없는 예전 봉사자
            if (!regularOf.isEmpty()) regularVols++;
            counts.merge(state, 1, Integer::sum);
            String sub;
            Map<String, Object> nx = null;
            if (next != null) {
                String who = mine.get(next.elderId).alias + " 님";
                sub = (next.date.equals(today) ? "오늘" : Rules.md(next.date)) + " " + hhmm(next.startHour) + " " + who;
                nx = Views.map("requestId", next.id, "date", next.date.toString(), "startHour", next.startHour, "hours", next.hours, "category", next.category,
                        "elderId", next.elderId, "elderName", who, "status", next.status);
            } else if (!regularOf.isEmpty()) sub = "단골 " + regularOf.size() + "명";
            else sub = "최근 활동 " + Rules.md(last);
            Map<String, Long> cats = new LinkedHashMap<>();
            done.forEach(r -> { if (r.category != null) cats.merge(r.category, 1L, Long::sum); });
            rows.add(Views.map("volunteerId", v.id, "name", v.name, "area", v.area, "photoUrl", v.photoUrl,
                    "state", state, "stateLabel", label(state), "sub", sub, "next", nx,
                    "regularCount", regularOf.size(), "regularOf", regularOf, "doneCount", done.size(),
                    "lastDoneDate", last == null ? null : last.toString(),
                    "categories", cats.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(2).map(Map.Entry::getKey).toList()));
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> m) -> ORDER.indexOf((String) m.get("state")))
                .thenComparing(m -> m.get("next") == null ? "9999" : (String) ((Map<?, ?>) m.get("next")).get("date"))
                .thenComparingInt(m -> -((Number) m.get("doneCount")).intValue())
                .thenComparing(m -> (Long) m.get("volunteerId")));
        int total = rows.size();
        List<Map<String, Object>> items = limit != null && limit > 0 && rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
        return Views.map("date", today.toString(), "items", items,
                "counts", Views.map("total", total, "activeNow", counts.get("ACTIVE_NOW"), "today", counts.get("TODAY"), "scheduled", counts.get("SCHEDULED"),
                        "pending", counts.get("PENDING"), "idle", counts.get("IDLE"), "regulars", regularVols),
                "recentDays", RECENT_DAYS,
                "notice", "내 관리 대상과 엮인 봉사자예요. 최근 " + RECENT_DAYS + "일 안에 활동했거나, 지금 요청이 걸려 있거나, 단골(완료 " + REGULAR_MIN + "회 이상)인 분만 보여요.");
    }
}
