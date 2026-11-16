package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Views;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 담당자 AI 도우미(2026-11-16, 프로토타입 "AI 도우미" 패널)의 AI 부분.
 * 하는 일은 두 가지뿐이다: ① 질문을 정해진 의도 하나로 나누기 ② 코드가 만든 답 문장을 읽기 쉽게 다듬기.
 * 숫자·목록·링크는 OrgAssistantService 가 실제 자료로 만든다. 다듬은 문장에 자료에 없는 숫자나 진단하는 말이 있으면 버리고 규칙 문장을 쓴다.
 * 키가 없거나 실패하면 규칙(source:'rule').
 */
@Component
public class OrgAssistantAi {
    public static final List<String> INTENTS = List.of("GAPS", "CHECK_FIRST", "WEATHER", "DELEGATION", "APPLICATIONS", "VOLUNTEERS", "TODO", "DRAFT", "HELP");
    private final LlmJson llm;

    public OrgAssistantAi(LlmJson llm) { this.llm = llm; }

    private static final Pattern GAPS = Pattern.compile("빈\\s*칸|편성|비는|비어");
    private static final Pattern CHECK = Pattern.compile("안부|리포트|먼저\\s*(살|챙|물|볼)|변화");
    private static final Pattern WEATHER = Pattern.compile("한파|특보|폭염|대설|호우|태풍|날씨");
    private static final Pattern DELEGATION = Pattern.compile("위임|동의|만료|맡긴\\s*일");
    private static final Pattern APPS = Pattern.compile("신청|기관\\s*공고|모집|해\\s*드려요");
    private static final Pattern VOLS = Pattern.compile("봉사자|단골");
    private static final Pattern TODO = Pattern.compile("처리할|할\\s*일|오늘|대기|수락|일지|밀린");
    private static final Pattern DRAFT = Pattern.compile("공고|동행|올려|요청|장보기|말동무|병원");
    /** 건강 상태를 판단해 달라는 질문 */
    private static final Pattern DIAGNOSE = Pattern.compile("치매|우울증|우울한|조현|정신\\s*질환|병\\s*(인가|일까|같)|진단|증상이|아픈\\s*(건|거)|건강\\s*상태.*(어때|판단|알려)");
    /** 대신 결정·실행해 달라는 말 */
    private static final Pattern DECIDE = Pattern.compile("(수락|거절|승인|확정|회수|해지|마감|등록|올려|보내)\\s*(해|해\\s*줘|줘|주세요|해\\s*주세요|할까)|누구(로|를)\\s*(정|골|뽑)");

    public static boolean asksDiagnosis(String q) { return q != null && DIAGNOSE.matcher(q).find(); }

    public static boolean asksDecision(String q) { return q != null && DECIDE.matcher(q).find(); }

    /** 규칙 분류(키 없을 때·LLM 실패 때). 프로토타입 answer(q) 의 낱말 묶음을 그대로 쓰고 새 화면(위임·신청·봉사자) 낱말을 더했다. */
    public static String ruleIntent(String q) {
        String t = q == null ? "" : q;
        if (GAPS.matcher(t).find()) return "GAPS";
        if (CHECK.matcher(t).find()) return "CHECK_FIRST";
        if (WEATHER.matcher(t).find()) return "WEATHER";
        if (DELEGATION.matcher(t).find()) return "DELEGATION";
        if (APPS.matcher(t).find()) return "APPLICATIONS";
        if (VOLS.matcher(t).find()) return "VOLUNTEERS";
        if (DRAFT.matcher(t).find() && !TODO.matcher(t).find()) return "DRAFT";
        if (TODO.matcher(t).find()) return "TODO";
        if (DRAFT.matcher(t).find()) return "DRAFT";
        return "HELP";
    }

    public record Intent(String intent, String source) {}

