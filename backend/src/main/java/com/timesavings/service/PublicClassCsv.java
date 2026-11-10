package com.timesavings.service;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 "전국평생학습강좌표준데이터" CSV 읽기(네트워크·DB 없음).
 * - 글자 코드: UTF-8(BOM 포함)을 먼저 엄격하게 읽어 보고, 깨지면 CP949(MS949)로 읽는다.
 * - 열 이름: 공백·괄호를 빼고 정확히 맞는 이름 → 비슷한 이름(포함 규칙) 순서로 칸에 맞춘다. 맞추지 못한 열은 raw 로 남긴다.
 */
public final class PublicClassCsv {
    private PublicClassCsv() {}

    /** 칸 이름 → (정확한 열 이름들, 포함되면 맞추는 낱말들). 위에 있을수록 먼저 맞춘다. */
    static final LinkedHashMap<String, String[][]> FIELDS = new LinkedHashMap<>();
    static {
        f("title", new String[]{"강좌명", "강좌명칭", "프로그램명", "교육명", "과정명", "강의명", "교육과정명"}, new String[]{"강좌명", "프로그램명", "과정명", "강의명", "교육명"});
        f("instructor", new String[]{"강사명", "강사"}, new String[]{"강사"});
        f("startDate", new String[]{"교육시작일자", "강좌시작일자", "교육시작일", "시작일자", "시작일", "운영시작일자", "교육기간시작일"}, new String[]{"교육시작일", "강좌시작일", "운영시작일", "시작일"});
        f("endDate", new String[]{"교육종료일자", "강좌종료일자", "교육종료일", "종료일자", "종료일", "운영종료일자", "교육기간종료일"}, new String[]{"교육종료일", "강좌종료일", "운영종료일", "종료일"});
        f("startTime", new String[]{"교육시작시각", "강좌시작시각", "시작시각", "교육시작시간"}, new String[]{"시작시각"});
        f("endTime", new String[]{"교육종료시각", "강좌종료시각", "종료시각", "교육종료시간"}, new String[]{"종료시각"});
        f("days", new String[]{"교육요일", "운영요일", "강좌요일", "요일"}, new String[]{"요일"});
        f("time", new String[]{"교육시간", "강좌시간", "운영시간", "교육시간대"}, new String[]{"교육시간", "운영시간", "강좌시간"});
        f("target", new String[]{"교육대상", "수강대상", "대상", "교육대상구분", "모집대상"}, new String[]{"대상"});
        f("method", new String[]{"교육방법", "교육방법구분", "운영방법", "수업방식"}, new String[]{"교육방법", "운영방법"});
        f("capacity", new String[]{"수강정원", "정원", "모집인원", "교육정원", "수강인원"}, new String[]{"정원", "모집인원"});
        f("fee", new String[]{"교육비", "수강료", "강좌비", "교육비용", "수강료금액", "교육료"}, new String[]{"교육비", "수강료", "강좌비"});
        f("place", new String[]{"교육장소", "강좌장소", "교육장소명", "장소", "교육장명"}, new String[]{"교육장소", "강좌장소"});
        f("address", new String[]{"교육장도로명주소", "도로명주소", "소재지도로명주소", "교육장소도로명주소", "주소", "교육장주소"}, new String[]{"도로명주소", "주소"});
        f("orgName", new String[]{"운영기관명", "운영기관", "주관기관명", "기관명", "제공기관명", "교육기관명"}, new String[]{"운영기관", "주관기관", "교육기관"});
        f("phone", new String[]{"운영기관전화번호", "전화번호", "문의전화", "연락처"}, new String[]{"전화번호", "문의전화"});
        f("applyHow", new String[]{"접수방법", "신청방법", "접수방식"}, new String[]{"접수방법", "신청방법"});
        f("applyUrl", new String[]{"홈페이지주소", "홈페이지", "신청홈페이지", "접수URL", "강좌홈페이지", "홈페이지URL"}, new String[]{"홈페이지", "url"});
        f("lat", new String[]{"위도"}, new String[]{"위도"});
        f("lng", new String[]{"경도"}, new String[]{"경도"});
        f("content", new String[]{"강좌내용", "교육내용", "강좌소개", "프로그램내용"}, new String[]{"강좌내용", "교육내용"});
    }
    private static void f(String k, String[] exact, String[] contains) { FIELDS.put(k, new String[][]{exact, contains}); }

    public record Parsed(String encoding, List<String> headers, Map<String, String> mapped, List<String> unmapped, List<Map<String, String>> rows, List<Map<String, String>> raws) {}

