package com.timesavings.ai;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12): 소리로 읽기 좋은 한국어로 바꾸는 코드(전화 알림·쉬운 말 읽기·비서 답).
 * 숫자는 LLM 이 아니라 여기서 한글로 바꾼다: "11/17 14:00" → "십일월 십칠일 오후 두 시", "3.0시간" → "세 시간".
 */
public final class KoreanSpeech {
    private KoreanSpeech() {}

    private static final String[] SINO = {"", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};
    private static final String[] NATIVE_ONES = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"};
    private static final String[] NATIVE_TENS = {"", "열", "스물", "서른", "마흔", "쉰"};

    /** 한자어 수(0~99999): 15 → "십오", 2026 → "이천이십육" */
    public static String sino(long n) {
        if (n == 0) return "영";
        if (n < 0) return "마이너스 " + sino(-n);
        StringBuilder sb = new StringBuilder();
        String[] units = {"만", "천", "백", "십", ""};
        long[] div = {10000, 1000, 100, 10, 1};
        for (int i = 0; i < div.length; i++) {
            long d = n / div[i];
            n %= div[i];
            if (d == 0) continue;
            if (i == 0) { sb.append(sino(d)).append("만"); continue; }
            if (d == 1 && i < 4) sb.append(units[i]);
            else sb.append(SINO[(int) d]).append(units[i]);
        }
        return sb.toString();
    }

    /** 고유어 수(1~59, 시·시간·번·명 앞): 2 → "두", 24 → "스물네". 그 밖은 한자어. */
    public static String nativeNum(int n) {
        if (n <= 0 || n >= 60) return sino(n);
        if (n == 20) return "스무";
        return NATIVE_TENS[n / 10] + NATIVE_ONES[n % 10];
    }

    /** 시각(소수 시각): 14 → "오후 두 시", 9.5 → "오전 아홉 시 반" */
    public static String clock(double h) {
        int H = (int) Math.floor(h);
        int min = (int) Math.round((h - H) * 60);
        return clock(H, min);
    }

    public static String clock(int H, int min) {
        String ap = H == 12 ? "낮" : (H == 0 || H == 24) ? "밤" : H < 12 ? "오전" : "오후";
        int disp = H > 12 ? H - 12 : (H == 0 ? 12 : H);
        String m = min == 0 ? "" : min == 30 ? " 반" : " " + sino(min) + " 분";
        return ap + " " + nativeNum(disp) + " 시" + m;
    }

    /** 시간 길이: 3 → "세 시간", 1.5 → "한 시간 반", 0.5 → "삼십 분" */
    public static String duration(double hours) {
        int H = (int) Math.floor(hours + 1e-9);
        boolean half = hours - H >= 0.5 - 1e-9;
        if (H == 0) return half ? "삼십 분" : "영 시간";
        return nativeNum(H) + " 시간" + (half ? " 반" : "");
    }

    /** 날짜: 11월 17일 월요일 → "십일월 십칠일 월요일" */
    public static String date(LocalDate d) {
        String[] w = {"월", "화", "수", "목", "금", "토", "일"};
        return month(d.getMonthValue()) + " " + sino(d.getDayOfMonth()) + "일 " + w[d.getDayOfWeek().getValue() - 1] + "요일";
    }

    private static String month(int m) {
        return m == 6 ? "유월" : m == 10 ? "시월" : sino(m) + "월";
    }

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern MD_SLASH = Pattern.compile("(?<![\\d.:])(\\d{1,2})/(\\d{1,2})(?![\\d/])");
    private static final Pattern MD_KO = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern HHMM = Pattern.compile("(?<![\\d.])(오전|오후)?\\s*(\\d{1,2}):(\\d{2})(?!\\d)");
    private static final Pattern KO_CLOCK = Pattern.compile("(오전|오후|아침|저녁|낮|밤)\\s*(\\d{1,2})\\s*시(\\s*(\\d{1,2})\\s*분|\\s*반)?(?!간)");
    private static final Pattern HOURS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*시간(\\s*(\\d{1,2})\\s*분)?");
    private static final Pattern COUNT = Pattern.compile("(\\d{1,2})\\s*(번|명|개|살|건|회)");
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*분");
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * 글 안의 숫자를 소리로 읽기 좋게 바꾼다. 날짜·시각·시간 길이·횟수·분 순서로 바꾸고, 남은 숫자는 한자어로.
     * 결과에는 아라비아 숫자가 남지 않는다.
     */
    public static String speakable(String text) {
        if (text == null) return "";
        String s = text;
        s = replace(ISO, s, m -> month(Integer.parseInt(m.group(2))) + " " + sino(Integer.parseInt(m.group(3))) + "일");
        s = replace(MD_KO, s, m -> month(Integer.parseInt(m.group(1))) + " " + sino(Integer.parseInt(m.group(2))) + "일");
        s = replace(HHMM, s, m -> {
            int h = Integer.parseInt(m.group(2)), mi = Integer.parseInt(m.group(3));
            if (h > 24 || mi > 59) return m.group();
            if ("오후".equals(m.group(1)) && h < 12) h += 12;
            return clock(h, mi);
        });
        s = replace(MD_SLASH, s, m -> {
            int mo = Integer.parseInt(m.group(1)), d = Integer.parseInt(m.group(2));
            if (mo < 1 || mo > 12 || d < 1 || d > 31) return m.group();
            return month(mo) + " " + sino(d) + "일";
        });
        s = replace(KO_CLOCK, s, m -> {
            int h = Integer.parseInt(m.group(2));
            String rest = m.group(3) == null ? "" : m.group(3).contains("반") ? " 반" : " " + sino(Integer.parseInt(m.group(4))) + " 분";
            return m.group(1) + " " + nativeNum(h) + " 시" + rest;
        });
        s = replace(HOURS, s, m -> {
            double v = Double.parseDouble(m.group(1));
            if (m.group(3) != null) v += Integer.parseInt(m.group(3)) / 60.0;
            return duration(Math.round(v * 2) / 2.0);
        });
        s = replace(COUNT, s, m -> nativeNum(Integer.parseInt(m.group(1))) + " " + m.group(2));
        s = replace(MINUTES, s, m -> sino(Integer.parseInt(m.group(1))) + " 분");
        s = replace(NUM, s, m -> {
            String g = m.group();
            if (g.contains(".")) {
                String[] p = g.split("\\.");
                return sino(Long.parseLong(p[0])) + " 점 " + p[1].chars().mapToObj(c -> c == '0' ? "영" : SINO[c - '0']).reduce("", String::concat);
            }
            return g.length() > 9 ? g.chars().mapToObj(c -> c == '0' ? "공" : SINO[c - '0']).reduce("", (a, b) -> a + b) : sino(Long.parseLong(g));
        });
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    private interface Fn { String apply(Matcher m); }

    private static String replace(Pattern p, String s, Fn f) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }
}
