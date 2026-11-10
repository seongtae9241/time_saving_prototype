package com.timesavings.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 기관 클래스 AI 웹 검색(Gemini 그라운딩) 요청 모양·응답 파싱·켜짐 조건. 실제 외부 API 는 부르지 않는다. */
class PublicClassAiTest {
    static final String BASE = "https://gemini.test/v1beta/models";
    static final String FAKE_KEY = "test-only-not-a-key";

    static String groundedResponse(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + TextNode.valueOf(text) + "}]},"
                + "\"groundingMetadata\":{\"webSearchQueries\":[\"금천구 평생학습 강좌\"],\"groundingChunks\":["
                + "{\"web\":{\"uri\":\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\",\"title\":\"geumcheon.go.kr\"}},"
                + "{\"web\":{\"uri\":\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\",\"title\":\"dup\"}},"
                + "{\"web\":{\"uri\":\"https://example.org/lib\",\"title\":\"example.org\"}}]}}]}";
    }

    @Test
    void 그라운딩_요청_모양과_응답_파싱() {
        RestClient.Builder gb = RestClient.builder();
        MockRestServiceServer gs = MockRestServiceServer.bindTo(gb).build();
        GeminiGrounding grounding = new GeminiGrounding(gb, FAKE_KEY, "gemini-flash-latest", BASE);
        String text = "찾아봤어요.\n```json\n{\"classes\":[{\"title\":\"스마트폰 활용 교실\",\"orgName\":\"금천구 평생학습관\",\"place\":\"독산동 주민센터\","
                + "\"startDate\":\"2026-11-20\",\"endDate\":\"2026-12-18\",\"schedule\":\"목 10:00~12:00\",\"target\":\"50세 이상\",\"fee\":\"0\","
                + "\"applyUrl\":\"https://www.geumcheon.go.kr/edu/index.do\",\"summary\":\"휴대폰 기초\"},"
                + "{\"title\":\"주소 없는 강좌\",\"orgName\":\"어딘가\",\"applyUrl\":\"\"}]}\n```";
        gs.expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", FAKE_KEY))
                .andExpect(jsonPath("$.tools[0].google_search").exists())
                .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.containsString("금천구 독산동")))
                .andRespond(withSuccess(groundedResponse(text), MediaType.APPLICATION_JSON));
        RestClient.Builder pb = RestClient.builder();
        GeminiProvider gemini = new GeminiProvider(pb, FAKE_KEY, "gemini-flash-latest", BASE);
        AiClient ai = new AiClient(null, gemini, new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "");
        PublicClassAi pai = new PublicClassAi(ai, new LlmJson(ai), grounding);
        assertTrue(pai.searchAvailable());
        Optional<PublicClassAi.SearchResult> r = pai.search("금천구 독산동", null);
        assertTrue(r.isPresent());
        List<PublicClassAi.Candidate> items = r.get().items();
        assertEquals(2, items.size());
        assertEquals("스마트폰 활용 교실", items.get(0).title());
        assertEquals("https://www.geumcheon.go.kr/edu/index.do", items.get(0).applyUrl());
        assertEquals("2026-11-20", items.get(0).startDate());
        assertEquals("", items.get(1).applyUrl()); // 버리는 것은 서비스가 한다
        assertEquals(2, r.get().sources().size()); // 같은 주소 근거는 한 번만
        gs.verify();
    }

    @Test
    void 배열만_온_응답도_읽는다() {
        List<PublicClassAi.Candidate> c = PublicClassAi.parseCandidates(new ObjectMapper(), "결과: [{\"title\":\"노래 교실\",\"url\":\"https://example.org/a\",\"dates\":\"2026-12-01\"}] 끝");
        assertEquals(1, c.size());
        assertEquals("https://example.org/a", c.get(0).applyUrl());
        assertEquals("2026-12-01", c.get(0).startDate());
        assertTrue(PublicClassAi.parseCandidates(new ObjectMapper(), "모르겠어요").isEmpty());
    }

    @Test
    void 키_없음이나_Anthropic이면_검색_꺼짐() {
        GeminiGrounding noKey = new GeminiGrounding(RestClient.builder(), null, null, BASE);
        AiClient rule = new AiClient(null, new GeminiProvider(RestClient.builder(), null, null, BASE),
                new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "");
        assertFalse(new PublicClassAi(rule, new LlmJson(rule), noKey).searchAvailable());
        // Anthropic 을 고르면(Gemini 키가 있어도) 그라운딩은 끈다
        GeminiGrounding g = new GeminiGrounding(RestClient.builder(), FAKE_KEY, null, BASE);
        AiClient anth = new AiClient(null, new GeminiProvider(RestClient.builder(), FAKE_KEY, null, BASE),
                new AnthropicProvider(RestClient.builder(), FAKE_KEY, "m", "https://anthropic.test"), true, "anthropic");
        assertFalse(new PublicClassAi(anth, new LlmJson(anth), g).searchAvailable());
        assertTrue(new PublicClassAi(new AiClient(null, new GeminiProvider(RestClient.builder(), FAKE_KEY, null, BASE),
                new AnthropicProvider(RestClient.builder(), null, "m", "x"), true, "gemini"), null, g).searchAvailable());
    }
}
