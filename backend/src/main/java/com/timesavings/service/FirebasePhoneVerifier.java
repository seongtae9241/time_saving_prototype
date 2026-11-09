package com.timesavings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Firebase Phone Auth 로 받은 ID 토큰을 Identity Toolkit accounts:lookup 으로 확인해 인증된 전화번호(+82…)를 돌려준다.
 * 웹 API 키는 환경변수 FIREBASE_WEB_API_KEY 에서만 읽고 로그에 남기지 않는다.
 */
@Component
public class FirebasePhoneVerifier {
    private static final Logger log = LoggerFactory.getLogger(FirebasePhoneVerifier.class);
    private final ObjectMapper om = new ObjectMapper();
    private final RestClient http;
    private final String key;
    private final String lookupUrl;

    @Autowired
    public FirebasePhoneVerifier(@Value("${FIREBASE_WEB_API_KEY:}") String key,
                                 @Value("${app.firebase.lookup-url:https://identitytoolkit.googleapis.com/v1/accounts:lookup}") String lookupUrl) {
        this(RestClient.builder().requestFactory(factory()), key, lookupUrl);
    }

    FirebasePhoneVerifier(RestClient.Builder builder, String key, String lookupUrl) {
        this.http = builder.build();
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.lookupUrl = lookupUrl;
    }

    private static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(10_000);
        return f;
    }

    public boolean enabled() { return key != null; }

    /** 토큰이 유효하면 Firebase 가 확인한 전화번호(E.164, 예 +821012345678). */
    public String verifiedPhone(String idToken) {
        if (!enabled()) throw ApiException.bad("FIREBASE_DISABLED", "문자 인증이 아직 설정되지 않았어요. 테스트 인증번호로 진행해 주세요");
        if (idToken == null || idToken.isBlank()) throw ApiException.bad("FIREBASE_TOKEN_REQUIRED", "문자 인증을 먼저 해 주세요");
        try {
            String res = http.post()
                    .uri(lookupUrl + "?key={key}", Map.of("key", key))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(om.writeValueAsString(Map.of("idToken", idToken)))
                    .retrieve().body(String.class);
            JsonNode root = om.readTree(res);
            String phone = root.path("users").path(0).path("phoneNumber").asText("");
            if (phone.isBlank()) throw ApiException.bad("FIREBASE_INVALID", "문자 인증 정보를 확인하지 못했어요. 다시 시도해 주세요");
            return phone;
        } catch (ApiException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            log.warn("Firebase 토큰 확인 실패(HTTP {})", e.getStatusCode().value());
            throw ApiException.bad("FIREBASE_INVALID", "문자 인증이 만료됐거나 맞지 않아요. 인증번호를 다시 받아 주세요");
        } catch (Exception e) {
            log.warn("Firebase 토큰 확인 실패({})", e.getClass().getSimpleName());
            throw ApiException.bad("FIREBASE_INVALID", "문자 인증을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요");
        }
    }

    /** 010-1234-5678 → +821012345678 (한국 휴대폰만). */
    public static String toE164(String phone) {
        String d = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (d.startsWith("82")) d = "0" + d.substring(2);
        if (!d.startsWith("0")) return "+" + d;
        return "+82" + d.substring(1);
    }
}
