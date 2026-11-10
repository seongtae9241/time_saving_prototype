package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** 공고(B-02~B-11, B-20, C-04)와 관심 조건 알림(B-21). */
@RestController
@RequestMapping("/api")
@Transactional
public class PostController {
    private final PostService posts;
    private final PostRepo postRepo;
    private final RequestRepo requests;
    private final ElderRepo elders;
    private final InterestAlertRepo alerts;
    private final Views views;
    /** LLM 추가 A(7.12): 봉사자 공고의 추천 이유(코드), 내 공고의 고치기 제안 */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private MatchReasonService matchReasons;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private UnmatchedPostService unmatched;

    public PostController(PostService posts, PostRepo postRepo, RequestRepo requests, ElderRepo elders, InterestAlertRepo alerts, Views views) {
        this.posts = posts; this.postRepo = postRepo; this.requests = requests; this.elders = elders; this.alerts = alerts; this.views = views;
    }

    public record Repeat(Boolean weekly, Integer weeks) {}
    public record PostBody(PostType type, LocalDate date, Double startHour, Double endHour, String area, List<String> categories,
                           String title, String detail, String note, Double estHours, Repeat repeat, Long elderId,
                           Level difficulty, String difficultyReason) {
        PostService.PostCmd cmd() {
            return new PostService.PostCmd(type, date, startHour, endHour, area, categories, title, detail, note, estHours,
                    repeat != null && Boolean.TRUE.equals(repeat.weekly()), repeat == null ? null : repeat.weeks(), elderId, difficulty, difficultyReason);
        }
    }
    public record AlertBody(String area, List<String> categories, String dayType, Boolean enabled) {}

    @GetMapping("/posts")
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) PostType type,
                                          @RequestParam(required = false) String area,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam(required = false) String cat, @RequestParam(required = false) String sort) {
        List<Map<String, Object>> list = posts.list(u, type, area, date, cat, sort);
        if (matchReasons != null) matchReasons.decorateCards(u, list);
        return list;
    }

    @GetMapping("/posts/mine")
    public List<Map<String, Object>> mine(@CurrentUser User u) {
        Set<Long> myElders = new HashSet<>();
        if (u.role == Role.ORG) elders.findByManagerId(u.id).forEach(e -> myElders.add(e.id));
        return postRepo.findAll().stream()
                .filter(p -> p.status != PostStatus.DELETED && (u.id.equals(p.ownerId) || (p.elderId != null && myElders.contains(p.elderId))))
                .sorted(Comparator.comparing((Post p) -> p.date).reversed())
                .map(p -> withRequestCount(views.postCard(p, u), p)).toList();
    }

    private Map<String, Object> withRequestCount(Map<String, Object> card, Post p) {
        card.put("requestCount", requests.findByPostId(p.id).stream().filter(r -> r.status.pending()).count());
        return card;
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) {
        Post p = posts.get(id);
        if (p.status == PostStatus.DELETED) throw ApiException.notFound("삭제된 공고예요");
        Map<String, Object> card = views.postCard(p, u);
        if (matchReasons != null) matchReasons.decorateCards(u, List.of(card));
        if (unmatched != null && posts.canEdit(p, u) && p.status == PostStatus.OPEN) card.put("fixTips", unmatched.tipsOf(p));
        if (posts.canEdit(p, u)) {
            card.put("requests", requests.findByPostId(p.id).stream().sorted(Comparator.comparing((Request r) -> r.id).reversed())
                    .map(r -> views.requestView(r, u)).toList());
        } else {
            card.put("requests", List.of());
            card.put("myRequest", requests.findByPostId(p.id).stream().filter(r -> u.id.equals(r.senderId))
                    .reduce((a, b) -> b).map(r -> views.requestView(r, u)).orElse(null));
        }
        return card;
    }

    @PostMapping("/posts")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody PostBody b) {
        List<Post> created = posts.create(u, b.cmd());
        Map<String, Object> first = views.postCard(created.get(0), u);
        first.put("createdCount", created.size());
        first.put("createdIds", created.stream().map(p -> p.id).toList());
        return first;
    }

    @PutMapping("/posts/{id}")
    public Map<String, Object> update(@CurrentUser User u, @PathVariable Long id, @RequestBody PostBody b) {
        return views.postCard(posts.update(u, id, b.cmd()), u);
    }

    @DeleteMapping("/posts/{id}")
    public Map<String, Object> delete(@CurrentUser User u, @PathVariable Long id) {
        posts.delete(u, id);
        return Views.map("ok", true);
    }

    // ---------------- 관심 조건 알림 (B-21) ----------------
    @GetMapping("/interest-alerts")
    public List<InterestAlert> alerts(@CurrentUser User u) { return alerts.findByUserId(u.id); }

    private InterestAlert apply(InterestAlert a, AlertBody b) {
        if (b.area() != null) a.area = b.area();
        if (b.categories() != null) a.categories = AuthController.clean(b.categories());
        if (b.dayType() != null) a.dayType = b.dayType();
        if (b.enabled() != null) a.enabled = b.enabled();
        return alerts.save(a);
    }

    @PostMapping("/interest-alerts")
    public InterestAlert addAlert(@CurrentUser User u, @RequestBody AlertBody b) {
        InterestAlert a = new InterestAlert();
        a.userId = u.id;
        return apply(a, b);
    }

    private InterestAlert mineAlert(User u, Long id) {
        InterestAlert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("알림 조건을 찾을 수 없어요"));
        if (!a.userId.equals(u.id)) throw ApiException.forbidden("내 알림 조건만 바꿀 수 있어요");
        return a;
    }

    @PutMapping("/interest-alerts/{id}")
    public InterestAlert putAlert(@CurrentUser User u, @PathVariable Long id, @RequestBody AlertBody b) { return apply(mineAlert(u, id), b); }

    /** 계약서의 "PUT /api/interest-alerts"(id 없이) 도 받는다: 첫 조건을 고치거나 없으면 만든다. */
    @PutMapping("/interest-alerts")
    public InterestAlert putFirst(@CurrentUser User u, @RequestBody AlertBody b) {
        InterestAlert a = alerts.findByUserId(u.id).stream().findFirst().orElseGet(() -> { InterestAlert x = new InterestAlert(); x.userId = u.id; return x; });
        return apply(a, b);
    }

    @DeleteMapping("/interest-alerts/{id}")
    public Map<String, Object> delAlert(@CurrentUser User u, @PathVariable Long id) {
        alerts.delete(mineAlert(u, id));
        return Views.map("ok", true);
    }
}
