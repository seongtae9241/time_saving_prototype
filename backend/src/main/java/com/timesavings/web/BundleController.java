package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Enums.Kind;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.Post;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.repo.PostRepo;
import com.timesavings.service.BundleService;
import com.timesavings.service.MatchReasonService;
import com.timesavings.service.RequestService;
import com.timesavings.service.Views;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 기능 U-12 빈 시간 묶음 추천: 날짜와 가능한 시간 범위를 넣으면 겹치지 않고 가까운 도움 요청 공고 2~3건을 묶어 추천하고,
 * "한 번에 제안"으로 공고마다 봉사 제안을 차례로 보낸다(공고마다 보통 제안과 같은 규칙·알림·24시간 기한).
 * 이 컨트롤러는 트랜잭션을 걸지 않는다: 제안 한 건이 실패해도 앞서 보낸 제안은 그대로 남는다.
 */
@RestController
@RequestMapping("/api/bundles")
public class BundleController {
    private final BundleService bundles;
    private final RequestService requests;
    private final PostRepo posts;
    private final Views views;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private MatchReasonService matchReasons;

    public BundleController(BundleService bundles, RequestService requests, PostRepo posts, Views views) {
        this.bundles = bundles; this.requests = requests; this.posts = posts; this.views = views;
    }

    /** lat·lng: 지금 위치(선택). 출발 동을 정하는 데만 쓰고 저장하지 않는다. */
    public record RecommendBody(LocalDate date, Double fromHour, Double toHour, Double lat, Double lng) {}
    public record Item(Long postId, Double startHour, Double hours) {}
    public record ProposeBody(List<Item> items, String memo) {}

    @PostMapping("/recommend")
    public Map<String, Object> recommend(@CurrentUser User u, @RequestBody RecommendBody b) {
        if (b == null) throw ApiException.bad("INVALID", "날짜와 시간을 정해 주세요");
        return bundles.recommend(u, b.date(), b.fromHour(), b.toHour(), b.lat(), b.lng());
    }

    /**
     * 한 번에 제안: 묶음의 공고마다 봉사 제안(PROPOSAL)을 시간 순서대로 보낸다.
     * 보내기 전에 묶음 안에서 시간이 겹치지 않는지만 먼저 본다(겹치면 아무것도 보내지 않고 400).
     * 일부가 실패하면 어느 것이 됐고 안 됐는지를 results 에 건별로 돌려준다(HTTP 200).
     */
    @PostMapping("/propose")
    public Map<String, Object> propose(@CurrentUser User u, @RequestBody ProposeBody b) {
        if (u.role != Role.USER) throw ApiException.forbidden("봉사하는 사용자만 제안할 수 있어요");
        if (b == null || b.items() == null || b.items().isEmpty()) throw ApiException.bad("ITEMS_REQUIRED", "제안할 공고를 골라 주세요");
        if (b.items().size() > 3) throw ApiException.bad("TOO_MANY", "한 번에 3건까지 제안할 수 있어요");
        List<Item> items = new ArrayList<>(b.items());
        for (Item it : items)
            if (it == null || it.postId() == null || it.startHour() == null || it.hours() == null) throw ApiException.bad("INVALID_ITEM", "공고와 시간을 다시 확인해 주세요");
        if (items.stream().map(Item::postId).distinct().count() != items.size()) throw ApiException.bad("DUPLICATE", "같은 공고가 두 번 들어 있어요");
        items.sort(Comparator.comparingDouble(Item::startHour));
        LocalDate day = null;
        for (int i = 0; i < items.size(); i++) {
            Post p = posts.findById(items.get(i).postId()).orElseThrow(() -> ApiException.notFound("공고를 찾을 수 없어요"));
            if (day == null) day = p.date;
            else if (!day.equals(p.date)) throw ApiException.bad("DIFFERENT_DAY", "같은 날짜의 공고만 한 번에 제안할 수 있어요");
            if (i > 0 && items.get(i).startHour() < items.get(i - 1).startHour() + items.get(i - 1).hours() - 1e-9)
                throw ApiException.bad("TIME_OVERLAP", "묶음 안에서 시간이 겹쳐요. 다시 추천받아 주세요");
        }
        String memo = b.memo() == null || b.memo().isBlank() ? null : b.memo().trim();
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        for (Item it : items) {
            Post p = posts.findById(it.postId()).orElse(null);
            String title = p == null ? null : p.title;
            try {
                // 공고마다 기존 제안 만들기와 같은 길: 검사·알림·24시간 기한이 그대로다
                Request r = requests.create(u, new RequestService.CreateCmd(Kind.PROPOSAL, it.postId(), null, null, null, it.startHour(), it.hours(),
                        null, title == null || title.isBlank() ? "봉사를 제안해요" : title, memo));
                if (matchReasons != null) { try { matchReasons.fill(r); } catch (RuntimeException ignore) { /* 이유 문장은 없어도 된다 */ } }
                ok++;
                results.add(Views.map("postId", it.postId(), "title", title, "ok", true, "requestId", r.id, "startHour", r.startHour, "hours", r.hours,
                        "deadlineAt", r.deadlineAt));
            } catch (ApiException e) {
                results.add(Views.map("postId", it.postId(), "title", title, "ok", false, "code", e.code, "error", e.getMessage()));
            }
        }
        int fail = results.size() - ok;
        return Views.map("results", results, "okCount", ok, "failCount", fail,
                "message", fail == 0 ? ok + "건 모두 제안을 보냈어요. 상대가 24시간 안에 수락·거절해요."
                        : ok == 0 ? "제안을 보내지 못했어요. 아래 까닭을 확인해 주세요."
                        : ok + "건은 보냈고 " + fail + "건은 보내지 못했어요. 아래에서 확인해 주세요.");
    }
}
