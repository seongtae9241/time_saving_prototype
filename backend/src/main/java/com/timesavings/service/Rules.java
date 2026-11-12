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

    public static double weight(Level l) {
        return switch (l) { case BASIC -> 1.0; case EFFORT -> 1.2; case RESPONSIBLE -> 1.5; };
    }

    public static String levelKo(Level l) {
        return switch (l) { case BASIC -> "기본"; case EFFORT -> "수고"; case RESPONSIBLE -> "책임"; };
    }

    /** 기능 A-15: 운영자가 건의를 승인해 더한 카테고리(이름 → 등급). 서버가 뜰 때와 승인할 때 CategoryRegistry 가 채운다. 기본 6종은 그대로다. */
    private static final Map<String, Level> EXTRA_LEVEL = new java.util.concurrent.ConcurrentHashMap<>();
    public static void putExtraCategory(String name, Level level) { if (name != null && !CAT_LEVEL.containsKey(name)) EXTRA_LEVEL.put(name, level == null ? Level.BASIC : level); }
    public static void clearExtraCategories() { EXTRA_LEVEL.clear(); }
    public static Map<String, Level> extraCategories() { return new java.util.TreeMap<>(EXTRA_LEVEL); }

    public static Level categoryLevel(String cat) {
        Level l = CAT_LEVEL.get(cat);
        if (l == null && cat != null) l = EXTRA_LEVEL.get(cat);
        if (l == null) throw ApiException.bad("INVALID_CATEGORY", "알 수 없는 카테고리예요: " + cat);
        return l;
    }

    public static boolean isCategory(String c) { return c != null && (CAT_LEVEL.containsKey(c) || EXTRA_LEVEL.containsKey(c)); }

    public static Level max(Level a, Level b) { return a.ordinal() >= b.ordinal() ? a : b; }

    /** B-29 복합 요청: 여러 카테고리면 최고 등급. */
    public static Level maxLevel(Collection<String> cats) {
        Level l = Level.BASIC;
        for (String c : cats) l = max(l, categoryLevel(c));
        return l;
    }

    /** 알림·안내 문장의 날짜 표기: 11/20(금) */
    public static String md(java.time.LocalDate d) {
        if (d == null) return "";
        String[] w = {"월", "화", "수", "목", "금", "토", "일"};
        return d.getMonthValue() + "/" + d.getDayOfMonth() + "(" + w[d.getDayOfWeek().getValue() - 1] + ")";
    }

    private static int jong(String word) {
        if (word == null || word.isEmpty()) return 0;
        char c = word.charAt(word.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 ? (c - 0xAC00) % 28 : 0;
    }

    /** 낱말 + 받침에 맞는 조사: josa("동행", "은", "는") → "동행은", josa("장보기", "을", "를") → "장보기를" */
    public static String josa(String word, String withBatchim, String without) {
        return (word == null ? "" : word) + (jong(word) != 0 ? withBatchim : without);
    }

    /** 낱말 + (으)로: 받침이 없거나 ㄹ 받침이면 "로", 그 밖의 받침은 "으로". */
    public static String ro(String word) {
        int j = jong(word);
        return (word == null ? "" : word) + (j == 0 || j == 8 ? "로" : "으로");
    }

    /** 0.5 단위 반올림(B-26). 1.8 → 2.0, 1.2 → 1.0, 1.25 → 1.5 */
    public static double round05(double v) {
        return BigDecimal.valueOf(v).multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(2)).doubleValue();
    }

    /** 적립·차감 = 예정 시간 × 가중치, 0.5 단위 반올림(41판 B-30: 요청에 적힌 예정 시간 기준. 양쪽이 받아들인 시간 조정이 있으면 그 시간). */
    public static double earn(double hours, double weight) {
        return round05(BigDecimal.valueOf(hours).multiply(BigDecimal.valueOf(weight)).doubleValue());
    }

    public static double earn(double hours, Level level) { return earn(hours, weight(level)); }

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

    /** QR에 담는 글: TS1|종류(REQ 요청 체크 · ELD 도움 대상 현관 카드)|대상 id|6자리 코드 */
    public static String qrText(String kind, Long id, String code) { return "TS1|" + kind + "|" + id + "|" + code; }

    // ---------------- 활동 진행 시간 기준 (B-51~B-53) ----------------
    /** 받는 분 화면 QR·번호가 바뀌는 간격(초). */
    public static final int QR_STEP_SEC = 30;
    /** 예정 시작 뒤 이 분이 지나도 시작 기록이 없으면 지각 알림. */
    public static final int LATE_MIN = 10;
    /** 예정 시작 뒤 이 분이 지나도 시작 기록이 없으면 노쇼 의심 알림 · 이때부터 노쇼 신고 가능. */
    public static final int NOSHOW_MIN = 30;
    /** 예정 종료 뒤 이 분이 지나도 종료 기록이 없으면 예정 종료 시각으로 자동 마감. */
    public static final int AUTO_CLOSE_MIN = 30;
    /** 기록된 활동 시간이 예정과 이 분 이상 다르면 양쪽에 안내. */
    public static final int GAP_MIN = 30;

    /** 지금이 몇 번째 30초 구간인지. */
    public static long qrWindow(long epochMillis) { return Math.floorDiv(epochMillis / 1000, (long) QR_STEP_SEC); }

    /** 이 구간이 끝날 때까지 남은 초(1~30). */
    public static int qrLeftSec(long epochMillis) { return QR_STEP_SEC - (int) Math.floorMod(epochMillis / 1000, (long) QR_STEP_SEC); }

    /** 시각 기반 6자리 코드: 요청마다 다른 비밀값과 30초 구간 번호로 만든다(HMAC-SHA256). 같은 구간에서는 늘 같은 값. */
    public static String rotatingCode(String secret, long window) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(String.valueOf(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(Long.toString(window).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

}
