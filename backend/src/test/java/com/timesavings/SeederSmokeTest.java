package com.timesavings;

import com.timesavings.repo.*;
import com.timesavings.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** 데모 시드가 오류 없이 들어가는지(규칙 모드). */
@SpringBootTest(properties = {"app.seed.enabled=true", "spring.datasource.url=jdbc:h2:mem:seedtest;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class SeederSmokeTest {
    @Autowired UserRepo users;
    @Autowired PublicServiceRepo services;
    @Autowired RoleplayPersonaRepo personas;
    @Autowired BankService bank;
    @Autowired OrgEventRepo orgEvents;

    @Test
    void 데모_계정과_데이터() {
        assertTrue(users.findByLoginId("yujin").isPresent());
        assertTrue(users.findByLoginId("org1").isPresent());
        assertTrue(users.findByLoginId("admin").isPresent());
        assertTrue(services.count() >= 10);
        assertEquals(3, personas.count());
        assertTrue(orgEvents.count() >= 3); // 동네 소식(기관 행사)
        BankService.Summary s = bank.summary(users.findByLoginId("yujin").orElseThrow().id);
        assertTrue(s.canUseSelf());
        assertTrue(users.findByLoginId("youngho").isEmpty()); // 1:1 클래스 선생님 데모 계정 삭제(2026-11-10)
        assertEquals(24.5, s.balance()); // 동네 클래스 수강 기록(−1.5) 시드 삭제 후(2026-11-10). (프로토타입 표시값은 23.5)
        for (String id : new String[]{"bok", "mom", "sky", "mikyung", "doyun"})
            assertTrue(bank.summary(users.findByLoginId(id).orElseThrow().id).balance() >= 0, id);
    }
}
