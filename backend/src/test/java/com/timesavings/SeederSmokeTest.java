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
    @Autowired NeighborClassRepo classes;
    @Autowired RoleplayPersonaRepo personas;
    @Autowired BankService bank;
    @Autowired OrgEventRepo orgEvents;

    @Test
    void 데모_계정과_데이터() {
        assertTrue(users.findByLoginId("yujin").isPresent());
        assertTrue(users.findByLoginId("org1").isPresent());
        assertTrue(users.findByLoginId("admin").isPresent());
        assertTrue(services.count() >= 10);
        assertTrue(classes.count() >= 6);
        assertEquals(4, classes.findAll().stream().filter(c -> c.one()).count()); // 1:1 클래스 o1~o4
        assertTrue(classes.findAll().stream().filter(c -> !c.one() && !"DONE".equals(c.status.name())).allMatch(c -> c.capacity <= 4)); // 같이 해 보기 4명까지
        assertEquals(3, personas.count());
        // 열린 클래스는 말벗·이야기 / 전자기기 사용법 두 가지만
        assertTrue(classes.findAll().stream().filter(c -> "OPEN".equals(c.status.name()) || "SPACE_PENDING".equals(c.status.name()))
                .allMatch(c -> com.timesavings.service.Rules.CLASS_KINDS.contains(c.kind)));
        assertTrue(orgEvents.count() >= 3); // 동네 소식(기관 행사)
        BankService.Summary s = bank.summary(users.findByLoginId("yujin").orElseThrow().id);
        assertTrue(s.canUseSelf());
        assertEquals(23.0, s.balance()); // 지난 클래스 1.5시간 수강 = 1.5시간(평등 정산, 이전 ×0.5)
        for (String id : new String[]{"bok", "mom", "sky", "mikyung", "doyun"})
            assertTrue(bank.summary(users.findByLoginId(id).orElseThrow().id).balance() >= 0, id);
    }
}
