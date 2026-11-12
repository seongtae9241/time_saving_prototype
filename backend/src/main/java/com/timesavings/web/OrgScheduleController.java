package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.CareScheduleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 기능 O-11 돌봄 편성표 · O-12 편성 대안 추천 · O-13 공고 한꺼번에 올리기. /api/org/** 는 ORG 역할만(인터셉터).
 * 일괄 등록은 칸마다 따로 저장해야 해서(한 칸 실패가 다른 칸을 되돌리지 않게) 이 컨트롤러에는 @Transactional 을 붙이지 않는다.
 */
@RestController
@RequestMapping("/api/org/schedule")
public class OrgScheduleController {
    private final CareScheduleService svc;

    public OrgScheduleController(CareScheduleService svc) { this.svc = svc; }

    public record BulkBody(List<CareScheduleService.BulkItem> items) {}

    /** 주간 편성표. week 는 그 주의 아무 날짜(비우면 이번 주), elderId 를 주면 그 대상만. */
    @GetMapping
    public Map<String, Object> week(@CurrentUser User u,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                                    @RequestParam(required = false) Long elderId) {
        return svc.week(u, week, elderId);
    }

    /** 빈 칸 하나에 맞는 봉사자 추천(없으면 다른 요일·공고 올리기 제안). 아무것도 보내지 않는다. */
    @PostMapping("/suggest")
    public Map<String, Object> suggest(@CurrentUser User u, @RequestBody CareScheduleService.SlotCmd b) { return svc.suggest(u, b); }

    /** 빈 칸 여러 개에 대리 공고를 한꺼번에. 실패한 칸은 failed 에 이유와 함께. */
    @PostMapping("/bulk-posts")
    public Map<String, Object> bulk(@CurrentUser User u, @RequestBody BulkBody b) { return svc.bulkPosts(u, b == null ? null : b.items()); }
}
