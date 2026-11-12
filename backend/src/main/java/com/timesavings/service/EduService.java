package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.AiClient;
import com.timesavings.ai.RoleplayAi;
import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.AppSetting;
import com.timesavings.domain.EduProgress;
import com.timesavings.domain.RoleplayPersona;
import com.timesavings.domain.User;
import com.timesavings.repo.AppSettingRepo;
import com.timesavings.repo.EduProgressRepo;
import com.timesavings.repo.RoleplayPersonaRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 첫 활동 연습(41판 B-54): 활동 전에 골라서 해 볼 수 있는 연습이다. 필수가 아니고 수료·배지·활동 제한이 없다.
 * AI 가 도움 받는 어르신 역할로 말하고(RoleplayAi), 답이 안전 규칙에 어긋나면 알려 주며 "이렇게 바꿔 말해 보세요"를 돌려준다.
 * 키가 없으면 가상 상대의 대본과 상황 목록(서버 설정 edu.situations)으로 말하고 규칙으로 판정한다.
 * 규칙 카드·퀴즈는 읽을거리로 남아 있고 어떤 조건에도 쓰이지 않는다.
 */
@Service
@Transactional
public class EduService {
    public static final TypeReference<List<Map<String, Object>>> TURNS = new TypeReference<>() {};
    public static final String SITUATIONS_KEY = "edu.situations";
    /** 상황 목록 기본값(운영자가 PUT /api/admin/edu/situations 로 바꾼다). flag = 그 상황에서 지켜야 할 안전 규칙 */
    public static final List<Map<String, Object>> DEFAULT_SITUATIONS = List.of(
            Views.map("key", "contact", "title", "연락처 교환", "line", "다음에도 자네가 와 주면 좋겠는데, 전화번호 좀 적어 주고 가.", "flag", "연락처"),
            Views.map("key", "money", "title", "금전 부탁", "line", "고마워서 그래. 얼마 안 되지만 이거 받아 둬. 차비라도 해.", "flag", "금전·선물"),
            Views.map("key", "body", "title", "신체 돌봄 요청", "line", "허리가 쑤셔서 그러는데, 여기 파스 좀 붙여 줄 수 있나?", "flag", "신체 돌봄"),
            Views.map("key", "privacy", "title", "개인 정보 질문", "line", "은행 앱이 안 돼서 그러는데, 비밀번호 불러 줄 테니 대신 좀 눌러 줘.", "flag", "개인정보"));
    private static final Map<String, String> BETTER = Map.of(
            "연락처", "다시 뵙고 싶으면 앱에서 저를 단골로 등록해 주세요. 연락처는 서로 주고받지 않기로 되어 있어요.",
            "금전·선물", "마음만 감사히 받을게요. 대신 도운 시간이 제 통장에 쌓여서 괜찮아요.",
            "신체 돌봄", "그건 제가 해 드릴 수 없어요. 손 닿는 곳에 꺼내 드릴게요. 많이 불편하시면 담당자께 꼭 알려 드릴게요.",
            "개인정보", "비밀번호 같은 건 제가 보거나 누르면 안 돼요. 직접 눌러 보시면 옆에서 천천히 알려 드릴게요.");
    private static final String BETTER_SOFT = "먼저 말씀을 한 번 받아 주고(“아, 그러셨어요?”), 천천히 또박또박 이어서 말해 보세요.";
    private static final String CLOSING = "그려, 고마워. 덕분에 든든했어. 다음에 또 와.";

    private final EduProgressRepo progress;
    private final RoleplayPersonaRepo personas;
    private final UserRepo users;
    private final AiClient ai;
    private final RoleplayAi roleplayAi;
    private final AppSettingRepo settings;

    public EduService(EduProgressRepo progress, RoleplayPersonaRepo personas, UserRepo users, AiClient ai, RoleplayAi roleplayAi, AppSettingRepo settings) {
        this.progress = progress; this.personas = personas; this.users = users; this.ai = ai; this.roleplayAi = roleplayAi; this.settings = settings;
    }

    public EduProgress of(Long userId) {
        return progress.findById(userId).orElseGet(() -> { EduProgress p = new EduProgress(); p.userId = userId; return progress.save(p); });
    }

    // ---------------- 상황 목록 (서버 설정) ----------------
    public List<Map<String, Object>> situations() {
        List<Map<String, Object>> saved = settings.findById(SITUATIONS_KEY).map(s -> Views.parse(s.value, TURNS, List.<Map<String, Object>>of())).orElse(List.of());
        return saved.isEmpty() ? DEFAULT_SITUATIONS : saved;
    }

