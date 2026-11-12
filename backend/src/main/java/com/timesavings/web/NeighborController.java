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
 * 단골 등록·해지(2026-11-09 회의 U7, 41판 B-65·B-42)와 "내가 자주 받는 도움" 순위(U3).
 */
@RestController
@RequestMapping("/api")
@Transactional
public class NeighborController {
    private final RegularMarkRepo marks;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final UserRepo users;
    private final Views views;

    public NeighborController(RegularMarkRepo marks, RequestRepo requests, PostRepo posts, UserRepo users, Views views) {
        this.marks = marks; this.requests = requests; this.posts = posts; this.users = users; this.views = views;
    }

    // ---------------- 단골 등록·해지 (U7 → 41판 B-65, B-42) ----------------
    private long doneWith(Long me, Long volunteerId) {
        return requests.findAll().stream()
                .filter(r -> r.status == ReqStatus.DONE && r.elderId == null && me.equals(r.recipientId) && volunteerId.equals(r.volunteerId)).count();
    }

    /**
     * 단골 상태: regular(자동이든 직접이든, 해지되지 않음), auto(DONE 3회 이상), manual(직접 등록), removed(해지됨),
     * removedByThem(등록된 쪽이 해지해 다시 등록할 수 없음), canMark(직접 등록할 수 있음 — 활동이 없어도 됨), doneCount
     */
    @GetMapping("/regulars/{volunteerId}/status")
    public Map<String, Object> status(@CurrentUser User u, @PathVariable Long volunteerId) {
        boolean self = u.id.equals(volunteerId);
        long n = self ? 0 : doneWith(u.id, volunteerId);
        RegularMark m = marks.findByUserIdAndVolunteerId(u.id, volunteerId).orElse(null);
        boolean removed = m != null && m.isRemoved();
        boolean manual = m != null && !removed;
        boolean byThem = removed && volunteerId.equals(m.removedBy);
        boolean regular = !removed && (manual || n >= 3);
        return Views.map("volunteerId", volunteerId, "doneCount", n, "auto", n >= 3 && !removed, "manual", manual, "removed", removed,
                "removedByThem", byThem, "regular", regular, "canMark", u.role == Role.USER && !self && !regular && !byThem);
    }

    /** B-65: 프로필·평가 화면에서 즐겨찾기처럼 직접 등록. 한 번도 만나지 않은 사람도 된다. 내가 해지했던 사람은 다시 등록할 수 있다. */
    @PostMapping("/regulars/{volunteerId}")
    public Map<String, Object> mark(@CurrentUser User u, @PathVariable Long volunteerId) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 단골을 등록할 수 있어요");
        if (u.id.equals(volunteerId)) throw ApiException.bad("SELF", "나 자신은 단골로 등록할 수 없어요");
        User v = users.findById(volunteerId).orElseThrow(() -> ApiException.notFound("봉사자를 찾을 수 없어요"));
        if (v.role != Role.USER || v.status != UserStatus.ACTIVE) throw ApiException.bad("INVALID_TARGET", "단골로 등록할 수 없는 계정이에요");
        RegularMark m = marks.findByUserIdAndVolunteerId(u.id, volunteerId).orElse(null);
        if (m != null && m.isRemoved() && volunteerId.equals(m.removedBy))
            throw ApiException.conflict("REMOVED_BY_VOLUNTEER", "상대가 단골을 해지해서 다시 등록할 수 없어요");
        if (m == null) {
            m = new RegularMark();
            m.userId = u.id;
            m.volunteerId = volunteerId;
        }
        m.removed = false;
        m.removedBy = null;
        marks.save(m);
        return status(u, volunteerId);
    }

    private void remove(Long userId, Long volunteerId, Long by) {
        RegularMark m = marks.findByUserIdAndVolunteerId(userId, volunteerId).orElseGet(() -> {
            RegularMark x = new RegularMark();
            x.userId = userId;
            x.volunteerId = volunteerId;
            return x;
        });
        if (m.isRemoved()) return; // 먼저 해지한 쪽의 기록을 남긴다
        m.removed = true;
        m.removedBy = by;
        marks.save(m);
    }

    /** B-65: 등록한 쪽(도움 받는 사람)의 해지. 자동(3회) 단골도 해지된다. */
    @DeleteMapping("/regulars/{volunteerId}")
    public Map<String, Object> unmark(@CurrentUser User u, @PathVariable Long volunteerId) {
        if (marks.findByUserIdAndVolunteerId(u.id, volunteerId).isPresent() || doneWith(u.id, volunteerId) >= 3) remove(u.id, volunteerId, u.id);
        return status(u, volunteerId);
    }

    /** B-42: 내가 받은 단골 배지(누구를 몇 회 도왔는지). */
    @GetMapping("/me/regular-badges")
    public List<Map<String, Object>> myBadges(@CurrentUser User u) { return views.regularBadgeList(u.id, u.id); }

    /** B-65: 등록된 쪽(봉사하는 사람)의 해지. 그 사람의 단골 목록에서 빠지고 내 배지도 사라진다. */
    @DeleteMapping("/me/regular-badges/{userId}")
    public List<Map<String, Object>> dropBadge(@CurrentUser User u, @PathVariable Long userId) {
        boolean mine = views.regularBadgeList(u.id, u.id).stream().anyMatch(b -> userId.equals(b.get("userId")));
        if (!mine) throw ApiException.notFound("그분의 단골이 아니에요");
        remove(userId, u.id, u.id);
        return views.regularBadgeList(u.id, u.id);
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
