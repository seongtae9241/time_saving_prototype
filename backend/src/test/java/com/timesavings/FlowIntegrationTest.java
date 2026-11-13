package com.timesavings;

import com.timesavings.config.ApiException;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 상태 흐름·통장 규칙 통합 테스트(AI 없이 규칙 모드, 메모리 H2). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlowIntegrationTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired PostRepo posts;
    @Autowired RequestRepo requests;
    @Autowired LedgerRepo ledger;
    @Autowired FamilyLinkRepo family;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;

    User vol, rec, other, mgr;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (1000 + id.length());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    @BeforeEach
    void setUp() {
        vol = user("vol", Role.USER, 0);
        rec = user("rec", Role.USER, 20);
        other = user("other", Role.USER, 20);
        mgr = user("mgr", Role.ORG, 0);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행");
        elders.save(elder);
    }

    Post offer(User owner) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동",
                List.of("동행", "장보기·심부름"), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
    }

    Request request(User sender, Post p, double start, double hours, String cat, Long elderId) {
        return reqs.create(sender, new RequestService.CreateCmd(Kind.REQUEST, p.id, null, elderId, day, start, hours, cat, "병원 진료 동행", null));
    }

    double sum(User u, LedgerType t) {
        return ledger.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(l -> l.type == t).mapToDouble(l -> l.amount).sum();
    }

    @Test
    void 요청부터_적립까지_전체_흐름과_나머지_자동_거절() {
        Post p = offer(vol);
        Request r = request(rec, p, 14, 2, "동행", null);
        Request r2 = request(other, p, 15, 1, "장보기·심부름", null);
        assertEquals(ReqStatus.SENT, r.status);
        assertEquals(2.0, r.estEarn); // 동행 2시간 × 1.0 (7.21)
        assertNotNull(r.deadlineAt);

        reqs.accept(vol, r.id);                       // 봉사자 수락
        assertEquals(ReqStatus.VOL_ACCEPTED, r.status);
        assertEquals(PostStatus.MATCHED, posts.findById(p.id).orElseThrow().status);
        assertEquals(ReqStatus.REJECTED, r2.status);  // 같은 공고 나머지 자동 거절
        assertEquals("다른 요청이 수락됨", r2.rejectReason);

        reqs.accept(rec, r.id);                       // 받는 사람 최종 수락
        assertEquals(ReqStatus.CONFIRMED, r.status);
        assertEquals(2.0, bank.summary(rec.id).hold()); // 보류
        assertEquals(6, r.checkCode.length());

        assertThrows(ApiException.class, () -> reqs.checkIn(vol, r.id, "000000x", null));
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        assertEquals(ReqStatus.IN_PROGRESS, r.status);
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "qr");
        assertEquals(ReqStatus.CHECKED_OUT, r.status);
        assertThrows(ApiException.class, () -> reqs.confirm(rec, r.id)); // 일지 없이는 확인 불가
        reqs.submitLog(vol, r.id, "어 정형외과 접수하고 약국 들름", null, null, false);
        assertEquals(ReqStatus.LOG_SUBMITTED, r.status);
        assertNotNull(r.logText);

        reqs.confirm(rec, r.id);
        assertEquals(ReqStatus.DONE, r.status);
        assertEquals(2.0, r.earned);
        assertEquals(2.0, sum(vol, LedgerType.EARN));
        assertEquals(-2.0, sum(rec, LedgerType.USE));
        BankService.Summary s = bank.summary(rec.id);
        assertEquals(0.0, s.hold());
        assertEquals(18.0, s.balance());

        reqs.rate(rec, r.id, 5, List.of("친절해요", "용돈 감사"), null);
        assertThrows(ApiException.class, () -> reqs.rate(rec, r.id, 4, List.of(), null)); // 한 번만
    }

    @Test
    void 응답_기한_24시간이_지나면_만료되고_공고는_다시_열리지_않는다() {
        Post p = offer(vol);
        Request r = request(rec, p, 14, 1, "동행", null);
        assertEquals(0, reqs.expireDue(LocalDateTime.now().plusHours(23)));
        reqs.accept(vol, r.id);
        assertEquals(1, reqs.expireDue(LocalDateTime.now().plusHours(25)));
        assertEquals(ReqStatus.EXPIRED, r.status);
        assertEquals(PostStatus.CLOSED, posts.findById(p.id).orElseThrow().status);
        assertThrows(ApiException.class, () -> request(other, p, 14, 1, "동행", null));
    }

    @Test
    void 독거노인_건은_받는_쪽_차감_없이_봉사자만_적립() {
        Post p = offer(vol);
        Request r = request(mgr, p, 14, 2, "동행", elder.id);
        assertNull(r.recipientId);
        reqs.accept(vol, r.id);
        reqs.accept(mgr, r.id);                       // 담당자 최종 수락 대리 입력
        reqs.checkIn(vol, r.id, reqs.codeFor(r), null);
        reqs.checkOut(vol, r.id, reqs.codeFor(r), null);
        reqs.submitLog(vol, r.id, "진료 동행", null, null, false);
        reqs.confirm(mgr, r.id);                      // 담당자 대신 확인
        assertEquals(2.0, sum(vol, LedgerType.EARN));
        assertEquals(0, ledger.findByRequestId(r.id).stream().filter(l -> l.type == LedgerType.USE || l.type == LedgerType.HOLD).count());
        assertThrows(ApiException.class, () -> reqs.rate(vol, r.id, 5, List.of(), null)); // 독거노인은 평가 안 함
    }

    @Test
    void 본인_사용은_직접_적립_10시간_이상_선물받은_시간은_바로() {
        User newbie = user("newbie", Role.USER, 4);   // 직접 적립 4시간
        Post p = offer(vol);
        ApiException e = assertThrows(ApiException.class, () -> request(newbie, p, 14, 1, "동행", null));
        assertEquals("SELF_USE_LOCKED", e.code);
        bank.add(newbie.id, LedgerType.GIFT_IN, 2.0, null, null, null, "선물");
        Request r = request(newbie, p, 14, 1, "동행", null); // 선물 2시간으로 1시간 요청 가능
        assertEquals(1.0, r.estEarn);
        User poor = user("poor", Role.USER, 10);
        bank.add(poor.id, LedgerType.USE, -9.5, null, null, null, "사용");
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(ApiException.class, () -> request(poor, p, 13, 2, "동행", null)).code);
    }

    @Test
    void 일지_제출_24시간_뒤_자동_확정_일지_없으면_안_함() {
        Post p = offer(vol);
        Request r = request(rec, p, 14, 1, "장보기·심부름", null);
        reqs.accept(vol, r.id);
        reqs.accept(rec, r.id);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), null);
        reqs.checkOut(vol, r.id, reqs.codeFor(r), null);
        assertEquals(0, reqs.autoConfirmDue(LocalDateTime.now().plusDays(3))); // 일지 없음
        reqs.submitLog(vol, r.id, "장보기", "장을 봤어요.", "/api/files/receipt.png", false); // 기능 U-16: 장보기는 영수증 사진이 있어야 제출
        assertEquals(0, reqs.autoConfirmDue(LocalDateTime.now().plusHours(23)));
        assertEquals(1, reqs.autoConfirmDue(LocalDateTime.now().plusHours(25)));
        assertEquals(ReqStatus.DONE, r.status);
        assertEquals(1.0, r.earned); // 장보기 1시간 × 1.2 = 1.2 → 1.0
    }

    @Test
    void 제안은_받는_쪽_수락이_최종_시작_24시간_안_봉사자_취소는_패널티() {
        Post need = postSvc.create(rec, new PostService.PostCmd(PostType.NEED, LocalDate.now(), 0.0, 24.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기 나눠요", null, 1.0, false, null, null, null, null)).get(0);
        Request r = reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, need.id, null, null, LocalDate.now(), 23.0, 1.0, null, "말벗 해 드릴게요", null));
        reqs.accept(rec, r.id);
        assertEquals(ReqStatus.CONFIRMED, r.status);
        assertEquals(1.0, bank.summary(rec.id).hold());
        reqs.cancel(vol, r.id, "급한 일");
        assertEquals(ReqStatus.CANCELLED, r.status);
        assertEquals(1, users.findById(vol.id).orElseThrow().penaltyCount);
        assertEquals(0.0, bank.summary(rec.id).hold());
    }

    @Test
    void 금지_요청은_공고_등록에서_막힌다() {
        ApiException e = assertThrows(ApiException.class, () -> postSvc.create(rec, new PostService.PostCmd(PostType.NEED, day, 10.0, 12.0, "행복동",
                List.of("생활 정리"), "목욕 도와주세요", "어머니 목욕과 기저귀", null, 1.0, false, null, null, null, null)));
        assertEquals("FORBIDDEN_REQUEST", e.code);
    }

    /** 41판 B-06: 자동 반복 등록은 하지 않는다. 예전 반복 칸(weekly, weeks)이 와도 한 건만 만든다. */
    @Test
    void 반복_칸이_와도_공고는_한_건만() {
        List<Post> list = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 10.0, 13.0, "행복동", List.of("말동무·정서"),
                null, null, null, null, true, 4, null, null, null));
        assertEquals(1, list.size());
        assertEquals(day, list.get(0).date);
        assertNull(list.get(0).seriesId);
        assertNull(list.get(0).repeatRule);
    }

    @Test
    void 시간_선물은_연결된_가족만_HTTP() throws Exception {
        String token = auth.issueToken(rec, false);
        String body = "{\"toUserId\":" + other.id + ",\"amount\":2}";
        mvc.perform(post("/api/bank/gift").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        FamilyLink f = new FamilyLink();
        f.requesterId = rec.id; f.targetId = other.id; f.status = LinkStatus.ACCEPTED;
        family.save(f);
        mvc.perform(post("/api/bank/gift").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals(2.0, sum(other, LedgerType.GIFT_IN));
        assertEquals(-2.0, sum(rec, LedgerType.GIFT_OUT));
    }

    @Test
    void 로그인과_역할_검사_HTTP() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"rec\",\"password\":\"1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists()).andExpect(jsonPath("$.user.balance").value(20.0));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"rec\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        String token = auth.issueToken(rec, false);
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/org/home").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/post-chat").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"NEED\",\"inputMode\":\"voice\",\"messages\":[{\"role\":\"user\",\"text\":\"내일 오후 세 시에 한 시간 카톡 알려 줘\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rule")).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.draft.startHour").value(15.0)).andExpect(jsonPath("$.safety.emergency").value(false))
                .andExpect(jsonPath("$.intent").value("NEW_REQUEST"));
    }
}
