package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.CareSignalService;
import com.timesavings.service.OpsSafetyService;
import com.timesavings.service.OrgBriefingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 7.14 LLM 추가 C(담당자·운영자·안전). /api/org/** 는 ORG, /api/admin/** 는 ADMIN 만(인터셉터).
 * 모두 초안·요약·제안만 돌려준다. 등록·발송·제재·보류는 기존 API 를 사람이 버튼으로 부른다.
 */
@RestController
public class OpsAiController {
    private final OrgBriefingService briefing;
    private final CareSignalService signals;
    private final OpsSafetyService ops;

    public OpsAiController(OrgBriefingService briefing, CareSignalService signals, OpsSafetyService ops) {
        this.briefing = briefing; this.signals = signals; this.ops = ops;
    }

    public record MemoBody(Long elderId, String memo) {}
    public record CaseBody(String caseKey) {}

    // ---------- 담당자 ----------
    /** C2 통화 메모 → 공고 초안 여러 개(저장 안 함). */
    @PostMapping("/api/org/ai/memo-to-posts")
    public Map<String, Object> memoToPosts(@CurrentUser User u, @RequestBody MemoBody b) { return briefing.memoToPosts(u, b.elderId(), b.memo()); }

    /** C3 이번 주 브리핑. refresh=true 면 요약 글을 다시 만든다. */
    @GetMapping("/api/org/briefing")
    public Map<String, Object> briefing(@CurrentUser User u, @RequestParam(defaultValue = "false") boolean refresh) { return briefing.briefing(u, refresh); }

    /** C1 대상별 생활 변화 확인 권장 상태. */
    @GetMapping("/api/org/care-signals")
    public List<Map<String, Object>> careSignals(@CurrentUser User u) { return signals.forManager(u); }

    // ---------- 운영자 ----------
    /** C5 신고 정리(처리 전 → 심각도 → 접수일 순). */
    @GetMapping("/api/admin/ai/report-triage")
    public List<Map<String, Object>> triage() { return ops.triageList(); }

    @PostMapping("/api/admin/ai/report-triage/{id}")
    public Map<String, Object> retriage(@PathVariable Long id) { return ops.triageOne(id, true); }

    /** C6 이상 적립 패턴 설명. */
    @GetMapping("/api/admin/ai/anomalies")
    public List<Map<String, Object>> anomalies(@RequestParam(defaultValue = "false") boolean refresh) { return ops.anomalies(refresh); }

    /** C7 문의 답변 초안(발송 안 함). */
    @PostMapping("/api/admin/ai/inquiries/{id}/draft")
    public Map<String, Object> inquiryDraft(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean refresh) { return ops.inquiryDraft(id, refresh); }

    /** C4 실제 사례 집계 · 역할극 상대 초안(등록 안 함). */
    @GetMapping("/api/admin/ai/persona-cases")
    public Map<String, Object> personaCases() { return ops.personaCases(); }

    @PostMapping("/api/admin/ai/persona-draft")
    public Map<String, Object> personaDraft(@RequestBody(required = false) CaseBody b) { return ops.personaDraft(b == null ? null : b.caseKey()); }
}
