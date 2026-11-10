package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12, B-42) 음성 전화 알림 문장: 알림 제목·내용을 전화로 천천히 읽기 좋은 짧은 문장으로.
 * LLM 은 말투만 다듬고(새 사실 금지, 숫자는 원문에 있던 것만), 숫자→한글 읽기는 코드(KoreanSpeech)가 마지막에 한다.
 * 키가 없거나 실패하면 "제목. 내용"을 코드로 읽기 좋게 바꾼 문장.
 */
@Component
public class VoiceTextAi {
    public static final String PREFIX = "시간 적금입니다. ";
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private final LlmJson llm;

    public VoiceTextAi(LlmJson llm) { this.llm = llm; }

    public record Voice(String text, String source) {}

    /** 코드 문장(LLM 없이). */
    public static String rule(String title, String body) {
        String t = clean(title), b = clean(body);
        String s = t + (b.isEmpty() ? "" : (t.matches(".*[.!?]$") ? " " : ". ") + b);
        s = s.replace(" · ", ", ").replace("·", ", ");
        s = KoreanSpeech.speakable(s);
        if (!s.matches(".*[.!?요다]$")) s = s + ".";
        return clip(PREFIX + s, 300);
    }

    public Voice make(String title, String body) {
        String src = clean(title) + " / " + clean(body);
        Optional<JsonNode> j = llm.ask("""
                너는 휴대폰이 어려운 어르신께 알림을 전화로 읽어 드릴 문장을 만드는 도우미다.
                - 한두 문장, 60자 안, 천천히 읽기 좋은 쉬운 존댓말("~해요", "~와요"). 기호·영어·줄임말은 쓰지 않는다.
                - 알림에 있는 사실만 쓴다. 날짜·시각·이름을 새로 만들지 않는다. 숫자는 알림에 있던 것만.
                - 할 일이 있으면 마지막에 "앱에서 확인해 주세요"처럼 한 번만 알린다.
                출력: {"text":"..."}
                예: 알림 "김하늘 님이 요청을 수락했어요 / 11/18 14:00 · 동행" → {"text":"김하늘 님이 십일월 십팔일 오후 두 시 동행 요청을 수락했어요. 앱에서 최종 수락을 눌러 주세요."}
                """, "알림: " + src, 200);
        if (j.isPresent()) {
            String t = j.get().path("text").asText("").trim().replaceAll("[\\r\\n]+", " ");
            if (!t.isEmpty() && t.length() <= 120 && numbersFrom(src, t)) return new Voice(clip(PREFIX + KoreanSpeech.speakable(t), 300), "ai");
        }
        return new Voice(rule(title, body), "rule");
    }

    static boolean numbersFrom(String src, String t) {
        Set<String> ok = new HashSet<>();
        Matcher m = DIGITS.matcher(src);
        while (m.find()) ok.add(m.group());
        m = DIGITS.matcher(t);
        while (m.find()) if (!ok.contains(m.group())) return false;
        return true;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{So}\\p{Cn}]", "").replaceAll("\\s+", " ").trim();
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
