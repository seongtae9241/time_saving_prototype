package com.timesavings.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timesavings.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 서버 음성 인식이 지나가는 유일한 클래스. Gemini generateContent 에 오디오를 inline_data 로 보내 받아쓰기만 시킨다.
 * 녹음은 메모리에서만 다루고 파일·DB·로그 어디에도 남기지 않는다.
 */
@Component
public class SttClient {
    private static final Logger log = LoggerFactory.getLogger(SttClient.class);
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Set<String> TYPES = Set.of("audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
            "audio/aac", "audio/flac", "audio/x-m4a", "audio/m4a");
    static final String INSTRUCTION = "다음 한국어 음성을 들리는 그대로 받아쓰기만 한다. 설명·요약·따옴표·말한 사람 표시 없이 받아쓴 글자만 출력한다. "
            + "말소리가 없으면 아무것도 출력하지 않는다.";

    private final AiClient ai;
    private final GeminiProvider gemini;

    public SttClient(AiClient ai, GeminiProvider gemini) {
        this.ai = ai;
        this.gemini = gemini;
    }

    public boolean available() { return ai.sttAvailable(); }

    /** 'audio/webm;codecs=opus' → 'audio/webm'. 허용 목록 밖이면 null. */
    static String normType(String contentType) {
        if (contentType == null) return null;
        String t = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (t.equals("video/webm")) t = "audio/webm"; // 일부 브라우저가 video/webm 으로 붙인다
        if (t.equals("audio/x-m4a") || t.equals("audio/m4a")) t = "audio/mp4";
        return TYPES.contains(t) ? t : null;
    }

    public Map<String, Object> transcribe(byte[] audio, String contentType, String lang) {
        if (!available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STT_UNAVAILABLE", "서버 음성 인식이 꺼져 있어요. 직접 적어 주세요");
        if (audio == null || audio.length == 0) throw ApiException.bad("AUDIO_REQUIRED", "녹음된 소리가 없어요. 다시 눌러 말씀해 주세요");
        if (audio.length > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LARGE", "1분 안으로 짧게 말씀해 주세요");
        String mime = normType(contentType);
        if (mime == null) throw ApiException.bad("AUDIO_TYPE", "이 녹음 형식은 받을 수 없어요");
        String instr = INSTRUCTION + ("ko".equalsIgnoreCase(lang == null ? "ko" : lang) ? "" : " (언어: " + lang + ")");
        try {
            ArrayNode parts = gemini.mapper().createArrayNode();
            ObjectNode inline = parts.addObject().putObject("inline_data");
            inline.put("mime_type", mime);
            inline.put("data", Base64.getEncoder().encodeToString(audio));
            parts.addObject().put("text", instr);
            String text = GeminiProvider.text(gemini.generate(null, parts, false, 600)).trim();
            text = text.replaceAll("^[\"“”']+|[\"“”']+$", "").trim();
            return Map.of("text", text, "source", "gemini");
        } catch (HttpStatusCodeException e) {
            log.warn("음성 인식 실패(gemini, HTTP {})", e.getStatusCode().value());
            if (e.getStatusCode().value() == 429)
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STT_BUSY", "음성 인식 사용량이 잠깐 넘쳤어요. 잠시 뒤 다시 하거나 직접 적어 주세요");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STT_FAILED", "음성을 글자로 바꾸지 못했어요. 다시 하거나 직접 적어 주세요");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("음성 인식 실패(gemini, {})", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STT_FAILED", "음성을 글자로 바꾸지 못했어요. 다시 하거나 직접 적어 주세요");
        }
    }
}
