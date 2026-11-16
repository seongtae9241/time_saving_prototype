package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.service.OrgNoticeService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 기관 공고 3종(2026-11-16, 프로토타입 O-POST-05·07·08·09). 명세: docs/specs/2026-11-16-담당자-추가기능-API.md 2장.
 * 담당자 쪽은 /api/org/notices·/api/org/notice-applications (ORG 만, 우리 기관 공고만), 사용자 쪽은 /api/org-notices.
 * 신청 수락·거절·마감·참석 확인은 모두 사람이 누른다. AI 를 부르지 않는다.
 */
@RestController
@RequestMapping("/api")
@Transactional
public class OrgNoticeController {
    private final OrgNoticeService svc;

    public OrgNoticeController(OrgNoticeService svc) { this.svc = svc; }

    public record CompleteBody(List<Long> attended) {}

    // ---------- 담당자 ----------
    @GetMapping("/org/notices")
    public Map<String, Object> list(@CurrentUser User u, @RequestParam(required = false) String kind, @RequestParam(required = false) String state) {
        return svc.listForOrg(u, kind, state);
    }

    @PostMapping("/org/notices")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody OrgNoticeService.Cmd b) { return svc.detail(svc.create(u, b), u); }

    @GetMapping("/org/notices/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return svc.getMine(u, id); }

    /** 신청 마감(새 신청만 막는다) */
    @PostMapping("/org/notices/{id}/close")
    public Map<String, Object> close(@CurrentUser User u, @PathVariable Long id) { return svc.detail(svc.close(u, id), u); }

    @PostMapping("/org/notices/{id}/reopen")
    public Map<String, Object> reopen(@CurrentUser User u, @PathVariable Long id) { return svc.detail(svc.reopen(u, id), u); }

    /** 공고 내리기(취소) */
    @DeleteMapping("/org/notices/{id}")
    public Map<String, Object> cancel(@CurrentUser User u, @PathVariable Long id) { return svc.detail(svc.cancel(u, id), u); }

    /** 봉사자 모집·단체 봉사: 참석 확인·완료 → 참석한 사람마다 적립 */
    @PostMapping("/org/notices/{id}/complete")
    public Map<String, Object> complete(@CurrentUser User u, @PathVariable Long id, @RequestBody CompleteBody b) {
        return svc.complete(u, id, b == null ? null : b.attended());
    }

    @GetMapping("/org/notice-applications")
    public Map<String, Object> applications(@CurrentUser User u, @RequestParam(required = false) String box, @RequestParam(required = false) String kind) {
        return svc.applicationsForOrg(u, box, kind);
    }

    @PostMapping("/org/notice-applications/{id}/approve")
    public Map<String, Object> approve(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) OrgNoticeService.DecideCmd b) {
        return svc.approve(u, id, b);
    }

    @PostMapping("/org/notice-applications/{id}/reject")
    public Map<String, Object> reject(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) OrgNoticeService.DecideCmd b) {
        return svc.reject(u, id, b);
    }

    /** 해 드려요: 방문 완료 기록(적립 없음) */
    @PostMapping("/org/notice-applications/{id}/served")
    public Map<String, Object> served(@CurrentUser User u, @PathVariable Long id) { return svc.served(u, id); }

    // ---------- 사용자 ----------
    private static void userOnly(User u) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정만 쓸 수 있어요");
    }

    @GetMapping("/org-notices")
    public List<Map<String, Object>> open(@CurrentUser User u, @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String area, @RequestParam(required = false) String cat) {
        return svc.listOpen(u, kind, area, cat);
    }

    @GetMapping("/org-notices/mine")
    public List<Map<String, Object>> mine(@CurrentUser User u) { userOnly(u); return svc.mine(u); }

    @GetMapping("/org-notices/{id}")
    public Map<String, Object> view(@CurrentUser User u, @PathVariable Long id) { return svc.get(u, id); }

    @PostMapping("/org-notices/{id}/apply")
    public Map<String, Object> apply(@CurrentUser User u, @PathVariable Long id, @RequestBody(required = false) OrgNoticeService.ApplyCmd b) {
        userOnly(u);
        return svc.apply(u, id, b);
    }

    @PostMapping("/org-notices/{id}/withdraw")
    public Map<String, Object> withdraw(@CurrentUser User u, @PathVariable Long id) { userOnly(u); return svc.withdraw(u, id); }
}
