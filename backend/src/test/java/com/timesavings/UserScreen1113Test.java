package com.timesavings;

import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.AuthService;
import com.timesavings.service.BankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 2026-11-13 대표 지시(사용자 화면): 관심 공고(북마크) 추가·해제·목록, 시간 통장 거래 건별 잔액과 상대 이름.
 * AI 키 없이 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserScreen1113Test {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired PostRepo posts;
    @Autowired RequestRepo requests;
    @Autowired PostBookmarkRepo bookmarks;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired OrganizationRepo orgs;
    @Autowired OrgEventRepo events;

    User a, b;

    User user(String id) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = Role.USER; u.area = "행복동"; u.ageGroup = "60대";
        u.phone = "010-0000-0000"; u.idStatus = VerifyStatus.APPROVED;
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Post need(User owner, String title) {
        Post p = new Post();
        p.type = PostType.NEED; p.ownerId = owner.id; p.date = LocalDate.now().plusDays(3); p.startHour = 10.0; p.endHour = 12.0; p.estHours = 2.0;
        p.area = "행복동"; p.categories = List.of("말동무·정서"); p.title = title; p.status = PostStatus.OPEN;
        return posts.save(p);
    }

    @BeforeEach
    void setUp() { a = user("u13a"); b = user("u13b"); }

    @Test
    void 관심_공고를_담고_빼고_목록으로_본다() throws Exception {
        Post p1 = need(b, "말동무 해 주세요"), p2 = need(b, "산책 같이 가요");
        mvc.perform(get("/api/posts/bookmarks").header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/posts/" + p1.id + "/bookmark").header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(true));
        // 두 번 눌러도 한 번만 담긴다
        mvc.perform(post("/api/posts/" + p1.id + "/bookmark").header("Authorization", tok(a))).andExpect(status().isOk());
        assertEquals(1, bookmarks.findByUserIdOrderByCreatedAtDescIdDesc(a.id).size());
        mvc.perform(get("/api/posts/bookmarks").header("Authorization", tok(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(p1.id.intValue())).andExpect(jsonPath("$[0].bookmarked").value(true));
        // 목록·상세에 bookmarked 가 붙는다(담은 것만 true)
        mvc.perform(get("/api/posts?type=NEED").header("Authorization", tok(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + p1.id + ")].bookmarked").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.id==" + p2.id + ")].bookmarked").value(hasItem(false)));
        mvc.perform(get("/api/posts/" + p1.id).header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(true));
        // 다른 사람의 관심 공고와 섞이지 않는다
        mvc.perform(get("/api/posts/bookmarks").header("Authorization", tok(b))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        // 내가 올린 공고는 담지 않는다
        mvc.perform(post("/api/posts/" + p1.id + "/bookmark").header("Authorization", tok(b))).andExpect(status().isBadRequest());
        // 공고가 삭제되면 목록에서 빠진다
        mvc.perform(post("/api/posts/" + p2.id + "/bookmark").header("Authorization", tok(a))).andExpect(status().isOk());
        p2.status = PostStatus.DELETED; posts.save(p2);
        mvc.perform(get("/api/posts/bookmarks").header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        // 해제
        mvc.perform(delete("/api/posts/" + p1.id + "/bookmark").header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(false));
        mvc.perform(get("/api/posts/bookmarks").header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/posts/" + p1.id).header("Authorization", tok(a))).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(false));
        // 로그인 없이는 안 된다
        mvc.perform(get("/api/posts/bookmarks")).andExpect(status().isUnauthorized());
    }

    @Test
    void 통장_거래마다_그_뒤_잔액과_상대_이름이_내려온다() throws Exception {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = b.id; r.recipientId = b.id; r.volunteerId = a.id; r.date = LocalDate.now().minusDays(2); r.startHour = 10; r.hours = 2;
        r.category = "말동무·정서"; r.content = "말동무"; r.weight = 1; r.status = ReqStatus.DONE;
        r = requests.save(r);
        // 시간순: +2(봉사) → 보류 1 → −0.5(선물) → +3(봉사)
        Ledger l1 = bank.add(a.id, LedgerType.EARN, 2, 2.0, 1.0, r.id, "말동무·정서");
        Ledger l2 = bank.add(a.id, LedgerType.HOLD, 1, null, null, null, "보류");
        Ledger l3 = bank.add(a.id, LedgerType.GIFT_OUT, -0.5, null, null, null, "가족에게 선물");
        Ledger l4 = bank.add(a.id, LedgerType.EARN, 3, 3.0, 1.0, null, "단체 봉사");
        bank.add(b.id, LedgerType.GIFT_IN, 5, null, null, null, "선물 받음");
        bank.add(b.id, LedgerType.USE, -2, 2.0, 1.0, r.id, "말동무·정서");
        mvc.perform(get("/api/bank").header("Authorization", tok(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(4.5))
                .andExpect(jsonPath("$.entries[?(@.id==" + l1.id + ")].balanceAfter").value(hasItem(2.0)))
                .andExpect(jsonPath("$.entries[?(@.id==" + l2.id + ")].balanceAfter").value(hasItem(2.0))) // 보류는 잔액을 바꾸지 않는다
                .andExpect(jsonPath("$.entries[?(@.id==" + l3.id + ")].balanceAfter").value(hasItem(1.5)))
                .andExpect(jsonPath("$.entries[?(@.id==" + l4.id + ")].balanceAfter").value(hasItem(4.5)))
                // 봉사한 쪽: 내가 도운 사람
                .andExpect(jsonPath("$.entries[?(@.id==" + l1.id + ")].counterpart").value(hasItem(b.name)))
                .andExpect(jsonPath("$.entries[?(@.id==" + l1.id + ")].counterpartRole").value(hasItem("HELPED")))
                // 요청과 이어지지 않은 거래는 상대가 없다
                .andExpect(jsonPath("$.entries[?(@.id==" + l3.id + ")].counterpart").value(hasItem(nullValue())));
        // 도움 받은 쪽: 나를 도와준 사람, 마지막 거래 뒤 잔액 = 지금 잔액
        mvc.perform(get("/api/bank").header("Authorization", tok(b))).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(3.0))
                .andExpect(jsonPath("$.entries[?(@.type=='USE')].balanceAfter").value(hasItem(3.0)))
                .andExpect(jsonPath("$.entries[?(@.type=='USE')].counterpart").value(hasItem(a.name)))
                .andExpect(jsonPath("$.entries[?(@.type=='USE')].counterpartRole").value(hasItem("HELPER")));
    }

    @Test
    void 동네_기관_소식은_최신순으로_받을_수_있다() throws Exception {
        Organization o = new Organization();
        o.name = "최신순 시험 복지관"; o.orgType = "복지관"; o.address = "행복동 1번지"; o.status = OrgStatus.APPROVED;
        o = orgs.save(o);
        OrgEvent old = new OrgEvent(); old.orgId = o.id; old.title = "U13 먼저 올린 소식"; old.eventDate = LocalDate.now().plusDays(1); old.url = "https://example.org/old";
        old.createdAt = java.time.LocalDateTime.now().plusMinutes(1); events.save(old);
        OrgEvent fresh = new OrgEvent(); fresh.orgId = o.id; fresh.title = "U13 나중에 올린 소식"; fresh.eventDate = LocalDate.now().plusDays(9); fresh.url = "https://example.org/new";
        fresh.createdAt = java.time.LocalDateTime.now().plusMinutes(2); events.save(fresh);
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mvc.perform(get("/api/news?sort=new").header("Authorization", tok(a))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> titles = new java.util.ArrayList<>();
        n.get("items").forEach(x -> titles.add(x.get("title").asText()));
        // 가장 나중에 올린 것이 맨 앞, 그다음이 그 앞에 올린 것(행사 날짜와 상관없이)
        assertEquals("U13 나중에 올린 소식", titles.get(0));
        assertEquals("U13 먼저 올린 소식", titles.get(1));
        assertTrue(n.get("items").get(0).hasNonNull("createdAt"));
    }
}
