package com.timesavings.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말로 요청하기(음성 분석보고서 4.3·4.5·4.6)의 규칙 처리:
 * 의도 구분, 위급 신호, 개인정보(전화번호·계좌·상세 주소) 지우기, 방문 주의사항(병명 없이), 반복 규칙.
 */
public final class VoiceSafety {
    private VoiceSafety() {}

    public static final String PII_NOTE = "(수락 뒤 메시지로 안내돼요)";

    // ---------- 위급 ----------
    private static final Pattern EMERGENCY = Pattern.compile(
            "쓰러졌|쓰러져|쓰러지|숨이\\s*안|숨을\\s*못|숨이\\s*차|숨\\s*쉬기\\s*힘|피가\\s*나|피가\\s*많이|피를\\s*흘|의식이\\s*없|정신을\\s*잃|못\\s*일어나|가슴이\\s*(너무\\s*)?(아파|조여)|119");

    public static boolean emergency(String t) { return t != null && EMERGENCY.matcher(t).find(); }

    public static String emergencyReply(boolean voice) {
        return voice ? "위급한 상황이면 지금 바로 119에 전화하세요. 요청서 작성은 멈춰 둘게요."
                : "위급한 상황이면 지금 바로 119에 전화해 주세요. 혼자 계신 어르신이라면 응급안전안심서비스도 알아볼 수 있어요. 요청서 작성은 잠시 멈춰 둘게요.";
    }

