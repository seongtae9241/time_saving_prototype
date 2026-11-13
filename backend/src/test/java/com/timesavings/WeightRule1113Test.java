package com.timesavings;

import com.timesavings.ai.RuleEngine;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 계약서 7.21 (2026-11-13 대표 결정): 책임 등급(×1.5)을 없애고 기본 1.0·수고 1.2만 쓴다.
 * 동행은 기본, 책임 등급으로 들어온 값은 수고로 받고, 끝나지 않은 것은 새 규칙으로 옮기고, 예전 기록은 그대로 둔다. AI 키 없이 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeightRule1113Test {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired PostRepo posts;
    @Autowired RequestRepo requests;
    @Autowired LedgerRepo ledger;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired RequestService requestService;
    @Autowired WeightRuleMigration migration;

    User vol, rec;

    User user(String id) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = Role.USER; u.area = "행복동"; u.ageGroup = "60대";
        u.phone = "010-0000-0000"; u.idStatus = VerifyStatus.APPROVED;
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Request req(String cat, double hours, Level lv, ReqStatus st) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = rec.id; r.volunteerId = vol.id; r.recipientId = rec.id; r.date = LocalDate.now().plusDays(2);
        r.startHour = 10; r.hours = hours; r.category = cat; r.content = "병원 진료 동행"; r.difficulty = lv; r.weight = Rules.weight(lv);
        r.estEarn = Rules.earn(hours, lv); r.status = st;
        if (st == ReqStatus.DONE) r.earned = r.estEarn;
        return requests.save(r);
    }

    Post openPost(Level lv, PostStatus st) {
        Post p = new Post();
        p.type = PostType.NEED; p.ownerId = rec.id; p.date = LocalDate.now().plusDays(3); p.startHour = 9.0; p.endHour = 12.0; p.estHours = 2.0;
        p.area = "행복동"; p.categories = List.of("동행"); p.title = "정형외과 재진 동행"; p.detail = "진료 후 약국 들르기"; p.status = st;
        p.difficulty = lv; p.weight = Rules.weight(lv); p.difficultyReason = "동행은 기본 책임 등급 → 책임 등급(×1.5)을 제안해요.";
        return posts.save(p);
    }

    @BeforeEach
    void setUp() { vol = user("w13v"); rec = user("w13r"); }

    @Test
    void 동행은_기본_등급이고_수고는_그대로다() {
        assertEquals(Level.BASIC, Rules.categoryLevel("동행"));
        assertEquals(2.0, Rules.earn(2, Rules.categoryLevel("동행")));           // 동행 2시간 → 2.0
        assertEquals(1.2, Rules.weight(Level.EFFORT));
        assertEquals(2.0, Rules.earn(1.5, Rules.categoryLevel("장보기·심부름"))); // 1.8 → 2.0
        assertEquals(List.of(Level.BASIC, Level.EFFORT), Rules.SELECTABLE);
        assertEquals(Level.EFFORT, Rules.current(Level.RESPONSIBLE));
        assertEquals(Level.BASIC, Rules.current(Level.BASIC));
        assertNull(Rules.current(null));
        // 예전 기록을 보여 줄 때 쓰는 값은 남아 있다
        assertEquals(1.5, Rules.weight(Level.RESPONSIBLE));
        assertEquals("책임", Rules.levelKo(Level.RESPONSIBLE));
    }

    @Test
    void 난이도_제안은_책임_등급을_내지_않는다() {
        RuleEngine.DifficultyResult d = RuleEngine.difficulty("정형외과 재진 동행, 진료 후 약국 들르기", List.of("동행"));
        assertEquals(Level.BASIC, d.level());
        assertFalse(d.reason().contains("책임 등급"));
        assertFalse(d.reason().contains("1.5"));
        // 책임 요소에 다른 부담이 겹쳐도 수고까지만
        RuleEngine.DifficultyResult heavy = RuleEngine.difficulty("병원 검사 동행, 쌀 20kg 묶음을 4층 계단으로 나르고 버스로 왕복", List.of("동행"));
        assertEquals(Level.EFFORT, heavy.level());
        assertTrue(heavy.reason().contains("×1.2"));
    }

    @Test
    void 이의_신청에_책임_등급이_들어오면_수고로_받고_더_올릴_수_없으면_막는다() throws Exception {
        Request basic = req("말동무·정서", 2, Level.BASIC, ReqStatus.DONE);
        mvc.perform(post("/api/requests/" + basic.id + "/appeal").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"무거운 짐을 날랐어요\",\"requestedLevel\":\"RESPONSIBLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedLevel").value("EFFORT"));
        Request effort = req("장보기·심부름", 2, Level.EFFORT, ReqStatus.DONE);
        mvc.perform(post("/api/requests/" + effort.id + "/appeal").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"무거운 짐을 날랐어요\",\"requestedLevel\":\"RESPONSIBLE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LEVEL"));
    }

    @Test
    void 열린_공고와_끝나지_않은_요청은_새_규칙으로_옮기고_끝난_기록은_그대로_둔다() {
        bank.add(rec.id, LedgerType.EARN, 20, 20.0, 1.0, null, "바탕 적립");
        Post open = openPost(Level.RESPONSIBLE, PostStatus.OPEN), closed = openPost(Level.RESPONSIBLE, PostStatus.CLOSED);
        Request going = req("동행", 2, Level.RESPONSIBLE, ReqStatus.CONFIRMED);
        bank.add(rec.id, LedgerType.HOLD, going.estEarn, 2.0, 1.5, going.id, "동행 예약 보류");
        Request old = req("동행", 2, Level.RESPONSIBLE, ReqStatus.DONE);
        Ledger oldEarn = bank.add(vol.id, LedgerType.EARN, 3.0, 2.0, 1.5, old.id, "동행 · 예전 기록");
        assertEquals(3.0, bank.summary(rec.id).hold());

        int[] n = migration.apply();
        assertEquals(1, n[0]);
        assertEquals(1, n[1]);
        // 열린 공고: 기본 1.0, 닫힌 공고: 그대로
        assertEquals(Level.BASIC, open.difficulty);
        assertEquals(1.0, open.weight);
        assertFalse(open.difficultyReason.contains("책임 등급"));
        assertEquals(Level.RESPONSIBLE, closed.difficulty);
        assertEquals(1.5, closed.weight);
        // 진행 중 요청: 기본 1.0, 예상 2.0, 보류도 2.0 으로
        assertEquals(Level.BASIC, going.difficulty);
        assertEquals(1.0, going.weight);
        assertEquals(2.0, going.estEarn);
        assertEquals(2.0, bank.summary(rec.id).hold());
        // 끝난 요청과 원장: 1.5 그대로
        assertEquals(Level.RESPONSIBLE, old.difficulty);
        assertEquals(1.5, old.weight);
        assertEquals(3.0, old.earned);
        Ledger kept = ledger.findById(oldEarn.id).orElseThrow();
        assertEquals(1.5, kept.weight);
        assertEquals(3.0, kept.amount);
        // 다시 돌려도 바뀌는 것이 없다
        assertArrayEquals(new int[]{0, 0}, migration.apply());

        // 정산은 새 규칙으로: 봉사자 +2.0, 받는 사람 보류 0 · −2.0
        going.status = ReqStatus.LOG_SUBMITTED;
        requestService.settle(going, false);
        assertEquals(2.0, going.earned);
        assertEquals(0.0, bank.summary(rec.id).hold());
        assertEquals(18.0, bank.summary(rec.id).balance());
        assertEquals(3.0 + 2.0, bank.summary(vol.id).balance());
    }
}
