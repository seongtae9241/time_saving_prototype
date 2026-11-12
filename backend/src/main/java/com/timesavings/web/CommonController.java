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
    /** kind: "WELFARE" 면 독거노인 이상 징후(안부 확인) 접수(B-36). 없으면 일반 신고(B-37). */
    public record ReportBody(ReportTarget targetType, Long targetId, String targetLabel, String type, String content, String kind) {}
    @org.springframework.beans.factory.annotation.Autowired private RequestRepo requests;
    @org.springframework.beans.factory.annotation.Autowired private ElderRepo elders;
    @org.springframework.beans.factory.annotation.Autowired private Views views;

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
        return notices.findAll().stream().sorted(Comparator.comparing((Notice n) -> !Boolean.TRUE.equals(n.pinned)) // 기능 A-16: 고정 공지 먼저
                .thenComparing(Comparator.comparing((Notice n) -> n.createdAt).reversed())).toList();
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
        r.type = b.type().trim(); r.content = b.content();
        if (Report.WELFARE.equals(b.kind()) || Report.WELFARE_TYPE.equals(r.type)) return addWelfare(u, r);
        if (r.targetType == ReportTarget.USER) {
            // B-37 활동 상대 신고: 실제 있는 다른 사용자여야 한다
            User t = r.targetId == null ? null : users.findById(r.targetId).orElse(null);
            if (t == null) throw ApiException.bad("INVALID_INPUT", "신고할 상대를 찾을 수 없어요");
            if (t.id.equals(u.id)) throw ApiException.bad("INVALID_INPUT", "나 자신은 신고할 수 없어요");
            if (r.targetLabel == null || r.targetLabel.isBlank()) r.targetLabel = "활동 상대 · " + t.name + " 님";
        }
        reports.save(r);
        if (opsSafety != null) opsSafety.onReport(r); // 7.14 C5 신고 정리(요약·심각도 제안 저장)
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "REPORT", "새 신고가 접수됐어요", r.type + " · " + Objects.toString(r.targetLabel, ""), "/admin/reports"));
        return r;
    }

    /**
     * B-36 독거노인 안부 확인: 방문 중 본 이상 징후(식사 거름, 몸 상태 이상 등)를 접수한다.
     * 그 대상의 담당자에게 알림이 가고, 운영자 신고 목록에는 제재 없는 접수 기록으로 남는다(D-02).
     * 응급 상황은 여기서 처리하지 않는다(화면이 119·112 전화로 안내).
     */
    private Report addWelfare(User u, Report r) {
        Request q = r.targetType == ReportTarget.REQUEST && r.targetId != null ? requests.findById(r.targetId).orElse(null) : null;
        if (q == null || q.elderId == null) throw ApiException.bad("NOT_ELDER_ACTIVITY", "이상 징후(안부 확인)는 기관 도움 대상의 활동에서만 접수할 수 있어요");
        if (views.roleOf(q, u) == null) throw ApiException.forbidden("이 활동의 봉사자나 담당자만 접수할 수 있어요");
        if (r.content == null || r.content.isBlank()) throw ApiException.bad("CONTENT_REQUIRED", "어떤 점이 걱정됐는지 적어 주세요");
        Elder e = elders.findById(q.elderId).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        r.kind = Report.WELFARE;
        r.elderId = e.id;
        r.type = Report.WELFARE_TYPE;
        r.content = r.content.trim();
        r.targetLabel = "안부 확인 · " + Rules.maskAlias(e.alias) + " (" + Objects.toString(e.dong, "") + ")";
        reports.save(r);
        String brief = r.content.length() > 60 ? r.content.substring(0, 60) + "…" : r.content;
        if (e.managerId != null && !e.managerId.equals(u.id))
            noti.send(e.managerId, "WELFARE", e.alias + " 님 안부 확인이 접수됐어요", u.name + " 님: " + brief + " · 응급이면 119에 먼저 연락해 주세요", "/requests/" + q.id);
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "REPORT", "안부 확인(이상 징후) 접수 기록", r.targetLabel + " · 제재 없이 확인만 하는 기록이에요", "/admin/reports"));
        return r;
    }

    @GetMapping("/reports/mine")
    public List<Report> myReports(@CurrentUser User u) { return reports.findByReporterIdOrderByIdDesc(u.id); }
}
