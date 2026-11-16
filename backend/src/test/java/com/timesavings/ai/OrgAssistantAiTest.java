package com.timesavings.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** 2026-11-16 담당자 AI 도우미: 규칙 의도 분류와, 공급자 응답(mock)을 받아들이는 조건. 실제 외부 API 는 부르지 않는다. */
class OrgAssistantAiTest {
    static final Map<String, Object> FACTS = Map.of("gaps", 3, "fixed", 9);
    static final String RULE = "이번 주 빈칸이 3개 있어요. 편성표에서 칸을 누르면 봉사자 추천을 보고 직접 요청할 수 있어요.";

    @Test
    void 프로토타입_예시_질문을_규칙으로_나눈다() {
        assertEquals("DRAFT", OrgAssistantAi.ruleIntent("오덕수 님 다음 주 동행 공고 올려줘"));
        assertEquals("GAPS", OrgAssistantAi.ruleIntent("이번 주 빈칸 정리해줘"));
        assertEquals("CHECK_FIRST", OrgAssistantAi.ruleIntent("먼저 안부 물어볼 분 알려줘"));
        assertEquals("WEATHER", OrgAssistantAi.ruleIntent("한파 대비 할 일 정리해줘"));
        assertEquals("DELEGATION", OrgAssistantAi.ruleIntent("위임 만료 임박한 분"));
        assertEquals("APPLICATIONS", OrgAssistantAi.ruleIntent("봉사자 모집 신청 몇 건이야"));
        assertEquals("VOLUNTEERS", OrgAssistantAi.ruleIntent("단골 봉사자 누구야"));
        assertEquals("TODO", OrgAssistantAi.ruleIntent("오늘 처리할 일"));
        assertEquals("HELP", OrgAssistantAi.ruleIntent("안녕"));
        assertTrue(OrgAssistantAi.asksDiagnosis("치매 같아요?"));
        assertTrue(OrgAssistantAi.asksDecision("이 신청 수락해줘"));
        assertFalse(OrgAssistantAi.asksDecision("신청 몇 건이야"));
    }

    @Test
    void 숫자가_근거에_있는_문장만_받아들인다() {
        assertTrue(OrgAssistantAi.numbersGrounded("빈칸 3개, 확정 9건", "gaps 3 fixed 9"));
        assertFalse(OrgAssistantAi.numbersGrounded("빈칸 4개", "gaps 3 fixed 9"));
        assertTrue(OrgAssistantAi.numbersGrounded("숫자 없는 문장", ""));
    }

    @Test
    void 키가_없으면_규칙_문장_그대로() {
        LlmJson off = new LlmJson(new AiClient(null, new GeminiProvider(org.springframework.web.client.RestClient.builder(), null, "m", LlmCMockTest.BASE),
                new AnthropicProvider(org.springframework.web.client.RestClient.builder(), null, "m", "https://anthropic.test"), true, ""));
        Map<String, Object> r = new OrgAssistantAi(off).polish("빈칸 정리해줘", RULE, FACTS);
        assertEquals("rule", r.get("source"));
        assertEquals(RULE, r.get("text"));
    }

    @Test
    void AI가_다듬은_문장은_숫자가_맞을_때만_쓴다() {
        LlmCMockTest.G g = LlmCMockTest.make();
        LlmCMockTest.reply(g, "{\"text\":\"이번 주에 비어 있는 칸이 3개예요. 편성표에서 칸을 눌러 직접 요청해 주세요.\"}");
        Map<String, Object> r = new OrgAssistantAi(g.llm()).polish("빈칸 정리해줘", RULE, FACTS);
        assertEquals("ai", r.get("source"));
        assertTrue(((String) r.get("text")).contains("3개"));
        g.server().verify();
    }

    @Test
    void 지어낸_숫자나_진단하는_말이_있으면_규칙_문장으로_돌아간다() {
        LlmCMockTest.G g = LlmCMockTest.make();
        LlmCMockTest.reply(g, "{\"text\":\"이번 주 빈칸이 5개 있어요.\"}");
        assertEquals(RULE, new OrgAssistantAi(g.llm()).polish("빈칸", RULE, FACTS).get("text"));
        LlmCMockTest.G g2 = LlmCMockTest.make();
        LlmCMockTest.reply(g2, "{\"text\":\"빈칸이 3개 있어요. 우울증이 의심되니 병원을 권해 주세요.\"}");
        Map<String, Object> r = new OrgAssistantAi(g2.llm()).polish("빈칸", RULE, FACTS);
        assertEquals("rule", r.get("source"));
        assertEquals(RULE, r.get("text"));
        LlmCMockTest.G g3 = LlmCMockTest.make();
        g3.server().expect(requestTo(LlmCMockTest.URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals("rule", new OrgAssistantAi(g3.llm()).polish("빈칸", RULE, FACTS).get("source"));
    }

    @Test
    void 규칙으로_못_나눈_질문만_AI에_의도를_묻는다() {
        LlmCMockTest.G g = LlmCMockTest.make();
        assertEquals("GAPS", new OrgAssistantAi(g.llm()).classify("빈칸 정리해줘").intent()); // 호출 없음
        g.server().verify();
        LlmCMockTest.reply(g, "{\"intent\":\"VOLUNTEERS\"}");
        OrgAssistantAi.Intent i = new OrgAssistantAi(g.llm()).classify("누가 요즘 자주 와?");
        assertEquals("VOLUNTEERS", i.intent());
        assertEquals("ai", i.source());
    }
}
