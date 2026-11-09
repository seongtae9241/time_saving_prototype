package com.timesavings.ai;

import com.timesavings.service.Views;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** InsightAi 가 기존 공급자(Gemini)를 쓰고, 실패하면 규칙 문장으로 돌아가는지. 실제 외부 API 는 부르지 않는다(MockRestServiceServer). */
class InsightAiTest {
    static final String BASE = "https://gemini.test/v1beta/models";

    record G(InsightAi ai, MockRestServiceServer server) {}

    static G make() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        GeminiProvider gp = new GeminiProvider(b, "test-only-not-a-key", "gemini-flash-latest", BASE);
        AiClient client = new AiClient(null, gp, new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "gemini");
        return new G(new InsightAi(client), s);
    }

    @Test
    void handoverUsesProviderAndKeepsCount() {
        G g = make();
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.containsString("전달 메모: 사진첩 같이 보기")))
                .andRespond(withSuccess(AiProviderTest.candidates("{\"text\":\"최근 말벗 위주로 도움을 받았어요.\",\"points\":[\"사진첩 같이 보기\"]}"), MediaType.APPLICATION_JSON));
        Map<String, Object> r = g.ai().handover("이순례 님", List.of(Views.map("date", "2026-11-01", "category", "말동무·정서", "logText", "이야기", "careMemo", "사진첩 같이 보기")));
        assertEquals("ai", r.get("source"));
        assertEquals("gemini", r.get("provider"));
        assertEquals("최근 말벗 위주로 도움을 받았어요.", r.get("text"));
        assertEquals(1, r.get("count"));
        g.server().verify();
    }

    @Test
    void adminInsightsFallBackToRuleOn429AndKeepNumbers() {
        G g = make();
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        Map<String, Object> rule = Views.map("shortages", List.of(Views.map("category", "동행", "gap", 3, "reason", "규칙")), "newCategories", List.of(), "summary", "s");
        Map<String, Object> r = g.ai().adminInsights(Map.of(), List.of(), rule);
        assertEquals("rule", r.get("source"));
        assertEquals("s", r.get("summary"));
        g.server().verify();
    }

    @Test
    void adminInsightsAiReasonGetsCodeNumbers() {
        G g = make();
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andRespond(withSuccess(AiProviderTest.candidates("{\"shortages\":[{\"category\":\"동행\",\"reason\":\"평일 낮이라 짐작\",\"gap\":999}],\"newCategories\":[{\"name\":\"간단한 수리\",\"reason\":\"전구\",\"examples\":[\"전구 교체\"]}],\"summary\":\"동행 모집\"}"), MediaType.APPLICATION_JSON));
        Map<String, Object> rule = Views.map("shortages", List.of(Views.map("category", "동행", "demand", 5, "supply", 2, "gap", 3, "reason", "규칙")), "newCategories", List.of(), "summary", "s");
        Map<String, Object> r = g.ai().adminInsights(Map.of(), List.of("전구 교체"), rule);
        assertEquals("ai", r.get("source"));
        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) r.get("shortages")).get(0);
        assertEquals(3, s0.get("gap")); // AI 가 쓴 숫자가 아니라 코드 숫자
        assertEquals("평일 낮이라 짐작", s0.get("reason"));
        g.server().verify();
    }
}
