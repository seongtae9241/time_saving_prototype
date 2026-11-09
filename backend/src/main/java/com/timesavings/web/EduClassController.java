package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.repo.SpaceRepo;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** 첫 활동 교육(5.8)과 동네 클래스(5.9). */
@RestController
@RequestMapping("/api")
@Transactional
public class EduClassController {
    private final EduService edu;
    private final ClassService classes;
    private final SpaceRepo spaces;

    public EduClassController(EduService edu, ClassService classes, SpaceRepo spaces) {
        this.edu = edu; this.classes = classes; this.spaces = spaces;
    }

    public record QuizBody(List<Integer> answers) {}
    public record FinishRpBody(List<Map<String, Object>> log) {}
    public record ClassBody(String kind, String title, String intro, List<ClassService.Step> steps, String items, String caution,
                            LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId) {}
    public record CodeBody(String code) {}
    public record FinishBody(List<String> steps, String tip) {}
    public record TextBody(String text) {}
    public record SpaceBody(String name, Integer capacity, String equipment) {}
    public record AssignBody(Long spaceId, String place) {}

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

    // ---------------- 클래스 ----------------
    @GetMapping("/classes")
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) String kind, @RequestParam(required = false) String status) {
        return classes.list(u, kind, status);
    }

    @GetMapping("/classes/mine")
    public Map<String, Object> mine(@CurrentUser User u) { return classes.mine(u); }

    @GetMapping("/classes/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return classes.view(classes.get(id), u); }

    @PostMapping("/classes")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody ClassBody b) {
        NeighborClass c = classes.create(u, new ClassService.ClassCmd(b.kind(), b.title(), b.intro(), b.steps(), b.items(), b.caution(),
                b.date(), b.startHour(), b.hours(), b.capacity(), b.teacherElderId()));
        return classes.view(c, u);
    }

    @PostMapping("/classes/{id}/apply")
    public Map<String, Object> apply(@CurrentUser User u, @PathVariable Long id) { classes.apply(u, id); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/cancel")
    public Map<String, Object> cancel(@CurrentUser User u, @PathVariable Long id) { classes.cancel(u, id); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/attend")
    public Map<String, Object> attend(@CurrentUser User u, @PathVariable Long id, @RequestBody CodeBody b) {
        classes.attend(u, id, b.code());
        return classes.view(classes.get(id), u);
    }

    @GetMapping("/classes/{id}/qr")
    public Map<String, Object> qr(@CurrentUser User u, @PathVariable Long id) { return classes.qr(u, id); }

    @PostMapping("/classes/{id}/finish")
    public Map<String, Object> finish(@CurrentUser User u, @PathVariable Long id, @RequestBody FinishBody b) { return classes.finish(u, id, b.steps(), b.tip()); }

    @PostMapping("/classes/{id}/review")
    public Map<String, Object> review(@CurrentUser User u, @PathVariable Long id, @RequestBody TextBody b) {
        classes.review(u, id, b.text());
        return classes.view(classes.get(id), u);
    }

    // ---------------- 담당자: 공간 ----------------
    @GetMapping("/org/spaces")
    public Map<String, Object> spaces(@CurrentUser User u) {
        return Views.map("spaces", spaces.findByOrgId(u.orgId), "pendingClasses", classes.list(u, null, "SPACE_PENDING"));
    }

    @PostMapping("/org/spaces")
    public Space addSpace(@CurrentUser User u, @RequestBody SpaceBody b) {
        if (b.name() == null || b.name().isBlank()) throw ApiException.bad("NAME_REQUIRED", "공간 이름을 적어 주세요");
        Space s = new Space();
        s.orgId = u.orgId; s.name = b.name().trim(); s.capacity = b.capacity() == null ? 0 : b.capacity(); s.equipment = b.equipment();
        return spaces.save(s);
    }

    @PostMapping("/org/classes/{id}/assign-space")
    public Map<String, Object> assign(@CurrentUser User u, @PathVariable Long id, @RequestBody AssignBody b) {
        return classes.view(classes.assignSpace(u, id, b.spaceId(), b.place()), u);
    }
}
