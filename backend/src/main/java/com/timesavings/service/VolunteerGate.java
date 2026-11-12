package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.Enums.UserStatus;
import com.timesavings.domain.Enums.VerifyStatus;
import com.timesavings.domain.User;
import com.timesavings.repo.AppSettingRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 봉사 자격 검사 한 곳(기능 U-34 봉사 인증): 신분증 확인 + 첫 활동 교육(안전 교육) 이수.
 * 봉사 제안·요청 수락(RequestService)과 봉사 가능 공고 등록(PostService)이 모두 여기를 부른다.
 * 교육이 필수인지는 운영자의 "봉사 인증 항목" 설정(app_settings 의 verify.items, GET /api/verify-items 의 edu 항목)으로 정한다.
 * 설정을 한 번도 바꾸지 않았으면 app.verify.edu-required-default(기본 true = 필수)를 따른다.
 */
@Service
public class VolunteerGate {
    public static final String SETTING_KEY = "verify.items";
    private static final ObjectMapper OM = new ObjectMapper();
    /** key, 이름, 설명, 기본 켜짐, 기본 필수, 잠금(끄거나 선택으로 바꿀 수 없음) */
    public static final Object[][] VERIFY_DEFAULT = {
            {"phone", "휴대폰 본인인증", "가입할 때 · 필수", true, true, true},
            {"id", "신분증 인증", "운영자가 확인하고 승인 · 필수", true, true, false},
            {"edu", "첫 활동 교육(안전 교육)", "첫 활동 연습 상황을 모두 마치면 이수", true, true, false},
            {"crime", "범죄경력 회보서", "내면 신뢰 배지 · 선택", true, false, false},
    };

    private final AppSettingRepo settings;
    private final boolean eduRequiredDefault;

    public VolunteerGate(AppSettingRepo settings, @Value("${app.verify.edu-required-default:true}") boolean eduRequiredDefault) {
        this.settings = settings;
        this.eduRequiredDefault = eduRequiredDefault;
    }

    public static boolean knownKey(String key) { return key != null && Arrays.stream(VERIFY_DEFAULT).anyMatch(d -> d[0].equals(key)); }

    /** GET /api/verify-items 의 items: [{key,title,desc,on,required,locked}] */
    public List<Map<String, Object>> items() {
        Map<String, Map<String, Boolean>> saved = new HashMap<>();
        settings.findById(SETTING_KEY).ifPresent(s -> {
            try { saved.putAll(OM.readValue(s.value, new TypeReference<Map<String, Map<String, Boolean>>>() {})); } catch (Exception ignored) { }
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] d : VERIFY_DEFAULT) {
            Map<String, Boolean> v = saved.get(d[0]);
            boolean locked = (boolean) d[5];
            boolean defReq = "edu".equals(d[0]) ? eduRequiredDefault : (boolean) d[4];
            boolean on = locked || (v != null && v.get("on") != null ? v.get("on") : (boolean) d[3]);
            boolean req = locked || (on && (v != null && v.get("required") != null ? v.get("required") : defReq));
            out.add(Views.map("key", d[0], "title", d[1], "desc", d[2], "on", on, "required", req, "locked", locked));
        }
        return out;
    }

    private Map<String, Object> item(String key) {
        return items().stream().filter(i -> key.equals(i.get("key"))).findFirst().orElse(Map.of());
    }

    /** 첫 활동 교육 항목이 켜져 있는가(꺼져 있으면 인증 화면에 보이지 않는다). */
    public boolean eduOn() { return Boolean.TRUE.equals(item("edu").get("on")); }

    /** 첫 활동 교육 이수가 봉사의 필수 조건인가. */
    public boolean eduRequired() { return Boolean.TRUE.equals(item("edu").get("required")); }

    /** 봉사할 수 없는 까닭(코드, 문구). 할 수 있으면 null. self = 본인이 하려는 일인지(문구가 달라진다). */
    public String[] block(User v, boolean self) {
        if (v == null || v.status != UserStatus.ACTIVE || v.role != Role.USER) return new String[]{"VOLUNTEER_UNAVAILABLE", "지금 활동할 수 없는 봉사자예요"};
        if (v.idStatus != VerifyStatus.APPROVED) return new String[]{"ID_NOT_VERIFIED", "신분증 인증이 끝난 사용자만 봉사할 수 있어요 (B-01)"};
        if (!v.eduDone && eduRequired())
            return new String[]{"EDU_REQUIRED", self
                    ? "첫 활동 교육(안전 교육)을 마쳐야 봉사할 수 있어요. 마이 › 봉사 인증에서 첫 활동 연습을 끝까지 해 주세요"
                    : "이 봉사자는 아직 첫 활동 교육(안전 교육)을 마치지 않았어요. 다른 봉사자에게 요청해 주세요"};
        return null;
    }

    /** 봉사 자격이 없으면 400 으로 막는다. */
    public void require(User v, boolean self) {
        String[] b = block(v, self);
        if (b != null) throw ApiException.bad(b[0], b[1]);
    }
}
