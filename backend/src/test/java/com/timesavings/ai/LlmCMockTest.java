package com.timesavings.ai;

import com.timesavings.service.OpsSafetyService;
import com.timesavings.service.Views;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 7.14 LLM 추가 C: 공급자 응답(mock) 파싱과 서버 후처리(진단 문장·병명 제거, 숫자는 코드, 형식 검사). 실제 외부 API 는 부르지 않는다. */
class LlmCMockTest {
    static final String BASE = "https://gemini.test/v1beta/models";
    static final String URL = BASE + "/gemini-flash-latest:generateContent";

    record G(LlmJson llm, MockRestServiceServer server) {}

    static G make() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        GeminiProvider gp = new GeminiProvider(b, "test-only-not-a-key", "gemini-flash-latest", BASE);
        AiClient client = new AiClient(null, gp, new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "gemini");
        return new G(new LlmJson(client), s);
    }

    static void reply(G g, String json) {
        g.server().expect(requestTo(URL)).andRespond(withSuccess(AiProviderTest.candidates(json), MediaType.APPLICATION_JSON));
    }

    @Test
    void 메모_정리_AI_결과에서도_진단_문장과_병명을_지운다() {
        G g = make();
        g.server().expect(requestTo(URL)).andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.containsString("(수락 뒤 메시지로 안내돼요)")))
                .andRespond(withSuccess(AiProviderTest.candidates("{\"next\":[\"죽 사 가기\",\"우울증이 의심되니 병원 권하기\"],\"hospital\":\"고혈압 약 시간\","
                        + "\"life\":[\"점심을 거르심\",\"치매 증상이 보임\"],\"signals\":[\"식사 거름\",\"우울\"]}"), MediaType.APPLICATION_JSON));
        CareMemoAi.Structured s = new CareMemoAi(g.llm()).structure("점심을 거르셨대요. 010-1234-5678 로 연락 달래요. 다음엔 죽 사 가기로 했어요.", null);
        assertEquals("ai", s.source());
        assertEquals(List.of("죽 사 가기"), s.next());
        assertEquals(List.of("점심을 거르심"), s.life());
        assertEquals(List.of("식사 거름"), s.signals()); // 정해진 이름만
        assertFalse(s.hospital().contains("고혈압"), s.hospital());
        assertTrue(s.hospital().startsWith("병원 안내 있음"));
        g.server().verify();
    }

    @Test
    void 메모_정리_실패하면_규칙() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        CareMemoAi.Structured s = new CareMemoAi(g.llm()).structure("기운이 없다고 하셨어요.", null);
        assertEquals("rule", s.source());
        assertEquals(List.of("기운 없음"), s.signals());
    }

    @Test
    void 통화_메모_AI_나누기_날짜는_코드가_구절에서_읽는다() {
        G g = make();
        reply(g, "{\"items\":[{\"segment\":\"매주 목요일 오전 말벗\",\"title\":\"말벗 방문\",\"detail\":\"이야기 나눠 주세요\",\"caution\":\"치매가 있어요\",\"categories\":[\"말동무·정서\"],\"date\":\"2020-01-01\"},"
                + "{\"segment\":\"다음 주 화요일 오후 정형외과 동행\",\"title\":\"정형외과 동행\",\"detail\":\"진료 같이 가 주세요\",\"caution\":\"\",\"categories\":[\"동행\",\"없는카테고리\"]}]}");
        Map<String, Object> out = new OrgAi(g.llm()).memoToPosts("매주 목요일 오전 말벗, 다음 주 화요일 오후 정형외과 동행", "이순례", "행복동", null, LocalDate.now());
        assertEquals("ai", out.get("source"));
        List<?> d = (List<?>) out.get("drafts");
        assertEquals(2, d.size());
        Map<?, ?> a = (Map<?, ?>) d.get(0), b = (Map<?, ?>) d.get(1);
        assertEquals(DayOfWeek.THURSDAY, LocalDate.parse((String) a.get("date")).getDayOfWeek()); // AI 가 준 날짜가 아니라 코드
        assertFalse(String.valueOf(a.get("note")).contains("치매"));
        assertEquals(List.of("동행"), b.get("categories"));
        assertEquals("정형외과 동행", b.get("title"));
        g.server().verify();
    }

    @Test
    void 역할극_초안_형식이_맞으면_쓰고_가명으로_바꾼다() {
        G g = make();
        String opt = "{\"t\":\"좋은 대답\",\"q\":\"good\"},{\"t\":\"그럭저럭\",\"q\":\"ok\"},{\"t\":\"아쉬운 대답\",\"q\":\"poor\"},{\"t\":\"제 번호는 010-9999-8888이에요\",\"q\":\"bad\",\"f\":\"연락처\"}";
        reply(g, "{\"name\":\"박영자\",\"ageLabel\":\"84세 · 어르신\",\"fitCategory\":\"장보기·심부름\",\"trait\":\"정이 많아요\",\"goal\":\"규칙 지키기\",\"opening\":\"왔어?\","
                + "\"turns\":[{\"options\":[" + opt + "],\"next\":\"고마워\"},{\"options\":[" + opt + "],\"next\":\"번호 줘\"},{\"options\":[" + opt + "],\"next\":\"잘 가\"}]}");
        Map<String, Object> r = new AdminAi(g.llm()).persona(List.of(), Map.of("label", "돈"), OpsSafetyService.template("money"));
        assertEquals("ai", r.get("source"));
        assertEquals("박○자 님", r.get("name"));
        assertEquals("자", r.get("initial"));
        assertEquals(false, r.get("active"));
        assertFalse(r.toString().contains("9999-8888"));
        g.server().verify();
    }

    @Test
    void 역할극_초안_형식이_틀리면_템플릿() {
        G g = make();
        reply(g, "{\"name\":\"김철수\",\"turns\":[{\"options\":[{\"t\":\"a\",\"q\":\"great\"}],\"next\":\"x\"}]}");
        Map<String, Object> r = new AdminAi(g.llm()).persona(List.of(), Map.of(), OpsSafetyService.template("privacy"));
        assertEquals("rule", r.get("source"));
        assertEquals("윤○희 님", r.get("name"));
        assertEquals(3, ((List<?>) r.get("turns")).size());
    }

    @Test
    void 신고_요약_AI_심각도는_제안만() {
        G g = make();
        reply(g, "{\"summary\":\"대상이 돈을 요구했다는 신고예요.\",\"severity\":\"low\",\"suggestion\":\"경고를 검토해 보세요.\"}");
        Map<String, Object> r = new AdminAi(g.llm()).triage(Views.map("type", "기타"), Views.map("summary", "s", "suggestion", "규칙"));
        assertEquals("ai", r.get("source"));
        assertEquals("low", r.get("aiSeverity")); // 서비스가 코드 규칙(high)을 바닥으로 쓴다(OpsSafetyService.triage)
        assertEquals("경고를 검토해 보세요.", r.get("suggestion"));
    }

    @Test
    void 이상_적립_설명_실패하면_규칙_근거() {
        G g = make();
        g.server().expect(requestTo(URL)).andRespond(withServerError());
        Map<String, Object> rule = Views.map("text", "규칙 설명", "checks", List.of("확인"));
        Map<String, Object> r = new AdminAi(g.llm()).anomaly(Map.of("count", 3), rule);
        assertEquals("rule", r.get("source"));
        assertEquals("규칙 설명", r.get("text"));
    }

    @Test
    void 문의_초안_AI_used_번호는_근거_범위_안만() {
        G g = make();
        reply(g, "{\"draft\":\"일지를 올리면 적립돼요.\",\"used\":[0,5,-1]}");
        Map<String, Object> r = new AdminAi(g.llm()).inquiry(Map.of(), List.of(Map.of("label", "규칙")), "규칙 초안");
        assertEquals("ai", r.get("source"));
        assertEquals(List.of(0), r.get("used"));
    }

    @Test
    void 브리핑_AI_요약_줄이_적으면_규칙_줄() {
        G g = make();
        reply(g, "{\"lines\":[\"한 줄\"],\"todo\":[\"일지 확인하기\"]}");
        Map<String, Object> r = new OrgAi(g.llm()).briefing(Map.of(), List.of("a", "b", "c"), List.of("t"));
        assertEquals("ai", r.get("source"));
        assertEquals(List.of("a", "b", "c"), r.get("lines"));
        assertEquals(List.of("일지 확인하기"), r.get("todo"));
    }
}
