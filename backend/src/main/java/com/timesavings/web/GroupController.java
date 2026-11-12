package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.service.GroupService;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 단체 봉사 공고(기능 U-10·U-11·O-07). 1:1 공고(/api/posts)와 따로 둔다.
 * 결정(참여 신청, 받기, 참석 확인·완료)은 모두 사람이 누른다. AI 를 부르지 않는다.
 */
@RestController
@RequestMapping("/api/group-posts")
@Transactional
public class GroupController {
    private final GroupService groups;

    public GroupController(GroupService groups) { this.groups = groups; }

    public record CompleteBody(List<Long> attended) {}

    private static void notAdmin(User u) {
        if (u.role == Role.ADMIN) throw ApiException.forbidden("사용자·담당자만 쓸 수 있어요");
    }

    /** 단체 공고 목록(U-10). kind: RECRUIT(단체 모집) | OFFER(단체 봉사 가능), area, cat 로 거른다. */
    @GetMapping
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String area, @RequestParam(required = false) String cat) {
        return groups.list(u, kind, area, cat);
    }

    /** 내 단체 봉사: 사용자=올렸거나 참여한 것, 담당자=우리 기관이 올렸거나 받기로 한 것. */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine(@CurrentUser User u) { return groups.mine(u); }

    /** 내 단체 일정(플래너 달력·내 활동용). month: 2026-11(없으면 전체). */
    @GetMapping("/schedule")
    public List<Map<String, Object>> schedule(@CurrentUser User u, @RequestParam(required = false) String month) {
        YearMonth ym = null;
        if (month != null && !month.isBlank()) {
            try { ym = YearMonth.parse(month); } catch (Exception e) { throw ApiException.bad("INVALID_MONTH", "달은 2026-11 처럼 적어 주세요"); }
        }
        return groups.schedule(u, ym);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return groups.detail(groups.get(id), u); }

    /** 단체 공고 올리기(U-11 사용자: 제공형만, O-07 담당자: 모집형·제공형). 인원 2명 이상. */
    @PostMapping
    public Map<String, Object> create(@CurrentUser User u, @RequestBody GroupService.Cmd b) {
        notAdmin(u);
        return groups.detail(groups.create(u, b), u);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancel(@CurrentUser User u, @PathVariable Long id) {
        groups.cancel(u, id);
        return groups.detail(groups.get(id), u);
    }

    /** 참여 신청(정원까지 선착순, 신분증 인증된 사용자만). */
    @PostMapping("/{id}/join")
    public Map<String, Object> join(@CurrentUser User u, @PathVariable Long id) {
        groups.join(u, id);
        return groups.detail(groups.get(id), u);
    }

    @PostMapping("/{id}/leave")
    public Map<String, Object> leave(@CurrentUser User u, @PathVariable Long id) {
        groups.leave(u, id);
        return groups.detail(groups.get(id), u);
    }

    /** 제공형 단체 봉사를 우리 기관에서 받기(담당자). */
    @PostMapping("/{id}/host")
    public Map<String, Object> host(@CurrentUser User u, @PathVariable Long id) {
        groups.host(u, id);
        return groups.detail(groups.get(id), u);
    }

    @PostMapping("/{id}/unhost")
    public Map<String, Object> unhost(@CurrentUser User u, @PathVariable Long id) {
        groups.unhost(u, id);
        return groups.detail(groups.get(id), u);
    }

    /** 참석 확인·완료(담당자). attended: 참석한 사용자 id 목록 → 그 사람들 통장에 각각 적립. */
    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@CurrentUser User u, @PathVariable Long id, @RequestBody CompleteBody b) {
        Map<String, Object> r = groups.complete(u, id, b == null ? null : b.attended());
        Map<String, Object> out = groups.detail(groups.get(id), u);
        out.put("result", r);
        return out;
    }
}
