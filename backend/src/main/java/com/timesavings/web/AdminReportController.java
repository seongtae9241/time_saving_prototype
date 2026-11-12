package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import com.timesavings.service.FinanceModel.Adj;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 기능명세서 운영자 범위에서 새로 만든 API(기능 A-02·A-04·A-05·A-06·A-07·A-08·A-09·A-13·A-15·A-16).
 * /api/admin/** 는 ADMIN 만(인터셉터). 숫자·예측은 코드가 계산하고 AI 는 해석·문장만. 게시·전달·승인은 운영자가 버튼으로.
 */
@RestController
@RequestMapping("/api")
public class AdminReportController {
    private final FinanceForecastService finance;
    private final AdminReportService reportSvc;
    private final WeatherAlertService weather;
    private final CategoryRegistry categories;
    private final NoticeRepo notices;
    private final CategorySuggestionRepo suggestions;
    private final ReportRepo reports;
    private final UserRepo users;
    private final RequestRepo requests;
    private final MessageRepo messages;
    private final PostRepo posts;
    private final NotificationService noti;

    public AdminReportController(FinanceForecastService finance, AdminReportService reportSvc, WeatherAlertService weather, CategoryRegistry categories,
                                 NoticeRepo notices, CategorySuggestionRepo suggestions, ReportRepo reports, UserRepo users, RequestRepo requests,
                                 MessageRepo messages, PostRepo posts, NotificationService noti) {
        this.finance = finance; this.reportSvc = reportSvc; this.weather = weather; this.categories = categories; this.notices = notices;
        this.suggestions = suggestions; this.reports = reports; this.users = users; this.requests = requests; this.messages = messages;
        this.posts = posts; this.noti = noti;
    }

    public record SimBody(String text, List<Adj> adjustments) {}
    public record AlertBody(String type, String level, List<String> areas, LocalDate startDate, LocalDate endDate, String memo) {}
    public record PinBody(Boolean pinned) {}
    public record AdoptBody(Level level, String answer) {}

    // ---------------- 기능 A-05 시간 은행 재정 추계 ----------------
    @GetMapping("/admin/forecast")
    public Map<String, Object> forecast() { return finance.forecast(); }

