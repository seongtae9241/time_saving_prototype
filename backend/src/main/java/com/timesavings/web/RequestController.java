package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** 요청·제안·활동(계약서 5.4). */
@RestController
@RequestMapping("/api")
@Transactional
public class RequestController {
    private final RequestService svc;
    private final RequestRepo repo;
    private final ElderRepo elders;
    private final Views views;

    public RequestController(RequestService svc, RequestRepo repo, ElderRepo elders, Views views) {
        this.svc = svc; this.repo = repo; this.elders = elders; this.views = views;
    }

    public record CreateBody(Kind kind, Long postId, Long volunteerId, Long elderId, LocalDate date, Double startHour, Double hours,
                             String category, String content, String memo) {}
    public record ReasonBody(String reason) {}
    public record CodeBody(String code, String method) {}
    public record LogBody(String raw, String text, String photoUrl, Boolean photoConsent, String careMemo) {}
    public record AdjustBody(Double hours, String reason) {}
    public record AnswerBody(Boolean accept) {}
    public record RateBody(Integer stars, List<String> tags, String comment) {}
    public record AppealBody(String reason, Level requestedLevel) {}
    public record TextBody(String text) {}

    private Map<String, Object> view(Request r, User u) {
        Map<String, Object> v = views.requestView(r, u);
        v.put("messages", views.messagesOf(r.id));
        v.put("canMessage", svc.canMessage(r) && views.roleOf(r, u) != null);
        return v;
    }

    /** box 필터. 사용자: 내가 봉사자·받는 사람·보낸 사람인 것. */
    public static boolean inBox(Request r, User u, String box, String role) {
        if (role == null) return false;
        return switch (box == null ? "all" : box) {
            case "received" -> !u.id.equals(r.senderId) && r.status.pending();
            case "sent" -> u.id.equals(r.senderId);
            case "active" -> r.status == ReqStatus.VOL_ACCEPTED || r.status.active();
            case "done" -> r.status.terminal();
            default -> true;
        };
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) String box) {
        List<Map<String, Object>> out = new ArrayList<>();
        repo.findAll().stream().sorted(Comparator.comparing((Request r) -> r.id).reversed()).forEach(r -> {
            boolean party = u.id.equals(r.volunteerId) || u.id.equals(r.recipientId) || u.id.equals(r.senderId);
            if (!party) return;
            String role = views.roleOf(r, u);
            if (inBox(r, u, box, role)) out.add(views.requestView(r, u));
        });
        return out;
    }

    @PostMapping("/requests")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody CreateBody b) {
        Request r = svc.create(u, new RequestService.CreateCmd(b.kind(), b.postId(), b.volunteerId(), b.elderId(), b.date(),
                b.startHour(), b.hours(), b.category(), b.content(), b.memo()));
        return view(r, u);
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) {
        Request r = svc.get(id);
        svc.requireRole(r, u);
        return view(r, u);
    }

    @PostMapping("/requests/{id}/accept")
    public Map<String, Object> accept(@CurrentUser User u, @PathVariable Long id) { return view(svc.accept(u, id), u); }

    @PostMapping("/requests/{id}/reject")
    public Map<String, Object> reject(@CurrentUser User u, @PathVariable Long id, @RequestBody ReasonBody b) { return view(svc.reject(u, id, b.reason()), u); }

    @PostMapping("/requests/{id}/decline")
    public Map<String, Object> decline(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) ReasonBody b) {
        return view(svc.decline(u, id, b == null ? null : b.reason()), u);
    }

    @PostMapping("/requests/{id}/cancel")
    public Map<String, Object> cancel(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) ReasonBody b) {
        return view(svc.cancel(u, id, b == null ? null : b.reason()), u);
    }

    @PostMapping("/requests/{id}/noshow")
    public Map<String, Object> noshow(@CurrentUser User u, @PathVariable Long id) { return view(svc.noshow(u, id), u); }

    @PostMapping("/requests/{id}/check-in")
    public Map<String, Object> checkIn(@CurrentUser User u, @PathVariable Long id, @RequestBody CodeBody b) {
        return view(svc.checkIn(u, id, b.code(), b.method()), u);
    }

    @PostMapping("/requests/{id}/check-out")
    public Map<String, Object> checkOut(@CurrentUser User u, @PathVariable Long id, @RequestBody CodeBody b) {
        return view(svc.checkOut(u, id, b.code(), b.method()), u);
    }

    @PostMapping("/requests/{id}/log")
    public Map<String, Object> log(@CurrentUser User u, @PathVariable Long id, @RequestBody LogBody b) {
        return view(svc.submitLog(u, id, b.raw(), b.text(), b.photoUrl(), Boolean.TRUE.equals(b.photoConsent()), b.careMemo()), u);
    }

    @PostMapping("/requests/{id}/confirm")
    public Map<String, Object> confirm(@CurrentUser User u, @PathVariable Long id) { return view(svc.confirm(u, id), u); }

    @PostMapping("/requests/{id}/adjust")
    public Map<String, Object> adjust(@CurrentUser User u, @PathVariable Long id, @RequestBody AdjustBody b) {
        return view(svc.adjust(u, id, b.hours(), b.reason()), u);
    }

    @PostMapping("/requests/{id}/adjust/answer")
    public Map<String, Object> adjustAnswer(@CurrentUser User u, @PathVariable Long id, @RequestBody AnswerBody b) {
        return view(svc.adjustAnswer(u, id, Boolean.TRUE.equals(b.accept())), u);
    }

    @PostMapping("/requests/{id}/rate")
    public Map<String, Object> rate(@CurrentUser User u, @PathVariable Long id, @RequestBody RateBody b) {
        Rating x = svc.rate(u, id, b.stars() == null ? 0 : b.stars(), b.tags(), b.comment());
        return Views.map("id", x.id, "stars", x.stars, "tags", x.tags, "isPrivate", x.isPrivate);
    }

    @PostMapping("/requests/{id}/appeal")
    public Map<String, Object> appeal(@CurrentUser User u, @PathVariable Long id, @RequestBody AppealBody b) {
        DifficultyAppeal a = svc.appeal(u, id, b.reason(), b.requestedLevel());
        return Views.map("id", a.id, "status", a.status, "requestedLevel", a.requestedLevel);
    }

    @GetMapping("/requests/{id}/messages")
    public Map<String, Object> messages(@CurrentUser User u, @PathVariable Long id) {
        Request r = svc.get(id);
        svc.requireRole(r, u);
        return Views.map("messages", views.messagesOf(id), "canMessage", svc.canMessage(r),
                "notice", "연락처 교환·금전 요구는 금지예요. 불편한 메시지는 바로 신고해 주세요.");
    }

    @PostMapping("/requests/{id}/messages")
    public Map<String, Object> send(@CurrentUser User u, @PathVariable Long id, @RequestBody TextBody b) {
        Message m = svc.sendMessage(u, id, b.text());
        return Views.map("id", m.id, "text", m.text, "warn", m.warn, "senderLabel", m.senderLabel, "createdAt", m.createdAt);
    }

    @GetMapping("/regulars")
    public List<Map<String, Object>> regulars(@CurrentUser User u, @RequestParam(required = false) Long elderId) {
        if (elderId != null) {
            if (!views.isManagerOf(u, elderId)) throw ApiException.forbidden("내가 담당하는 대상만 볼 수 있어요");
            return views.regularsOf(null, elderId);
        }
        return views.regularsOf(u.id, null);
    }
}
