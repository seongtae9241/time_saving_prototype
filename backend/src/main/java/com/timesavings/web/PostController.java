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

    /**
     * 예전 자동 반복 등록의 칸. 41판 B-06 에서 자동 반복을 없앴다: 어떤 모양("매주 목요일" 글자, {weekly, weeks} 객체, true)으로 와도
     * 400 을 내지 않고 받아서 무시하고, 공고는 한 건만 올린다. 반복을 바랐는지만 기억해 "다시 올리기" 안내를 돌려준다.
     */
    public record Repeat(boolean asked) {
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Repeat from(com.fasterxml.jackson.databind.JsonNode n) {
            if (n == null || n.isNull()) return null;
            if (n.isTextual()) return new Repeat(!n.asText().isBlank());
            if (n.isBoolean()) return new Repeat(n.asBoolean());
            if (n.isObject()) return new Repeat(n.path("weekly").asBoolean(false));
            return new Repeat(false);
        }
    }
    /** forUserId: 연결된 가족 대신 올릴 때 그 가족의 회원 번호(B-57). */
    public record PostBody(PostType type, LocalDate date, Double startHour, Double endHour, String area, List<String> categories,
                           String title, String detail, String note, Double estHours, Repeat repeat, Long elderId,
                           Level difficulty, String difficultyReason, String elderIssue, Long forUserId) {
        PostService.PostCmd cmd() {
            return new PostService.PostCmd(type, date, startHour, endHour, area, categories, title, detail, note, estHours,
                    false, 1, elderId, difficulty, difficultyReason);
        }
    }
    public record RepostBody(LocalDate date) {}
    /** dateFrom·dateTo: "YYYY-MM-DD", 빈 글자면 그 조건을 지운다(B-21 날짜 조건). */
    public record AlertBody(String area, List<String> categories, String dayType, Boolean enabled, String dateFrom, String dateTo) {}

    /** lat·lng: 가까운 순 정렬에 쓸 현재 위치(B-59). 거리 계산에만 쓰고 저장하지 않는다. dateFrom·dateTo: 날짜 범위 필터(B-20). */
    @GetMapping("/posts")
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) PostType type,
                                          @RequestParam(required = false) String area,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam(required = false) String cat, @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                          @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        if ((lat != null || lng != null) && !Geo.valid(lat, lng)) throw ApiException.bad("INVALID_LOCATION", "위치 값이 맞지 않아요");
        List<Map<String, Object>> list = posts.list(u, type, area, date, cat, sort, dateFrom, dateTo, lat, lng);
        if (matchReasons != null) matchReasons.decorateCards(u, list);
        return list;
    }

    /**
     * 내가 올린 공고(지난 것 포함, 다시 올리기 목록으로도 쓴다 B-06). 내 공고 + 담당 독거노인 공고 + 내가 가족 대신 올린 공고(proxyMine).
     * elderId 를 주면 그 대상의 공고만(담당자의 "지난 요청 다시 올리기").
     */
    @GetMapping("/posts/mine")
    public List<Map<String, Object>> mine(@CurrentUser User u, @RequestParam(required = false) Long elderId) {
        Set<Long> myElders = new HashSet<>();
        if (u.role == Role.ORG) elders.findByManagerId(u.id).forEach(e -> myElders.add(e.id));
        return postRepo.findAll().stream()
                .filter(p -> p.status != PostStatus.DELETED && (u.id.equals(p.ownerId) || posts.isProxyAuthor(p, u) || (p.elderId != null && myElders.contains(p.elderId))))
                .filter(p -> elderId == null || elderId.equals(p.elderId))
                .sorted(Comparator.comparing((Post p) -> p.date).reversed())
                .map(p -> withRequestCount(posts.decorate(views.postCard(p, u), p, u), p)).toList();
    }

    private Map<String, Object> withRequestCount(Map<String, Object> card, Post p) {
        card.put("requestCount", requests.findByPostId(p.id).stream().filter(r -> r.status.pending()).count());
        return card;
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) {
        Post p = posts.get(id);
        if (p.status == PostStatus.DELETED) throw ApiException.notFound("삭제된 공고예요");
        Map<String, Object> card = posts.decorate(views.postCard(p, u), p, u);
        if (matchReasons != null) matchReasons.decorateCards(u, List.of(card));
        if (unmatched != null && posts.canEdit(p, u) && p.status == PostStatus.OPEN) card.put("fixTips", unmatched.tipsOf(p));
        if (posts.canSeeRequests(p, u)) {
            // 대신 올린 사람(B-57)은 진행 상황을 읽기만 한다: requestView 의 actions 가 비어 있고, 수락·거부 API 도 가족 본인만 통과한다
            card.put("requests", requests.findByPostId(p.id).stream().sorted(Comparator.comparing((Request r) -> r.id).reversed())
                    .map(r -> views.requestView(r, u)).toList());
        } else {
            card.put("requests", List.of());
            card.put("myRequest", requests.findByPostId(p.id).stream().filter(r -> u.id.equals(r.senderId))
                    .reduce((a, b) -> b).map(r -> views.requestView(r, u)).orElse(null));
        }
        return card;
    }

    /** 이 주소(POST /api/posts)는 늘 한 건만 올린다. 반복을 바란 요청이면 정기 요청(기능 U-09, POST /api/recurring)과 다시 올리기를 안내한다. */
    public static final String REPEAT_NOTICE = "이번 한 번만 올렸어요. 매주 올리려면 '정기 요청'으로 등록해 주세요. 한 번씩 올릴 때는 '다시 올리기'에서 날짜만 고르면 돼요.";

    @PostMapping("/posts")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody PostBody b) {
        String issue = cleanIssue(b);
        Post p = posts.create(u, b.cmd(), b.forUserId()).get(0);
        if (issue != null && p.elderId != null) { p.issueNote = issue; postRepo.save(p); }
        Map<String, Object> first = posts.decorate(views.postCard(p, u), p, u);
        first.put("createdCount", 1);
        first.put("createdIds", List.of(p.id));
        if (b.repeat() != null && b.repeat().asked()) first.put("repeatNotice", REPEAT_NOTICE);
        return first;
    }

    /** B-06 다시 올리기: 지난 공고를 같은 내용으로, 날짜만 골라 새 공고 1건으로 올린다. 담당자는 담당 독거노인의 지난 요청에 같은 API 를 쓴다. */
    @PostMapping("/posts/{id}/repost")
    public Map<String, Object> repost(@CurrentUser User u, @PathVariable Long id, @RequestBody RepostBody b) {
        Post p = posts.repost(u, id, b == null ? null : b.date());
        postRepo.save(p);
        Map<String, Object> card = posts.decorate(views.postCard(p, u), p, u);
        card.put("repostOf", id);
        return card;
    }

    @PutMapping("/posts/{id}")
    public Map<String, Object> update(@CurrentUser User u, @PathVariable Long id, @RequestBody PostBody b) {
        String issue = cleanIssue(b);
        Post p = posts.update(u, id, b.cmd());
        if (b.elderIssue() != null && p.elderId != null) { p.issueNote = issue; postRepo.save(p); }
        return posts.decorate(views.postCard(p, u), p, u);
    }

    /** 담당자 대리 공고의 "관리 대상 이슈": 300자, 연락처·상세 주소·병명은 받지 않는다(대상 등록과 같은 규칙). 빈 값이면 null(대상 기본 주의사항 사용). */
    private static String cleanIssue(PostBody b) {
        if (b.elderIssue() == null || b.elderIssue().isBlank()) return null;
        String t = b.elderIssue().trim();
        if (b.elderId() == null) throw ApiException.bad("ISSUE_ONLY_FOR_ELDER", "관리 대상 이슈는 담당자가 대신 올리는 공고에만 적을 수 있어요");
        if (t.length() > 300) throw ApiException.bad("ISSUE_TOO_LONG", "관리 대상 이슈는 300자 안으로 적어 주세요");
        if (!com.timesavings.ai.VoiceSafety.scrub(t).removed().isEmpty())
            throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요. 방문할 때 알아야 할 주의사항만 적어 주세요");
        return com.timesavings.ai.VoiceSafety.sanitizeCaution(t); // 병명이 보이면 규칙 문장으로 바꾼다
    }

    @DeleteMapping("/posts/{id}")
    public Map<String, Object> delete(@CurrentUser User u, @PathVariable Long id) {
        posts.delete(u, id);
        return Views.map("ok", true);
    }

    // ---------------- 관심 조건 알림 (B-21) ----------------
    @GetMapping("/interest-alerts")
    public List<InterestAlert> alerts(@CurrentUser User u) { return alerts.findByUserId(u.id); }

    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper om;

    /** 날짜 칸은 빈 글자("")나 null 을 보내면 지운다(칸을 아예 안 보내면 그대로 둔다). */
    private InterestAlert apply(InterestAlert a, com.fasterxml.jackson.databind.JsonNode j) {
        AlertBody b;
        try { b = om.treeToValue(j, AlertBody.class); } catch (Exception e) { throw ApiException.bad("INVALID", "조건을 다시 확인해 주세요"); }
        if (b == null) throw ApiException.bad("INVALID", "조건을 다시 확인해 주세요");
        if (j.has("dateFrom") && j.get("dateFrom").isNull()) a.dateFrom = null;
        if (j.has("dateTo") && j.get("dateTo").isNull()) a.dateTo = null;
        if (b.area() != null) a.area = b.area();
        if (b.categories() != null) a.categories = AuthController.clean(b.categories());
        if (b.dayType() != null) a.dayType = b.dayType();
        if (b.dateFrom() != null) a.dateFrom = day(b.dateFrom());
        if (b.dateTo() != null) a.dateTo = day(b.dateTo());
        if (a.dateFrom != null && a.dateTo != null && a.dateTo.isBefore(a.dateFrom)) throw ApiException.bad("INVALID_DATE", "끝 날짜가 시작 날짜보다 앞이에요");
        if (b.enabled() != null) a.enabled = b.enabled();
        return alerts.save(a);
    }

    private static LocalDate day(String s) {
        if (s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { throw ApiException.bad("INVALID_DATE", "날짜는 2026-11-20 처럼 적어 주세요"); }
    }

    @PostMapping("/interest-alerts")
    public InterestAlert addAlert(@CurrentUser User u, @RequestBody com.fasterxml.jackson.databind.JsonNode b) {
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
    public InterestAlert putAlert(@CurrentUser User u, @PathVariable Long id, @RequestBody com.fasterxml.jackson.databind.JsonNode b) { return apply(mineAlert(u, id), b); }

    /** 계약서의 "PUT /api/interest-alerts"(id 없이) 도 받는다: 첫 조건을 고치거나 없으면 만든다. */
    @PutMapping("/interest-alerts")
    public InterestAlert putFirst(@CurrentUser User u, @RequestBody com.fasterxml.jackson.databind.JsonNode b) {
        InterestAlert a = alerts.findByUserId(u.id).stream().findFirst().orElseGet(() -> { InterestAlert x = new InterestAlert(); x.userId = u.id; return x; });
        return apply(a, b);
    }

    @DeleteMapping("/interest-alerts/{id}")
    public Map<String, Object> delAlert(@CurrentUser User u, @PathVariable Long id) {
        alerts.delete(mineAlert(u, id));
        return Views.map("ok", true);
    }
}
