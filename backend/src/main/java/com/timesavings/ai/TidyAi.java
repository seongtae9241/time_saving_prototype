package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.service.Rules;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * O2 담당자 대리 공고 "AI로 정리하기": 담당자가 적은 글을 제목·내용·주의사항으로 다듬는다(새 사실을 지어내지 않음).
 * 전화번호·계좌·상세 주소는 지우고, 주의사항에는 병명 대신 방문에 필요한 문장만 남긴다(AI 결과에도 서버가 다시 적용).
 * 키가 없거나 실패하면 규칙으로 정리(source:'rule').
 */
@Component
public class TidyAi {
    private final LlmJson llm;

    public TidyAi(LlmJson llm) { this.llm = llm; }

    public Map<String, Object> tidy(String raw, String elderCaution) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() > 1000) text = text.substring(0, 1000);
        VoiceSafety.Scrubbed sc = VoiceSafety.scrub(text);
        String clean = sc.text();
        String title = null, detail = null, caution = null, source = "rule";
        List<String> cats = new ArrayList<>(KoreanParser.categories(clean));
        Optional<JsonNode> j = llm.ask("""
                너는 기관 담당자가 독거 어르신 대신 올리는 '도움 요청 공고'를 명확하게 다듬는 정리 도우미다.
                - 입력에 있는 사실만 쓴다. 날짜·시간·장소·사람을 지어내지 않는다.
                - title: 무엇을 도와 달라는지 20자 안의 명사형(예: "병원 동행 (정형외과 재진)" 대신 "정형외과 병원 동행").
                - detail: 봉사자가 할 일을 1~3문장, '~해 주세요' 체.
                - caution: 방문할 때 주의할 점 1~2문장. 병명·진단명은 쓰지 않고 행동으로 쓴다(예: "걸음이 불편하셔서 천천히 걸어 주세요"). 없으면 빈 문자열.
                - categories: 다음 중에서만 1~2개 [말동무·정서, 디지털 도움, 생활 정리, 장보기·심부름, 가벼운 집안일, 동행].
                출력: {"title":"...","detail":"...","caution":"...","categories":["..."]}
                """, "담당자가 적은 글: " + clean + (elderCaution == null || elderCaution.isBlank() ? "" : "\n대상 기본 주의사항: " + elderCaution), 500);
        if (j.isPresent() && !j.get().path("title").asText("").isBlank()) {
            title = j.get().path("title").asText().trim();
            detail = j.get().path("detail").asText("").trim();
            caution = j.get().path("caution").asText("").trim();
            List<String> ac = new ArrayList<>();
            for (JsonNode c : j.get().path("categories")) if (Rules.isCategory(c.asText()) && !ac.contains(c.asText())) ac.add(c.asText());
            if (!ac.isEmpty()) cats = ac;
            source = "ai";
        }
        if (title == null) {
            // 규칙: 카테고리 + 눈에 띄는 낱말로 짧은 제목, 원문 → 내용, 주의 낱말 → 주의사항
            title = ruleTitle(clean, cats);
            detail = clean;
            caution = VoiceSafety.caution(clean + " " + Objects.toString(elderCaution, ""));
        }
        title = VoiceSafety.scrub(title).text();
        detail = VoiceSafety.stripHealth(VoiceSafety.scrub(detail).text());
        caution = VoiceSafety.sanitizeCaution(caution == null || caution.isBlank() ? (elderCaution == null || elderCaution.isBlank() ? null : elderCaution) : caution);
        if (cats.size() > 2) cats = cats.subList(0, 2);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("detail", detail);
        out.put("caution", caution);
        out.put("categories", cats);
        out.put("piiRemoved", sc.removed());
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", llm.providerName());
        return out;
    }

    private static final java.util.regex.Pattern PLACE = java.util.regex.Pattern.compile("([가-힣]{1,6}(외과|내과|치과|안과|이비인후과|피부과|한의원|의원|병원|약국|보건소|은행|주민센터|시장|마트))");

    static String ruleTitle(String t, List<String> cats) {
        java.util.regex.Matcher m = PLACE.matcher(t);
        String place = m.find() ? m.group(1) : null;
        String c = cats.isEmpty() ? "" : cats.get(0);
        String base = switch (c) {
            case "동행" -> place != null && place.matches(".*(과|의원|병원|한의원|약국|보건소)$") ? "병원 동행" : "외출 동행";
            case "장보기·심부름" -> "장보기·심부름 도움";
            case "디지털 도움" -> "휴대폰 사용 도움";
            case "말동무·정서" -> "말벗 방문";
            case "생활 정리" -> "생활 정리 도움";
            case "가벼운 집안일" -> "가벼운 집안일 도움";
            default -> null;
        };
        if (base == null) {
            String first = t.split("(?<=[.!?。])\\s+|\\n+")[0].trim();
            return first.length() > 24 ? first.substring(0, 24).trim() + "…" : first;
        }
        return place != null && !base.contains(place) ? base + " (" + place + ")" : base;
    }
}
