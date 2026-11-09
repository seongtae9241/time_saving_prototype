package com.timesavings.web;

import com.timesavings.ai.SttClient;
import com.timesavings.config.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** 서버 음성 인식(계약서 7.9). 녹음은 저장하지 않고 메모리에서만 처리한다. 로그인 필요. */
@RestController
@RequestMapping("/api")
public class SttController {
    private final SttClient stt;

    public SttController(SttClient stt) { this.stt = stt; }

    @PostMapping("/stt")
    public Map<String, Object> stt(@RequestParam(value = "audio", required = false) MultipartFile audio,
                                   @RequestParam(value = "lang", defaultValue = "ko") String lang) throws IOException {
        if (!stt.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STT_UNAVAILABLE", "서버 음성 인식이 꺼져 있어요. 직접 적어 주세요");
        if (audio == null || audio.isEmpty()) throw ApiException.bad("AUDIO_REQUIRED", "녹음된 소리가 없어요. 다시 눌러 말씀해 주세요");
        if (audio.getSize() > SttClient.MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LARGE", "1분 안으로 짧게 말씀해 주세요");
        return stt.transcribe(audio.getBytes(), audio.getContentType(), lang);
    }
}
