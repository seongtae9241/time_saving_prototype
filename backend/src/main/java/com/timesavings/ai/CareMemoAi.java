package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 7.14 C1 전달 메모 정리: 메모를 "다음에 챙길 것 / 병원 안내 있음(요약, 병명 없음) / 생활 변화"로 나누고 생활 변화 신호를 고른다.
 * 신호 = 코드 키워드 ∪ LLM 분류(정해진 이름만). 진단·건강 판단 문장은 남기지 않는다(LLM 결과도 서버가 다시 거른다).
 * 키가 없거나 실패하면 규칙으로 나눈다(source:'rule').
 */
@Component
public class CareMemoAi {
    private final LlmJson llm;

    public CareMemoAi(LlmJson llm) { this.llm = llm; }

    /** 생활 변화 신호(코드 키워드). 이름은 이 목록에서만 쓴다. */
    public static final LinkedHashMap<String, Pattern> SIGNALS = new LinkedHashMap<>();
    static {
        // 41판 B-36: 한 번만 적혀도 담당자에게 알리는 뚜렷한 표현(URGENT 참고)
        SIGNALS.put("쓰러짐", Pattern.compile("쓰러지|쓰러졌|쓰러져|쓰러짐|쓰러진|실신|기절|의식(을|이)\\s*(잃|없)|정신을\\s*잃"));
        SIGNALS.put("넘어짐", Pattern.compile("넘어지|넘어졌|넘어져|넘어짐|넘어진|낙상|미끄러져|미끄러지셨|주저앉"));
        SIGNALS.put("식사 거름", Pattern.compile("(식사|끼니|밥|점심|저녁|아침)\\s*(을|를|도)?\\s*(자주\\s*|계속\\s*)?(거르|거름|거른|걸러|걸렀|굶|안\\s*드|못\\s*드|안\\s*먹|못\\s*먹)|입맛이\\s*없|식욕이\\s*없|반찬이\\s*그대로|음식이\\s*그대로"));
        SIGNALS.put("외출 감소", Pattern.compile("외출(을|이)?\\s*(안|못|줄|잘\\s*안)|밖에\\s*(잘\\s*)?(안|못)\\s*나가|집에만\\s*계|나가기\\s*싫|산책(은|을)?\\s*(다음에|안\\s*하|싫|못)|(경로당|복지관)에?도?\\s*(요즘\\s*)?(안|못)\\s*가"));
        SIGNALS.put("기운 없음", Pattern.compile("기운이\\s*없|힘이\\s*없|기력이\\s*(없|떨어)|축\\s*처지|하루\\s*종일\\s*누워|누워만\\s*계|피곤해\\s*하"));
        SIGNALS.put("잠 못 잠", Pattern.compile("잠(을|이)?\\s*(잘\\s*)?(못\\s*주무|못\\s*자|안\\s*와|설치)|밤에\\s*(자주\\s*)?깨|못\\s*주무"));
        SIGNALS.put("말수·기분 변화", Pattern.compile("말수가\\s*줄|말씀이\\s*줄|웃지\\s*않|웃음이\\s*줄|기분이\\s*(가라앉|안\\s*좋|처지)|울적|적적해\\s*하|외로워\\s*하"));
    }

    /** 일지 한 건에만 있어도 담당자에게 알리는 표현(명세 B-36 예시: 쓰러짐, 식사 거름 등). 나머지 신호는 여러 번 이어질 때만 알린다. */
    public static final Set<String> URGENT = Set.of("쓰러짐", "넘어짐", "식사 거름");

    /** 신호 이름 가운데 한 번만 나와도 알릴 것만 고른다(순서 유지). */
    public static List<String> urgentOf(Collection<String> signals) {
        return signals == null ? List.of() : signals.stream().filter(URGENT::contains).toList();
    }

    /** 진단·건강 판단으로 읽힐 수 있는 말. 이런 문장은 저장·표시하지 않는다. */
    public static final Pattern DIAGNOSIS = Pattern.compile("진단|의심|증상|질환|증세|병이\\s*(있|생|난)|병에\\s*걸|우울증|치매|불면증|영양실조|탈수|인지\\s*저하|노쇠|건강이\\s*나빠|위험한\\s*상태|치료가\\s*필요|약을\\s*바꿔|처방");
    /** 약 이름처럼 보이는 낱말(…정·캡슐·시럽·mg) */
    private static final Pattern DRUG = Pattern.compile("[가-힣A-Za-z]{1,10}(정|캡슐|시럽|연고|패치)(?![가-힣])|\\d+\\s*(mg|밀리그램|알)");
    private static final Pattern TODO = Pattern.compile("다음|챙기|챙겨|확인|부탁|하기로|예정|필요|준비|가져|연습|알려|전해|드리기로|약속");

    public record Structured(List<String> next, String hospital, List<String> life, List<String> signals, String source, String provider) {}

