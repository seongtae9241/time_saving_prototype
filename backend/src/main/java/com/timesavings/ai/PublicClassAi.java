package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.*;

/**
 * 기관 클래스 추천(2026-11-10)의 AI 부분. 새 클래스 — AiClient 는 고치지 않는다.
 * 1) search: Gemini 구글 검색 그라운딩으로 지역 주변 공공기관 강좌를 찾아 JSON 배열로 받는다(Gemini 공급자일 때만, Anthropic·키 없음이면 꺼짐).
 *    모델이 쓴 항목은 "후보"일 뿐이다. 주소 확인·숨김은 PublicClassService 가 한다.
 * 2) reasons: 코드가 고른 상위 강좌마다 추천 이유 한 줄(LlmJson — 키 없거나 실패하면 빈 결과 → 규칙 문장).
 * 프롬프트에는 이름·연락처를 넣지 않는다(동네·나이대·관심 분야만). 키·지역·응답은 로그에 남기지 않는다.
 */
@Component
public class PublicClassAi {
    private static final Logger log = LoggerFactory.getLogger(PublicClassAi.class);
    private final ObjectMapper om = new ObjectMapper();
    private final AiClient ai;
    private final LlmJson llm;
    private final GeminiGrounding grounding;

    public PublicClassAi(AiClient ai, LlmJson llm, GeminiGrounding grounding) { this.ai = ai; this.llm = llm; this.grounding = grounding; }

    /** 웹 검색을 쓸 수 있는지: AI 가 켜져 있고 고른 공급자가 Gemini 이며 키가 있을 때. */
    public boolean searchAvailable() {
        return ai.enabled() && ai.provider() instanceof GeminiProvider && grounding.available();
    }

    public record Candidate(String title, String orgName, String place, String startDate, String endDate, String schedule,
                            String target, String fee, String applyUrl, String summary, String raw) {}
    public record SearchResult(List<Candidate> items, List<String> sources) {}

    static final String SEARCH_SYSTEM = """
            너는 한국의 50~80대 어르신과 이웃에게 공공기관 강좌를 찾아 주는 도우미다. 구글 검색으로 실제 페이지를 찾아 그 내용만 옮긴다.
            - 대상 기관: 주민센터(행정복지센터), 구청·시청 평생학습관(센터), 노인·종합사회복지관, 공공도서관, 보건소, 문화원, 디지털배움터 등 공공기관.
            - 우선: 휴대폰·디지털 사용법, 정서·건강(체조·노래·마음·치매 예방), 생활(요리·금융 사기 예방 등) 같은 쉬운 강좌. 5060·7080 대상이면 더 좋다.
            - 지금 모집 중이거나 앞으로 열리는 강좌만. 이미 끝난 강좌는 빼라.
            - 각 항목에 원래 안내 페이지 주소(applyUrl)를 꼭 넣어라. 주소를 모르면 그 항목은 쓰지 마라.
            - 모르는 값은 빈 문자열로 둔다. 날짜·장소·수강료·대상을 지어내지 마라.
            - 최대 8개.
            출력은 JSON 객체 하나: {"classes":[{"title":"","orgName":"","place":"","startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","schedule":"","target":"","fee":"","applyUrl":"","summary":"한 줄 쉬운 말"}]}
            JSON 말고 다른 글은 쓰지 마라.""";

    /** 그라운딩 검색. 실패·꺼짐이면 Optional.empty(). */
    public Optional<SearchResult> search(String region, String coordText) {
        if (!searchAvailable()) return Optional.empty();
        String where = (region == null || region.isBlank() ? "" : region.trim()) + (coordText == null ? "" : " (" + coordText + ")");
        String user = "지역: " + where.trim() + "\n이 지역과 가까운 곳에서 열리는 공공기관 클래스·강좌를 찾아 줘. 오늘 날짜: " + java.time.LocalDate.now();
        try {
            GeminiGrounding.Result r = grounding.search(SEARCH_SYSTEM, user, 2048);
            List<Candidate> items = parseCandidates(om, r.text());
            List<String> uris = r.sources().stream().map(GeminiGrounding.Source::uri).toList();
            return Optional.of(new SearchResult(items, uris));
        } catch (HttpStatusCodeException e) {
            log.warn("기관 클래스 검색 실패(gemini, HTTP {})", e.getStatusCode().value());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("기관 클래스 검색 실패(gemini, {})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 모델 글에서 JSON 부분을 꺼내 후보 목록으로(코드 블록·앞뒤 설명 허용, {"classes":[…]} 또는 […]). */
    public static List<Candidate> parseCandidates(ObjectMapper om, String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        JsonNode arr = null;
        Optional<JsonNode> obj = AiClient.parseJsonObject(om, text);
        if (obj.isPresent()) {
            JsonNode o = obj.get();
            arr = o.has("classes") ? o.get("classes") : o.has("items") ? o.get("items") : null;
        }
        if (arr == null || !arr.isArray()) {
            int a = text.indexOf('['), b = text.lastIndexOf(']');
            if (a >= 0 && b > a) { try { arr = om.readTree(text.substring(a, b + 1)); } catch (Exception ignored) { arr = null; } }
        }
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            String dates = s(n, "dates");
            out.add(new Candidate(s(n, "title"), s(n, "orgName"), s(n, "place"),
                    or(s(n, "startDate"), dates), s(n, "endDate"), s(n, "schedule"), s(n, "target"), s(n, "fee"),
                    or(s(n, "applyUrl"), s(n, "url")), s(n, "summary"), n.toString()));
            if (out.size() >= 12) break;
        }
        return out;
    }

    private static String s(JsonNode n, String k) {
        JsonNode v = n.get(k);
        if (v == null || v.isNull()) return "";
        return v.asText("").trim();
    }

    private static String or(String a, String b) { return a == null || a.isBlank() ? (b == null ? "" : b) : a; }

    /**
     * 추천 이유 한 줄씩. items 는 {id, title, orgName, category, when, place, target, fee} 맵 목록, profile 은 "동네·나이대·관심" 한 줄.
     * 키가 없거나 실패하면 빈 맵(호출한 쪽이 규칙 문장).
     */
    public Map<Long, String> reasons(String profile, List<Map<String, Object>> items) {
        Map<Long, String> out = new HashMap<>();
        if (items.isEmpty() || !llm.available()) return out;
        String list;
        try { list = om.writeValueAsString(items); } catch (Exception e) { return out; }
        Optional<JsonNode> j = llm.ask("""
                너는 50~80대 어르신께 공공기관 강좌를 권하는 친절한 안내자다. 각 강좌마다 왜 이분께 맞는지 추천 이유를 한 줄로 쓴다.
                - 25자 안팎, 쉬운 말, 존댓말 명사형(예: "같은 동네라 가깝고, 휴대폰 사진 정리를 배워요").
                - 주어진 정보에 없는 사실(시간·가격·혜택)은 쓰지 않는다. 자격을 판정하지 않는다.
                출력: {"reasons":[{"id":숫자,"reason":"한 줄"}]}""",
                "사용자: " + profile + "\n강좌 목록: " + list, 700);
        if (j.isEmpty()) return out;
        for (JsonNode r : j.get().path("reasons")) {
            long id = r.path("id").asLong(-1);
            String reason = r.path("reason").asText("").replaceAll("\\s+", " ").trim();
            if (id > 0 && !reason.isBlank() && reason.length() <= 80) out.put(id, reason);
        }
        return out;
    }
}
