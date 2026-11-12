package com.timesavings.ai;

import com.timesavings.service.Views;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 41판 B-54·B-56·B-61 의 AI 응답(mock) 처리. 실제 외부 API 는 부르지 않는다. */
class Req41AiMockTest {
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

    static void reply(G g, String json) {
        g.server().expect(requestTo(URL)).andRespond(withSuccess(AiProviderTest.candidates(json), MediaType.APPLICATION_JSON));
    }

    static final Map<String, Object> PERSONA = Views.map("name", "김○자 님", "trait", "말수가 적으세요");
    static final List<Map<String, Object>> SITS = List.of(Views.map("title", "연락처 교환", "line", "전화번호 좀 적어 줘."));

    @Test
    void 역할극_AI는_어르신의_다음_말과_판정과_바꿔_말하기를_준다() {
        G g = make();
        reply(g, "{\"reply\":\"그려, 그럼 번호 좀 적어 주고 가.\",\"quality\":\"poor\",\"flag\":\"없는값\",\"better\":\"아, 그러셨어요? 천천히 말씀해 주세요.\"}");
        Optional<RoleplayAi.Turn> t = new RoleplayAi(g.ai()).turn(PERSONA, SITS, List.of(Views.map("who", "them", "text", "어서 와요.")), "네.", false);
        assertTrue(t.isPresent());
        assertEquals("그려, 그럼 번호 좀 적어 주고 가.", t.get().reply());
        assertEquals("poor", t.get().quality());
        assertNull(t.get().flag()); // 정해진 이름만
        assertEquals("아, 그러셨어요? 천천히 말씀해 주세요.", t.get().better());
        g.server().verify();
    }

    @Test
    void 역할극_AI_응답이_이상하거나_실패하면_빈_값이라_대본과_규칙으로_간다() {
        G g = make();
        reply(g, "{\"reply\":\"\",\"quality\":\"great\"}");
        assertTrue(new RoleplayAi(g.ai()).turn(PERSONA, SITS, List.of(), "안녕하세요", false).isEmpty());
        G g2 = make();
        g2.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertTrue(new RoleplayAi(g2.ai()).turn(PERSONA, SITS, List.of(), "안녕하세요", true).isEmpty());
    }

    @Test
    void 확인서_요약은_횟수를_바꿔_쓰면_규칙_문장으로_돌아간다() {
        Map<String, Object> facts = Views.map("count", 7, "totalHours", 12.5, "elderCount", 3);
        G g = make();
        reply(g, "{\"text\":\"저는 이웃 돌봄 봉사에 7회 참여했습니다.\"}");
        Map<String, Object> ok = new CertAi(g.ai()).summary(facts, "규칙 문장 7회");
        assertEquals("ai", ok.get("source"));
        assertEquals("저는 이웃 돌봄 봉사에 7회 참여했습니다.", ok.get("text"));
        G g2 = make();
        reply(g2, "{\"text\":\"저는 수십 회 봉사했습니다.\"}");
        Map<String, Object> bad = new CertAi(g2.ai()).summary(facts, "규칙 문장 7회");
        assertEquals("rule", bad.get("source"));
        assertEquals("규칙 문장 7회", bad.get("text"));
    }

    @Test
    void 플래너_AI가_고른_시각을_공고별로_읽는다() {
        G g = make();
        reply(g, "{\"plans\":[{\"postId\":11,\"startHour\":15.5},{\"postId\":\"x\",\"startHour\":9},{\"postId\":12}]}");
        Map<Long, Double> m = new PlannerAi(g.ai()).pickTimes(Views.map("timeSlots", List.of("평일 오후")),
                List.of(Views.map("postId", 11, "candidates", List.of(15.0, 15.5))));
        assertEquals(Map.of(11L, 15.5), m);
        // 고를 것이 없으면 AI 를 부르지 않는다
        assertTrue(new PlannerAi(make().ai()).pickTimes(Map.of(), List.of()).isEmpty());
    }
}