    /** 운영자 설정: [{title, line, flag?}] 1~8개. flag 는 연락처·금전·선물·신체 돌봄·개인정보 중 하나 또는 없음. */
    public List<Map<String, Object>> saveSituations(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty() || items.size() > 8) throw ApiException.bad("INVALID_SITUATIONS", "상황은 1~8개로 정해 주세요");
        List<Map<String, Object>> out = new ArrayList<>();
        int no = 1;
        for (Map<String, Object> it : items) {
            String title = it.get("title") == null ? "" : String.valueOf(it.get("title")).trim();
            String line = it.get("line") == null ? "" : String.valueOf(it.get("line")).trim();
            String flag = it.get("flag") == null || String.valueOf(it.get("flag")).isBlank() ? null : String.valueOf(it.get("flag")).trim();
            if (title.length() < 2 || title.length() > 30) throw ApiException.bad("INVALID_SITUATIONS", "상황 이름은 2~30자로 적어 주세요");
            if (line.length() < 5 || line.length() > 150) throw ApiException.bad("INVALID_SITUATIONS", "어르신이 할 말은 5~150자로 적어 주세요");
            if (flag != null && !RoleplayAi.FLAGS.contains(flag)) throw ApiException.bad("INVALID_SITUATIONS", "안전 규칙은 " + String.join(", ", RoleplayAi.FLAGS) + " 중에서 골라 주세요");
            String key = it.get("key") == null || String.valueOf(it.get("key")).isBlank() ? "s" + no : String.valueOf(it.get("key")).trim();
            out.add(Views.map("key", key.length() > 20 ? key.substring(0, 20) : key, "title", title, "line", line, "flag", flag));
            no++;
        }
        AppSetting s = settings.findById(SITUATIONS_KEY).orElseGet(() -> { AppSetting x = new AppSetting(); x.key = SITUATIONS_KEY; return x; });
        s.value = Views.json(out);
        if (s.value.length() > 2000) throw ApiException.bad("INVALID_SITUATIONS", "상황 내용이 너무 길어요. 문장을 줄여 주세요");
        s.updatedAt = LocalDateTime.now();
        settings.save(s);
        return out;
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
        return Views.map("optional", true, "diag", diag, "cardsDone", p.cardsDone, "quizScore", p.quizScore,
                "roleplay", Views.parse(p.roleplayJson, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>()),
                "personas", personas.findAll().stream().filter(x -> x.active).map(x -> personaView(x, false)).toList(),
                "situations", situations().stream().map(s -> Views.map("key", s.get("key"), "title", s.get("title"))).toList(),
                "rules", EduContent.cardsFor(cats), "quiz", quiz);
    }

    public void diag(User me, Map<String, Object> body) { of(me.id).diagJson = Views.json(body); }

    public void cardsDone(User me) { of(me.id).cardsDone = true; }

    /** 읽을거리 퀴즈: 점수만 알려 준다(통과·탈락 없음). */
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
        return Views.map("score", score, "total", EduContent.QUIZ.size(), "results", results);
    }

    public RoleplayPersona persona(Long id) { return personas.findById(id).orElseThrow(() -> ApiException.notFound("가상 상대를 찾을 수 없어요")); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> options(List<Map<String, Object>> turns, int i) {
        return i < turns.size() ? (List<Map<String, Object>>) turns.get(i).getOrDefault("options", List.of()) : List.of();
    }

    /** 대본 선택지와 글자가 같으면 그 판정(없으면 null). */
    private static Map<String, Object> scripted(List<Map<String, Object>> turns, int i, String text) {
        for (Map<String, Object> o : options(turns, i))
            if (text.trim().equals(String.valueOf(o.get("t")).trim())) return o;
        return null;
    }

    /** 대본 선택지와 같으면 그 판정을, 아니면 AiClient(AI 또는 규칙)로 판정. */
    public Map<String, Object> classify(RoleplayPersona p, int turnIndex, String text) {
        Map<String, Object> o = scripted(Views.parse(p.turnsJson, TURNS, List.of()), turnIndex, text);
        if (o != null) return Views.map("quality", o.get("q"), "flag", o.get("f"), "source", "rule");
        return ai.roleplayClassify(p.name + "(" + p.trait + ")", text);
    }

    /** 연습 한 번의 길이: 대본이 있으면 대본 길이, 없으면 3번 주고받는다. */
    static int totalTurns(List<Map<String, Object>> turns) { return turns.isEmpty() ? 3 : turns.size(); }

    /** 키가 없을 때 어르신의 다음 말: 대본 → (대본이 없으면) 상황 목록 순서대로 → 마지막은 맺는 말. */
    private String ruleNext(List<Map<String, Object>> turns, int i, boolean last) {
        if (i < turns.size() && turns.get(i).get("next") != null && !String.valueOf(turns.get(i).get("next")).isBlank()) return String.valueOf(turns.get(i).get("next"));
        if (last) return CLOSING;
        List<Map<String, Object>> sit = situations();
        return String.valueOf(sit.get(i % sit.size()).get("line"));
    }

    private static String goodOption(List<Map<String, Object>> turns, int i) {
        return options(turns, i).stream().filter(o -> "good".equals(o.get("q"))).map(o -> String.valueOf(o.get("t"))).findFirst().orElse(null);
    }

    /** 안전 규칙 위반 알림과 "이렇게 바꿔 말해 보세요"(B-54). 잘한 답이면 null. */
    static Map<String, Object> feedback(String quality, String flag, String better) {
        boolean bad = "bad".equals(quality);
        if (!bad && !"poor".equals(quality)) return null;
        String b = better != null && !better.isBlank() ? better : flag != null && BETTER.containsKey(flag) ? BETTER.get(flag) : BETTER_SOFT;
        return Views.map("violation", bad, "flag", flag,
                "message", bad ? "안전 규칙에 어긋나요" + (flag == null ? "" : " · " + flag) : "조금 더 따뜻하게 말해 볼까요?", "better", b);
    }

    /**
     * POST /api/ai/roleplay — 봉사자의 한 마디를 받아 판정하고 어르신(AI)의 다음 말을 돌려준다.
     * {quality, flag, feedback:{violation, flag, message, better}|null, next, options[], done, turn, source}
     */
    public Map<String, Object> roleplayTurn(Long personaId, List<Map<String, Object>> history, String text) {
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "할 말을 입력해 주세요");
        RoleplayPersona p = persona(personaId);
        List<Map<String, Object>> turns = Views.parse(p.turnsJson, TURNS, List.of());
        int total = totalTurns(turns);
        int i = history == null ? 0 : (int) history.stream().filter(h -> "me".equals(h.get("who"))).count();
        if (i >= total) throw ApiException.conflict("ROLEPLAY_DONE", "이 연습은 끝났어요. 결과를 확인해 주세요");
        boolean last = i + 1 >= total;
        Map<String, Object> script = scripted(turns, i, text);
        RuleEngine.RpClass rule = RuleEngine.rpClassify(text);
        Optional<RoleplayAi.Turn> at = roleplayAi.turn(Views.map("name", p.name, "ageLabel", p.ageLabel, "trait", p.trait, "goal", p.goal),
                situations().stream().map(s -> Views.map("title", s.get("title"), "line", s.get("line"))).toList(), history, text, last);
        String quality, flag, better, next, source;
        List<Object> nextOptions = new ArrayList<>();
        if (at.isPresent()) {
            RoleplayAi.Turn t = at.get();
            // 대본 선택지 → 코드 규칙의 위반 판정 → AI 판정 순으로 믿는다(AI 는 위반을 뒤집지 못한다)
            if (script != null) { quality = (String) script.get("q"); flag = (String) script.get("f"); }
            else if ("bad".equals(rule.quality())) { quality = "bad"; flag = rule.flag(); }
            else { quality = t.quality(); flag = "bad".equals(t.quality()) ? t.flag() : null; }
            better = t.better();
            next = t.reply();
            source = "ai";
        } else {
            Map<String, Object> c = classify(p, i, text);
            quality = (String) c.get("quality"); flag = (String) c.get("flag");
            better = goodOption(turns, i);
            next = ruleNext(turns, i, last);
            source = String.valueOf(c.get("source"));
            if (!last) for (Map<String, Object> o : options(turns, i + 1)) nextOptions.add(o.get("t"));
        }
        Map<String, Object> out = Views.map("quality", quality, "flag", flag, "feedback", feedback(quality, flag, better), "next", next,
                "options", nextOptions, "done", last, "turn", i, "total", total, "source", source);
        if (at.isPresent()) out.put("provider", at.get().provider());
        return out;
    }

    /** 연습을 마치면 소통·공감 피드백을 모아 준다. 통과·수료는 없고, 해 본 기록만 남긴다. */
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
            if ("bad".equals(q) || "poor".equals(q)) {
                String better = l.get("better") instanceof String b && !b.isBlank() ? b : goodOption(turns, turn);
                Map<String, Object> fb = feedback(q, f, better);
                fixes.add(Views.map("said", text, "better", fb.get("better"), "flag", f, "violation", fb.get("violation")));
            }
            if ("good".equals(q) && goodOne == null) goodOne = Views.map("text", text);
            turn++;
        }
        RuleEngine.RpScore s = RuleEngine.rpScore(qs, flags);
        EduProgress pr = of(me.id);
        Map<String, Object> rp = Views.parse(pr.roleplayJson, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>());
        rp.put(String.valueOf(personaId), Views.map("practiced", true, "comm", s.comm(), "emp", s.emp(), "safe", s.safe(), "at", LocalDateTime.now().toString()));
        pr.roleplayJson = Views.json(rp);
        return Views.map("safe", s.safe(), "comm", s.comm(), "emp", s.emp(), "flags", s.badFlags(), "fixes", fixes, "good", goodOne,
                "note", "연습이라 점수는 참고용이에요. 수료나 통과는 없고, 몇 번이든 다시 해 볼 수 있어요.");
    }
}