    /** 규칙으로 못 나눈 질문만 LLM 에 의도를 묻는다(질문의 연락처·주소는 지우고 보낸다). */
    public Intent classify(String question) {
        String rule = ruleIntent(question);
        if (!"HELP".equals(rule) || !llm.available()) return new Intent(rule, "rule");
        String q = VoiceSafety.scrub(question == null ? "" : question).text();
        Optional<JsonNode> j = llm.ask("""
                너는 돌봄 기관 담당자 화면의 도우미다. 담당자의 말을 아래 의도 중 하나로만 나눈다. 답을 지어내지 않는다.
                GAPS(편성표 빈칸), CHECK_FIRST(먼저 안부 물어볼 분·변화 리포트), WEATHER(기상 특보 대비), DELEGATION(권한 위임·동의·만료),
                APPLICATIONS(기관 공고에 들어온 신청), VOLUNTEERS(봉사자 현황·단골), TODO(오늘 처리할 일), DRAFT(관리 대상 대신 요청 초안), HELP(그 밖).
                출력: {"intent":"GAPS"}
                """, "말: " + (q.length() > 200 ? q.substring(0, 200) : q), 60);
        if (j.isPresent()) {
            String i = j.get().path("intent").asText("").trim().toUpperCase();
            if (INTENTS.contains(i)) return new Intent(i, "ai");
        }
        return new Intent(rule, "rule");
    }

    private static final Pattern NUM = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /** 다듬은 문장의 숫자가 모두 근거(규칙 문장 + 자료)에 있는지. 하나라도 없으면 false. */
    static boolean numbersGrounded(String text, String basis) {
        Set<String> ok = new HashSet<>();
        Matcher b = NUM.matcher(basis == null ? "" : basis);
        while (b.find()) ok.add(b.group());
        Matcher m = NUM.matcher(text == null ? "" : text);
        while (m.find()) if (!ok.contains(m.group())) return false;
        return true;
    }

    /**
     * 답 문장 다듬기. ruleText 는 코드가 실제 자료로 만든 문장, facts 는 그 근거 값.
     * 돌려주는 값: {text, source: ai|rule, provider?}
     */
    public Map<String, Object> polish(String question, String ruleText, Map<String, Object> facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", ruleText);
        out.put("source", "rule");
        if (!llm.available()) return out;
        String q = VoiceSafety.scrub(question == null ? "" : question).text();
        String factJson = Views.json(facts == null ? Map.of() : facts);
        Optional<JsonNode> j = llm.ask("""
                너는 돌봄 기관 담당자 화면의 도우미다. '기본 답'을 뜻은 그대로 두고 읽기 쉬운 존댓말 한두 문장(120자 안)으로만 다듬는다.
                지킬 것: 숫자·이름·날짜는 '기본 답'과 '자료'에 있는 것만 쓰고 바꾸거나 새로 만들지 않는다. 자료에 없는 사실을 덧붙이지 않는다.
                건강·질병을 판단하는 말을 하지 않는다. 수락·거절·등록·위임 같은 결정을 대신했다고 말하지 않는다(담당자가 화면에서 직접 누른다).
                출력: {"text":"..."}
                """, "담당자의 말: " + (q.length() > 200 ? q.substring(0, 200) : q) + "\n기본 답: " + ruleText + "\n자료(JSON): " + factJson, 300);
        if (j.isEmpty()) return out;
        String t = j.get().path("text").asText("").replaceAll("\\s+", " ").trim();
        if (t.length() < 5 || t.length() > 240) return out;
        if (CareMemoAi.clean(t) == null || asksDiagnosis(t)) return out;            // 진단하는 말
        if (!VoiceSafety.scrub(t).removed().isEmpty()) return out;                     // 연락처·주소
        if (!numbersGrounded(t, ruleText + " " + factJson)) return out;               // 지어낸 숫자
        out.put("text", t);
        out.put("source", "ai");
        out.put("provider", llm.providerName());
        return out;
    }
}
