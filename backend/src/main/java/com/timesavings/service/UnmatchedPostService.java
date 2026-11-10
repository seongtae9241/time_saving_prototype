package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.FixTipsAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * LLM 추가 A(7.12) 안 맞춰지는 공고 고치기 제안.
 * 스케줄러가 등록 24시간이 지나도 요청·제안이 0건인 OPEN 공고를 찾아 **공고당 1번만**(posts.fix_checked_at) 근거를 계산하고,
 * LLM 이 후보 중 1~3개를 다듬어 posts.fix_tips_json 에 저장한 뒤 공고 주인(독거노인 건은 담당자)에게 알린다.
 * 근거 숫자(같은 도움의 반대쪽 공고 수·요일·시간대·최근 매칭 시간대)는 모두 코드 값이다. 공고를 바꾸는 것은 주인이 직접 한다.
 */
@Service
@Transactional
public class UnmatchedPostService {
    public static final int AFTER_HOURS = 24;
    private static final String[] DAY_KO = {"월", "화", "수", "목", "금", "토", "일"};
    private final PostRepo posts;
    private final RequestRepo requests;
    private final ElderRepo elders;
    private final NotificationService noti;
    private final FixTipsAi ai;

    public UnmatchedPostService(PostRepo posts, RequestRepo requests, ElderRepo elders, NotificationService noti, FixTipsAi ai) {
        this.posts = posts; this.requests = requests; this.elders = elders; this.noti = noti; this.ai = ai;
    }

    static String slotOf(double startHour) { return startHour < 12 ? "오전" : startHour < 18 ? "오후" : "저녁"; }

    /** 시간대 이름 → 제안 범위 [시작, 끝] */
    static double[] slotRange(String slot) {
        return switch (slot) { case "오전" -> new double[]{9, 12}; case "저녁" -> new double[]{18, 20}; default -> new double[]{13, 17}; };
    }

