package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.AiClient;
import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.EduProgress;
import com.timesavings.domain.RoleplayPersona;
import com.timesavings.domain.User;
import com.timesavings.repo.EduProgressRepo;
import com.timesavings.repo.RoleplayPersonaRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 첫 활동 교육(B-01 안전 교육, 프로토타입 edu). */
@Service
@Transactional
public class EduService {
    public static final TypeReference<List<Map<String, Object>>> TURNS = new TypeReference<>() {};
    private final EduProgressRepo progress;
    private final RoleplayPersonaRepo personas;
    private final UserRepo users;
    private final AiClient ai;

    public EduService(EduProgressRepo progress, RoleplayPersonaRepo personas, UserRepo users, AiClient ai) {
        this.progress = progress; this.personas = personas; this.users = users; this.ai = ai;
    }

    public EduProgress of(Long userId) {
        return progress.findById(userId).orElseGet(() -> { EduProgress p = new EduProgress(); p.userId = userId; return progress.save(p); });
    }

    public Map<String, Object> personaView(RoleplayPersona p, boolean withAnswers) {
        List<Map<String, Object>> turns = Views.parse(p.turnsJson, TURNS, List.of());
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> opts = (List<Map<String, Object>>) t.getOrDefault("options", List.of());
            List<Object> o = new ArrayList<>();
            for (Map<String, Object> x : opts) o.add(withAnswers ? x : Views.map("t", x.get("t")));
            safe.add(Views.map("options", o, "next", t.get("next")));
        }
        return Views.map("id", p.id, "name", p.name, "initial", p.initial, "ageLabel", p.ageLabel, "fitCategory", p.fitCategory,
                "trait", p.trait, "goal", p.goal, "opening", p.opening, "turns", safe, "active", p.active);
    }

    public Map<String, Object> get(User me) {
        EduProgress p = of(me.id);
        Map<String, Object> diag = Views.parse(p.diagJson, new TypeReference<Map<String, Object>>() {}, null);
        @SuppressWarnings("unchecked") List<String> cats = diag != null && diag.get("cats") instanceof List<?> l ? (List<String>) l : me.categories;
        List<Map<String, Object>> quiz = new ArrayList<>();
        for (int i = 0; i < EduContent.QUIZ.size(); i++) {
            EduContent.Quiz q = EduContent.QUIZ.get(i);
            quiz.add(Views.map("no", i, "q", q.q(), "options", q.options(), "card", q.card()));
        }
        return Views.map("diag", diag, "cardsDone", p.cardsDone, "quizScore", p.quizScore,
                "roleplay", Views.parse(p.roleplayJson, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>()),
                "completed", me.eduDone, "completedAt", p.completedAt,
                "personas", personas.findAll().stream().filter(x -> x.active).map(x -> personaView(x, false)).toList(),
                "rules", EduContent.cardsFor(cats), "quiz", quiz);
    }

    public void diag(User me, Map<String, Object> body) { of(me.id).diagJson = Views.json(body); }

    public void cardsDone(User me) { of(me.id).cardsDone = true; }

    public Map<String, Object> quiz(User me, List<Integer> answers) {
        if (answers == null) throw ApiException.bad("ANSWERS_REQUIRED", "답을 골라 주세요");
        int score = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < EduContent.QUIZ.size(); i++) {
            EduContent.Quiz q = EduContent.QUIZ.get(i);
            Integer a = i < answers.size() ? answers.get(i) : null;
            boolean ok = a != null && a == q.answer();
            if (ok) score++;
            results.add(Views.map("no", i, "correct", ok, "answer", q.answer(), "picked", a, "explain", q.explain(), "card", q.card()));
        }
        of(me.id).quizScore = score;
        return Views.map("score", score, "total", EduContent.QUIZ.size(), "passed", score >= 4, "results", results);
    }

    public RoleplayPersona persona(Long id) { return personas.findById(id).orElseThrow(() -> ApiException.notFound("가상 상대를 찾을 수 없어요")); }

    /** 대본 선택지와 같으면 그 판정을, 아니면 AiClient(AI 또는 규칙)로 판정. */
    public Map<String, Object> classify(RoleplayPersona p, int turnIndex, String text) {
        List<Map<String, Object>> turns = Views.parse(p.turnsJson, TURNS, List.of());
        if (turnIndex < turns.size()) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> opts = (List<Map<String, Object>>) turns.get(turnIndex).getOrDefault("options", List.of());
            for (Map<String, Object> o : opts)
                if (text.trim().equals(String.valueOf(o.get("t")).trim()))
                    return Views.map("quality", o.get("q"), "flag", o.get("f"), "source", "rule");
        }
        return ai.roleplayClassify(p.name + "(" + p.trait + ")", text);
    }

    /** POST /api/ai/roleplay */
    public Map<String, Object> roleplayTurn(Long personaId, List<Map<String, Object>> history, String text) {
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "할 말을 입력해 주세요");
        RoleplayPersona p = persona(personaId);
        List<Map<String, Object>> turns = Views.parse(p.turnsJson, TURNS, List.of());
        int i = history == null ? 0 : (int) history.stream().filter(h -> "me".equals(h.get("who"))).count();
        if (i >= turns.size()) throw ApiException.conflict("ROLEPLAY_DONE", "이 상황은 끝났어요. 결과를 확인해 주세요");
        Map<String, Object> c = classify(p, i, text);
        return Views.map("quality", c.get("quality"), "flag", c.get("flag"), "next", turns.get(i).get("next"),
                "done", i + 1 >= turns.size(), "turn", i, "source", c.get("source"));
    }

    public Map<String, Object> finish(User me, Long personaId, List<Map<String, Object>> log) {
        RoleplayPersona p = persona(personaId);
        List<Map<String, Object>> turns = Views.parse(p.turnsJson, TURNS, List.of());
        List<String> qs = new ArrayList<>(), flags = new ArrayList<>();
        List<Map<String, Object>> fixes = new ArrayList<>();
        Map<String, Object> goodOne = null;
        int turn = 0;
        for (Map<String, Object> l : log == null ? List.<Map<String, Object>>of() : log) {
            if (!"me".equals(l.get("who"))) continue;
            String text = String.valueOf(l.getOrDefault("text", ""));
            String q = (String) l.get("quality");
            String f = (String) l.get("flag");
            if (q == null) { Map<String, Object> c = classify(p, turn, text); q = (String) c.get("quality"); f = (String) c.get("flag"); }
            qs.add(q);
            flags.add(f);
            if (("bad".equals(q) || "poor".equals(q)) && turn < turns.size()) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> opts = (List<Map<String, Object>>) turns.get(turn).getOrDefault("options", List.of());
                String better = opts.stream().filter(o -> "good".equals(o.get("q"))).map(o -> String.valueOf(o.get("t"))).findFirst().orElse(null);
                fixes.add(Views.map("said", text, "better", better, "flag", f));
            }
            if ("good".equals(q) && goodOne == null) goodOne = Views.map("text", text);
            turn++;
        }
        RuleEngine.RpScore s = RuleEngine.rpScore(qs, flags);
        EduProgress pr = of(me.id);
        Map<String, Object> rp = Views.parse(pr.roleplayJson, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>());
        rp.put(String.valueOf(personaId), Views.map("passed", s.pass(), "comm", s.comm(), "emp", s.emp(), "safe", s.safe()));
        pr.roleplayJson = Views.json(rp);
        return Views.map("safe", s.safe(), "comm", s.comm(), "emp", s.emp(), "pass", s.pass(), "flags", s.badFlags(), "fixes", fixes, "good", goodOne);
    }

    public Map<String, Object> complete(User me) {
        EduProgress p = of(me.id);
        Map<String, Object> rp = Views.parse(p.roleplayJson, new TypeReference<Map<String, Object>>() {}, Map.of());
        boolean rpPassed = rp.values().stream().anyMatch(v -> v instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("passed")));
        List<String> lack = new ArrayList<>();
        if (!p.cardsDone) lack.add("규칙 카드");
        if (p.quizScore == null || p.quizScore < 4) lack.add("상황 퀴즈 4/5 이상");
        if (!rpPassed) lack.add("역할극 1명 이상 통과");
        if (!lack.isEmpty()) throw ApiException.bad("EDU_INCOMPLETE", "아직 남은 단계가 있어요: " + String.join(", ", lack));
        User u = users.findById(me.id).orElseThrow();
        u.eduDone = true;
        p.completedAt = LocalDateTime.now();
        return Views.map("completed", true, "completedAt", p.completedAt);
    }
}
