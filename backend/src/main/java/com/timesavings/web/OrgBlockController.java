package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.service.VolunteerBlockService;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** O1 담당자의 봉사자 거부·받지 않기(회의반영 3장). /api/org/** 는 ORG 역할만(인터셉터). */
@RestController
@RequestMapping("/api/org")
@Transactional
public class OrgBlockController {
    private final VolunteerBlockService svc;
    private final Views views;

    public OrgBlockController(VolunteerBlockService svc, Views views) { this.svc = svc; this.views = views; }

    public record RejectBody(String reason, Boolean block) {}

    /** 담당 대상 건의 요청·제안·확정된 봉사자를 거부. block=true 면 그 봉사자를 이 대상에게서 받지 않는다. */
    @PostMapping("/requests/{id}/reject-volunteer")
    public Map<String, Object> reject(@CurrentUser User u, @PathVariable Long id, @RequestBody RejectBody b) {
        Request r = svc.rejectVolunteer(u, id, b.reason(), Boolean.TRUE.equals(b.block()));
        Map<String, Object> v = views.requestView(r, u);
        v.put("blocked", svc.blocked(r.elderId, r.volunteerId));
        return v;
    }

    @GetMapping("/elders/{id}/blocks")
    public List<Map<String, Object>> blocks(@CurrentUser User u, @PathVariable Long id) { return svc.list(u, id); }

    @DeleteMapping("/blocks/{id}")
    public Map<String, Object> unblock(@CurrentUser User u, @PathVariable Long id) {
        svc.unblock(u, id);
        return Views.map("ok", true);
    }
}
