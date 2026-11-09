package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.Level;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 계약서 3.1 카테고리·난이도·적립 규칙. 서버가 강제한다. */
public final class Rules {
    private Rules() {}

    public static final List<String> CATEGORIES = List.of("말동무·정서", "디지털 도움", "생활 정리", "장보기·심부름", "가벼운 집안일", "동행");
    private static final Map<String, Level> CAT_LEVEL = Map.of(
            "말동무·정서", Level.BASIC, "디지털 도움", Level.BASIC, "생활 정리", Level.BASIC,
            "장보기·심부름", Level.EFFORT, "가벼운 집안일", Level.EFFORT, "동행", Level.RESPONSIBLE);
    public static final List<String> CLASS_KINDS = List.of("요리", "손재주", "노래·이야기", "디지털", "생활 지혜");

    public static double weight(Level l) {
        return switch (l) { case BASIC -> 1.0; case EFFORT -> 1.2; case RESPONSIBLE -> 1.5; };
    }

    public static String levelKo(Level l) {
        return switch (l) { case BASIC -> "기본"; case EFFORT -> "수고"; case RESPONSIBLE -> "책임"; };
    }

    public static Level categoryLevel(String cat) {
        Level l = CAT_LEVEL.get(cat);
        if (l == null) throw ApiException.bad("INVALID_CATEGORY", "알 수 없는 카테고리예요: " + cat);
        return l;
    }

    public static boolean isCategory(String c) { return CAT_LEVEL.containsKey(c); }

    public static Level max(Level a, Level b) { return a.ordinal() >= b.ordinal() ? a : b; }

    /** B-29 복합 요청: 여러 카테고리면 최고 등급. */
    public static Level maxLevel(Collection<String> cats) {
        Level l = Level.BASIC;
        for (String c : cats) l = max(l, categoryLevel(c));
        return l;
    }

    /** 0.5 단위 반올림(B-26). 1.8 → 2.0, 1.2 → 1.0, 1.25 → 1.5 */
    public static double round05(double v) {
        return BigDecimal.valueOf(v).multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(2)).doubleValue();
    }

    /** 적립 = 활동 시간 × 가중치, 0.5 단위 반올림. */
    public static double earn(double hours, double weight) {
        return round05(BigDecimal.valueOf(hours).multiply(BigDecimal.valueOf(weight)).doubleValue());
    }

    public static double earn(double hours, Level level) { return earn(hours, weight(level)); }

    /** 수강료 = 수업 시간 × 0.5 (0.5 단위). */
    public static double classFee(double hours) { return earn(hours, 0.5); }

    public static boolean halfStep(double v) { return Math.abs(v * 2 - Math.round(v * 2)) < 1e-9; }

    /** B-10 일정 규칙: 30분 단위, 1회 0.5~4시간. */
    public static void checkHours(double startHour, double hours) {
        if (!halfStep(startHour) || startHour < 0 || startHour >= 24)
            throw ApiException.bad("INVALID_TIME", "시작 시각은 30분 단위로 정해 주세요");
        if (!halfStep(hours) || hours < 0.5 || hours > 4)
            throw ApiException.bad("INVALID_HOURS", "활동 시간은 30분 단위로 30분~4시간까지 정할 수 있어요");
        if (startHour + hours > 24) throw ApiException.bad("INVALID_TIME", "하루 안에서 끝나야 해요");
    }

    /** 봉사자에게 보이는 독거노인 표시 이름: "이○례 님" (C-02, B-13). */
    public static String maskAlias(String alias) {
        if (alias == null || alias.isBlank()) return "○○ 님";
        String a = alias.trim();
        if (a.length() == 1) return a + "○ 님";
        return a.charAt(0) + "○" + a.charAt(a.length() - 1) + " 님";
    }

    public static String fmtHours(double h) {
        return h == Math.floor(h) ? String.valueOf((long) h) + ".0" : String.valueOf(h);
    }
}
