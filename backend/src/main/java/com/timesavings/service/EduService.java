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
    /**
     * 상황 목록 기본값(2026-11-12 프로토타입 문구, 운영자가 PUT /api/admin/edu/situations 로 바꾼다).
     * line = 어르신이 던지는 말, bad·good = 고를 수 있는 답 두 가지, reason = 왜 바꿔 말해야 하는지, flag = 그 상황의 안전 규칙
     */
    public static final List<Map<String, Object>> DEFAULT_SITUATIONS = List.of(
            Views.map("key", "contact", "title", "연락처를 물어볼 때", "line", "선생님 덕분에 참 좋네요. 전화번호 좀 알려줘요. 나중에 따로 부탁하고 싶어서요.",
                    "bad", "네, 제 번호 적어 드릴게요.", "good", "번호는 서로 못 주고받게 돼 있어요. 필요하시면 앱에서 공고로 다시 불러 주세요.",
                    "reason", "방문 밖 개인 연락은 안전 규칙에 어긋나요. 부드럽게 앱으로 안내해 보세요.", "flag", "연락처"),
            Views.map("key", "money", "title", "돈을 부탁받을 때", "line", "오늘 장 보는 데 돈이 모자라서… 5만 원만 먼저 내 줄 수 있어요?",
                    "bad", "네, 일단 제가 내 드릴게요.", "good", "돈 문제는 제가 도와드리기 어려워요. 대신 장 보는 걸 같이 해 드릴게요.",
                    "reason", "금전 거래는 안 돼요. 거절하되 도울 수 있는 일을 함께 말해 보세요.", "flag", "금전·선물"),
            Views.map("key", "body", "title", "몸 돌봄을 부탁받을 때", "line", "허리가 아파서 그런데, 목욕 좀 시켜 줄 수 있어요?",
                    "bad", "네, 제가 씻겨 드릴게요.", "good", "몸 돌봄은 제가 할 수 없는 일이에요. 방문요양 같은 서비스를 같이 알아봐 드릴게요.",
                    "reason", "신체 돌봄·의료 행위는 하지 않아요. 알맞은 서비스를 안내하는 말이 좋아요.", "flag", "신체 돌봄"),
            Views.map("key", "privacy", "title", "개인 정보를 물어볼 때", "line", "혹시 어디 사세요? 집 호수도 알려 주면 좋겠어요.",
                    "bad", "네, 저는 ○○아파트 101동이에요.", "good", "제 개인 정보는 말씀드리기 어려워요. 오늘 필요한 일부터 같이 해 볼까요?",
                    "reason", "내 개인 정보는 나누지 않아요. 편안한 말투로 화제를 돌려 보세요.", "flag", "개인정보"));
    public static final String PRAISE = "잘했어요! 안전 규칙을 지키면서 따뜻하게 말했어요.";
    private static final java.util.regex.Pattern AGREE = java.util.regex.Pattern.compile("^\\s*(네|예|그럼요|그래요|알겠|좋아요)");
    private static final java.util.regex.Pattern REFUSE = java.util.regex.Pattern.compile("안 돼|안돼|못 |못해|어려워|어렵|할 수 없|없어요|규칙|않아|않기|대신|죄송|마음만");
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
            Map<String, Object> row = Views.map("key", key.length() > 20 ? key.substring(0, 20) : key, "title", title, "line", line, "flag", flag);
            for (String k : List.of("bad", "good", "reason")) { // 선택: 고를 답 두 가지와 바꿔 말해야 하는 이유
                String v = it.get(k) == null ? "" : String.valueOf(it.get(k)).trim();
                if (v.length() > 150) throw ApiException.bad("INVALID_SITUATIONS", "답과 이유는 150자 안으로 적어 주세요");
                if (!v.isBlank()) row.put(k, v);
            }
            out.add(row);
            no++;
        }
        AppSetting s = settings.findById(SITUATIONS_KEY).orElseGet(() -> { AppSetting x = new AppSetting(); x.key = SITUATIONS_KEY; return x; });
        s.value = Views.json(out);
        if (s.value.length() > 2000) throw ApiException.bad("INVALID_SITUATIONS", "상황 내용이 너무 길어요. 문장을 줄여 주세요");
        s.updatedAt = LocalDateTime.now();
        settings.save(s);
        return out;
    }

    public List<Map<String, Object>> situationViews() { return situations().stream().map(EduService::situationView).toList(); }

    /** 사용자에게 주는 상황: 어느 답이 맞는지는 알려 주지 않고 고를 답만 섞어서 준다. */
    static Map<String, Object> situationView(Map<String, Object> s) {
        List<Object> options = new ArrayList<>();
        if (s.get("bad") != null) options.add(s.get("bad"));
        if (s.get("good") != null) options.add(s.get("good"));
        if (options.size() == 2 && Math.floorMod(String.valueOf(s.get("key")).hashCode(), 2) == 1) java.util.Collections.reverse(options);
        return Views.map("key", s.get("key"), "title", s.get("title"), "line", s.get("line"), "options", options);
    }

    /**
     * POST /api/edu/practice — 상황 하나 연습(프로토타입 2026-11-12): 어르신(AI)이 상황을 던지고, 내 답이 안전 규칙에 어긋나면
     * "이렇게 바꿔 말해 보세요"(이유 + 바꿔 말할 문장)를, 잘했으면 칭찬을 돌려준다. 기록·수료는 남기지 않는다.
     * {quality, flag, ok, praise|null, feedback:{violation, flag, message, reason, better}|null, reply, source}
     */
    public Map<String, Object> practice(String key, String text) { return practice(null, key, text); }

    /**
     * 기능 U-34 첫 활동 교육(안전 교육): 로그인한 사용자가 상황을 안전 규칙에 맞게 답하면 그 상황을 끝낸 것으로 적고,
     * 지금 상황 목록을 모두 끝내면 이수(users.edu_done, edu_progress.completed_at)로 기록한다. 응답에 education{…}이 붙는다.
     */
    public Map<String, Object> practice(User me, String key, String text) {
        Map<String, Object> out = practiceOnce(key, text);
        if (me != null && me.role == com.timesavings.domain.Enums.Role.USER) {
            if (Boolean.TRUE.equals(out.get("ok"))) markPracticed(me, key);
            out.put("education", education(users.findById(me.id).orElse(me)));
        }
        return out;
    }

    private static final TypeReference<List<String>> KEYS = new TypeReference<>() {};

    private void markPracticed(User me, String key) {
        EduProgress p = of(me.id);
        List<String> done = new ArrayList<>(Views.parse(p.practiceJson, KEYS, List.<String>of()));
        if (!done.contains(key)) { done.add(key); p.practiceJson = Views.json(done); }
        boolean all = situations().stream().allMatch(s -> done.contains(String.valueOf(s.get("key"))));
        if (!all) return;
        User u = users.findById(me.id).orElse(null);
        if (u != null && !u.eduDone) {
            u.eduDone = true;
            p.completedAt = LocalDateTime.now();
        }
    }

    /** 교육 진행 상태: {done, completedAt, total, doneCount, doneKeys[], required, on} */
    public Map<String, Object> education(User me) {
        EduProgress p = of(me.id);
        List<String> keys = situations().stream().map(s -> String.valueOf(s.get("key"))).toList();
        List<String> done = Views.parse(p.practiceJson, KEYS, List.<String>of()).stream().filter(keys::contains).toList();
        return Views.map("done", me.eduDone, "completedAt", p.completedAt == null ? null : p.completedAt.toString(),
                "total", keys.size(), "doneCount", me.eduDone ? keys.size() : done.size(), "doneKeys", done,
                "required", gate != null && gate.eduRequired(), "on", gate == null || gate.eduOn());
    }

    /** 기능 U-34: 교육이 필수인지(운영자 설정) — 생성자를 바꾸지 않으려고 필드 주입 */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private VolunteerGate gate;

    private Map<String, Object> practiceOnce(String key, String text) {
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "할 말을 입력해 주세요");
        Map<String, Object> sit = situations().stream().filter(x -> String.valueOf(x.get("key")).equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("연습 상황을 찾을 수 없어요"));
        String t = text.trim();
        String sitFlag = (String) sit.get("flag");
        boolean pickedGood = t.equals(sit.get("good")), pickedBad = t.equals(sit.get("bad"));
        RuleEngine.RpClass rule = RuleEngine.rpClassify(t);
        boolean ruleBad = "bad".equals(rule.quality()) || (sitFlag != null && AGREE.matcher(t).find() && !REFUSE.matcher(t).find()); // 부탁을 그대로 들어준 답
        Optional<RoleplayAi.Turn> at = pickedGood || pickedBad ? Optional.empty()
                : roleplayAi.turn(Views.map("name", "도움 받는 어르신", "trait", "혼자 사시고 정이 많으세요"), List.of(Views.map("title", sit.get("title"), "line", sit.get("line"))),
                List.of(Views.map("who", "them", "text", sit.get("line"))), t, true);
        String quality, flag, better = (String) sit.get("good"), source = "rule";
        if (pickedGood) { quality = "good"; flag = null; }
        else if (pickedBad || ruleBad) { quality = "bad"; flag = rule.flag() != null ? rule.flag() : sitFlag; }
        else if (at.isPresent()) {
            quality = at.get().quality(); flag = "bad".equals(quality) ? (at.get().flag() != null ? at.get().flag() : sitFlag) : null;
            if (better == null) better = at.get().better();
            source = "ai";
        } else { quality = rule.quality(); flag = null; }
        boolean ok = "good".equals(quality) || "ok".equals(quality);
        Map<String, Object> fb = feedback(quality, flag, better);
        if (fb != null) fb.put("reason", sit.get("reason") != null ? sit.get("reason") : fb.get("message"));
        String reply = at.isPresent() ? at.get().reply() : ok ? "그려요, 알겠어요. 그렇게 말해 주니 고맙네요." : "아이고, 고마워라. 그럼 그렇게 해 줘요.";
        Map<String, Object> out = Views.map("key", key, "quality", quality, "flag", flag, "ok", ok, "praise", ok ? PRAISE : null, "feedback", fb,
                "reply", reply, "source", at.isPresent() ? "ai" : source);
        if (at.isPresent()) out.put("provider", at.get().provider());
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
        return Views.map("optional", gate == null || !gate.eduRequired(), "education", education(users.findById(me.id).orElse(me)), "diag", diag, "cardsDone", p.cardsDone, "quizScore", p.quizScore,
                "roleplay", Views.parse(p.roleplayJson, new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>()),
                "personas", personas.findAll().stream().filter(x -> x.active).map(x -> personaView(x, false)).toList(),
                "situations", situationViews(),
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
