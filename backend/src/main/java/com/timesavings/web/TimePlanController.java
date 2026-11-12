package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.TimePlanService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 기능명세서 시간 통장: 기능 U-24 시간 플랜(GET /api/me/time-plan), 기능 U-25 월별 리포트(GET /api/me/month-report).
 * 숫자는 TimePlanService(코드)가 계산하고 설명 문장만 AI(키 없으면 규칙 문장)가 쓴다. 응답에 source: ai | rule.
 */
@RestController
@RequestMapping("/api")
public class TimePlanController {
    private final TimePlanService svc;

    public TimePlanController(TimePlanService svc) { this.svc = svc; }

    /** age·startAge 는 선택(바꿔서 다시 계산해 볼 때). 저장하지 않는다. */
    @GetMapping("/me/time-plan")
    public Map<String, Object> timePlan(@CurrentUser User u, @RequestParam(required = false) Integer age, @RequestParam(required = false) Integer startAge) {
        return svc.plan(u, age, startAge);
    }

    /** month=YYYY-MM (비우면 이번 달). */
    @GetMapping("/me/month-report")
    public Map<String, Object> monthReport(@CurrentUser User u, @RequestParam(required = false) String month) {
        return svc.monthReport(u, month);
    }
}
