package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.ActivityAiService;
import com.timesavings.service.ActivityAiService.GoalCmd;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 계약서 7.13 LLM 추가 B: 방문 전 브리핑, 메시지 도우미(안전 감지·빠른 답장·일정 변경), 판단 보조, 적립 목표 코치, 월별 결산 설명. */
@RestController
@RequestMapping("/api")
@Transactional
public class ActivityAiController {
    private final ActivityAiService svc;

    public ActivityAiController(ActivityAiService svc) { this.svc = svc; }

    @GetMapping("/requests/{id}/briefing")
    public Map<String, Object> briefing(@CurrentUser User u, @PathVariable Long id) { return svc.briefing(u, id); }

    @GetMapping("/requests/{id}/message-assist")
    public Map<String, Object> messageAssist(@CurrentUser User u, @PathVariable Long id) { return svc.messageAssist(u, id); }

    /** kind=ADJUST(id=요청 id) | APPEAL(id=이의 신청 id) */
    @GetMapping("/judge-assist")
    public Map<String, Object> judge(@CurrentUser User u, @RequestParam String kind, @RequestParam Long id) { return svc.judge(u, kind, id); }

    @PostMapping("/me/goal-plan")
    public Map<String, Object> goalPlan(@CurrentUser User u, @RequestBody GoalCmd b) { return svc.goalPlan(u, b); }

    @GetMapping("/bank/month-note")
    public Map<String, Object> monthNote(@CurrentUser User u, @RequestParam(required = false) String month) { return svc.monthNote(u, month); }
}
