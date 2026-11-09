package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.AuthToken;
import com.timesavings.domain.Enums.UserStatus;
import com.timesavings.domain.User;
import com.timesavings.repo.AuthTokenRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 로그인·토큰·모의 휴대폰 인증. */
@Service
public class AuthService {
    public static final String DEMO_CODE = "123456";
    private final UserRepo users;
    private final AuthTokenRepo tokens;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    private final SecureRandom rnd = new SecureRandom();
    /** verifyToken → phone (모의 본인인증, 메모리 보관 10분) */
    private final Map<String, Object[]> verified = new ConcurrentHashMap<>();

    public AuthService(UserRepo users, AuthTokenRepo tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public String hash(String raw) { return enc.encode(raw); }
    public boolean matches(String raw, String hash) { return raw != null && hash != null && enc.matches(raw, hash); }

    public String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public String sixDigits() { return String.format("%06d", rnd.nextInt(1_000_000)); }

    @Transactional
    public String issueToken(User u, boolean keep) {
        AuthToken t = new AuthToken();
        t.token = randomHex(24);
        t.userId = u.id;
        t.expiresAt = LocalDateTime.now().plusDays(keep ? 30 : 1);
        tokens.save(t);
        return t.token;
    }

    @Transactional(readOnly = true)
    public User userByToken(String token) {
        return tokens.findById(token)
                .filter(t -> t.expiresAt.isAfter(LocalDateTime.now()))
                .flatMap(t -> users.findById(t.userId))
                .filter(u -> u.status == UserStatus.ACTIVE)
                .orElse(null);
    }

    @Transactional
    public void logout(String token) { tokens.deleteById(token); }

    @Transactional
    public void revokeAll(Long userId) { tokens.deleteByUserId(userId); }

    @Transactional(readOnly = true)
    public User login(String loginId, String password) {
        User u = users.findByLoginId(loginId == null ? "" : loginId.trim()).orElse(null);
        if (u == null || !matches(password, u.passwordHash)) throw ApiException.bad("LOGIN_FAILED", "아이디 또는 비밀번호가 맞지 않아요");
        if (u.status == UserStatus.SUSPENDED) throw ApiException.forbidden("이용이 정지된 계정이에요. 고객센터에 문의해 주세요");
        if (u.status == UserStatus.WITHDRAWN) throw ApiException.bad("WITHDRAWN", "탈퇴한 계정이에요");
        return u;
    }

    // ---- 모의 휴대폰 본인인증 ----
    public String sendCode(String phone) {
        if (phone == null || !phone.replaceAll("[^0-9]", "").matches("01\\d{8,9}"))
            throw ApiException.bad("INVALID_PHONE", "휴대폰 번호를 확인해 주세요");
        return DEMO_CODE;
    }

    public String verify(String phone, String code) {
        if (!DEMO_CODE.equals(code)) throw ApiException.bad("INVALID_CODE", "인증번호가 맞지 않아요");
        String vt = randomHex(16);
        verified.put(vt, new Object[]{normPhone(phone), LocalDateTime.now().plusMinutes(10)});
        return vt;
    }

    public void requireVerified(String verifyToken, String phone) {
        Object[] v = verifyToken == null ? null : verified.get(verifyToken);
        if (v == null || ((LocalDateTime) v[1]).isBefore(LocalDateTime.now()) || !v[0].equals(normPhone(phone)))
            throw ApiException.bad("PHONE_NOT_VERIFIED", "휴대폰 본인인증을 먼저 해 주세요");
    }

    public static String normPhone(String p) {
        String d = p == null ? "" : p.replaceAll("[^0-9]", "");
        if (d.length() == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
        if (d.length() == 10) return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6);
        return d;
    }
}
