package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 첫 활동 연습(5.8, 41판 B-54: 선택 연습 — 수료 없음). (동네 클래스 5.9는 2026-11-10 사용자 결정으로 삭제) */
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

    // ---------------- 연습 상황 목록 (서버 설정, 41판 B-54) ----------------
    public record SituationsBody(List<Map<String, Object>> items) {}
    public record PracticeBody(String key, String text) {}

    /** 상황 하나 연습: 답을 보내면 위반 알림·바꿔 말하기 또는 칭찬이 온다. */
    @PostMapping("/edu/practice")
    public Map<String, Object> practice(@CurrentUser User u, @RequestBody PracticeBody b) { return edu.practice(u, b.key(), b.text()); }

    /** 사용자용: 어느 답이 맞는지는 빼고 준다. */
    @GetMapping("/edu/situations")
    public Map<String, Object> situations() { return Views.map("items", edu.situationViews()); }

    /** 운영자용: 고를 답·이유·안전 규칙까지 모두. */
    @GetMapping("/admin/edu/situations")
    public Map<String, Object> adminSituations() { return Views.map("items", edu.situations()); }

    /** /api/admin/** 는 운영자만(AuthInterceptor). */
    @PutMapping("/admin/edu/situations")
    public Map<String, Object> putSituations(@RequestBody SituationsBody b) { return Views.map("items", edu.saveSituations(b == null ? null : b.items())); }
}
