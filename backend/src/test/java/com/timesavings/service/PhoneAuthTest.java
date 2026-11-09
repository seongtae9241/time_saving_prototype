package com.timesavings.service;

import com.timesavings.config.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Firebase ID 토큰 확인(accounts:lookup)과 데모 코드. 실제 Firebase 는 부르지 않는다. */
class PhoneAuthTest {
    static final String URL = "https://idtk.test/v1/accounts:lookup";

    @Test
    void lookupRequestAndPhoneMatch() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        FirebasePhoneVerifier v = new FirebasePhoneVerifier(b, "test-web-key", URL);
        s.expect(requestTo(URL + "?key=test-web-key"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idToken").value("id-token-1"))
                .andRespond(withSuccess("{\"users\":[{\"localId\":\"u1\",\"phoneNumber\":\"+821012345678\"}]}", MediaType.APPLICATION_JSON));
        assertTrue(v.enabled());
        String fb = v.verifiedPhone("id-token-1");
        assertEquals("+821012345678", fb);
        AuthService auth = new AuthService(null, null, true);
        assertNotNull(auth.verifyFirebase("010-1234-5678", fb));
        assertEquals("PHONE_MISMATCH", assertThrows(ApiException.class, () -> auth.verifyFirebase("010-9999-0000", fb)).code);
        s.verify();
    }

    @Test
    void lookupErrors() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        FirebasePhoneVerifier v = new FirebasePhoneVerifier(b, "test-web-key", URL);
        s.expect(requestTo(URL + "?key=test-web-key")).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"message\":\"INVALID_ID_TOKEN\"}}"));
        s.expect(requestTo(URL + "?key=test-web-key")).andRespond(withSuccess("{\"users\":[{\"localId\":\"u1\"}]}", MediaType.APPLICATION_JSON));
        assertEquals("FIREBASE_INVALID", assertThrows(ApiException.class, () -> v.verifiedPhone("bad")).code);
        assertEquals("FIREBASE_INVALID", assertThrows(ApiException.class, () -> v.verifiedPhone("no-phone")).code);
        FirebasePhoneVerifier off = new FirebasePhoneVerifier(RestClient.builder(), "", URL);
        assertFalse(off.enabled());
        assertEquals("FIREBASE_DISABLED", assertThrows(ApiException.class, () -> off.verifiedPhone("x")).code);
    }

    @Test
    void e164AndDemoCode() {
        assertEquals("+821012345678", FirebasePhoneVerifier.toE164("010-1234-5678"));
        assertEquals("+82111234567", FirebasePhoneVerifier.toE164("011 123 4567"));
        AuthService on = new AuthService(null, null, true);
        assertNotNull(on.verify("010-1234-5678", "123456"));
        assertEquals("INVALID_CODE", assertThrows(ApiException.class, () -> on.verify("010-1234-5678", "000000")).code);
        AuthService off = new AuthService(null, null, false);
        assertEquals("DEMO_DISABLED", assertThrows(ApiException.class, () -> off.verify("010-1234-5678", "123456")).code);
        assertEquals("DEMO_DISABLED", assertThrows(ApiException.class, () -> off.sendCode("010-1234-5678")).code);
    }
}