    // ---------- 개인정보 ----------
    private static final String KD = "(?:공|영|일|이|삼|사|오|육|칠|팔|구)";
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}|0\\d{1,2}-\\d{3,4}-\\d{4})(?!\\d)|공일공(?:\\s*" + KD + "){6,9}");
    private static final Pattern ACCOUNT = Pattern.compile(
            "\\d{2,6}-\\d{2,6}-\\d{2,8}|(?:계좌|통장)\\s*(?:번호)?\\s*(?:는|은)?\\s*[\\d-]{6,}");
    private static final Pattern ADDRESS = Pattern.compile(
            "[가-힣A-Za-z0-9]{1,12}(?:아파트|빌라|맨션|오피스텔)\\s*\\d{1,4}\\s*동(?:\\s*\\d{1,4}\\s*호)?|\\d{1,4}\\s*동\\s*\\d{1,4}\\s*호|\\d{1,4}\\s*호(?=[\\s,.]|$)|[가-힣]+(?:로|길)\\s*\\d{1,4}(?:-\\d{1,4})?(?:번길)?|\\d{1,5}(?:-\\d{1,4})?\\s*번지");

    public record Scrubbed(String text, List<String> removed) {}

    public static Scrubbed scrub(String t) {
        if (t == null) return new Scrubbed(null, List.of());
        List<String> removed = new ArrayList<>();
        String s = t;
        Matcher m = PHONE.matcher(s);
        if (m.find()) { removed.add("phone"); s = PHONE.matcher(s).replaceAll(PII_NOTE); }
        m = ACCOUNT.matcher(s);
        if (m.find()) { removed.add("account"); s = ACCOUNT.matcher(s).replaceAll(PII_NOTE); }
        m = ADDRESS.matcher(s);
        if (m.find()) { removed.add("address"); s = ADDRESS.matcher(s).replaceAll(PII_NOTE); }
        s = s.replaceAll("(\\Q" + PII_NOTE + "\\E\\s*)+", PII_NOTE + " ").replaceAll("\\s+", " ").trim();
        return new Scrubbed(s, removed);
    }

    // ---------- 방문 주의사항(병명 저장 안 함) ----------
    private record CautionRule(Pattern p, String sentence) {}

    private static final List<CautionRule> CAUTIONS = List.of(
            new CautionRule(Pattern.compile("무릎|허리|다리|관절|고관절|걷기\\s*힘|지팡이|수술"), "걸음이 불편하셔서 천천히 걸어 주세요"),
            new CautionRule(Pattern.compile("귀가\\s*(어둡|잘\\s*안)|잘\\s*못\\s*들|보청기|난청"), "귀가 어두우셔서 천천히 크게 말해 주세요"),
            new CautionRule(Pattern.compile("눈이\\s*(잘\\s*안|어둡|침침)|시력|백내장|녹내장"), "글씨를 크게, 천천히 보여 주세요"),
            new CautionRule(Pattern.compile("치매|기억이\\s*(깜빡|잘\\s*안)|자꾸\\s*잊|건망"), "같은 말씀을 반복하셔도 처음처럼 천천히 응대해 주세요"),
            new CautionRule(Pattern.compile("반려견|강아지|(?<![가-힣])개가|고양이"), "반려동물이 있어요"),
            new CautionRule(Pattern.compile("계단|엘리베이터\\s*(가|는)?\\s*없"), "계단이 있어요"),
            new CautionRule(Pattern.compile("휠체어|보행기"), "보조기구를 쓰셔서 이동 시간을 넉넉히 잡아 주세요"),
            new CautionRule(Pattern.compile("오전에\\s*(는)?\\s*주무|낮잠"), "주무시는 시간이 있어 도착 전에 메시지로 알려 주세요"));

    public static String caution(String t) {
        if (t == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (CautionRule r : CAUTIONS) if (r.p.matcher(t).find()) out.add(r.sentence);
        return out.isEmpty() ? null : String.join(". ", out);
    }

    /** AI 가 준 주의사항에서 병명을 지운다(민감정보). */
    private static final Pattern DISEASE = Pattern.compile(
            "치매|파킨슨|당뇨|고혈압|암(?![기호])|뇌졸중|중풍|우울증|관절염|골다공증|협심증|심근경색|백내장|녹내장|디스크|수술(을|했|받)?[가-힣]*");

    public static String sanitizeCaution(String c) {
        if (c == null || c.isBlank()) return null;
        if (DISEASE.matcher(c).find()) {
            String rule = caution(c);
            return rule != null ? rule : "방문 전에 담당자·요청자에게 필요한 배려를 물어봐 주세요";
        }
        return c.trim();
    }

    /** 세부 내용에서 병명을 지운다. */
    public static String stripHealth(String t) {
        if (t == null) return null;
        return DISEASE.matcher(t).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    // ---------- 반복 ----------
    private static final Pattern REPEAT = Pattern.compile("(매주|매번|격주|2주마다|매일|매달|매월)\\s*((월|화|수|목|금|토|일)요일)?(마다)?|((월|화|수|목|금|토|일)요일)\\s*마다");

    public static String repeat(String t) {
        if (t == null) return null;
        Matcher m = REPEAT.matcher(t);
        if (!m.find()) return null;
        String day = m.group(2) != null ? m.group(2) : m.group(5);
        String unit = m.group(1) == null ? "매주" : (m.group(1).equals("매번") ? "매주" : m.group(1).equals("2주마다") ? "격주" : m.group(1).equals("매월") ? "매달" : m.group(1));
        return day == null ? unit : unit + " " + day;
    }

    // ---------- 의도 ----------
    public static final List<String> INTENTS = List.of("NEW_REQUEST", "REGULAR_REQUEST", "CHANGE_CANCEL", "CONFIRM", "QUESTION", "CHAT", "EMERGENCY");
    private static final Pattern CANCEL = Pattern.compile("취소|못\\s*가겠|못\\s*올|바꿔\\s*(줘|주|야)|변경|미뤄|날짜를?\\s*옮");
    private static final Pattern CONFIRM = Pattern.compile("왔다\\s*갔|다녀갔|잘\\s*해\\s*줬|잘해줬|끝났어|다\\s*했어|확인했");
    private static final Pattern REGULAR = Pattern.compile("지난번\\s*(그)?|또\\s*와|단골|그\\s*학생|씨가?\\s*또|저번\\s*(그)?\\s*(분|사람|학생)");
    private static final Pattern QUESTION = Pattern.compile("\\?|지원|없나|있나|있을까|어떻게\\s*해|뭐예요|뭐에요|알려\\s*줘|받을\\s*수\\s*있|신청");
    private static final Pattern CHAT = Pattern.compile("심심|외로|적적|그냥|날씨|뭐\\s*해|밥\\s*먹었");

    public static String intent(String t, boolean hasCategory, boolean hasWhen) {
        if (t == null || t.isBlank()) return "CHAT";
        if (emergency(t)) return "EMERGENCY";
        if (CANCEL.matcher(t).find()) return "CHANGE_CANCEL";
        if (CONFIRM.matcher(t).find() && !hasWhen) return "CONFIRM";
        if (REGULAR.matcher(t).find()) return "REGULAR_REQUEST";
        if (!hasCategory && !hasWhen && QUESTION.matcher(t).find()) return "QUESTION";
        if (!hasCategory && !hasWhen && CHAT.matcher(t).find()) return "CHAT";
        if (hasCategory && QUESTION.matcher(t).find() && Pattern.compile("지원|신청|받을\\s*수\\s*있").matcher(t).find() && !hasWhen) return "QUESTION";
        return "NEW_REQUEST";
    }

    public static String intentReply(String intent, boolean voice) {
        return switch (intent) {
            case "CHANGE_CANCEL" -> voice ? "기존 약속을 바꾸거나 취소하시려면 '내 활동'에서 그 활동을 골라 주세요."
                    : "이미 잡힌 활동을 바꾸거나 취소하려면 '내 활동'에서 해당 활동을 열어 주세요. 시작 24시간 전까지는 무료로 취소할 수 있어요.";
            case "CONFIRM" -> voice ? "활동이 끝났다면 '내 활동'에서 확인 버튼을 눌러 주세요."
                    : "활동이 끝났다면 '내 활동'에서 해당 활동을 열고 확인 버튼을 눌러 주세요. 확인해야 시간이 정산돼요.";
            case "QUESTION" -> voice ? "궁금한 것은 '공공 서비스 안내'에서 물어보시면 알려 드릴게요."
                    : "지원 제도가 궁금하시면 '공공 서비스 안내'에 물어봐 주세요. 등록된 서비스 중에서 찾아 드려요.";
            case "CHAT" -> voice ? "이야기 나눌 이웃이 필요하시면 말벗 요청서를 만들어 드릴까요?"
                    : "이야기 나눌 이웃이 필요하시면 말동무 요청서를 만들어 드릴 수 있어요. 언제가 좋으세요?";
            default -> null;
        };
    }
}
