package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 첫 활동 교육(5.8). (동네 클래스 5.9는 2026-11-10 사용자 결정으로 삭제) */
@RestController
@RequestMapping("/api")
@Transactional
public class EduController {
    private final EduService edu;

    public EduController(EduService edu) {
        this.edu = edu;
    }

    public record QuizBody(List<Integer> answers) {}
    public record FinishRpBody(List<Map<String, Object>> log) {}

    // ---------------- 교육 ----------------
    @GetMapping("/edu")
    public Map<String, Object> get(@CurrentUser User u) { return edu.get(u); }

    @PutMapping("/edu/diag")
    public Map<String, Object> diag(@CurrentUser User u, @RequestBody Map<String, Object> body) { edu.diag(u, body); return edu.get(u); }

    @PostMapping("/edu/cards-done")
    public Map<String, Object> cards(@CurrentUser User u) { edu.cardsDone(u); return Views.map("cardsDone", true); }

    @PostMapping("/edu/quiz")
    public Map<String, Object> quiz(@CurrentUser User u, @RequestBody QuizBody b) { return edu.quiz(u, b.answers()); }

    @PostMapping("/edu/roleplay/{personaId}/finish")
    public Map<String, Object> rpFinish(@CurrentUser User u, @PathVariable Long personaId, @RequestBody FinishRpBody b) {
        return edu.finish(u, personaId, b.log());
    }

    @PostMapping("/edu/complete")
    public Map<String, Object> complete(@CurrentUser User u) { return edu.complete(u); }
}
