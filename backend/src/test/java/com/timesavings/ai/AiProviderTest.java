package com.timesavings.ai;

import com.timesavings.config.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 공급자 추상화·Gemini 요청 모양·응답 파싱·429 규칙 대체·서버 음성 인식. 실제 외부 API 는 부르지 않는다(MockRestServiceServer). */
class AiProviderTest {
    static final String BASE = "https://gemini.test/v1beta/models";
    static final String FAKE_KEY = "test-only-not-a-key";

    record G(GeminiProvider p, MockRestServiceServer server) {}

    static G gemini(String key) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        return new G(new GeminiProvider(b, key, "gemini-flash-latest", BASE), s);
    }

    static AnthropicProvider anthropic(String key) {
        return new AnthropicProvider(RestClient.builder(), key, "claude-haiku-5-5", "https://anthropic.test/v1/messages");
    }

    static String candidates(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}]}}]}";
    }

    @Test
    void geminiRequestShapeAndJsonParsing() {
        G g = gemini(FAKE_KEY);
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", FAKE_KEY))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("기록: 장보고 같이 걸었어요"))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").exists())
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess(candidates("{\"text\":\"장을 같이 봤어요.\\n천천히 걸었어요.\"}"), MediaType.APPLICATION_JSON));
        AiClient ai = new AiClient(null, g.p(), anthropic(null), true, "");
        assertEquals("gemini", ai.providerName());
        Map<String, Object> r = ai.logTidy("장보고 같이 걸었어요");
        assertEquals("ai", r.get("source"));
        assertEquals("gemini", r.get("provider"));
        assertEquals("장을 같이 봤어요.\n천천히 걸었어요.", r.get("text"));
        g.server().verify();
    }

    @Test
    void gemini429FallsBackToRule() {
        G g = gemini(FAKE_KEY);
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        AiClient ai = new AiClient(null, g.p(), anthropic(null), true, "gemini");
        Map<String, Object> r = ai.difficulty("병원 같이 가 주세요", java.util.List.of("동행"), 2.0);
        assertEquals("rule", r.get("source"));
        assertNull(r.get("provider"));
        assertEquals("BASIC", r.get("level")); // 동행은 기본 등급(7.21)
        g.server().verify();
    }

    @Test
    void geminiMalformedFallsBackToRule() {
        G g = gemini(FAKE_KEY);
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andRespond(withSuccess(candidates("죄송해요, 설명만 할게요"), MediaType.APPLICATION_JSON));
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        AiClient ai = new AiClient(null, g.p(), anthropic(null), true, "");
        assertEquals("rule", ai.logTidy("말동무 했어요").get("source"));
        assertEquals("rule", ai.logTidy("말동무 했어요").get("source"));
        g.server().verify();
    }

    @Test
    void geminiTextSkipsThoughtParts() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = om.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"생각\",\"thought\":true},{\"text\":\"{\\\"a\\\":\"},{\"text\":\"1}\"}]}}]}");
        assertEquals("{\"a\":1}", GeminiProvider.text(root));
        assertEquals("", GeminiProvider.text(om.readTree("{}")));
    }

    @Test
    void providerSelection() {
        AnthropicProvider an = anthropic("k2");
        assertEquals("gemini", new AiClient(null, gemini("k1").p(), an, true, "").providerName());
        assertEquals("anthropic", new AiClient(null, gemini(null).p(), an, true, "").providerName());
        assertEquals("anthropic", new AiClient(null, gemini("k1").p(), an, true, "anthropic").providerName());
        assertEquals("rule", new AiClient(null, gemini("k1").p(), an, true, "rule").providerName());
        assertEquals("rule", new AiClient(null, gemini("k1").p(), an, false, "").providerName());
        assertEquals("rule", new AiClient(null, gemini(null).p(), anthropic(null), true, "").providerName());
        assertEquals("rule", new AiClient(null, gemini(null).p(), an, true, "gemini").providerName()); // 고른 공급자에 키가 없으면 규칙
        assertTrue(new AiClient(null, gemini("k1").p(), an, true, "anthropic").sttAvailable());
        assertFalse(new AiClient(null, gemini("k1").p(), an, true, "rule").sttAvailable());
        assertFalse(new AiClient(null, gemini(null).p(), an, true, "").sttAvailable());
    }

    @Test
    void anthropicRequestShape() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        AnthropicProvider an = new AnthropicProvider(b, FAKE_KEY, "claude-haiku-5-5", "https://anthropic.test/v1/messages");
        s.expect(requestTo("https://anthropic.test/v1/messages"))
                .andExpect(header("x-api-key", FAKE_KEY))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.messages[0].content").value("기록: 산책"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"text\\\":\\\"산책했어요\\\"}\"}]}", MediaType.APPLICATION_JSON));
        Map<String, Object> r = new AiClient(null, gemini(null).p(), an, true, "").logTidy("산책");
        assertEquals("ai", r.get("source"));
        assertEquals("anthropic", r.get("provider"));
        assertEquals("산책했어요", r.get("text"));
        s.verify();
    }

    @Test
    void sttSendsInlineAudioAndReturnsText() {
        G g = gemini(FAKE_KEY);
        byte[] audio = "fake-opus-bytes".getBytes(StandardCharsets.UTF_8);
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent"))
                .andExpect(header("x-goog-api-key", FAKE_KEY))
                .andExpect(jsonPath("$.contents[0].parts[0].inline_data.mime_type").value("audio/webm"))
                .andExpect(jsonPath("$.contents[0].parts[0].inline_data.data").value(Base64.getEncoder().encodeToString(audio)))
                .andExpect(jsonPath("$.contents[0].parts[1].text").value(org.hamcrest.Matchers.containsString("받아쓰기")))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
                .andRespond(withSuccess(candidates("“다음 주 화요일에 병원 같이 가 주세요”\n"), MediaType.APPLICATION_JSON));
        SttClient stt = new SttClient(new AiClient(null, g.p(), anthropic(null), true, ""), g.p());
        Map<String, Object> r = stt.transcribe(audio, "audio/webm;codecs=opus", "ko");
        assertEquals("다음 주 화요일에 병원 같이 가 주세요", r.get("text"));
        assertEquals("gemini", r.get("source"));
        g.server().verify();
    }

    @Test
    void sttErrors() {
        SttClient off = new SttClient(new AiClient(null, gemini(null).p(), anthropic("k"), true, ""), gemini(null).p());
        ApiException e = assertThrows(ApiException.class, () -> off.transcribe(new byte[]{1}, "audio/webm", "ko"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status);
        assertEquals("STT_UNAVAILABLE", e.code);

        G g = gemini(FAKE_KEY);
        SttClient on = new SttClient(new AiClient(null, g.p(), anthropic(null), true, ""), g.p());
        assertEquals("AUDIO_TYPE", assertThrows(ApiException.class, () -> on.transcribe(new byte[]{1}, "text/plain", "ko")).code);
        assertEquals("AUDIO_TOO_LARGE", assertThrows(ApiException.class, () -> on.transcribe(new byte[(int) SttClient.MAX_BYTES + 1], "audio/webm", "ko")).code);
        g.server().expect(requestTo(BASE + "/gemini-flash-latest:generateContent")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals("STT_BUSY", assertThrows(ApiException.class, () -> on.transcribe(new byte[]{1, 2}, "audio/ogg", "ko")).code);
        assertEquals("audio/mp4", SttClient.normType("audio/mp4"));
        assertEquals("audio/webm", SttClient.normType("video/webm;codecs=opus"));
        assertNull(SttClient.normType(null));
    }
}
