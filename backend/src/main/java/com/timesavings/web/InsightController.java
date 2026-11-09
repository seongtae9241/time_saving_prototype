package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.service.AnalyticsService;
import com.timesavings.service.CareReportService;
import com.timesavings.service.PersonalInsightService;
import com.timesavings.service.PersonalInsightService.PlanItem;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 회의반영 4~6장:
 * 최근 활동 리포트(GET /api/reports/recent), 개인 AI 분석(GET /api/me/insights), 봉사 플랜(POST /api/me/care-plan),
 * 운영자 분석(GET /api/admin/analytics)·인사이트(GET /api/admin/insights — /api/admin/** 는 ADMIN 만).
 */
@RestController
@RequestMapping("/api")
public class InsightController {
    private final CareReportService care;
    private final PersonalInsightService personal;
    private final AnalyticsService analytics;

    public InsightController(CareReportService care, PersonalInsightService personal, AnalyticsService analytics) {
        this.care = care; this.personal = personal; this.analytics = analytics;
    }

    public record PlanBody(List<PlanItem> plans) {}

    /** subject = me | elder:{id} | family:{userId} */
    @GetMapping("/reports/recent")
    public Map<String, Object> recent(@CurrentUser User u, @RequestParam(required = false) String subject) {
        return care.recent(u, subject);
    }

    @GetMapping("/me/insights")
    public Map<String, Object> myInsights(@CurrentUser User u) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        return personal.insights(u);
    }

    @PostMapping("/me/care-plan")
    public Map<String, Object> carePlan(@CurrentUser User u, @RequestBody PlanBody b) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 볼 수 있어요");
        return personal.carePlan(u, b == null ? null : b.plans());
    }

    @GetMapping("/admin/analytics")
    public Map<String, Object> adminAnalytics() { return analytics.analytics(); }

    /** refresh=true 면 1시간 캐시를 무시하고 다시 분석한다("다시 분석" 버튼). */
    @GetMapping("/admin/insights")
    public Map<String, Object> adminInsights(@RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return analytics.insights(refresh);
    }
}