    /** 스케줄러: 대상 공고마다 1번. 돌려주는 값 = 제안을 만든 공고 수. */
    public int run(LocalDateTime now) {
        int n = 0;
        LocalDate today = now.toLocalDate();
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (p.fixCheckedAt != null || p.createdAt == null || p.createdAt.isAfter(now.minusHours(AFTER_HOURS))) continue;
            if (p.date.isBefore(today)) continue;
            if (!requests.findByPostId(p.id).isEmpty()) { p.fixCheckedAt = now; continue; } // 요청이 온 공고는 제안하지 않음(다시 보지도 않음)
            make(p, now);
            n++;
        }
        return n;
    }

    /** 근거 계산(코드). */
    public Map<String, Object> evidence(Post p, LocalDate today) {
        PostType other = p.type == PostType.NEED ? PostType.OFFER : PostType.NEED;
        List<Post> opp = posts.findByStatus(PostStatus.OPEN).stream()
                .filter(x -> x.type == other && !x.date.isBefore(today) && !Objects.equals(x.ownerId, p.ownerId) && x.categories.stream().anyMatch(p.categories::contains))
                .toList();
        double need = p.type == PostType.NEED && p.estHours != null ? p.estHours : 1.0;
        long sameDay = opp.stream().filter(x -> x.date.getDayOfWeek() == p.date.getDayOfWeek()).count();
        long overlap = opp.stream().filter(x -> Math.min(x.endHour, p.endHour) - Math.max(x.startHour, p.startHour) >= need - 1e-9).count();
        // 최근 90일 같은 카테고리 매칭(확정 이후) 요청
        LocalDateTime since = today.atStartOfDay().minusDays(90);
        List<Request> matched = requests.findAll().stream()
                .filter(r -> p.categories.contains(r.category) && r.confirmedAt != null && r.confirmedAt.isAfter(since)
                        && (r.status.active() || r.status == ReqStatus.DONE))
                .toList();
        Map<String, Integer> slots = new LinkedHashMap<>();
        Map<DayOfWeek, Integer> days = new EnumMap<>(DayOfWeek.class);
        for (Post x : opp) { slots.merge(slotOf(x.startHour), 1, Integer::sum); days.merge(x.date.getDayOfWeek(), 1, Integer::sum); }
        for (Request r : matched) { slots.merge(slotOf(r.startHour), 1, Integer::sum); days.merge(r.date.getDayOfWeek(), 1, Integer::sum); }
        String bestSlot = slots.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        DayOfWeek bestDay = days.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("categories", p.categories);
        ev.put("oppositeOpen", opp.size());
        ev.put("sameWeekday", sameDay);
        ev.put("overlapTime", overlap);
        ev.put("recentMatched", matched.size());
        ev.put("slotCounts", slots);
        ev.put("bestSlot", bestSlot);
        ev.put("bestSlotCount", bestSlot == null ? 0 : slots.get(bestSlot));
        ev.put("mySlot", slotOf(p.startHour));
        ev.put("bestWeekday", bestDay == null ? null : DAY_KO[bestDay.getValue() - 1] + "요일");
        ev.put("bestWeekdayCount", bestDay == null ? 0 : days.get(bestDay));
        ev.put("myWeekday", DAY_KO[p.date.getDayOfWeek().getValue() - 1] + "요일");
        ev.put("rangeHours", p.endHour - p.startHour);
        ev.put("needHours", need);
        return ev;
    }

    /** 근거 → 후보 제안(코드, 중요한 순). */
    public List<Map<String, Object>> candidates(Post p, Map<String, Object> ev, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        String other = p.type == PostType.NEED ? "봉사 가능 공고" : "도움 요청 공고";
        double need = ((Number) ev.get("needHours")).doubleValue();
        String bestSlot = (String) ev.get("bestSlot");
        int slotCnt = ((Number) ev.get("bestSlotCount")).intValue();
        if (bestSlot != null && !bestSlot.equals(ev.get("mySlot"))) {
            double[] r = slotRange(bestSlot);
            if (r[1] - r[0] < need) r[1] = Math.min(20, r[0] + Math.max(need, 1));
            out.add(Views.map("action", "TIME", "text", bestSlot + " 시간대로 바꿔 보세요. 같은 도움의 " + other + "·최근 연결이 " + bestSlot + "에 " + slotCnt + "건 있어요.",
                    "suggest", Views.map("startHour", r[0], "endHour", r[1], "slot", bestSlot)));
        } else if (p.endHour - p.startHour < 6 && (((Number) ev.get("rangeHours")).doubleValue() <= need + 1e-9
                || (((Number) ev.get("overlapTime")).intValue() == 0 && ((Number) ev.get("oppositeOpen")).intValue() > 0))) {
            double s = Math.max(8, p.startHour - 1), e = Math.min(20, p.endHour + 1);
            int opp = ((Number) ev.get("oppositeOpen")).intValue();
            out.add(Views.map("action", "TIME", "text", "시간 범위를 앞뒤로 1시간씩 넓혀 보세요." + (opp > 0 && ((Number) ev.get("overlapTime")).intValue() == 0 ? " 같은 도움의 " + other + " " + opp + "개가 있지만 시간이 맞는 공고는 아직 없어요." : " 맞출 수 있는 사람이 늘어나요."),
                    "suggest", Views.map("startHour", s, "endHour", e)));
        }
        if (p.categories.size() > 1) {
            out.add(Views.map("action", "SPLIT", "text", "도움 종류를 나눠 올려 보세요. " + String.join("·", p.categories) + "를 한 번에 맡을 사람은 적어요.",
                    "suggest", Views.map("categories", List.of(p.categories.get(0)), "rest", p.categories.subList(1, p.categories.size()))));
        }
        String bestDay = (String) ev.get("bestWeekday");
        if (bestDay != null && !bestDay.equals(ev.get("myWeekday"))) {
            DayOfWeek d = DayOfWeek.of(Arrays.asList(DAY_KO).indexOf(bestDay.substring(0, 1)) + 1);
            LocalDate nd = today.plusDays(1).with(TemporalAdjusters.nextOrSame(d));
            out.add(Views.map("action", "DATE", "text", bestDay + "로 옮겨 보세요. 같은 도움이 " + bestDay + "에 " + ev.get("bestWeekdayCount") + "건 연결됐거나 열려 있어요.",
                    "suggest", Views.map("date", nd.toString(), "weekday", bestDay)));
        }
        String body = Objects.toString(p.title, "") + Objects.toString(p.detail, "") + Objects.toString(p.note, "");
        if (body.replaceAll("\\s", "").length() < 15) {
            out.add(Views.map("action", "EDIT", "text", "할 일을 한두 줄 더 자세히 적어 보세요. 무엇을 하는지 알면 신청하기 쉬워요.", "suggest", Views.map()));
        }
        if (out.isEmpty()) {
            out.add(Views.map("action", "EDIT", "text", "지금 열린 " + other + "가 " + ev.get("oppositeOpen") + "개예요. 시간이나 내용을 조금 바꿔 보세요.", "suggest", Views.map()));
        }
        return out;
    }

    /** 한 공고에 제안을 만들고 저장 + 알림. 이미 확인한 공고면 아무것도 하지 않는다. */
    public Map<String, Object> make(Post p, LocalDateTime now) {
        if (p.fixCheckedAt != null) return tipsOf(p);
        LocalDate today = now.toLocalDate();
        Map<String, Object> ev = evidence(p, today);
        List<Map<String, Object>> cands = candidates(p, ev, today);
        String evText = "같은 도움의 반대쪽 공고 " + ev.get("oppositeOpen") + "개, 같은 요일 " + ev.get("sameWeekday") + "개, 시간이 맞는 것 " + ev.get("overlapTime")
                + "개, 최근 90일 연결 " + ev.get("recentMatched") + "건, 가장 많은 시간대 " + ev.get("bestSlot") + "(" + ev.get("bestSlotCount") + "건), 가장 많은 요일 "
                + ev.get("bestWeekday") + "(" + ev.get("bestWeekdayCount") + "건)";
        String postText = p.title + " / " + String.join(",", p.categories) + " / " + p.date + " " + p.startHour + "~" + p.endHour + "시 / " + Objects.toString(p.detail, "");
        FixTipsAi.Result r = ai.polish(cands, postText, evText);
        Map<String, Object> saved = Views.map("tips", r.tips(), "evidence", ev, "source", r.source(), "createdAt", now.toString());
        p.fixTipsJson = Views.json(saved);
        p.fixCheckedAt = now;
        posts.save(p);
        Long to = p.ownerId != null ? p.ownerId : p.elderId == null ? null : elders.findById(p.elderId).map(e -> e.managerId).orElse(null);
        String first = r.tips().isEmpty() ? "" : String.valueOf(r.tips().get(0).get("text"));
        noti.send(to, "POST_TIPS", "올린 공고에 아직 요청이 없어요", "이렇게 바꿔 보세요: " + first, "/post/" + p.id);
        return saved;
    }

    /** 저장된 제안(없으면 null). */
    public Map<String, Object> tipsOf(Post p) {
        if (p.fixTipsJson == null) return null;
        return Views.parse(p.fixTipsJson, new TypeReference<Map<String, Object>>() {}, null);
    }
}
