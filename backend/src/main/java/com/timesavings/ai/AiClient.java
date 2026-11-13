package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.Enums.FilterLevel;
import com.timesavings.domain.Enums.Level;
import com.timesavings.domain.PublicService;
import com.timesavings.repo.PublicServiceRepo;
import com.timesavings.service.Rules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.LocalDate;
import java.util.*;

/**
 * 모든 AI 호출이 지나가는 유일한 클래스.
 * 공급자(LlmProvider: Gemini, Anthropic)는 AI_PROVIDER(gemini|anthropic|rule)로 고르고, 비우면 GEMINI_API_KEY → ANTHROPIC_API_KEY → 규칙 순으로 자동 선택한다.
 * 키가 없거나 호출이 실패(429·오류·형식 이상)하면 그 호출만 RuleEngine 결과를 돌려준다.
 * 결과에는 항상 source: "ai" | "rule" 이 들어가고, ai 면 provider 도 들어간다. 키 값은 어디에도 기록하지 않는다.
 */
@Component
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private final ObjectMapper om = new ObjectMapper();
    private final PublicServiceRepo services;
    private final boolean enabledFlag;
    private final String providerSetting;
    private final GeminiProvider gemini;
    private final AnthropicProvider anthropic;

    public AiClient(PublicServiceRepo services, GeminiProvider gemini, AnthropicProvider anthropic,
                    @Value("${app.ai.enabled:true}") boolean enabledFlag,
                    @Value("${AI_PROVIDER:}") String providerSetting) {
        this.services = services;
        this.gemini = gemini;
        this.anthropic = anthropic;
        this.enabledFlag = enabledFlag;
        this.providerSetting = providerSetting == null ? "" : providerSetting.trim().toLowerCase();
    }

    private static final ThreadLocal<Boolean> RULE_ONLY = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> LAST_PROVIDER = new ThreadLocal<>();

    /** 설정과 키로 정한 공급자. 없으면 null(규칙 모드). */
    public LlmProvider provider() {
        if (!enabledFlag) return null;
        return switch (providerSetting) {
            case "rule", "none", "off" -> null;
            case "gemini", "google" -> gemini.available() ? gemini : null;
            case "anthropic", "claude" -> anthropic.available() ? anthropic : null;
            default -> gemini.available() ? gemini : anthropic.available() ? anthropic : null;
        };
    }

    public boolean enabled() { return !RULE_ONLY.get() && provider() != null; }

    /** 상태 표시용: 'gemini' | 'anthropic' | 'rule' */
    public String providerName() { LlmProvider p = provider(); return p == null ? "rule" : p.name(); }

    public String modelName() { LlmProvider p = provider(); return p == null ? null : p.model(); }

    /** 서버 음성 인식(Gemini 오디오)을 쓸 수 있는지: AI 가 켜져 있고 규칙 고정이 아니며 Gemini 키가 있을 때. */
    public boolean sttAvailable() {
        return enabledFlag && !List.of("rule", "none", "off").contains(providerSetting) && gemini.available();
    }

    /** 시드 데이터처럼 비용을 쓰면 안 되는 작업은 규칙 모드로만 돌린다. */
    public void ruleOnly(Runnable r) {
        RULE_ONLY.set(true);
        try { r.run(); } finally { RULE_ONLY.set(false); }
    }

    /** 공급자에게 JSON 하나만 답하게 한다. 실패하면 Optional.empty(). */
    Optional<JsonNode> askJson(String system, String user, int maxTokens) {
        if (RULE_ONLY.get()) return Optional.empty();
        LlmProvider p = provider();
        if (p == null) return Optional.empty();
        try {
            String t = p.completeJson(system + "\n\n반드시 JSON 객체 하나만 출력한다. 설명 문장·코드 블록 표시는 쓰지 않는다.", user, maxTokens);
            Optional<JsonNode> j = parseJsonObject(om, t);
            if (j.isEmpty()) log.warn("AI 응답 형식 이상({}), 규칙 모드로 대체", p.name());
            else LAST_PROVIDER.set(p.name());
            return j;
        } catch (HttpStatusCodeException e) {
            log.warn("AI 호출 실패({}, HTTP {}), 규칙 모드로 대체", p.name(), e.getStatusCode().value());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AI 호출 실패({}, {}), 규칙 모드로 대체", p.name(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 글 안에서 첫 '{' ~ 마지막 '}' 를 JSON 객체로 읽는다. */
    static Optional<JsonNode> parseJsonObject(ObjectMapper om, String t) {
        if (t == null) return Optional.empty();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) return Optional.empty();
        try {
            JsonNode n = om.readTree(t.substring(a, b + 1));
            return n != null && n.isObject() ? Optional.of(n) : Optional.empty();
        } catch (Exception e) { return Optional.empty(); }
    }

    /** source 를 넣고, ai 결과면 어느 공급자가 답했는지 provider 도 넣는다. */
    private Map<String, Object> src(Map<String, Object> out, String source) {
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", LAST_PROVIDER.get() != null ? LAST_PROVIDER.get() : providerName());
        return out;
    }

    private static String today(LocalDate d) { return d + " (" + KoreanParser.dateSpeech(d) + ")"; }

    private List<Map<String, Object>> svcMaps(List<PublicService> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicService s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id); m.put("name", s.name); m.put("target", s.target); m.put("content", s.content);
            m.put("applyHow", s.applyHow); m.put("contact", s.contact); m.put("region", s.region); m.put("keywords", s.keywords);
            out.add(m);
        }
        return out;
    }

    /**
     * 41판 B-67·B-09: 금지 요청(불가)의 대체 안내를 "근처에서 받을 수 있는 공공 서비스 안내"(위치 기준 안내 화면)로 잇는 길.
     * question 은 개인정보를 지운 요청 문장(위치 기준 안내의 질문으로 쓴다), link 는 화면 경로.
     */
    public static Map<String, Object> nearbyGuide(String text) {
        String q = VoiceSafety.scrub(text == null ? "" : text).text().replaceAll("\\s+", " ").trim();
        if (q.length() > 60) q = q.substring(0, 60);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", "근처에서 받을 수 있는 공공 서비스 찾기");
        m.put("question", q);
        m.put("link", "/public?near=1" + (q.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)));
        return m;
    }

    // ===================== 금지 요청 필터 (B-09) =====================
    public Map<String, Object> filter(String text) {
        RuleEngine.FilterResult rule = RuleEngine.filter(text);
        FilterLevel level = rule.level();
        String reason = rule.reason();
        List<String> svcKeys = new ArrayList<>(rule.serviceKeywords());
        String source = "rule";
        Optional<JsonNode> ai = askJson("""
                너는 이웃 돌봄 봉사 플랫폼의 '이건 안 돼요' 필터다. 요청 문장을 ALLOW(허용)/CAUTION(주의)/BLOCK(불가)로 분류한다.
                BLOCK: 신체 돌봄(목욕·기저귀·파스 붙이기·부축을 넘는 신체 접촉), 의료 행위(주사·투약·약 먹이기), 금전 관리(통장·카드·현금 맡기·대출), 위험 작업(사다리·고소 작업·전기 공사·가스).
                CAUTION: 허용되지만 주의가 필요한 경우(부축, 은행 동행, 칼·불 사용, 반려동물, 무거운 짐 등).
                출력: {"level":"ALLOW|CAUTION|BLOCK","reason":"사용자에게 보여 줄 쉬운 한국어 1~2문장","category":"신체 돌봄|의료 행위|금전 관리|위험 작업|null"}
                """, "요청: " + text, 300);
        if (ai.isPresent()) {
            JsonNode j = ai.get();
            try {
                FilterLevel l = FilterLevel.valueOf(j.path("level").asText());
                // 규칙이 BLOCK 으로 잡은 것은 AI 가 풀어 주지 않는다(안전 쪽으로)
                level = rule.level() == FilterLevel.BLOCK ? FilterLevel.BLOCK : l;
                if (level == l && !j.path("reason").asText().isBlank()) reason = j.path("reason").asText();
                String cat = j.path("category").asText("");
                if (cat.contains("신체")) svcKeys.addAll(List.of("장기요양", "방문요양", "신체"));
                if (cat.contains("의료")) svcKeys.addAll(List.of("방문건강", "방문간호", "의료"));
                if (cat.contains("금전")) svcKeys.addAll(List.of("금전", "후견"));
                if (cat.contains("위험")) svcKeys.addAll(List.of("주거", "집수리"));
                source = "ai";
            } catch (Exception ignore) { /* 형식이 이상하면 규칙 결과 사용 */ }
        }
        List<PublicService> alt = level == FilterLevel.BLOCK
                ? RuleEngine.searchServices(text, svcKeys, null, services.findAll(), 3) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level.name());
        out.put("reason", reason);
        out.put("alternatives", svcMaps(alt));
        if (level == FilterLevel.BLOCK) out.put("nearby", nearbyGuide(text));
        return src(out, source);
    }

    // ===================== 난이도 제안 (B-08, B-27) =====================
    public Map<String, Object> difficulty(String text, List<String> cats, Double hours) {
        List<String> cs = cats == null ? List.of() : cats.stream().filter(Rules::isCategory).toList();
        RuleEngine.DifficultyResult r = RuleEngine.difficulty(text, cs);
        Level level = r.level();
        String reason = r.reason();
        Map<String, Integer> factors = r.factors();
        String source = "rule";
        Optional<JsonNode> ai = askJson("""
                너는 이웃 돌봄 활동의 난이도를 제안한다. 요소: physical(신체 부담), responsibility(책임), risk(위험), expertise(전문성), travel(이동), access(접근성) 각각 0~2.
                등급: BASIC(기본 1.0), EFFORT(수고 1.2) 두 가지뿐이다. 카테고리 기본 등급보다 낮게 제안하지 않는다(장보기·심부름/가벼운 집안일=EFFORT, 동행을 포함한 나머지=BASIC). 책임 요소만으로는 등급을 올리지 않는다.
                출력: {"level":"BASIC|EFFORT","reason":"근거 1~2문장","factors":{"physical":0,"responsibility":0,"risk":0,"expertise":0,"travel":0,"access":0}}
                """, "카테고리: " + cs + "\n요청 내용: " + text, 300);
        if (ai.isPresent()) {
            try {
                Level l = Level.valueOf(ai.get().path("level").asText());
                Level base = cs.isEmpty() ? Level.BASIC : Rules.maxLevel(cs);
                level = Rules.current(Rules.max(base, l)); // 예전 등급(RESPONSIBLE)이 와도 수고로 받는다(7.21)
                if (!ai.get().path("reason").asText().isBlank()) reason = ai.get().path("reason").asText();
                Map<String, Integer> f = new LinkedHashMap<>();
                for (String k : List.of("physical", "responsibility", "risk", "expertise", "travel", "access"))
                    f.put(k, Math.max(0, Math.min(2, ai.get().path("factors").path(k).asInt(factors.get(k)))));
                factors = f;
                source = "ai";
            } catch (Exception ignore) { }
        }
        double w = Rules.weight(level);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level.name());
        out.put("weight", w);
        out.put("reason", reason);
        out.put("factors", factors);
        out.put("estEarn", hours == null ? null : Rules.earn(hours, w));
        return src(out, source);
    }

    // ===================== 대화형 공고 작성 (B-02) =====================
    public Map<String, Object> postChat(String type, List<PostChatRule.Msg> messages, LocalDate today, String defaultArea, boolean voice) {
        PostChatRule.Result rule = PostChatRule.run(type, messages, today, defaultArea, voice);
        PostChatRule.Draft d = rule.draft();
        String reply = rule.reply();
        boolean done = rule.done();
        List<String> missing = rule.missing();
        String intent = rule.intent();
        Map<String, String> confidence = rule.confidence();
        boolean emergency = rule.emergency();
        LinkedHashSet<String> pii = new LinkedHashSet<>(rule.piiRemoved());
        String source = "rule";

        if (enabled() && !emergency) {
            StringBuilder conv = new StringBuilder();
            for (PostChatRule.Msg m : messages) conv.append(m.role().equals("user") ? "사용자: " : "도우미: ").append(m.text()).append("\n");
            String voiceRule = voice ? """
                    입력은 음성 받아쓰기라 띄어쓰기·오타·군말(어, 음, 그러니까)이 섞여 있다. 뜻을 너그럽게 읽는다.
                    되물을 때는 존댓말로 짧게, 한 번에 하나만 묻는다. reply 는 소리로 읽기 좋은 1~2문장으로 쓴다.
                    """ : "되물을 때는 한 번에 하나만, 친절한 존댓말로 묻는다.";
            Optional<JsonNode> ai = askJson("""
                    너는 이웃 돌봄 플랫폼 '시간 적금'의 공고 작성 도우미다. 오늘은 %s 이다.
                    공고 종류: %s (NEED=도움 요청 공고, OFFER=봉사 가능 공고).
                    대화에서 날짜(YYYY-MM-DD), 시작 시각·끝 시각(소수 시각, 14.5=오후 2시 30분, 30분 단위), 장소(동 이름), 도움 내용, 카테고리를 뽑는다.
                    카테고리는 다음 6개 중에서만: 말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행.
                    NEED 는 estHours(활동 시간, 0.5~4) 가 필요하고, OFFER 는 가능한 시간 범위(startHour~endHour)가 필요하다.
                    빠진 항목이 있으면 missing 에 넣고 reply 로 하나만 되묻는다. 모두 있으면 done=true 이고 reply 로 요약을 보여 준다.
                    지어내지 않는다. 사용자가 말하지 않은 날짜·시간은 비워 둔다. 장소가 없으면 기본 지역 "%s" 를 쓴다.
                    summarySpeech: done 이면 소리로 읽어 줄 요약 한 문장(예: "11월 18일 수요일 오후 2시부터 2시간, 병원 동행 요청서를 만들었어요. 올릴까요?").
                    intent: NEW_REQUEST(새 요청) / REGULAR_REQUEST(지난번 그 사람 등 단골에게) / CHANGE_CANCEL(기존 약속 변경·취소) / CONFIRM(활동 끝 확인) / QUESTION(제도·이용 질문) / CHAT(그냥 이야기) / EMERGENCY(위급).
                    confidence: date·time·area·category 칸마다 high|medium|low. 오전·오후를 말하지 않은 시각은 medium.
                    caution: 방문에 필요한 배려만 짧은 문장으로(예: "천천히 걸어 주세요"). 병명·진단명은 쓰지 않는다.
                    자동 반복 등록은 없다. 사용자가 "매주"·"화요일마다"처럼 말해도 가장 가까운 한 번만 초안으로 만들고, 반복해서 올려 준다고 말하지 않는다.
                    전화번호·계좌번호·상세 주소(아파트 동·호수, 번지)는 draft 어디에도 쓰지 않는다.
                    %s
                    출력: {"reply":"","done":false,"intent":"NEW_REQUEST","missing":["date|startHour|endHour|estHours|area|content"],
                     "confidence":{"date":"low","time":"low","area":"low","category":"low"},
                     "draft":{"date":null,"startHour":null,"endHour":null,"area":null,"categories":[],"title":"","detail":"","note":"","estHours":null,"caution":null,"summarySpeech":null}}
                    """.formatted(today(today), type, defaultArea == null ? "" : defaultArea, voiceRule),
                    conv.toString(), 700);
            if (ai.isPresent()) {
                try {
                    JsonNode j = ai.get();
                    JsonNode dj = j.path("draft");
                    PostChatRule.Draft x = new PostChatRule.Draft();
                    if (dj.hasNonNull("date") && !dj.path("date").asText().isBlank()) x.date = LocalDate.parse(dj.path("date").asText());
                    if (dj.hasNonNull("startHour")) x.startHour = Rules.round05(dj.path("startHour").asDouble());
                    if (dj.hasNonNull("endHour")) x.endHour = Rules.round05(dj.path("endHour").asDouble());
                    if (dj.hasNonNull("estHours")) x.estHours = Math.max(0.5, Math.min(4, Rules.round05(dj.path("estHours").asDouble())));
                    x.area = dj.path("area").asText(null);
                    for (JsonNode c : dj.path("categories")) if (Rules.isCategory(c.asText())) x.categories.add(c.asText());
                    x.title = dj.path("title").asText(null);
                    x.detail = dj.path("detail").asText(null);
                    x.note = dj.path("note").asText(null);
                    x.summarySpeech = dj.path("summarySpeech").asText(null);
                    x.caution = VoiceSafety.sanitizeCaution(dj.path("caution").asText(null));
                    x.repeatNotice = d.repeatNotice; // 반복을 바랐는지는 규칙이 본다
                    x.repeatLabel = d.repeatLabel;
                    if (x.caution == null) x.caution = d.caution;
                    String in = j.path("intent").asText("");
                    if (VoiceSafety.INTENTS.contains(in)) intent = in;
                    Map<String, String> conf2 = new LinkedHashMap<>();
                    for (String k : List.of("date", "time", "area", "category")) {
                        String v = j.path("confidence").path(k).asText("");
                        conf2.put(k, List.of("high", "medium", "low").contains(v) ? v : confidence.get(k));
                    }
                    confidence = conf2;
                    List<String> miss = new ArrayList<>();
                    for (JsonNode n : j.path("missing")) miss.add(n.asText());
                    d = x;
                    done = j.path("done").asBoolean(false) && !x.categories.isEmpty() && x.date != null && x.startHour != null;
                    missing = done ? List.of() : miss;
                    reply = j.path("reply").asText(reply);
                    source = "ai";
                } catch (Exception e) {
                    log.warn("AI 공고 초안 형식 오류, 규칙 결과 사용");
                }
            }
        }

        // 서버 쪽 안전 장치: AI 결과에도 개인정보 지우기·병명 지우기를 다시 건다
        for (String f : new String[]{"title", "detail", "note"}) {
            String v = f.equals("title") ? d.title : f.equals("detail") ? d.detail : d.note;
            VoiceSafety.Scrubbed sc = VoiceSafety.scrub(v);
            pii.addAll(sc.removed());
            String clean = VoiceSafety.stripHealth(sc.text());
            if (f.equals("title")) d.title = clean; else if (f.equals("detail")) d.detail = clean; else d.note = clean;
        }
        if (emergency || "EMERGENCY".equals(intent)) {
            emergency = true;
            intent = "EMERGENCY";
            done = false;
            reply = VoiceSafety.emergencyReply(voice);
        }
        String all = String.join(" ", messages.stream().filter(m -> "user".equals(m.role())).map(m -> VoiceSafety.scrub(m.text()).text()).toList());
        Map<String, Object> filter = filter(all);
        Map<String, Object> diff = difficulty(all, d.categories, d.estHours);
        if ("BLOCK".equals(filter.get("level")) && !emergency) {
            done = false;
            reply = "이 부탁은 봉사로 도와드리기 어려워요. " + filter.get("reason") + " 대신 아래 공공 서비스를 알아보실 수 있어요.";
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("date", d.date == null ? null : d.date.toString());
        draft.put("startHour", d.startHour);
        draft.put("endHour", d.endHour);
        draft.put("area", d.area);
        draft.put("categories", d.categories);
        draft.put("title", d.title);
        draft.put("detail", d.detail);
        draft.put("note", d.note);
        draft.put("estHours", d.estHours);
        draft.put("caution", d.caution);
        draft.put("repeatNotice", d.repeatNotice);
        // 기능 U-09: "매주"면 정기 요청으로 이어 갈 수 있게 요일을 싣는다(요일을 말하지 않았으면 초안 날짜의 요일). 등록은 사용자가 고른다.
        if (PostChatRule.weekly(d.repeatLabel) && !"OFFER".equalsIgnoreCase(type)) {
            Integer wd = PostChatRule.weekdayOf(d.repeatLabel);
            if (wd == null && d.date != null) wd = d.date.getDayOfWeek().getValue();
            draft.put("repeat", com.timesavings.service.Views.map("weekly", true, "weekday", wd,
                    "label", wd == null ? "매주" : com.timesavings.service.RecurringService.dayLabel(wd)));
        }
        draft.put("summarySpeech", done ? PostChatRule.withRepeatNotice(d.summarySpeech, d.repeatNotice) : null);

        Map<String, Object> out = new LinkedHashMap<>();
        // 다 채워진 요약을 그대로 답으로 쓸 때(음성)는 답에도 반복 안내를 붙인다
        out.put("reply", done && reply != null && reply.equals(d.summarySpeech) ? PostChatRule.withRepeatNotice(reply, d.repeatNotice) : reply);
        out.put("done", done);
        out.put("intent", intent);
        out.put("draft", draft);
        out.put("confidence", confidence);
        out.put("missing", missing);
        out.put("filter", filter);
        out.put("difficulty", diff);
        out.put("safety", new LinkedHashMap<>(Map.of("emergency", emergency, "piiRemoved", new ArrayList<>(pii))));
        out.put("inputMode", voice ? "voice" : "text");
        return src(out, source);
    }

    // ===================== 활동 일지 정리 (B-24) =====================
    public Map<String, Object> logTidy(String raw) {
        String text = RuleEngine.logTidy(raw);
        String source = "rule";
        Optional<JsonNode> ai = askJson("""
                봉사자가 쓴 짧은 활동 기록을 읽기 쉬운 활동 일지 2~3줄로 다듬는다. 있었던 일만 쓰고 새 사실·감정·건강 판단을 지어내지 않는다.
                각 줄은 '~했어요' 체. 출력: {"text":"줄1\\n줄2"}
                """, "기록: " + raw, 300);
        if (ai.isPresent() && !ai.get().path("text").asText().isBlank()) { text = ai.get().path("text").asText(); source = "ai"; }
        return src(new LinkedHashMap<>(Map.of("text", text)), source);
    }

    // ===================== 공공 서비스 안내 (P-01, RAG) =====================
    public Map<String, Object> publicServices(String question, String area, String ageGroup) {
        List<PublicService> found = RuleEngine.searchServices(question, List.of(), area, services.findAll(), 4);
        String answer = RuleEngine.servicesAnswer(found);
        String source = "rule";
        if (!found.isEmpty()) {
            StringBuilder ctx = new StringBuilder();
            for (PublicService s : found)
                ctx.append("- ").append(s.name).append(" | 대상: ").append(s.target).append(" | 내용: ").append(s.content)
                        .append(" | 신청: ").append(s.applyHow).append(" | 문의: ").append(s.contact).append("\n");
            Optional<JsonNode> ai = askJson("""
                    너는 공공 서비스 안내 도우미다. 아래 '검색 결과'에 있는 서비스만 쉬운 말로 설명한다. 목록에 없는 서비스·전화번호·금액은 절대 만들지 않는다.
                    자격(대상 여부)을 판정하지 않는다. 마지막에 "대상이 되는지는 기관에 확인해 주세요"를 안내한다. 3~6문장.
                    출력: {"answer":"..."}
                    """, "질문: " + question + "\n상황: 지역 " + area + ", 연령대 " + ageGroup + "\n검색 결과:\n" + ctx, 600);
            if (ai.isPresent() && !ai.get().path("answer").asText().isBlank()) { answer = ai.get().path("answer").asText(); source = "ai"; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("services", svcMaps(found));
        return src(out, source);
    }

    // ===================== AI 도움 추천 =====================
    public Map<String, Object> recommend(String question) {
        List<String> cats = new ArrayList<>(KoreanParser.categories(question == null ? "" : question));
        boolean helper = RuleEngine.wantsToHelp(question == null ? "" : question);
        String answer = cats.isEmpty()
                ? "어떤 도움인지 조금 더 알려 주시면 맞는 공고를 찾아 드릴게요. 예: 병원 동행, 장보기, 휴대폰 사용"
                : String.join("·", cats) + (helper ? " 도움이 필요한 이웃의 공고를 찾아봤어요." : " 도움을 줄 수 있는 이웃의 공고를 찾아봤어요.");
        String source = "rule";
        Optional<JsonNode> ai = askJson("""
                사용자의 고민을 읽고 맞는 돌봄 카테고리를 고른다(말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행 중에서만).
                helper=true 는 사용자가 돕고 싶어 하는 경우. 출력: {"categories":[],"helper":false,"answer":"한두 문장 안내"}
                """, "고민: " + question, 300);
        if (ai.isPresent()) {
            List<String> c2 = new ArrayList<>();
            for (JsonNode n : ai.get().path("categories")) if (Rules.isCategory(n.asText())) c2.add(n.asText());
            if (!c2.isEmpty()) { cats = c2; helper = ai.get().path("helper").asBoolean(helper); answer = ai.get().path("answer").asText(answer); source = "ai"; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("categories", cats);
        out.put("helper", helper);
        return src(out, source);
    }

    // ===================== 교육 역할극 판정 =====================
    public Map<String, Object> roleplayClassify(String personaContext, String text) {
        RuleEngine.RpClass r = RuleEngine.rpClassify(text);
        String quality = r.quality(), flag = r.flag(), source = "rule";
        Optional<JsonNode> ai = askJson("""
                봉사자 안전 교육 역할극 채점자다. 봉사자의 한 마디를 good/ok/poor/bad 로 판정한다.
                bad = 안전 규칙 위반(연락처 주고받기, 돈·선물 받기, 신체 돌봄 수락, 비밀번호 등 개인정보 요구). 위반이면 flag 를 "연락처"|"금전·선물"|"신체 돌봄"|"개인정보" 중 하나로.
                good = 상대 말을 받아 주며 규칙을 지키고 대안을 안내. ok = 무난하지만 짧음. poor = 무심하거나 퉁명.
                출력: {"quality":"good|ok|poor|bad","flag":null}
                """, "상황: " + personaContext + "\n봉사자: " + text, 150);
        if (ai.isPresent()) {
            String q = ai.get().path("quality").asText("");
            if (List.of("good", "ok", "poor", "bad").contains(q)) {
                // 규칙이 위반으로 본 것은 그대로 위반(안전 쪽)
                if (!"bad".equals(quality)) { quality = q; flag = ai.get().hasNonNull("flag") ? ai.get().path("flag").asText(null) : null; }
                source = "ai";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quality", quality);
        out.put("flag", flag);
        return src(out, source);
    }

    // ===================== 생활 변화 리포트 서술 (P-03) =====================
    public Map<String, Object> lifeNarrative(String subjectLabel, int count, int prev, Map<String, Integer> byCat, List<String> logs) {
        String text = RuleEngine.narrative(subjectLabel, count, prev, byCat, logs);
        String source = "rule";
        Optional<JsonNode> ai = askJson("""
                월간 생활 변화 리포트의 서술 부분을 쓴다. 숫자는 주어진 통계만 쓰고 바꾸지 않는다.
                활동 일지 내용을 바탕으로 전월 대비 생활의 변화를 3~4문장으로 담담하게 쓴다. 진단·건강 상태 판단·의학적 추측은 하지 않는다.
                출력: {"narrative":"..."}
                """, "대상: " + subjectLabel + "\n이번 달 활동 " + count + "회, 지난달 " + prev + "회\n카테고리별: " + byCat
                + "\n활동 일지:\n" + String.join("\n", logs.stream().limit(10).toList()), 500);
        if (ai.isPresent() && !ai.get().path("narrative").asText().isBlank()) { text = ai.get().path("narrative").asText(); source = "ai"; }
        return src(new LinkedHashMap<>(Map.of("narrative", text)), source);
    }
}