    /** 이번 달 월간 요약. 저장된 것이 있으면 그대로 돌려주고, 없거나 refresh=true 면 다시 만들어 저장한다. */
    @PostMapping("/admin/forecast/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "false") boolean refresh) { return finance.monthly(refresh); }

    @GetMapping("/admin/forecast/summaries")
    public List<Map<String, Object>> summaries() { return finance.summaries(); }

    // ---------------- 기능 A-06 정책 시뮬레이션 ----------------
    @PostMapping("/admin/forecast/simulate")
    public Map<String, Object> simulate(@RequestBody SimBody b) {
        boolean manual = b != null && b.adjustments() != null && !b.adjustments().isEmpty();
        if (!manual && (b == null || b.text() == null || b.text().isBlank())) throw ApiException.bad("TEXT_REQUIRED", "바꿔 볼 조건을 적어 주세요");
        if (!manual && b.text().length() > 300) throw ApiException.bad("TEXT_TOO_LONG", "조건은 300자 안으로 적어 주세요");
        return finance.simulate(b.text(), b.adjustments());
    }

    // ---------------- 기능 A-02 · A-04 · A-07 ----------------
    @GetMapping("/admin/stats/types")
    public Map<String, Object> types() { return reportSvc.types(); }

    @GetMapping("/admin/stats/season")
    public Map<String, Object> season() { return reportSvc.season(); }

    @GetMapping("/admin/recruit-drafts")
    public Map<String, Object> recruit() { return reportSvc.recruit(); }

    // ---------------- 기능 A-08 · A-09 특보 ----------------
    @GetMapping("/admin/weather/alerts")
    public Map<String, Object> alerts() { return weather.list(); }

    @PostMapping("/admin/weather/alerts")
    public Map<String, Object> addAlert(@RequestBody AlertBody b) {
        return weather.view(weather.create(b.type(), b.level(), b.areas(), b.startDate(), b.endDate(), b.memo(), "MANUAL"));
    }

    @PostMapping("/admin/weather/alerts/sample")
    public Map<String, Object> sampleAlert() { return weather.view(weather.sample()); }

    @PostMapping("/admin/weather/fetch")
    public Map<String, Object> fetchAlerts() { return weather.fetch(); }

    @PostMapping("/admin/weather/alerts/{id}/refresh")
    public Map<String, Object> refreshAlert(@PathVariable Long id) { return weather.refresh(id); }

    @PostMapping("/admin/weather/alerts/{id}/send")
    public Map<String, Object> sendAlert(@PathVariable Long id) { return weather.send(id); }

    @DeleteMapping("/admin/weather/alerts/{id}")
    public Map<String, Object> delAlert(@PathVariable Long id) { weather.delete(id); return Views.map("ok", true); }

    // ---------------- 기능 A-16 공지 고정 ----------------
    @PostMapping("/admin/notices/{id}/pin")
    @Transactional
    public Notice pin(@PathVariable Long id, @RequestBody(required = false) PinBody b) {
        Notice n = notices.findById(id).orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요"));
        n.pinned = b == null || b.pinned() == null ? !Boolean.TRUE.equals(n.pinned) : b.pinned();
        return notices.save(n);
    }

    // ---------------- 기능 A-15 카테고리 ----------------
    /** 공고 카테고리 전체(기본 6종 + 운영자가 건의를 승인해 더한 것). 로그인한 누구나. */
    @GetMapping("/categories")
    public List<Map<String, Object>> categoryList() { return categories.list(); }

    /** 건의 승인: 같은 이름의 건의를 모두 '반영'으로 바꾸고, 고른 등급으로 공고 카테고리에 더하고, 건의자에게 알린다. */
    @PostMapping("/admin/category-suggestions/{id}/adopt")
    @Transactional
    public Map<String, Object> adopt(@CurrentUser User admin, @PathVariable Long id, @RequestBody(required = false) AdoptBody b) {
        CategorySuggestion s = suggestions.findById(id).orElseThrow(() -> ApiException.notFound("건의를 찾을 수 없어요"));
        Level lv = b == null || b.level() == null ? Level.BASIC : b.level();
        Map<String, Object> cat = categories.add(s.name, lv);
        String ans = b == null || b.answer() == null || b.answer().isBlank()
                ? "건의해 주신 '" + s.name + "' 종류를 공고 카테고리에 더했어요(" + Rules.levelKo(lv) + " 등급). 이제 공고를 올릴 때 고를 수 있어요." : b.answer().trim();
        if (ans.length() > 500) throw ApiException.bad("ANSWER_TOO_LONG", "답변은 500자 안으로 적어 주세요");
        int n = 0;
        for (CategorySuggestion x : suggestions.findByNameKey(s.nameKey)) {
            x.status = "ADOPTED"; x.answer = ans; x.answeredBy = admin.id; x.answeredAt = LocalDateTime.now();
            suggestions.save(x);
            noti.send(x.userId, "CATEGORY_SUGGESTION", "카테고리 건의 '" + x.name + "' · 반영", ans,
                    users.findById(x.userId).map(w -> w.role == Role.ORG ? "/o/my" : "/my/suggest").orElse("/my/suggest"));
            n++;
        }
        return Views.map("ok", true, "updated", n, "status", "ADOPTED", "category", cat);
    }

    @DeleteMapping("/admin/categories/{name}")
    public Map<String, Object> removeCategory(@PathVariable String name) { categories.remove(name); return Views.map("ok", true); }

    // ---------------- 기능 A-13 신고 관련 기록 ----------------
    private Long targetUser(Report r) {
        if (r.targetId == null) return null;
        return switch (r.targetType) {
            case USER -> r.targetId;
            case MESSAGE -> messages.findById(r.targetId).map(m -> m.senderId).orElse(null);
            case POST -> posts.findById(r.targetId).map(p -> p.ownerId).orElse(null);
            case REQUEST -> requests.findById(r.targetId).map(q -> r.reporterId.equals(q.volunteerId) ? q.recipientId : q.volunteerId).orElse(null);
        };
    }

    /** 신고 처리 전에 운영자가 볼 관련 기록: 신고 대상의 경고·정지·지난 신고, 관련 활동, 그 활동에서 오간 메시지(최근 10개), 두 사람 사이 활동 수. */
    @GetMapping("/admin/report-context/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> reportContext(@PathVariable Long id) {
        Report r = reports.findById(id).orElseThrow(() -> ApiException.notFound("신고를 찾을 수 없어요"));
        Map<String, Object> out = Views.map("reportId", r.id);
        Long t = r.welfare() ? null : targetUser(r);
        User tu = t == null ? null : users.findById(t).orElse(null);
        List<Report> all = reports.findAll();
        if (tu != null) {
            out.put("target", Views.map("id", tu.id, "name", tu.name, "role", tu.role, "status", tu.status, "banned", Boolean.TRUE.equals(tu.banned),
                    "warningCount", tu.warningCount, "penaltyCount", tu.penaltyCount, "createdAt", tu.createdAt,
                    "doneAsVolunteer", requests.findAll().stream().filter(q -> tu.id.equals(q.volunteerId) && q.status == ReqStatus.DONE).count()));
            out.put("pastReports", all.stream().filter(x -> !x.id.equals(r.id) && !x.welfare() && tu.id.equals(targetUser(x)))
                    .sorted(Comparator.comparing((Report x) -> -x.id)).limit(10)
                    .map(x -> Views.map("id", x.id, "type", x.type, "status", x.status, "action", x.action, "result", x.result, "createdAt", x.createdAt)).toList());
            out.put("betweenDone", requests.findAll().stream().filter(q -> q.status == ReqStatus.DONE
                    && ((tu.id.equals(q.volunteerId) && r.reporterId.equals(q.recipientId)) || (tu.id.equals(q.recipientId) && r.reporterId.equals(q.volunteerId)))).count());
        } else {
            out.put("target", null);
            out.put("pastReports", List.of());
        }
        out.put("reporterReportCount", all.stream().filter(x -> x.reporterId.equals(r.reporterId)).count());
        Long reqId = r.targetId == null ? null : r.targetType == ReportTarget.REQUEST ? r.targetId
                : r.targetType == ReportTarget.MESSAGE ? messages.findById(r.targetId).map(m -> m.requestId).orElse(null) : null;
        Request q = reqId == null ? null : requests.findById(reqId).orElse(null);
        if (q != null) {
            out.put("request", Views.map("id", q.id, "date", q.date == null ? null : q.date.toString(), "category", q.category, "status", q.status, "hours", q.hours,
                    "content", q.content, "logText", q.logText, "checkInAt", q.checkInAt, "checkOutAt", q.checkOutAt));
            List<Message> ms = messages.findByRequestIdOrderByIdAsc(q.id);
            out.put("messages", ms.subList(Math.max(0, ms.size() - 10), ms.size()).stream().map(m -> Views.map("id", m.id, "sender", m.systemMsg ? "시스템" : m.senderLabel,
                    "byTarget", tu != null && tu.id.equals(m.senderId), "text", m.text, "warn", m.warn, "createdAt", m.createdAt,
                    "reported", r.targetType == ReportTarget.MESSAGE && m.id.equals(r.targetId))).toList());
        }
        if (r.targetType == ReportTarget.POST && r.targetId != null) posts.findById(r.targetId).ifPresent(p -> out.put("post",
                Views.map("id", p.id, "title", p.title, "detail", p.detail, "status", p.status, "area", p.area, "categories", p.categories)));
        return out;
    }
}
