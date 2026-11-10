package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.Rules;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 2026-11-09 회의 반영: 수동 단골 등록(U7)과 "내가 자주 받는 도움" 순위(U3).
 */
@RestController
@RequestMapping("/api")
@Transactional
public class NeighborController {
    private final RegularMarkRepo marks;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final UserRepo users;

    public NeighborController(RegularMarkRepo marks, RequestRepo requests, PostRepo posts, UserRepo users) {
        this.marks = marks; this.requests = requests; this.posts = posts; this.users = users;
    }

    // ---------------- U7 수동 단골 ----------------
    private long doneWith(Long me, Long volunteerId) {
        return requests.findAll().stream()
                .filter(r -> r.status == ReqStatus.DONE && r.elderId == null && me.equals(r.recipientId) && volunteerId.equals(r.volunteerId)).count();
    }

    /** 단골 상태: regular(자동이든 수동이든), auto(DONE 3회 이상), manual, canMark(활동을 1~2번 끝낸 상대, 아직 단골 아님), doneCount */
    @GetMapping("/regulars/{volunteerId}/status")
    public Map<String, Object> status(@CurrentUser User u, @PathVariable Long volunteerId) {
        long n = u.id.equals(volunteerId) ? 0 : doneWith(u.id, volunteerId);
        boolean manual = marks.findByUserIdAndVolunteerId(u.id, volunteerId).isPresent();
        return Views.map("volunteerId", volunteerId, "doneCount", n, "auto", n >= 3, "manual", manual,
                "regular", manual || n >= 3, "canMark", u.role == Role.USER && n >= 1 && n < 3 && !manual); // 3회 이상은 이미 자동 단골
    }

    @PostMapping("/regulars/{volunteerId}")
    public Map<String, Object> mark(@CurrentUser User u, @PathVariable Long volunteerId) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 단골을 등록할 수 있어요");
        if (u.id.equals(volunteerId)) throw ApiException.bad("SELF", "나 자신은 단골로 등록할 수 없어요");
        User v = users.findById(volunteerId).orElseThrow(() -> ApiException.notFound("봉사자를 찾을 수 없어요"));
        if (v.role != Role.USER || v.status != UserStatus.ACTIVE) throw ApiException.bad("INVALID_TARGET", "단골로 등록할 수 없는 계정이에요");
        if (doneWith(u.id, volunteerId) < 1) throw ApiException.conflict("NO_DONE_ACTIVITY", "도움을 한 번 이상 받아 끝난 분만 단골로 등록할 수 있어요");
        if (marks.findByUserIdAndVolunteerId(u.id, volunteerId).isEmpty()) {
            RegularMark m = new RegularMark();
            m.userId = u.id;
            m.volunteerId = volunteerId;
            marks.save(m);
        }
        return status(u, volunteerId);
    }

    @DeleteMapping("/regulars/{volunteerId}")
    public Map<String, Object> unmark(@CurrentUser User u, @PathVariable Long volunteerId) {
        marks.findByUserIdAndVolunteerId(u.id, volunteerId).ifPresent(marks::delete);
        return status(u, volunteerId);
    }

    // ---------------- U3 내가 자주 받는 도움 ----------------
    /**
     * 내 요청(받는 쪽, 거절·취소 제외)과 완료 기록, 내가 올린 도움 요청 공고에서 카테고리 순위를 센다.
     * 완료(DONE)는 2점, 그 밖의 요청·공고는 1점. 결과: {top:[{category,count,score}], order:[6개 카테고리 순서], basis}
     */
    @GetMapping("/me/top-categories")
    public Map<String, Object> topCategories(@CurrentUser User u) {
        Map<String, int[]> s = new LinkedHashMap<>(); // [score, count]
        for (String c : Rules.CATEGORIES) s.put(c, new int[2]);
        int base = 0;
        for (Request r : requests.findAll()) {
            if (r.elderId != null || !u.id.equals(r.recipientId) || r.category == null || !s.containsKey(r.category)) continue;
            if (r.status == ReqStatus.REJECTED || r.status == ReqStatus.CANCELLED || r.status == ReqStatus.EXPIRED) continue;
            int[] v = s.get(r.category);
            v[0] += r.status == ReqStatus.DONE ? 2 : 1;
            v[1]++;
            base++;
        }
        for (Post p : posts.findByOwnerId(u.id)) {
            if (p.type != PostType.NEED || p.elderId != null || p.status == PostStatus.DELETED) continue;
            for (String c : p.categories) if (s.containsKey(c)) { s.get(c)[0]++; base++; }
        }
        List<String> order = new ArrayList<>(Rules.CATEGORIES);
        order.sort(Comparator.comparingInt((String c) -> -s.get(c)[0]));
        List<Map<String, Object>> top = new ArrayList<>();
        for (String c : order) if (s.get(c)[0] > 0) top.add(Views.map("category", c, "count", s.get(c)[1], "score", s.get(c)[0]));
        return Views.map("top", top, "order", order, "basis", base);
    }
}
