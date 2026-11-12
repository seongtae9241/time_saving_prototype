package com.timesavings.service;

import com.timesavings.ai.MatchReasonAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * LLM 추가 A(7.12) "왜 이 봉사자?": 점수와 근거는 모두 코드가 계산한다(거리·그 카테고리 완료 횟수·평균 별점·단골·교육 수료·범죄경력 확인).
 * 요청을 만들 때 1번 계산해 requests.match_reason 에 저장하고(LLM 이 문장만 다듬음), 공고 목록의 봉사자 공고에는 코드 문장만 붙인다(LLM 호출 없음).
 * 수락·순위 결정은 하지 않는다. 받는 사람에 대한 정보는 쓰지 않고 봉사자의 활동 기록만 쓴다.
 */
@Service
public class MatchReasonService {
    private static final Logger log = LoggerFactory.getLogger(MatchReasonService.class);
    private final RequestRepo requests;
    private final UserRepo users;
    private final ElderRepo elders;
    private final Views views;
    private final MatchReasonAi ai;

    public MatchReasonService(RequestRepo requests, UserRepo users, ElderRepo elders, Views views, MatchReasonAi ai) {
        this.requests = requests; this.users = users; this.elders = elders; this.views = views; this.ai = ai;
    }

    /** score: 비교용 점수, facts: 화면에 보일 근거(중요한 순, 최대 3개), parts: 항목별 점수(테스트·설명용) */
    public record Facts(double score, List<String> facts, Map<String, Object> parts) {}

    /**
     * 봉사자 v 가 받는 쪽(사용자 recipientUserId 또는 대상 elderId, 지역 recipientArea)에게 categories 도움을 줄 때의 근거.
     * doneList: 완료(DONE) 요청 목록(여러 카드를 한 번에 계산할 때 미리 읽어 넘긴다).
     */
    public Facts facts(User v, Collection<String> categories, String recipientArea, Long recipientUserId, Long elderId, List<Request> doneList) {
        Map<String, Object> parts = new LinkedHashMap<>();
        double score = 0;
        List<String[]> f = new ArrayList<>(); // [우선순위, 문장]
        // 단골(B-41, U7)
        boolean regular = (recipientUserId != null || elderId != null) && views.isRegular(v.id, recipientUserId, elderId);
        if (regular) { score += 4; f.add(new String[]{"1", "단골"}); }
        parts.put("regular", regular);
        // 그 카테고리 완료 횟수(가장 많은 카테고리 하나)
        String bestCat = null;
        long best = 0;
        for (String c : categories == null ? List.<String>of() : categories) {
            long n = doneList.stream().filter(r -> v.id.equals(r.volunteerId) && c.equals(r.category)).count();
            if (n > best) { best = n; bestCat = c; }
        }
        if (best > 0) { score += Math.min(best, 10) * 0.5; f.add(new String[]{"2", short_(bestCat) + " " + best + "회"}); }
        parts.put("categoryDone", best);
        // 거리
        boolean sameDong = v.area != null && v.area.equals(recipientArea);
        if (sameDong) { score += 3; f.add(new String[]{"3", "같은 동네"}); }
        else if (v.area != null && recipientArea != null) {
            double km = Views.distanceKm(v.area, recipientArea, 0) - 0.3;
            if (km <= 1.2) { score += 1.5; f.add(new String[]{"3", "가까운 동네"}); }
        }
        parts.put("sameDong", sameDong);
        // 평균 별점(공개 평가 3개 이상일 때만)
        double[] rt = views.rating(v.id);
        if (rt[1] >= 3 && rt[0] >= 4.0) { score += (rt[0] - 3.5) * 2; f.add(new String[]{"4", "별점 " + rt[0]}); }
        parts.put("ratingAvg", rt[0]);
        parts.put("ratingCount", (int) rt[1]);
        // 배지
        if (v.crimeStatus == VerifyStatus.APPROVED) { score += 1; f.add(new String[]{"5", "범죄경력 확인"}); }
        parts.put("crime", v.crimeStatus == VerifyStatus.APPROVED);
        f.sort(Comparator.comparing(a -> a[0]));
        List<String> facts = f.stream().limit(3).map(a -> a[1]).toList();
        return new Facts(Math.round(score * 10) / 10.0, facts, parts);
    }

