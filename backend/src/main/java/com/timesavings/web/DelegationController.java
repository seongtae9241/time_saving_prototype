package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.DelegationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 권한 위임(2026-11-16, 프로토타입 O-DLG-01~03). 명세: docs/specs/2026-11-16-담당자-추가기능-API.md 1장.
 * /api/org/** 는 ORG 만(인터셉터), 내 담당 대상·우리 기관 것만(서비스의 소유 검사). /api/delegations/** 는 사용자(본인·보호자) 쪽.
 * 위임·수락·회수는 사람이 눌러야 바뀐다. AI 를 부르지 않는다.
 */
@RestController
@RequestMapping("/api")
public class DelegationController {
    private final DelegationService svc;

    public DelegationController(DelegationService svc) { this.svc = svc; }

    // ---------- 담당자 ----------
    @GetMapping("/org/delegations")
    public Map<String, Object> list(@CurrentUser User u) { return svc.listForManager(u); }

    @GetMapping("/org/delegations/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return svc.detail(u, id); }

    /** 관리 대상 한 명의 지금 위임 + 변경 기록 + 새 위임 등록에 쓸 값(앱 연결 여부, 보호자로 고를 수 있는 가족 계정). */
    @GetMapping("/org/elders/{elderId}/delegation")
    public Map<String, Object> ofElder(@CurrentUser User u, @PathVariable Long elderId) { return svc.ofElder(u, elderId); }

    @PostMapping("/org/delegations")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody DelegationService.CreateCmd b) { return svc.create(u, b); }

    @PutMapping("/org/delegations/{id}")
    public Map<String, Object> update(@CurrentUser User u, @PathVariable Long id, @RequestBody DelegationService.UpdateCmd b) { return svc.update(u, id, b); }

    @PostMapping("/org/delegations/{id}/revoke")
    public Map<String, Object> revoke(@CurrentUser User u, @PathVariable Long id) { return svc.revokeByManager(u, id); }

    @PostMapping("/org/delegations/{id}/extend-request")
    public Map<String, Object> askExtend(@CurrentUser User u, @PathVariable Long id) { return svc.askExtend(u, id); }

    @GetMapping("/org/delegation-requests")
    public Map<String, Object> requests(@CurrentUser User u, @RequestParam(required = false) String status) { return svc.requestsForManager(u, status); }

    @PostMapping("/org/delegation-requests/{id}/answer")
    public Map<String, Object> answer(@CurrentUser User u, @PathVariable Long id, @RequestBody DelegationService.AnswerCmd b) { return svc.answer(u, id, b); }

    // ---------- 사용자(본인·보호자) ----------
    @GetMapping("/delegations/mine")
    public Map<String, Object> mine(@CurrentUser User u) { return svc.mine(u); }

    @PostMapping("/delegations/requests")
    public Map<String, Object> request(@CurrentUser User u, @RequestBody DelegationService.RequestCmd b) { return svc.request(u, b); }

    @PostMapping("/delegations/requests/{id}/cancel")
    public Map<String, Object> cancelRequest(@CurrentUser User u, @PathVariable Long id) { return svc.cancelRequest(u, id); }

    @PostMapping("/delegations/{id}/consent")
    public Map<String, Object> consent(@CurrentUser User u, @PathVariable Long id, @RequestBody DelegationService.ConsentCmd b) { return svc.consent(u, id, b); }

    @PostMapping("/delegations/{id}/revoke")
    public Map<String, Object> revokeMine(@CurrentUser User u, @PathVariable Long id) { return svc.revokeByUser(u, id); }

    @PostMapping("/delegations/{id}/extend")
    public Map<String, Object> extend(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) DelegationService.ExtendCmd b) { return svc.extend(u, id, b); }
}
