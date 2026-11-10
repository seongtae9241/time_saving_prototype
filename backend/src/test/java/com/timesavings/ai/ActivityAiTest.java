package com.timesavings.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** ActivityAi(7.13 B) mock 파싱: 실제 외부 API 는 부르지 않는다(MockRestServiceServer + 가짜 키). */
class ActivityAiTest {
    static final String BASE = "https://gemini.test/v1beta/models";
    static final String URL = BASE + "/gemini-flash-latest:generateContent";

    record G(ActivityAi ai, MockRestServiceServer server) {}

    static G make() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        GeminiProvider gp = new GeminiProvider(b, "test-only-not-a-key", "gemini-flash-latest", BASE);
        AiClient client = new AiClient(null, gp, new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "gemini");
        return new G(new ActivityAi(client), s);
    }

    static String ok(String json) { return AiProviderTest.candidates(json); }

    @Test
    void 브리핑은_기본_정보만_보내고_AI_줄을_쓴다() {
        G g = make();
        g.server().expect(requestTo(URL))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(containsString("행복동")))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(not(containsString("careMemo"))))
                .andRespond(withSuccess(ok("{\"lines\":[\"이○례 님(행복동) 병원 동행이에요\",\"진료 결정은 어르신이 해요\",\"부축까지만 해요\",\"연락은 앱으로만, 010-1234-5678\"]}"), MediaType.APPLICATION_JSON));
        Map<String, Object> basics = Map.of("alias", "이○례 님", "dong", "행복동", "content", "병원 동행");
        Map<String, Object> r = g.ai().briefing(basics, "동행", List.of("규칙1", "규칙2", "규칙3"));
        assertEquals("ai", r.get("source"));
        List<?> lines = (List<?>) r.get("lines");
        assertEquals(3, lines.size()); // 전화번호가 든 줄은 버림
        g.server().verify();
    }

    @Test
    void 메시지_분류는_AI가_수준을_올릴_수는_있어도_코드_경고를_내리지_못한다() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"level\":\"none\",\"reasons\":[],\"replies\":[\"네 좋아요\",\"카톡 주세요\"],\"schedule\":{\"change\":false}}"), MediaType.APPLICATION_JSON));
        String t = "제 번호 010-2222-3333으로 전화 주세요";
        ActivityRules.Safety s = ActivityRules.safety(t);
        Map<String, Object> r = g.ai().message(t, "동행", s, null, ActivityRules.replies(t, s, null), 14.0);
        assertEquals("warn", r.get("level"));
        assertTrue(((List<?>) r.get("reasons")).contains("연락처 교환"));
        assertFalse(((List<?>) r.get("replies")).contains("카톡 주세요"));
        g.server().verify();

        G g2 = make();
        g2.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"level\":\"warn\",\"reasons\":[\"앱 밖 만남 유도\",\"엉뚱한 이유\"],\"replies\":[\"네\",\"아니요\"],\"schedule\":{\"change\":true,\"kind\":\"DATE\",\"text\":\"다음 달로 미루자고 했어요\"}}"), MediaType.APPLICATION_JSON));
        String t2 = "다음엔 공원에서 그냥 보면 어때요, 굳이 신청 안 하고";
        ActivityRules.Safety s2 = ActivityRules.safety(t2);
        Map<String, Object> r2 = g2.ai().message(t2, "말동무", s2, null, ActivityRules.replies(t2, s2, null), 14.0);
        assertEquals("ai", r2.get("source"));
        assertEquals("warn", r2.get("level"));
        assertEquals(List.of("앱 밖 만남 유도"), r2.get("reasons")); // 목록 밖 이유는 버림
        assertNotNull(r2.get("warnText"));
        assertEquals("DATE", ((Map<?, ?>) r2.get("schedule")).get("kind"));
        assertTrue(((List<?>) r2.get("replies")).contains("연락은 앱 메시지로만 할게요")); // 경고면 거절 답장
        g2.server().verify();
    }

    @Test
    void 메시지_분류_실패하면_규칙() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        String t = "3시로 바꿔도 될까요?";
        ActivityRules.Schedule sc = ActivityRules.schedule(t, 14.0);
        Map<String, Object> r = g.ai().message(t, "동행", ActivityRules.safety(t), sc, ActivityRules.replies(t, ActivityRules.safety(t), sc), 14.0);
        assertEquals("rule", r.get("source"));
        assertEquals("none", r.get("level"));
        assertEquals("TIME", ((Map<?, ?>) r.get("schedule")).get("kind"));
        g.server().verify();
    }

    @Test
    void 판단_보조는_근거만_정리하고_결정하지_않는다() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"pro\":[\"체크 기록과 비슷해요\"],\"con\":[\"버튼 기록이에요\"],\"summary\":\"기록을 확인해 주세요\",\"decision\":\"ACCEPT\"}"), MediaType.APPLICATION_JSON));
        Map<String, Object> r = g.ai().judge("ADJUST", List.of(Map.of("item", "활동 시간")), "대기가 길었어요", "접수 대기", List.of("p"), List.of("c"), "s");
        assertEquals("ai", r.get("source"));
        assertEquals(List.of("체크 기록과 비슷해요"), r.get("pro"));
        assertFalse(r.containsKey("decision"));
        g.server().verify();
    }

    @Test
    void 결산_설명에_코드에_없는_숫자가_있으면_규칙_문장() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"text\":\"이번 달 9시간 쌓았어요\",\"suggestion\":\"다음 달도 화이팅\"}"), MediaType.APPLICATION_JSON));
        Map<String, Object> r = g.ai().monthNote(Map.of("earned", 6.5), List.of(6.5, 1.5), "이번 달 6.5시간 쌓았어요.", "제안");
        assertEquals("rule", r.get("source"));
        assertEquals("이번 달 6.5시간 쌓았어요.", r.get("text"));
        g.server().verify();

        G g2 = make();
        g2.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"text\":\"이번 달 6.5시간 쌓았어요. 동행이 1.5배라 많이 늘었어요\",\"suggestion\":\"다음 달엔 말동무도 해 보세요\"}"), MediaType.APPLICATION_JSON));
        Map<String, Object> r2 = g2.ai().monthNote(Map.of("earned", 6.5), List.of(6.5, 1.5), "규칙", "제안");
        assertEquals("ai", r2.get("source"));
        assertTrue(r2.get("text").toString().contains("1.5배"));
        g2.server().verify();
    }

    @Test
    void 목표_코치는_후보_번호만_고른다() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withSuccess(ok("{\"text\":\"오후 동행을 한 달 2번 해 보세요\",\"tips\":[\"관심 알림 켜기\"],\"pick\":[1,9,1]}"), MediaType.APPLICATION_JSON));
        Map<String, Object> r = g.ai().goalCoach(Map.of("monthlyTarget", 6.5), Map.of(), List.of(Map.of("no", 0), Map.of("no", 1)), "규칙", List.of("t"));
        assertEquals("ai", r.get("source"));
        assertEquals(List.of(1), r.get("pick"));
        g.server().verify();
    }
}
