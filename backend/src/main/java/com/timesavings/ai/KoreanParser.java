package com.timesavings.ai;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말(음성 받아쓰기)·글로 쓴 요청에서 날짜·시각·길이·장소·카테고리를 뽑는 규칙 파서.
 * AI 키가 없을 때 대화형 공고 작성(B-02)의 대체 구현으로 쓴다.
 */
public final class KoreanParser {
    private KoreanParser() {}

    /** 파싱 결과(뽑힌 것만 채워짐). */
    public static final class Parsed {
        public LocalDate date;
        public Double startHour;
        public Double endHour;
        public Double hours;
        public String area;
        public String place;
        public final LinkedHashSet<String> categories = new LinkedHashSet<>();
        public boolean hoursTooLong;
        public String cleaned = "";
        /** 확신도: high | medium | low (보고서 4.7) */
        public String dateConf = "low";
        public boolean periodExplicit;
    }

    // ---------------- 군말 ----------------
    private static final Pattern FILLER = Pattern.compile(
            "(?<![가-힣])(어+|음+|으음|저기요?|저|그러니까|그니까|그러니깐|뭐지|뭐냐|있잖아요?|아+|에+|막|그게|그래서요?|네네|응)(?![가-힣])");

    public static String removeFillers(String s) {
        if (s == null) return "";
        String t = FILLER.matcher(s).replaceAll(" ");
        t = t.replaceAll("[,…~]+\\s*(?=[,…])", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    // ---------------- 수사 ----------------
    private static final String NATIVE = "열두|열한|열|한|두|세|네|다섯|여섯|일곱|여덟|아홉";
    /** 시각에 쓰는 수: 숫자와 고유어만("오시면"의 '오'를 5시로 읽지 않게 한자어 한 글자는 뺀다). */
    private static final String NUM = "(\\d{1,2}|" + NATIVE + ")";

    static Integer num(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        return switch (s) {
            case "한", "일" -> 1; case "두", "이" -> 2; case "세", "삼" -> 3; case "네", "사" -> 4;
            case "다섯", "오" -> 5; case "여섯", "육" -> 6; case "일곱", "칠" -> 7; case "여덟", "팔" -> 8;
            case "아홉", "구" -> 9; case "열", "십" -> 10; case "열한", "십일" -> 11; case "열두", "십이" -> 12;
            default -> null;
        };
    }

    // ---------------- 시각 ----------------
    private static final String PERIOD = "(오전|오후|아침|점심|저녁|밤|낮|새벽)";
    private static final Pattern TIME = Pattern.compile(
            PERIOD + "?\\s*(?:(?<=오전|오후|아침|점심|저녁|밤|낮|새벽)|(?<![가-힣]))" + NUM + "\\s*시(?![간작장청민켜키])\\s*(반|(\\d{1,2})\\s*분|삼십\\s*분)?");
    private static final Pattern CLOCK = Pattern.compile("(?<![\\d/])(\\d{1,2}):(\\d{2})(?!\\d)");
    private static final Pattern RANGE_SEP = Pattern.compile("^\\s*(부터|에서|~|-|–|에서부터)\\s*");

    private record TimeHit(int start, int end, double hour, boolean explicitPeriod) {}

    private static double applyPeriod(String period, int h) {
        if (period == null) {
            if (h >= 13) return h;
            if (h >= 1 && h <= 6) return h + 12; // "두 시" → 오후 2시로 본다(돌봄 활동 시간대)
            return h;
        }
        return switch (period) {
            case "오후", "저녁", "밤", "낮" -> h < 12 ? h + 12 : h;
            case "점심" -> h <= 5 ? h + 12 : h;
            default -> h == 12 ? 0 : h; // 오전·아침·새벽
        };
    }

    private static List<TimeHit> times(String s) {
        List<TimeHit> hits = new ArrayList<>();
        Matcher m = TIME.matcher(s);
        while (m.find()) {
            Integer h = num(m.group(2));
            if (h == null || h > 24) continue;
            double v = applyPeriod(m.group(1), h);
            String min = m.group(3);
            if (min != null) {
                if (min.startsWith("반") || min.startsWith("삼십")) v += 0.5;
                else if (m.group(4) != null) v += Math.round(Integer.parseInt(m.group(4)) / 30.0) * 0.5;
            }
            hits.add(new TimeHit(m.start(), m.end(), v, m.group(1) != null || h >= 13));
        }
        Matcher c = CLOCK.matcher(s);
        while (c.find()) {
            int h = Integer.parseInt(c.group(1));
            if (h > 23) continue;
            double v = h + Math.round(Integer.parseInt(c.group(2)) / 30.0) * 0.5;
            hits.add(new TimeHit(c.start(), c.end(), v, true));
        }
        hits.sort(Comparator.comparingInt(TimeHit::start));
        return hits;
    }

    // ---------------- 길이 ----------------
    private static final Pattern DURATION = Pattern.compile(
            "(?<![가-힣])(\\d+(?:\\.\\d)?|" + NATIVE + ")\\s*시간\\s*(반|(\\d{1,2})\\s*분|삼십\\s*분)?");
    private static final Pattern MINUTES_ONLY = Pattern.compile("(?<![시\\d])\\s(30|삼십)\\s*분\\s*(정도|만|동안|쯤)");

    // ---------------- 날짜 ----------------
    private static final Pattern WEEKDAY = Pattern.compile(
            "(이번\\s*주|이번주|다음\\s*주|다음주|담주|다다음\\s*주)?\\s*(월|화|수|목|금|토|일)요일");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern SLASH_DAY = Pattern.compile("(?<![\\d:])(\\d{1,2})/(\\d{1,2})(?![\\d:])");
    private static final Pattern DAY_ONLY = Pattern.compile("(?<![\\d월])(\\d{1,2})\\s*일(?![요간])");
    private static final Pattern REL_DAY = Pattern.compile("(오늘|내일|낼|모레|글피|이번\\s*주말|다음\\s*주말|주말)");

    private static DayOfWeek dow(String k) {
        return switch (k) {
            case "월" -> DayOfWeek.MONDAY; case "화" -> DayOfWeek.TUESDAY; case "수" -> DayOfWeek.WEDNESDAY;
            case "목" -> DayOfWeek.THURSDAY; case "금" -> DayOfWeek.FRIDAY; case "토" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    static LocalDate parseDate(String s, LocalDate today) {
        Matcher m = MONTH_DAY.matcher(s);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1)), d = Integer.parseInt(m.group(2));
            try {
                LocalDate x = LocalDate.of(today.getYear(), mo, d);
                return x.isBefore(today) ? x.plusYears(1) : x;
            } catch (Exception ignore) { /* 잘못된 날짜 */ }
        }
        m = SLASH_DAY.matcher(s);
        if (m.find()) {
            try {
                LocalDate x = LocalDate.of(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return x.isBefore(today) ? x.plusYears(1) : x;
            } catch (Exception ignore) { }
        }
        m = WEEKDAY.matcher(s);
        if (m.find()) {
            String w = m.group(1) == null ? "" : m.group(1).replaceAll("\\s", "");
            DayOfWeek target = dow(m.group(2));
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (w.startsWith("다다음")) return monday.plusWeeks(2).with(TemporalAdjusters.nextOrSame(target));
            if (w.startsWith("다음") || w.equals("담주")) return monday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(target));
            if (w.startsWith("이번")) {
                LocalDate x = monday.with(TemporalAdjusters.nextOrSame(target));
                return x.isBefore(today) ? x.plusWeeks(1) : x;
            }
            return today.with(TemporalAdjusters.next(target));
        }
        m = REL_DAY.matcher(s);
        if (m.find()) {
            String k = m.group(1).replaceAll("\\s", "");
            return switch (k) {
                case "오늘" -> today;
                case "내일", "낼" -> today.plusDays(1);
                case "모레" -> today.plusDays(2);
                case "글피" -> today.plusDays(3);
                case "다음주말" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
                default -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            };
        }
        m = DAY_ONLY.matcher(s);
        if (m.find()) {
            int d = Integer.parseInt(m.group(1));
            if (d >= 1 && d <= 31) {
                try {
                    LocalDate x = today.withDayOfMonth(d);
                    return x.isBefore(today) ? today.plusMonths(1).withDayOfMonth(d) : x;
                } catch (Exception ignore) { }
            }
        }
        return null;
    }

    static String dateConfidence(String s) {
        if (MONTH_DAY.matcher(s).find() || SLASH_DAY.matcher(s).find() || REL_DAY.matcher(s).find()) return "high";
        Matcher w = WEEKDAY.matcher(s);
        if (w.find()) return w.group(1) != null ? "high" : "medium";
        return "medium";
    }

    // ---------------- 장소 ----------------
    private static final Pattern DONG = Pattern.compile("([가-힣]{1,5}동)(?=[\\s,.!?]|에|에서|으로|로|의|은|는|이|쪽|근처|$)");
    private static final Set<String> NOT_DONG = Set.of("활동", "이동", "자동", "운동", "행동", "감동", "공동", "노동", "아동", "동동", "출동", "변동", "작동", "충동");
    private static final Pattern PLACE = Pattern.compile(
            "([가-힣A-Za-z0-9]{0,8}(?:정형외과|내과|치과|안과|이비인후과|피부과|한의원|의원|병원|약국|마트|시장|주민센터|행정복지센터|복지관|경로당|은행|공원|우체국|보건소|도서관|카페))");

    // ---------------- 카테고리 ----------------
    private static final LinkedHashMap<String, Pattern> CAT = new LinkedHashMap<>();
    static {
        CAT.put("동행", Pattern.compile("병원|진료|정형외과|내과|치과|안과|의원|한의원|같이\\s*가|함께\\s*가|동행|데려다|모시고|모셔다|부축|외출|검진"));
        CAT.put("장보기·심부름", Pattern.compile("(?<![가-힣])장\\s*(좀\\s*)?(보|봐)|장을|(?<![가-힣])쌀|마트|시장|심부름|사\\s*다|사\\s*와|사\\s*오|택배|반찬거리"));
        CAT.put("디지털 도움", Pattern.compile("휴대폰|핸드폰|(?<![가-힣])폰|스마트폰|카톡|카카오|키오스크|(?<![가-힣])앱|어플|영상\\s*통화|문자|컴퓨터|인터넷|정부24|인증서|유튜브"));
        CAT.put("말동무·정서", Pattern.compile("말벗|말동무|이야기|얘기|수다|안부|외로|대화|적적|산책"));
        CAT.put("가벼운 집안일", Pattern.compile("청소|빨래|설거지|집안일|분리수거|쓰레기|걸레|이불"));
        CAT.put("생활 정리", Pattern.compile("정리|옷장|냉장고|수납|서랍|짐\\s*싸|버리기"));
    }

    public static Set<String> categories(String s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (var e : CAT.entrySet()) if (e.getValue().matcher(s).find()) out.add(e.getKey());
        // "냉장고 정리"·"옷장 정리"가 있으면 집안일·장보기보다 생활 정리가 핵심
        return out;
    }

    /** 한 발화를 읽어 뽑힌 항목만 채운다. */
    public static Parsed parse(String text, LocalDate today) {
        Parsed p = new Parsed();
        String s = removeFillers(text);
        p.cleaned = s;
        p.date = parseDate(s, today);
        if (p.date != null) p.dateConf = dateConfidence(s);

        // 길이 먼저 뽑고, 시각 탐색에서 그 구간을 지운다("한 시간"이 "한 시"로 읽히지 않게)
        String forTime = s;
        Matcher dm = DURATION.matcher(s);
        if (dm.find()) {
            Double base;
            String n = dm.group(1);
            if (n.matches("\\d+(\\.\\d)?")) base = Double.parseDouble(n);
            else { Integer k = num(n); base = k == null ? null : k.doubleValue(); }
            if (base != null) {
                String extra = dm.group(2);
                if (extra != null) {
                    if (extra.startsWith("반") || extra.startsWith("삼십")) base += 0.5;
                    else if (dm.group(3) != null) base += Math.round(Integer.parseInt(dm.group(3)) / 30.0) * 0.5;
                }
                base = Math.round(base * 2) / 2.0;
                if (base > 4) p.hoursTooLong = true; else if (base >= 0.5) p.hours = base;
            }
            forTime = s.substring(0, dm.start()) + " ".repeat(dm.end() - dm.start()) + s.substring(dm.end());
        } else {
            Matcher mm = MINUTES_ONLY.matcher(" " + s);
            if (mm.find()) p.hours = 0.5;
        }

        List<TimeHit> ts = times(forTime);
        if (!ts.isEmpty()) {
            TimeHit a = ts.get(0);
            p.startHour = a.hour;
            p.periodExplicit = a.explicitPeriod;
            if (ts.size() >= 2) {
                TimeHit b = ts.get(1);
                String between = forTime.substring(a.end, b.start);
                boolean range = RANGE_SEP.matcher(between).find() || between.isBlank()
                        || forTime.substring(b.end).trim().startsWith("까지");
                if (range) {
                    double end = b.hour;
                    if (!b.explicitPeriod && end <= a.hour && end + 12 <= 24) end += 12;
                    if (!b.explicitPeriod && a.explicitPeriod && a.hour >= 12 && end < 12) end += 12;
                    if (end > a.hour) p.endHour = end;
                }
            }
        }
        if (p.startHour != null && p.endHour == null && p.hours != null) p.endHour = p.startHour + p.hours;
        if (p.startHour != null && p.endHour != null && p.hours == null) {
            double span = p.endHour - p.startHour;
            if (span > 0 && span <= 4) p.hours = span;
        }

        Matcher pm = PLACE.matcher(s);
        if (pm.find()) p.place = pm.group(1);
        Matcher dg = DONG.matcher(s);
        while (dg.find()) {
            String d = dg.group(1);
            if (NOT_DONG.contains(d) || d.endsWith("행동")) continue;
            p.area = d;
            break;
        }
        p.categories.addAll(categories(s));
        return p;
    }

    // ---------------- 말로 읽기 좋은 표현 ----------------
    public static String hourSpeech(double h) {
        int H = (int) Math.floor(h);
        boolean half = h - H >= 0.5;
        String ap = H < 12 ? "오전" : "오후";
        int disp = H > 12 ? H - 12 : (H == 0 ? 12 : H);
        return ap + " " + disp + "시" + (half ? " 30분" : "");
    }

    public static String durationSpeech(double hours) {
        int H = (int) Math.floor(hours);
        boolean half = hours - H >= 0.5;
        if (H == 0) return "30분";
        return H + "시간" + (half ? " 30분" : "");
    }

    public static String dateSpeech(LocalDate d) {
        String[] w = {"월", "화", "수", "목", "금", "토", "일"};
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일 " + w[d.getDayOfWeek().getValue() - 1] + "요일";
    }
}
