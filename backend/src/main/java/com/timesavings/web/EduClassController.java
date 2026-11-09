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
                            LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId,
                            String mode, String bio, List<String> tags, List<String> qs, List<ClassService.Slot> slots, String meet, Integer weeklyCap) {}
    public record OneApplyBody(LocalDate slotDate, Double slotHour, String question) {}
    public record SlotBody(LocalDate date, Double hour) {}
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
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String mode) {
        return classes.list(u, kind, status, mode);
    }

    /** 수업 열기 인터뷰 첫 단계: 금지 주제 확인(규칙만, AI 호출 없음) */
    @PostMapping("/classes/check-topic")
    public Map<String, Object> checkTopic(@RequestBody TextBody b) {
        if (b.text() == null || b.text().isBlank()) throw ApiException.bad("TEXT_REQUIRED", "문장을 입력해 주세요");
        return classes.checkTopic(b.text());
    }

    @GetMapping("/classes/mine")
    public Map<String, Object> mine(@CurrentUser User u) { return classes.mine(u); }

    @GetMapping("/classes/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return classes.view(classes.get(id), u); }

    @PostMapping("/classes")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody ClassBody b) {
        NeighborClass c = classes.create(u, new ClassService.ClassCmd(b.kind(), b.title(), b.intro(), b.steps(), b.items(), b.caution(),
                b.date(), b.startHour(), b.hours(), b.capacity(), b.teacherElderId(),
                b.mode(), b.bio(), b.tags(), b.qs(), b.slots(), b.meet(), b.weeklyCap()));
        return classes.view(c, u);
    }

    // ---------------- 1:1 클래스 ----------------
    @PostMapping("/classes/{id}/one/apply")
    public Map<String, Object> oneApply(@CurrentUser User u, @PathVariable Long id, @RequestBody OneApplyBody b) {
        classes.applyOne(u, id, b.slotDate(), b.slotHour(), b.question());
        return classes.view(classes.get(id), u);
    }

    @PostMapping("/classes/{id}/one/cancel")
    public Map<String, Object> oneCancel(@CurrentUser User u, @PathVariable Long id) { classes.cancelOne(u, id); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/one/accept-proposal")
    public Map<String, Object> oneAcceptProposal(@CurrentUser User u, @PathVariable Long id) { classes.acceptProposal(u, id); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/one/start")
    public Map<String, Object> oneStart(@CurrentUser User u, @PathVariable Long id, @RequestBody CodeBody b) { classes.startOne(u, id, b.code()); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/one/end")
    public Map<String, Object> oneEnd(@CurrentUser User u, @PathVariable Long id, @RequestBody CodeBody b) { classes.endOne(u, id, b.code()); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/{id}/one/note")
    public Map<String, Object> oneNote(@CurrentUser User u, @PathVariable Long id, @RequestBody TextBody b) { classes.noteOne(u, id, b.text()); return classes.view(classes.get(id), u); }

    @PostMapping("/classes/one/enrollments/{eid}/accept")
    public Map<String, Object> oneAccept(@CurrentUser User u, @PathVariable Long eid) {
        ClassEnrollment e = classes.acceptOne(u, eid);
        return classes.view(classes.get(e.classId), u);
    }

    @PostMapping("/classes/one/enrollments/{eid}/propose")
    public Map<String, Object> onePropose(@CurrentUser User u, @PathVariable Long eid, @RequestBody SlotBody b) {
        ClassEnrollment e = classes.proposeOne(u, eid, b.date(), b.hour());
        return classes.view(classes.get(e.classId), u);
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
        return Views.map("spaces", spaces.findByOrgId(u.orgId), "pendingClasses", classes.list(u, null, "SPACE_PENDING"), "oneClasses", classes.orgOneClasses(u));
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