    private static String short_(String c) {
        if (c == null) return "";
        return switch (c) {
            case "말동무·정서" -> "말벗";
            case "디지털 도움" -> "디지털";
            case "장보기·심부름" -> "장보기";
            case "가벼운 집안일" -> "집안일";
            default -> c;
        };
    }

    private List<Request> doneList() {
        return requests.findByStatusIn(List.of(ReqStatus.DONE));
    }

    /** 요청 하나의 근거(받는 쪽 기준). */
    public Facts factsFor(Request r) {
        User v = users.findById(r.volunteerId).orElse(null);
        if (v == null) return new Facts(0, List.of(), Map.of());
        String area = null;
        if (r.elderId != null) area = elders.findById(r.elderId).map(e -> e.dong).orElse(null);
        else if (r.recipientId != null) area = users.findById(r.recipientId).map(u -> u.area).orElse(null);
        return facts(v, r.category == null ? List.of() : List.of(r.category), area, r.recipientId, r.elderId, doneList());
    }

    /** 요청 생성 때 1회: 코드 근거 → LLM 다듬기(키 없으면 코드 문장) → 저장. 이미 있으면 다시 부르지 않는다. */
    @Transactional
    public String fill(Request r) {
        if (r == null || r.matchReason != null) return r == null ? null : r.matchReason;
        Facts f = factsFor(r);
        if (f.facts().isEmpty()) { r.matchReason = ""; requests.save(r); return r.matchReason; }
        MatchReasonAi.Polished p = ai.polish(f.facts());
        r.matchReason = p.text().length() > 200 ? p.text().substring(0, 200) : p.text();
        requests.save(r);
        return r.matchReason;
    }

    /** 공고 목록·상세: 봉사자 공고(OFFER, 남의 공고)에 코드 근거 한 줄과 점수를 붙인다(LLM 호출 없음). */
    public void decorateCards(User viewer, List<Map<String, Object>> cards) {
        if (viewer == null || viewer.role != Role.USER || cards.isEmpty()) return;
        List<Request> done = null;
        for (Map<String, Object> c : cards) {
            if (!"OFFER".equals(String.valueOf(c.get("type"))) || Boolean.TRUE.equals(c.get("mine"))) continue;
            Object oid = c.get("ownerId");
            if (!(oid instanceof Long ownerId)) continue;
            User v = users.findById(ownerId).orElse(null);
            if (v == null) continue;
            if (done == null) done = doneList();
            @SuppressWarnings("unchecked") List<String> cats = (List<String>) c.get("categories");
            Facts f = facts(v, cats, viewer.area, viewer.id, null, done);
            c.put("matchReason", f.facts().isEmpty() ? null : String.join(" · ", f.facts()));
            c.put("matchScore", f.score());
        }
    }

    /** 이전에 만들어진(이유 칸이 빈) 진행 중 요청은 서버가 켜질 때 코드 문장으로 채운다(LLM 호출 없음, 데모 데이터용). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        try {
            List<Request> list = requests.findAll().stream().filter(r -> r.matchReason == null && !r.status.terminal()).toList();
            for (Request r : list) {
                Facts f = factsFor(r);
                r.matchReason = String.join(" · ", f.facts());
            }
            requests.saveAll(list);
            if (!list.isEmpty()) log.info("추천 이유 채움: {}건(규칙)", list.size());
        } catch (Exception e) {
            log.warn("추천 이유 채우기 건너뜀: {}", e.getClass().getSimpleName());
        }
    }
}
