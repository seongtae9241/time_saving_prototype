package com.timesavings.web;

import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Post;
import com.timesavings.domain.User;
import com.timesavings.service.PostService;
import com.timesavings.service.RecurringService;
import com.timesavings.service.Views;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기능 U-09 정기 요청: 반복 주기(매주 요일·시간)와 기간을 정하면 회차마다 도움 요청 공고가 자동으로 생긴다.
 * 목록(다음 회차·남은 회차), 일시정지·다시 시작·해지. 담당자는 elderId 로 관리 대상 대리 정기 요청을 등록한다.
 */
@RestController
@RequestMapping("/api/recurring")
public class RecurringController {
    private final RecurringService svc;
    private final PostService posts;
    private final Views views;

    public RecurringController(RecurringService svc, PostService posts, Views views) {
        this.svc = svc; this.posts = posts; this.views = views;
    }

    /**
     * weekday: 1=월 … 7=일 (없으면 startDate 의 요일). 기간은 weeks(회차 수 2~26) 또는 endDate.
     * forUserId: 연결된 가족 대신. elderId·elderIssue: 담당자의 관리 대상 대리(이슈는 300자, 연락처·주소·병명 없음).
     */
    public record Body(Integer weekday, LocalDate startDate, LocalDate endDate, Integer weeks, Double startHour, Double endHour, Double estHours,
                       String area, List<String> categories, String title, String detail, String note, Long elderId, Long forUserId, String elderIssue) {}

    private static String cleanIssue(Body b) {
        if (b.elderIssue() == null || b.elderIssue().isBlank()) return null;
        String t = b.elderIssue().trim();
        if (b.elderId() == null) throw ApiException.bad("ISSUE_ONLY_FOR_ELDER", "관리 대상 이슈는 담당자가 대신 올리는 공고에만 적을 수 있어요");
        if (t.length() > 300) throw ApiException.bad("ISSUE_TOO_LONG", "관리 대상 이슈는 300자 안으로 적어 주세요");
        if (!VoiceSafety.scrub(t).removed().isEmpty())
            throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요. 방문할 때 알아야 할 주의사항만 적어 주세요");
        return VoiceSafety.sanitizeCaution(t);
    }

    @GetMapping
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) Long elderId) {
        return svc.listFor(u, elderId, LocalDate.now());
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@CurrentUser User u, @PathVariable Long id) {
        var r = svc.get(id);
        if (!svc.canManage(r, u)) throw ApiException.forbidden("내 정기 요청만 볼 수 있어요");
        return svc.view(r, u, LocalDate.now());
    }

    @PostMapping
    public Map<String, Object> create(@CurrentUser User u, @RequestBody Body b) {
        if (b == null) throw ApiException.bad("INVALID", "정기 요청 내용을 다시 확인해 주세요");
        RecurringService.Created c = svc.create(u, new RecurringService.Cmd(b.weekday(), b.startDate(), b.endDate(), b.weeks(), b.startHour(), b.endHour(),
                b.estHours(), b.area(), b.categories(), b.title(), b.detail(), b.note(), b.elderId(), b.forUserId(), cleanIssue(b)), LocalDateTime.now());
        return withPosts(svc.view(c.rule(), u, LocalDate.now()), c.posts(), u);
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@CurrentUser User u, @PathVariable Long id) {
        return svc.view(svc.pause(u, id), u, LocalDate.now());
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@CurrentUser User u, @PathVariable Long id) {
        RecurringService.Created c = svc.resume(u, id, LocalDateTime.now());
        return withPosts(svc.view(c.rule(), u, LocalDate.now()), c.posts(), u);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@CurrentUser User u, @PathVariable Long id) { return svc.cancel(u, id, LocalDate.now()); }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@CurrentUser User u, @PathVariable Long id) { return svc.cancel(u, id, LocalDate.now()); }

    /** 방금 만든 회차 공고(7일 안 회차가 있을 때만)를 응답에 싣는다. */
    private Map<String, Object> withPosts(Map<String, Object> view, List<Post> made, User u) {
        List<Long> ids = new ArrayList<>();
        for (Post p : made) ids.add(p.id);
        view.put("createdPostIds", ids);
        view.put("createdCount", ids.size());
        if (!made.isEmpty()) view.put("firstPost", posts.decorate(views.postCard(made.get(0), u), made.get(0), u));
        return view;
    }
}