    /** 바이트 → 글자(UTF-8 엄격 → 실패하면 CP949). [0]=글, [1]=글자 코드 이름 */
    public static String[] decode(byte[] bytes) {
        int off = bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF ? 3 : 0;
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return new String[]{d.decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString(), "UTF-8"};
        } catch (CharacterCodingException e) {
            return new String[]{new String(bytes, Charset.forName("MS949")), "CP949"};
        }
    }

    /** 따옴표·쉼표·줄바꿈을 지키는 CSV 줄 나누기. */
    public static List<List<String>> splitCsv(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false;
                } else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { row.add(cur.toString()); cur.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) out.add(row);
                row = new ArrayList<>();
            } else cur.append(c);
        }
        if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); if (!(row.size() == 1 && row.get(0).isBlank())) out.add(row); }
        return out;
    }

    static String norm(String h) {
        return h == null ? "" : h.replace("﻿", "").replaceAll("\\(.*?\\)|\\[.*?]", "").replaceAll("[\\s_·./-]", "").toLowerCase(Locale.ROOT);
    }

    /** 열 이름 → 칸 이름 맞추기. 결과: 칸 → 열 번호 */
    public static Map<String, Integer> mapHeaders(List<String> headers) {
        Map<String, Integer> m = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        List<String> n = headers.stream().map(PublicClassCsv::norm).toList();
        for (var e : FIELDS.entrySet())
            for (String ex : e.getValue()[0]) {
                int i = n.indexOf(norm(ex));
                if (i >= 0 && !used.contains(i)) { m.put(e.getKey(), i); used.add(i); break; }
            }
        for (var e : FIELDS.entrySet()) {
            if (m.containsKey(e.getKey())) continue;
            outer:
            for (String part : e.getValue()[1])
                for (int i = 0; i < n.size(); i++)
                    if (!used.contains(i) && n.get(i).contains(norm(part))) { m.put(e.getKey(), i); used.add(i); break outer; }
        }
        return m;
    }

    public static Parsed parse(byte[] bytes, int maxRows) {
        String[] dec = decode(bytes);
        List<List<String>> lines = splitCsv(dec[0]);
        if (lines.isEmpty()) return new Parsed(dec[1], List.of(), Map.of(), List.of(), List.of(), List.of());
        List<String> headers = lines.get(0).stream().map(s -> s.replace("﻿", "").trim()).toList();
        Map<String, Integer> idx = mapHeaders(headers);
        Map<String, String> mapped = new LinkedHashMap<>();
        idx.forEach((k, i) -> mapped.put(k, headers.get(i)));
        Set<Integer> usedCols = new HashSet<>(idx.values());
        List<String> unmapped = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) if (!usedCols.contains(i) && !headers.get(i).isBlank()) unmapped.add(headers.get(i));
        List<Map<String, String>> rows = new ArrayList<>(), raws = new ArrayList<>();
        for (int r = 1; r < lines.size() && rows.size() < maxRows; r++) {
            List<String> line = lines.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            idx.forEach((k, i) -> row.put(k, i < line.size() ? line.get(i).trim() : ""));
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < line.size(); i++)
                if (!usedCols.contains(i) && !line.get(i).isBlank()) raw.put(headers.get(i), line.get(i).trim());
            rows.add(row);
            raws.add(raw);
        }
        return new Parsed(dec[1], headers, mapped, unmapped, rows, raws);
    }

    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})");

    public static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = DATE.matcher(s.trim());
        if (!m.find()) return null;
        try { return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); } catch (Exception e) { return null; }
    }

    /** "0"·"무료" → 무료, 숫자 → "12,000원", 그 밖 글은 그대로 */
    public static String fee(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        String digits = t.replaceAll("[,원\\s]", "");
        if (digits.matches("\\d+")) {
            long v = Long.parseLong(digits);
            return v == 0 ? "무료" : String.format("%,d원", v);
        }
        return t;
    }

    public static String capacity(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replaceAll("[,\\s]", "");
        if (t.matches("\\d+")) return Long.parseLong(t) == 0 ? null : t + "명";
        return s.trim();
    }

    /** 주소에서 "시군구 동" 부분: "서울특별시 금천구 독산동 123" → "금천구 독산동", "서울 금천구 시흥대로 73길" → "금천구" */
    public static String region(String address) {
        if (address == null || address.isBlank()) return null;
        String sgg = null, dong = null;
        String[] tk = address.trim().split("\\s+");
        for (int i = 0; i < tk.length; i++) {
            String t = tk[i].replaceAll("[,()]", "");
            if (i == 0 && (t.endsWith("특별시") || t.endsWith("광역시") || t.endsWith("특별자치시") || t.endsWith("도"))) continue;
            if (t.length() >= 2 && (t.endsWith("구") || t.endsWith("군") || t.endsWith("시")) && dong == null) {
                if (sgg == null) { sgg = t; continue; }
                if (sgg.endsWith("시") && !sgg.contains(" ") && (t.endsWith("구") || t.endsWith("군"))) { sgg = sgg + " " + t; continue; } // "수원시 장안구"
            }
            if (dong == null && t.length() >= 2 && t.matches(".*[가-힣](동|읍|면|가)$") && !t.matches(".*\\d.*로.*")) { dong = t; break; }
        }
        String r = ((sgg == null ? "" : sgg) + " " + (dong == null ? "" : dong)).trim();
        return r.isEmpty() ? null : r;
    }

    public static String hm(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.matches("\\d{3,4}")) { String p = t.length() == 3 ? "0" + t : t; return p.substring(0, 2) + ":" + p.substring(2); }
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t);
        return m.find() ? String.format("%02d:%s", Integer.parseInt(m.group(1)), m.group(2)) : t;
    }

    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
}
