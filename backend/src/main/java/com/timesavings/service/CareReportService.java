package com.timesavings.service;

import com.timesavings.ai.InsightAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 4장 "최근 활동" 리포트: 최근 활동 목록(최신순, 일지·전달 메모) + AI 인수인계 요약 + 지난 월간 리포트.
 * 보는 사람: 본인(내가 받은 도움), 담당자(담당 대상), 공유에 동의한 가족. 봉사자는 대상의 지난 메모를 볼 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class CareReportService {
    public static final int HANDOVER_N = 8;
    public static final int LIST_N = 30;
    private final RequestRepo requests;
    private final ElderRepo elders;
    private final UserRepo users;
    private final FamilyLinkRepo family;
    private final LifeReportRepo reports;
    private final LifeReportService reportSvc;
    private final NotificationService noti;
    private final InsightAi insight;

    public CareReportService(RequestRepo requests, ElderRepo elders, UserRepo users, FamilyLinkRepo family, LifeReportRepo reports,
                             LifeReportService reportSvc, NotificationService noti, InsightAi insight) {
        this.requests = requests; this.elders = elders; this.users = users; this.family = family; this.reports = reports;
        this.reportSvc = reportSvc; this.noti = noti; this.insight = insight;
    }

    record Subject(SubjectType type, Long id, String label, List<Request> list) {}

    private static boolean reported(Request r) {
        return (r.status == ReqStatus.DONE || r.status == ReqStatus.LOG_SUBMITTED) && r.logSubmittedAt != null;
    }

    /** subject: me | elder:{id} | family:{userId}. 권한이 없으면 403. */
    Subject resolve(User viewer, String subject) {
        String s = subject == null || subject.isBlank() ? "me" : subject.trim();
        List<Request> all = requests.findAll();
        if (s.equals("me")) {
            if (viewer.role != Role.USER) throw ApiException.forbidden("사용자 계정만 내 리포트를 볼 수 있어요");
            return new Subject(SubjectType.USER, viewer.id, viewer.name + " 님",
                    all.stream().filter(r -> viewer.id.equals(r.recipientId) && reported(r)).toList());
        }
        int c = s.indexOf(':');
        if (c < 0) throw ApiException.bad("INVALID_SUBJECT", "subject 는 me, elder:{id}, family:{userId} 중 하나예요");
        long id;
        try { id = Long.parseLong(s.substring(c + 1)); } catch (NumberFormatException e) { throw ApiException.bad("INVALID_SUBJECT", "대상 번호가 맞지 않아요"); }
        String kind = s.substring(0, c);
        if (kind.equals("elder")) {
            Elder e = elders.findById(id).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (viewer.role != Role.ORG || !viewer.id.equals(e.managerId)) throw ApiException.forbidden("담당자만 대상의 리포트를 볼 수 있어요");
            return new Subject(SubjectType.ELDER, e.id, e.alias + " 님", all.stream().filter(r -> e.id.equals(r.elderId) && reported(r)).toList());
        }
        if (kind.equals("family")) {
            if (id == viewer.id) throw ApiException.forbidden("본인 리포트는 '내 리포트'에서 봐요");
            FamilyLink f = family.findAll().stream().filter(x -> x.status == LinkStatus.ACCEPTED
                    && ((x.requesterId.equals(viewer.id) && x.targetId.equals(id)) || (x.requesterId.equals(id) && x.targetId.equals(viewer.id)))).findFirst().orElse(null);
            if (f == null) throw ApiException.forbidden("연결된 가족이 아니에요");
            if (!viewer.id.equals(LifeReportService.shareTarget(f, id))) throw ApiException.forbidden("상대가 리포트 공유에 동의하지 않았어요");
            User t = users.findById(id).orElseThrow(() -> ApiException.notFound("가족을 찾을 수 없어요"));
            return new Subject(SubjectType.USER, t.id, t.name + " 님", all.stream().filter(r -> t.id.equals(r.recipientId) && reported(r)).toList());
        }
        throw ApiException.bad("INVALID_SUBJECT", "subject 는 me, elder:{id}, family:{userId} 중 하나예요");
    }

    public Map<String, Object> recent(User viewer, String subject) {
        Subject sub = resolve(viewer, subject);
        List<Request> sorted = sub.list().stream()
                .sorted(Comparator.comparing((Request r) -> r.date).reversed().thenComparing(r -> r.id, Comparator.reverseOrder())).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Request r : sorted.stream().limit(LIST_N).toList()) {
            double h = "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours;
            items.add(Views.map("requestId", r.id, "date", r.date.toString(), "startHour", r.startHour, "hours", h, "category", r.category,
                    "content", r.content, "volunteer", noti.name(r.volunteerId), "status", r.status,
                    "logText", r.logText, "careMemo", r.careMemo));
        }
        Map<String, Object> handover = insight.handover(sub.label(), items.stream().limit(HANDOVER_N).toList());
        List<Map<String, Object>> monthly = reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(sub.type(), sub.id()).stream()
                .map(r -> reportSvc.view(r, sub.label())).toList();
        return Views.map("subject", subject == null ? "me" : subject, "subjectType", sub.type(), "subjectId", sub.id(), "subjectLabel", sub.label(),
                "items", items, "total", sorted.size(), "handover", handover, "monthly", monthly);
    }
}
