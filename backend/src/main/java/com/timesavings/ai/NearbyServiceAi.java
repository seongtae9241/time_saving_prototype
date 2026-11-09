package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.timesavings.domain.PublicService;
import com.timesavings.repo.PublicServiceRepo;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * U8 "내 위치로 찾기": 브라우저 위치(동의했을 때만)를 LLM 프롬프트에 넣어 가까운 공공 서비스를 찾는다.
 * 결과는 DB에 저장하지 않고 응답으로만 돌려준다. 좌표는 소수 둘째 자리(약 1km)로 줄여 보내고 로그에 남기지 않는다.
 * 키가 없거나 실패하면 DB 공공 서비스(지역·질문 검색)로 대체(source:'rule').
 */
@Component
public class NearbyServiceAi {
    public static final String NOTICE = "AI가 찾은 정보예요 · 꼭 기관에 확인하세요";
    private static final List<String> DEFAULT_TOPICS = List.of("돌봄", "안부", "병원", "식사", "치매", "건강");
    private final LlmJson llm;
    private final PublicServiceRepo services;

    public NearbyServiceAi(LlmJson llm, PublicServiceRepo services) { this.llm = llm; this.services = services; }

    static double round2(double v) { return Math.round(v * 100) / 100.0; }

    public static boolean validCoord(Double lat, Double lng) {
        return lat != null && lng != null && !lat.isNaN() && !lng.isNaN() && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    public Map<String, Object> find(String question, Double lat, Double lng, String area, String ageGroup) {
        String q = question == null ? "" : question.trim();
        if (q.length() > 300) q = q.substring(0, 300);
        boolean hasLoc = validCoord(lat, lng);
        Map<String, Object> out = new LinkedHashMap<>();
        if (hasLoc) {
            String loc = "위도 " + round2(lat) + ", 경도 " + round2(lng) + " 부근" + (area == null || area.isBlank() ? "" : " (사용자 프로필 동네: " + area + ")");
            Optional<JsonNode> j = llm.ask("""
                    너는 한국의 어르신·이웃을 위한 공공 서비스 안내 도우미다. 사용자의 현재 위치 부근에서 이용할 수 있는 공공 서비스·기관(행정복지센터, 보건소, 노인복지관, 치매안심센터, 노인일자리 등)을 찾아 쉬운 말로 알려 준다.
                    - 실제로 있을 법한 공공 기관·제도만 쓴다. 확실하지 않은 전화번호·주소·금액은 쓰지 말고 "기관에 확인"이라고 쓴다.
                    - 자격(대상 여부)을 판정하지 않는다.
                    - 3~5개. 각 항목은 짧게.
                    출력: {"answer":"2~3문장 요약","services":[{"name":"기관·서비스 이름","target":"대상","content":"하는 일","applyHow":"신청 방법","contact":"전화(모르면 빈 문자열)","where":"대략 위치(예: ○○구 보건소)"}]}
                    """, "위치: " + loc + "\n질문: " + (q.isEmpty() ? "내 근처에서 받을 수 있는 공공 서비스" : q) + "\n연령대: " + (ageGroup == null ? "모름" : ageGroup), 900);
            if (j.isPresent() && j.get().path("services").isArray() && j.get().path("services").size() > 0) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (JsonNode s : j.get().path("services")) {
                    if (list.size() >= 6) break;
                    String name = s.path("name").asText("").trim();
                    if (name.isEmpty()) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", clip(name, 80));
                    m.put("target", clip(s.path("target").asText(""), 200));
                    m.put("content", clip(s.path("content").asText(""), 300));
                    m.put("applyHow", clip(s.path("applyHow").asText(""), 200));
                    m.put("contact", clip(s.path("contact").asText(""), 40));
                    m.put("where", clip(s.path("where").asText(""), 80));
                    m.put("aiGenerated", true);
                    list.add(m);
                }
                if (!list.isEmpty()) {
                    out.put("answer", clip(j.get().path("answer").asText(""), 500));
                    out.put("services", list);
                    out.put("source", "ai");
                    out.put("provider", llm.providerName());
                    out.put("aiGenerated", true);
                    out.put("notice", NOTICE);
                    out.put("usedLocation", true);
                    out.put("saved", false);
                    return out;
                }
            }
        }
        // 규칙 대체: DB 공공 서비스(지역 + 질문, 질문이 없으면 기본 주제)
        List<String> extra = q.isEmpty() ? DEFAULT_TOPICS : List.of();
        List<PublicService> found = RuleEngine.searchServices(q, extra, area, services.findAll(), 4);
        List<Map<String, Object>> list = new ArrayList<>();
        for (PublicService s : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id); m.put("name", s.name); m.put("target", s.target); m.put("content", s.content);
            m.put("applyHow", s.applyHow); m.put("contact", s.contact); m.put("region", s.region); m.put("aiGenerated", false);
            list.add(m);
        }
        out.put("answer", (hasLoc ? "AI를 쓸 수 없어 " : "위치 없이 ") + (area == null || area.isBlank() ? "" : area + " 기준으로 ") + "등록된 공공 서비스에서 찾았어요. " + RuleEngine.servicesAnswer(found));
        out.put("services", list);
        out.put("source", "rule");
        out.put("aiGenerated", false);
        out.put("notice", "공공 서비스 DB (운영자 등록) · 자격은 기관에서 확인해요");
        out.put("usedLocation", false);
        out.put("saved", false);
        return out;
    }

    private static String clip(String s, int n) { if (s == null) return ""; s = s.trim(); return s.length() > n ? s.substring(0, n) : s; }
}
