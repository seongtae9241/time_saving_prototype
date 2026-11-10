package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 알림(A-07)·공지(A-10)·문의(B-48, B-49)·신고(B-36, B-37, B-47). */
@RestController
@RequestMapping("/api")
@Transactional
public class CommonController {
    private final NotificationRepo notis;
    private final NoticeRepo notices;
    private final InquiryRepo inquiries;
    private final ReportRepo reports;
    private final UserRepo users;
    private final NotificationService noti;
    @org.springframework.beans.factory.annotation.Autowired private com.timesavings.service.OpsSafetyService opsSafety;

    public CommonController(NotificationRepo notis, NoticeRepo notices, InquiryRepo inquiries, ReportRepo reports, UserRepo users, NotificationService noti) {
        this.notis = notis; this.notices = notices; this.inquiries = inquiries; this.reports = reports; this.users = users; this.noti = noti;
    }

    public record InquiryBody(String type, String title, String content) {}
    public record ReportBody(ReportTarget targetType, Long targetId, String targetLabel, String type, String content) {}

    @GetMapping("/notifications")
    public List<Notification> list(@CurrentUser User u) { return notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id); }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unread(@CurrentUser User u) { return Views.map("count", notis.countByUserIdAndIsReadFalse(u.id)); }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> read(@CurrentUser User u, @PathVariable Long id) {
        Notification n = notis.findById(id).orElseThrow(() -> ApiException.notFound("알림을 찾을 수 없어요"));
        if (!n.userId.equals(u.id)) throw ApiException.forbidden("내 알림만 읽을 수 있어요");
        n.isRead = true;
        return Views.map("ok", true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAll(@CurrentUser User u) {
        notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id).forEach(n -> n.isRead = true);
        return Views.map("ok", true);
    }

    @GetMapping("/notices")
    public List<Notice> notices() {
        return notices.findAll().stream().sorted(Comparator.comparing((Notice n) -> n.createdAt).reversed()).toList();
    }

    @GetMapping("/notices/{id}")
    public Notice notice(@PathVariable Long id) { return notices.findById(id).orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요")); }

    @GetMapping("/inquiries/mine")
    public List<Inquiry> myInquiries(@CurrentUser User u) { return inquiries.findByUserIdOrderByIdDesc(u.id); }

    @PostMapping("/inquiries")
    public Inquiry addInquiry(@CurrentUser User u, @RequestBody InquiryBody b) {
        if (b.title() == null || b.title().isBlank() || b.content() == null || b.content().isBlank())
            throw ApiException.bad("INVALID_INPUT", "제목과 내용을 적어 주세요");
        Inquiry q = new Inquiry();
        q.userId = u.id; q.type = b.type() == null ? "기타" : b.type(); q.title = b.title().trim(); q.content = b.content().trim();
        inquiries.save(q);
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "INQUIRY", "새 1:1 문의", q.title, "/admin/inquiries"));
        return q;
    }

    @PostMapping("/reports")
    public Report addReport(@CurrentUser User u, @RequestBody ReportBody b) {
        if (b.targetType() == null || b.type() == null || b.type().isBlank()) throw ApiException.bad("INVALID_INPUT", "신고 대상과 유형을 골라 주세요");
        Report r = new Report();
        r.reporterId = u.id; r.targetType = b.targetType(); r.targetId = b.targetId(); r.targetLabel = b.targetLabel();
        r.type = b.type(); r.content = b.content();
        reports.save(r);
        if (opsSafety != null) opsSafety.onReport(r); // 7.14 C5 신고 정리(요약·심각도 제안 저장)
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "REPORT", "새 신고가 접수됐어요", r.type + " · " + Objects.toString(r.targetLabel, ""), "/admin/reports"));
        return r;
    }

    @GetMapping("/reports/mine")
    public List<Report> myReports(@CurrentUser User u) { return reports.findByReporterIdOrderByIdDesc(u.id); }
}
