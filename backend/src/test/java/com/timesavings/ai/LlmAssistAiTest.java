package com.timesavings.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * LLM 추가 A(7.12): 코드 계산·규칙 대체·LLM 응답 파싱. 실제 외부 API 는 부르지 않는다(MockRestServiceServer).
 */
class LlmAssistAiTest {
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

    static G noKey() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        GeminiProvider gp = new GeminiProvider(b, null, "gemini-flash-latest", BASE);
        AiClient client = new AiClient(null, gp, new AnthropicProvider(RestClient.builder(), null, "m", "https://anthropic.test"), true, "");
        return new G(new LlmJson(client), s);
    }

    static void reply(G g, String json) {
        g.server().expect(requestTo(URL)).andRespond(withSuccess(AiProviderTest.candidates(json), MediaType.APPLICATION_JSON));
    }

    // ---------------- 숫자 → 한글 읽기 (코드) ----------------
    @Test
    void 숫자를_소리로_읽기_좋게() {
        assertEquals("오후 두 시", KoreanSpeech.clock(14));
        assertEquals("오전 아홉 시 반", KoreanSpeech.clock(9.5));
        assertEquals("낮 열두 시", KoreanSpeech.clock(12));
        assertEquals("한 시간 반", KoreanSpeech.duration(1.5));
        assertEquals("스물네 시간", KoreanSpeech.duration(24));
        assertEquals("이천이십육", KoreanSpeech.sino(2026));
        String s = KoreanSpeech.speakable("11/17 14:00 · 동행 · 3.0시간, 24시간 안에 응답해 주세요");
        assertFalse(s.matches(".*\\d.*"), s);
        assertTrue(s.contains("십일월 십칠일"), s);
        assertTrue(s.contains("오후 두 시"), s);
        assertTrue(s.contains("세 시간"), s);
        assertTrue(s.contains("스물네 시간"), s);
        assertTrue(KoreanSpeech.speakable("오후 2시 30분").contains("오후 두 시 삼십 분"));
        assertTrue(KoreanSpeech.date(LocalDate.of(2026, 6, 10)).startsWith("유월 십일"));
    }

    // ---------------- 1. 요청서 빠진 정보 점검 ----------------
    @Test
    void 빠진_정보는_카테고리_체크리스트로_최대_두개() {
        List<PostCheckAi.Item> m = PostCheckAi.missing(List.of("가벼운 집안일"), "베란다 청소 도와주세요");
        assertEquals(List.of("stairs", "pet"), m.stream().map(PostCheckAi.Item::key).toList());
        // 글에 이미 있으면 묻지 않는다
        m = PostCheckAi.missing(List.of("가벼운 집안일"), "3층 엘리베이터 없음, 고양이 있어요. 청소 도구는 집에 있어요");
        assertTrue(m.isEmpty());
        // 동행: 만날 곳·이동 방법
        m = PostCheckAi.missing(List.of("동행"), "정형외과 진료 같이 가 주세요");
        assertEquals(List.of("meet", "move"), m.stream().map(PostCheckAi.Item::key).toList());
        assertTrue(PostCheckAi.missing(List.of("동행"), "집 앞에서 만나 택시 타고 가요").isEmpty());
    }

    @Test
    void 점검_규칙_모드와_AI_다듬기() {
        G nk = noKey();
        Map<String, Object> r = new PostCheckAi(nk.llm()).check(List.of("장보기·심부름"), "장보기", "일주일치 장보기", null, null, null);
        assertEquals("rule", r.get("source"));
        List<?> qs = (List<?>) r.get("questions");
        assertEquals(2, qs.size());
        assertEquals("heavy", ((Map<?, ?>) qs.get(0)).get("key"));
        assertEquals(true, r.get("skippable"));

        G g = make();
        reply(g, "{\"questions\":[{\"key\":\"heavy\",\"text\":\"쌀이나 생수처럼 무거운 게 있나요?\"},{\"key\":\"zzz\",\"text\":\"모르는 항목\"}]}");
        r = new PostCheckAi(g.llm()).check(List.of("장보기·심부름"), "장보기", "일주일치 장보기", null, null, null);
        assertEquals("ai", r.get("source"));
        qs = (List<?>) r.get("questions");
        assertEquals(2, qs.size()); // 항목 수는 코드가 정한다(LLM 이 만든 zzz 는 무시)
        assertEquals("쌀이나 생수처럼 무거운 게 있나요?", ((Map<?, ?>) qs.get(0)).get("text"));
        assertNotNull(((Map<?, ?>) qs.get(0)).get("choices"));
        g.server().verify();
    }

    // ---------------- 2. 추천 이유 ----------------
    @Test
    void 추천_이유_LLM이_숫자를_바꾸면_코드_문장() {
        G g = make();
        reply(g, "{\"text\":\"동행을 15회 해 본 이웃이에요\"}");
        MatchReasonAi.Polished p = new MatchReasonAi(g.llm()).polish(List.of("단골", "동행 12회", "같은 동네"));
        assertEquals("rule", p.source());
        assertEquals("단골 · 동행 12회 · 같은 동네", p.text());

        G g2 = make();
        reply(g2, "{\"text\":\"동행을 12회 해 본 같은 동네 단골이에요\"}");
        p = new MatchReasonAi(g2.llm()).polish(List.of("단골", "동행 12회", "같은 동네"));
        assertEquals("ai", p.source());
        assertEquals("동행을 12회 해 본 같은 동네 단골이에요", p.text());
        g2.server().verify();
    }

    // ---------------- 3. 고치기 제안 ----------------
    @Test
    void 고치기_제안_LLM은_후보_행동만_다듬는다() {
        List<Map<String, Object>> cands = List.of(
                Map.of("action", "TIME", "text", "오후 시간대로 바꿔 보세요. 3건 있어요.", "suggest", Map.of("startHour", 13.0, "endHour", 17.0)),
                Map.of("action", "SPLIT", "text", "도움 종류를 나눠 올려 보세요.", "suggest", Map.of("categories", List.of("동행"))));
        G g = make();
        reply(g, "{\"tips\":[{\"action\":\"TIME\",\"text\":\"오후로 옮기면 맞는 이웃이 3명 있어요\"},{\"action\":\"DELETE\",\"text\":\"공고를 지우세요\"},{\"action\":\"SPLIT\",\"text\":\"99건이 있어요\"}]}");
        FixTipsAi.Result r = new FixTipsAi(g.llm()).polish(cands, "장보기", "같은 시간대 3건");
        assertEquals("ai", r.source());
        assertEquals(1, r.tips().size()); // DELETE(후보 아님)·99(근거에 없는 숫자)는 버림
        assertEquals("TIME", r.tips().get(0).get("action"));
        assertEquals(Map.of("startHour", 13.0, "endHour", 17.0), r.tips().get(0).get("suggest")); // 바꿀 값은 코드 값
        g.server().verify();

        G g2 = make();
        g2.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        r = new FixTipsAi(g2.llm()).polish(cands, "장보기", "근거");
        assertEquals("rule", r.source());
        assertEquals(2, r.tips().size());
    }

    // ---------------- 4. 쉬운 말 ----------------
    @Test
    void 쉬운_말_코드_문장과_검증() {
        String base = EasyTextAi.base(true, "김하늘", LocalDate.of(2026, 11, 18), 13, 17, List.of("동행"), null, null);
        assertEquals("김하늘 님이 십일월 십팔일 수요일 오후 한 시부터 다섯 시 사이에 병원이나 외출에 같이 가는 일을 도와줄 수 있어요.", base);
        String need = EasyTextAi.base(false, "이○례 님", LocalDate.of(2026, 11, 18), 9, 12, List.of("말동무·정서"), 2.0, null);
        assertTrue(need.contains("말벗이 되어 드리기를"), need);
        assertTrue(need.contains("두 시간 정도"), need);

        G g = make();
        reply(g, "{\"text\":\"김하늘 님이 11월 18일에 병원에 같이 가 줘요\"}"); // 숫자를 쓰면 버림
        EasyTextAi.Easy e = new EasyTextAi(g.llm()).make(base);
        assertEquals("rule", e.source());
        assertEquals(base, e.text());

        G g2 = make();
        reply(g2, "{\"text\":\"김하늘 님이 십일월 십팔일 수요일 오후 한 시부터 다섯 시 사이에 병원에 같이 가 줄 수 있어요.\"}");
        e = new EasyTextAi(g2.llm()).make(base);
        assertEquals("ai", e.source());
    }

    // ---------------- 5. 비서 의도(규칙) ----------------
    @Test
    void 비서_규칙_의도() {
        assertEquals("BALANCE", AssistantAi.ruleIntent("내 시간 얼마 남았어?"));
        assertEquals("TOMORROW", AssistantAi.ruleIntent("내일 누가 와?"));
        assertEquals("NEXT", AssistantAi.ruleIntent("다음 일정 언제야?"));
        assertEquals("CANCEL", AssistantAi.ruleIntent("목요일 거 취소해 줘"));
        assertEquals("REGULAR", AssistantAi.ruleIntent("단골 누구야?"));
        assertEquals("NOTI", AssistantAi.ruleIntent("새로 온 알림 있어?"));
        LocalDate mon = LocalDate.of(2026, 11, 9);
        assertEquals(LocalDate.of(2026, 11, 12), AssistantAi.date("목요일 거 취소해 줘", mon));

        G g = make();
        reply(g, "{\"intent\":\"HACK\"}");
        assertEquals("CANCEL", new AssistantAi(g.llm()).classify("목요일 거 취소해 줘", mon).intent()); // 모르는 의도면 규칙
        G g2 = make();
        reply(g2, "{\"intent\":\"regular\"}");
        AssistantAi.Intent in = new AssistantAi(g2.llm()).classify("자주 오던 학생 누구였지", mon);
        assertEquals("REGULAR", in.intent());
        assertEquals("ai", in.source());
    }

    // ---------------- 6. 전화 알림 문장 ----------------
    @Test
    void 전화_문장_숫자는_한글_LLM이_만든_숫자는_버림() {
        String r = VoiceTextAi.rule("김하늘 님이 도움을 요청했어요", "11/18 14:00 · 동행 · 24시간 안에 응답해 주세요");
        assertTrue(r.startsWith(VoiceTextAi.PREFIX));
        assertFalse(r.matches(".*\\d.*"), r);
        assertTrue(r.contains("오후 두 시"), r);

        G g = make();
        reply(g, "{\"text\":\"내일 오후 3시에 김하늘 님이 와요\"}"); // 원문에 없는 3
        VoiceTextAi.Voice v = new VoiceTextAi(g.llm()).make("김하늘 님이 수락했어요", "11/18 14:00 · 동행");
        assertEquals("rule", v.source());

        G g2 = make();
        reply(g2, "{\"text\":\"11월 18일 오후 14:00에 김하늘 님이 병원 동행하러 와요\"}");
        v = new VoiceTextAi(g2.llm()).make("김하늘 님이 수락했어요", "11/18 14:00 · 동행");
        assertEquals("ai", v.source());
        assertFalse(v.text().matches(".*\\d.*"), v.text());
        assertTrue(v.text().contains("십일월 십팔일"), v.text());
    }
}