    /** 문장 단위로 자른다. */
    static List<String> sentences(String t) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        for (String s : t.replace("\n", ". ").split("(?<=[.!?。])\\s*|(?<=어요|해요|세요|예요|에요|네요|대요|래요|군요)\\s+(?=[가-힣])")) {
            String x = s.trim().replaceAll("^[,.·\\-\\s]+", "");
            if (x.length() >= 2) out.add(x);
        }
        return out;
    }

    /** 코드 키워드로 고른 신호(정해진 이름만). */
    public static List<String> ruleSignals(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (var e : SIGNALS.entrySet()) if (e.getValue().matcher(text).find()) out.add(e.getKey());
        return out;
    }

    /** 진단 문장을 지우고 병명·약 이름을 뺀다. 남는 말이 없으면 null. */
    public static String clean(String s) {
        if (s == null) return null;
        if (DIAGNOSIS.matcher(s).find()) return null;
        String x = VoiceSafety.stripHealth(VoiceSafety.scrub(s).text());
        x = DRUG.matcher(x).replaceAll("").replaceAll("\\s+", " ").trim();
        return x.length() < 2 ? null : x;
    }

    /** 병원 안내 요약(코드): 무엇에 대한 안내인지만, 병명·약 이름 없이. */
    static String hospitalRule(List<String> healthSentences) {
        if (healthSentences.isEmpty()) return null;
        String all = String.join(" ", healthSentences);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (Pattern.compile("다음|재진|다시\\s*오|뒤에?\\s*(다시|오)|예약|날짜|일정").matcher(all).find()) tags.add("다음 진료·검진 일정");
        if (Pattern.compile("약(?!속)|복용|처방").matcher(all).find()) tags.add("약 먹는 시간 안내");
        if (Pattern.compile("검진|검사").matcher(all).find() && !tags.contains("다음 진료·검진 일정")) tags.add("검진 안내");
        return "병원 안내 있음" + (tags.isEmpty() ? "" : " (" + String.join(" · ", tags) + ")");
    }

    /** 규칙 정리. */
    public static Structured rule(String memo, String log) {
        List<String> next = new ArrayList<>(), life = new ArrayList<>(), health = new ArrayList<>();
        LinkedHashSet<String> signals = new LinkedHashSet<>(ruleSignals(Objects.toString(memo, "") + " " + Objects.toString(log, "")));
        for (String s : sentences(memo)) {
            boolean isHealth = InsightAi.HEALTH.matcher(s).find();
            if (isHealth) { health.add(s); continue; }
            if (!ruleSignals(s).isEmpty()) { String c = clean(s); if (c != null) life.add(c); continue; }
            if (TODO.matcher(s).find()) { String c = clean(s); if (c != null) next.add(c); }
        }
        return new Structured(limit(next, 4), hospitalRule(health), limit(life, 3), new ArrayList<>(signals), "rule", null);
    }

    private static List<String> limit(List<String> l, int n) { return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l; }

    /** 메모 정리(LLM 한 번 → 실패·키 없음이면 규칙). log 는 신호를 찾는 데만 쓴다. */
    public Structured structure(String memo, String log) {
        Structured rule = rule(memo, log);
        if ((memo == null || memo.isBlank()) && rule.signals().isEmpty()) return rule;
        String m = VoiceSafety.scrub(Objects.toString(memo, "")).text();
        String l = VoiceSafety.scrub(Objects.toString(log, "")).text();
        Optional<JsonNode> j = llm.ask("""
                너는 돌봄 봉사 '전달 메모'를 담당자·가족이 보기 쉽게 나누는 정리 도우미다.
                - 메모에 있는 사실만 쓴다. 진단·건강 상태 판단·의학적 추측은 절대 하지 않는다("~가 의심돼요", "~증상" 같은 말 금지).
                - 병명·약 이름·검사 수치는 옮기지 않는다.
                next: 다음 방문 때 챙길 것 0~4개(각 30자 안, '~하기' 체).
                hospital: 병원·약국·보건소에서 들은 안내가 있으면 무엇에 대한 안내인지만 15자 안(예: "다음 진료 일정", "약 먹는 시간"), 없으면 빈 문자열.
                life: 평소와 달라진 생활 모습 0~3개(관찰한 그대로, 예: "점심을 거르셨다고 하심").
                signals: 다음 이름 중 해당하는 것만 [쓰러짐, 넘어짐, 식사 거름, 외출 감소, 기운 없음, 잠 못 잠, 말수·기분 변화]. 없으면 [].
                출력: {"next":["..."],"hospital":"...","life":["..."],"signals":["..."]}
                """, "전달 메모: " + m + (l.isBlank() ? "" : "\n활동 일지: " + l), 500);
        if (j.isEmpty() || !(j.get().has("next") || j.get().has("signals"))) return rule;
        JsonNode n = j.get();
        List<String> next = cleanList(n.path("next"), 4);
        List<String> life = cleanList(n.path("life"), 3);
        LinkedHashSet<String> sig = new LinkedHashSet<>(rule.signals());
        for (JsonNode s : n.path("signals")) if (SIGNALS.containsKey(s.asText())) sig.add(s.asText());
        String hospital = rule.hospital();
        String h = n.path("hospital").asText("").trim();
        if (!h.isEmpty()) {
            String c = clean(h);
            if (c != null && c.length() <= 30) hospital = "병원 안내 있음 (" + c.replaceAll("^병원\\s*안내\\s*(있음)?\\s*", "").replaceAll("[()]", "") + ")";
            else if (hospital == null) hospital = "병원 안내 있음";
            hospital = hospital.replace(" ()", "");
        }
        return new Structured(next, hospital, life, new ArrayList<>(sig), "ai", llm.providerName());
    }

    private static List<String> cleanList(JsonNode arr, int limit) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode x : arr) {
            String c = clean(x.asText(""));
            if (c != null && !out.contains(c)) out.add(c.length() > 60 ? c.substring(0, 60) + "…" : c);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
