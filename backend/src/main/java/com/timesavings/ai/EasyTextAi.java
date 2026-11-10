package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * LLM 추가 A(7.12) 쉬운 말 공고 읽어 주기: 어르신이 듣기 쉬운 1~2문장.
 * 날짜·시각·시간은 코드가 한글 읽기로 만든 문장(base)을 그대로 쓰게 하고, LLM 은 말투만 다듬는다.
 * LLM 문장에 코드가 만든 시각 표현이 빠지거나 숫자가 섞이면 버리고 코드 문장을 쓴다.
 */
@Component
public class EasyTextAi {
    private final LlmJson llm;

    public EasyTextAi(LlmJson llm) { this.llm = llm; }

    public record Easy(String text, String source) {}

    static String catEasy(String c) {
        return switch (c) {
            case "동행" -> "병원이나 외출에 같이 가는 일";
            case "장보기·심부름" -> "장보기나 심부름";
            case "디지털 도움" -> "휴대폰 쓰는 법 알려 드리기";
            case "말동무·정서" -> "말벗이 되어 드리기";
            case "생활 정리" -> "집 정리";
            case "가벼운 집안일" -> "가벼운 집안일";
            default -> c;
        };
    }

    /** 코드 문장. offer: 봉사 가능 공고. who: 보이는 이름(독거노인은 가린 이름). */
    public static String base(boolean offer, String who, LocalDate date, double start, double end, List<String> cats, Double estHours, String area) {
        String when = KoreanSpeech.date(date) + " " + KoreanSpeech.clock(start) + "부터 " + KoreanSpeech.clock(end).replaceFirst("^(오전|오후) ", sameHalf(start, end) ? "" : "$1 ") + " 사이";
        List<String> words = new ArrayList<>();
        for (String c : cats == null ? List.<String>of() : cats) words.add(catEasy(c));
        String what = words.isEmpty() ? "도움" : String.join(", ", words);
        String name = who == null || who.isBlank() ? "이웃" : who.trim();
        if (!name.endsWith("님")) name = name + " 님";
        if (offer) return name + "이 " + when + "에 " + what + josa(what, "을", "를") + " 도와줄 수 있어요." + (area == null ? "" : " " + area + "에서 활동해요.");
        String dur = estHours == null ? "" : " " + KoreanSpeech.duration(estHours) + " 정도 걸려요.";
        return name + "이 " + when + "에 " + what + josa(what, "을", "를") + " 도와줄 이웃을 찾고 있어요." + dur;
    }

    /** 받침이 있으면 a(을), 없으면 b(를) */
    static String josa(String w, String a, String b) {
        if (w == null || w.isEmpty()) return a;
        char c = w.charAt(w.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return a;
        return (c - 0xAC00) % 28 == 0 ? b : a;
    }

    private static boolean sameHalf(double a, double b) { return (a < 12) == (b < 12); }

    public Easy make(String base) {
        Optional<JsonNode> j = llm.ask("""
                너는 어르신에게 동네 봉사 공고를 소리로 읽어 주는 도우미다. 주어진 문장을 듣기 쉬운 1~2문장으로 다듬는다.
                - 날짜·시각·시간 표현("십일월 십칠일 월요일", "오후 한 시부터 다섯 시 사이" 같은 부분)은 글자 그대로 둔다. 아라비아 숫자를 쓰지 않는다.
                - 새로운 사실을 더하지 않는다. 쉬운 낱말, 짧은 문장, 존댓말.
                출력: {"text":"..."}
                """, "문장: " + base, 200);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim().replaceAll("[\\r\\n]+", " ");
            if (!t.isEmpty() && t.length() <= 160 && !t.matches(".*\\d.*") && keepsTime(base, t)) return new Easy(t, "ai");
        }
        return new Easy(base, "rule");
    }

    /** 코드 문장의 "…시" 시각 표현과 요일이 LLM 문장에도 있는지 */
    static boolean keepsTime(String base, String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(오전|오후) [가-힣]+ 시( 반)?|[월화수목금토일]요일").matcher(base);
        while (m.find()) if (!t.contains(m.group())) return false;
        return true;
    }
}
