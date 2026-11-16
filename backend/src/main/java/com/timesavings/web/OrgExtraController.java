package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.OrgAssistantService;
import com.timesavings.service.OrgVolunteerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 2026-11-16 담당자 추가 기능: 홈 봉사자 현황(읽기), AI 도우미. /api/org/** 는 ORG 만(인터셉터).
 * 명세: docs/specs/2026-11-16-담당자-추가기능-API.md 3·4장.
 */
@RestController
@RequestMapping("/api/org")
public class OrgExtraController {
    private final OrgVolunteerService volunteers;
    private final OrgAssistantService assistant;

    public OrgExtraController(OrgVolunteerService volunteers, OrgAssistantService assistant) {
        this.volunteers = volunteers; this.assistant = assistant;
    }

    public record AskBody(String question, String screen) {}

    /** 홈 "봉사자 현황": 내 관리 대상과 엮인 봉사자와 상태. limit 을 주면 앞에서 그만큼만(개수 counts 는 전체 기준). */
    @GetMapping("/volunteers")
    public Map<String, Object> volunteers(@CurrentUser User u, @RequestParam(required = false) Integer limit) { return volunteers.status(u, limit); }

    /** AI 도우미 패널을 열 때: 예시 질문·안내 문구(AI 를 부르지 않는다) */
    @GetMapping("/assistant")
    public Map<String, Object> intro() { return assistant.intro(); }

    /** AI 도우미에게 묻기. 아무것도 실행하지 않는다(executed:false). */
    @PostMapping("/assistant")
    public Map<String, Object> ask(@CurrentUser User u, @RequestBody AskBody b) {
        return assistant.ask(u, b == null ? null : b.question(), b == null ? null : b.screen());
    }
}
