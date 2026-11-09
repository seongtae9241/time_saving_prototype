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
        BankService.Summary s = bank.summary(users.findByLoginId("yujin").orElseThrow().id);
        assertTrue(s.canUseSelf());
        assertEquals(23.5, s.balance());
        for (String id : new String[]{"bok", "mom", "sky", "mikyung", "doyun"})
            assertTrue(bank.summary(users.findByLoginId(id).orElseThrow().id).balance() >= 0, id);
    }
}
