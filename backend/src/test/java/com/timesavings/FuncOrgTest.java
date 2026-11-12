package com.timesavings;

import com.timesavings.ai.ScheduleAi;
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
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 기능명세서(73개 기능) 기관 담당자 범위: 기능 O-01·O-04·O-09·O-10·O-11·O-12·O-13.
 * AI 키 없이(규칙 모드) 돈다. 외부 네트워크를 부르지 않는다.
 */
@SpringBootTest(properties = "app.upload-dir=target/test-uploads")
@AutoConfigureMockMvc
@Transactional
class FuncOrgTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired ElderBlockRepo blocks;
    @Autowired RequestRepo requests;
    @Autowired PostRepo posts;
    @Autowired AuthService auth;
    @Autowired CareScheduleService schedule;
    @Autowired ScheduleAi scheduleAi;

    Organization org;
    User mgr, mgr2, v1, v2, v3, v4, v5, usr;
    Elder e1;
    final LocalDate today = LocalDate.now();
    /** 다음 주 월요일·화요일(늘 오늘보다 뒤) */
    final LocalDate nextMon = CareScheduleService.monday(today).plusDays(7);
    final LocalDate tue = nextMon.plusDays(1);

    User user(String id, Role role, String area) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = area; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED;
        if (role == Role.ORG) u.orgId = org.id;
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Elder elder(String alias, User m) {
        Elder e = new Elder();
        e.alias = alias; e.dong = "행복동"; e.managerId = m.id; e.orgId = org.id; e.needs = List.of("동행"); e.ageGroup = "80대";
        e.caution = "귀가 어두우셔서 천천히 크게 말해 주세요";
        return elders.save(e);
    }

    Request req(Elder e, User vol, LocalDate date, double start, ReqStatus st) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = mgr.id; r.volunteerId = vol.id; r.elderId = e.id; r.date = date;
        r.startHour = start; r.hours = 1; r.category = "동행"; r.content = "병원 동행"; r.weight = 1; r.status = st;
        if (st == ReqStatus.DONE) { r.logText = "잘 다녀왔어요"; r.logSubmittedAt = date.atTime(13, 0); r.doneAt = r.logSubmittedAt; }
        return requests.save(r);
    }

    Post mkPost(PostType type, User owner, Elder e, LocalDate date, double s, double en, String cat) {
        Post p = new Post();
        p.type = type; p.ownerId = owner == null ? null : owner.id; p.elderId = e == null ? null : e.id;
        p.createdBy = owner != null ? owner.id : mgr.id; p.date = date; p.startHour = s; p.endHour = en; p.area = "행복동";
        p.categories = new ArrayList<>(List.of(cat)); p.title = cat; p.estHours = type == PostType.NEED ? 1.0 : null;
        return posts.save(p);
    }

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.name = "행복동 주민센터"; org.orgType = "주민센터"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        mgr = user("fomgr", Role.ORG, "행복동");
        mgr2 = user("fomgr2", Role.ORG, "행복동");
        v1 = user("fov1", Role.USER, "행복동");
        v2 = user("fov2", Role.USER, "희망동");
        v3 = user("fov3", Role.USER, "행복동");
        v4 = user("fov4", Role.USER, "행복동");
        v5 = user("fov5", Role.USER, "행복동");
        usr = user("fousr", Role.USER, "행복동");
        e1 = elder("이순례", mgr);
    }

    // ---------- 기능 O-11 ----------
    @Test
    void O11_주간_편성표는_확정_대기_열린_공고_빈_칸을_구분하고_대상별로_거른다() throws Exception {
        Elder e2 = elder("정말순", mgr);
        elder("남의대상", mgr2);
        Request fixed = req(e1, v1, nextMon.plusDays(2), 10, ReqStatus.CONFIRMED);
        req(e1, v2, nextMon.plusDays(3), 14, ReqStatus.SENT);
        req(e1, v2, nextMon.plusDays(4), 14, ReqStatus.CANCELLED); // 취소는 일정이 아니다
        Post open = mkPost(PostType.NEED, null, e1, nextMon.plusDays(5), 9, 11, "동행");
        for (int w : new int[]{14, 21, 28}) req(e1, v1, tue.minusDays(w), 10, ReqStatus.DONE); // 지난 4주 중 3주 화요일 방문

        mvc.perform(get("/api/org/schedule").param("week", nextMon.plusDays(3).toString()).header("Authorization", tok(mgr)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value(nextMon.toString())).andExpect(jsonPath("$.weekEnd").value(nextMon.plusDays(6).toString()))
                .andExpect(jsonPath("$.prevWeek").value(nextMon.minusDays(7).toString())).andExpect(jsonPath("$.nextWeek").value(nextMon.plusDays(7).toString()))
                .andExpect(jsonPath("$.days.length()").value(7)).andExpect(jsonPath("$.days[0].dow").value("월")).andExpect(jsonPath("$.days[6].dow").value("일"))
                .andExpect(jsonPath("$.elders.length()").value(2)) // 내 대상만(세로)
                .andExpect(jsonPath("$.elders[0].displayName").value("이순례 님")).andExpect(jsonPath("$.elders[0].cells.length()").value(7))
                // 화: 빈 칸 + 지난 4주 흐름으로 본 빈 일정
                .andExpect(jsonPath("$.elders[0].cells[1].empty").value(true)).andExpect(jsonPath("$.elders[0].cells[1].selectable").value(true))
                .andExpect(jsonPath("$.elders[0].cells[1].gap.weeks").value(3)).andExpect(jsonPath("$.elders[0].cells[1].gap.category").value("동행"))
                // 수: 확정
                .andExpect(jsonPath("$.elders[0].cells[2].items[0].state").value("CONFIRMED")).andExpect(jsonPath("$.elders[0].cells[2].items[0].requestId").value(fixed.id))
                .andExpect(jsonPath("$.elders[0].cells[2].items[0].volunteerName").value(v1.name)).andExpect(jsonPath("$.elders[0].cells[2].empty").value(false))
                // 목: 응답 대기 / 금: 취소뿐이라 빈 칸(흐름 없음) / 토: 열려 있는 공고
                .andExpect(jsonPath("$.elders[0].cells[3].items[0].state").value("PENDING"))
                .andExpect(jsonPath("$.elders[0].cells[4].empty").value(true)).andExpect(jsonPath("$.elders[0].cells[4].gap").value(nullValue()))
                .andExpect(jsonPath("$.elders[0].cells[5].items[0].kind").value("POST")).andExpect(jsonPath("$.elders[0].cells[5].items[0].state").value("OPEN"))
                .andExpect(jsonPath("$.elders[0].cells[5].items[0].postId").value(open.id))
                .andExpect(jsonPath("$.counts.fixed").value(1)).andExpect(jsonPath("$.counts.pending").value(1))
                .andExpect(jsonPath("$.counts.open").value(1)).andExpect(jsonPath("$.counts.gaps").value(1))
                .andExpect(jsonPath("$.filter.length()").value(2));
        // 대상별 필터
        mvc.perform(get("/api/org/schedule").param("week", nextMon.toString()).param("elderId", String.valueOf(e2.id)).header("Authorization", tok(mgr)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elders.length()").value(1)).andExpect(jsonPath("$.elders[0].elderId").value(e2.id))
                .andExpect(jsonPath("$.filter.length()").value(2));
        // week 를 비우면 이번 주 · 지난 날짜 칸은 고를 수 없다
        mvc.perform(get("/api/org/schedule").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value(CareScheduleService.monday(today).toString())).andExpect(jsonPath("$.thisWeek").value(true));
        mvc.perform(get("/api/org/schedule").param("week", today.minusDays(14).toString()).header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.elders[0].cells[0].past").value(true)).andExpect(jsonPath("$.elders[0].cells[0].selectable").value(false));
        // 남의 대상·사용자 계정은 볼 수 없다
        mvc.perform(get("/api/org/schedule").param("elderId", String.valueOf(e1.id)).header("Authorization", tok(mgr2))).andExpect(status().isForbidden());
        mvc.perform(get("/api/org/schedule").header("Authorization", tok(usr))).andExpect(status().isForbidden());
    }

    // ---------- 기능 O-12 ----------
    @Test
    void O12_빈_칸에_단골_같은_동_시간_맞는_봉사자를_추천하고_요청은_보내지_않는다() throws Exception {
        for (int w : new int[]{14, 21, 28}) req(e1, v1, tue.minusDays(w), 10, ReqStatus.DONE); // v1 = 단골(3회), 같은 동
        Post offer2 = mkPost(PostType.OFFER, v2, null, tue, 9, 12, "동행");     // v2: 그 시간 봉사 가능 공고(다른 동)
        mkPost(PostType.OFFER, v3, null, tue, 9, 12, "동행");                   // v3: 받지 않기 목록
        ElderBlock b = new ElderBlock(); b.elderId = e1.id; b.volunteerId = v3.id; b.reason = "약속을 지키지 않음"; b.managerId = mgr.id; blocks.save(b);
        mkPost(PostType.OFFER, v4, null, tue, 9, 12, "동행");                   // v4: 같은 시간에 다른 일정
        Request busy = new Request();
        busy.kind = Kind.REQUEST; busy.senderId = usr.id; busy.recipientId = usr.id; busy.volunteerId = v4.id; busy.date = tue; busy.startHour = 10.5; busy.hours = 1;
        busy.category = "동행"; busy.content = "동행"; busy.weight = 1; busy.status = ReqStatus.CONFIRMED;
        requests.save(busy);
        mkPost(PostType.OFFER, v5, null, tue, 9, 12, "디지털 도움");            // v5: 도움 종류가 다름
        long before = requests.count(), postsBefore = posts.count();

        // 시간·종류를 비우면 지난 4주 흐름(화요일 10시 동행 1시간)으로 채운다
        mvc.perform(post("/api/org/schedule/suggest").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":" + e1.id + ",\"date\":\"" + tue + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot.category").value("동행")).andExpect(jsonPath("$.slot.startHour").value(10.0)).andExpect(jsonPath("$.slot.hours").value(1.0))
                .andExpect(jsonPath("$.pattern.weeks").value(3))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].volunteerId").value(v1.id)).andExpect(jsonPath("$.candidates[0].regular").value(true))
                .andExpect(jsonPath("$.candidates[0].sameDong").value(true)).andExpect(jsonPath("$.candidates[0].doneCount").value(3))
                .andExpect(jsonPath("$.candidates[0].action").value("REGULAR")).andExpect(jsonPath("$.candidates[0].reason", containsString("단골")))
                .andExpect(jsonPath("$.candidates[1].volunteerId").value(v2.id)).andExpect(jsonPath("$.candidates[1].timeFit").value(true))
                .andExpect(jsonPath("$.candidates[1].offerPostId").value(offer2.id)).andExpect(jsonPath("$.candidates[1].action").value("REQUEST"))
                .andExpect(jsonPath("$.candidates[1].sameDong").value(false))
                .andExpect(jsonPath("$.alternatives.length()").value(0))
                .andExpect(jsonPath("$.message", containsString("2명"))).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.sent").value(false)).andExpect(jsonPath("$.notice").value(CareScheduleService.NOTICE));
        assertEquals(before, requests.count());      // 자동으로 요청을 보내지 않는다
        assertEquals(postsBefore, posts.count());    // 공고도 만들지 않는다

        // 남의 대상·지난 날짜는 안 된다
        mvc.perform(post("/api/org/schedule/suggest").header("Authorization", tok(mgr2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"elderId\":" + e1.id + ",\"date\":\"" + tue + "\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/org/schedule/suggest").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"elderId\":" + e1.id + ",\"date\":\"" + today.minusDays(1) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void O12_맞는_봉사자가_없으면_다른_요일과_공고_올리기를_제안한다() throws Exception {
        Elder e = elder("한영자", mgr); // 단골·방문 기록 없음
        mkPost(PostType.OFFER, v2, null, tue.plusDays(2), 9, 12, "동행"); // 목요일에만 봉사 가능 공고
        mvc.perform(post("/api/org/schedule/suggest").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":" + e.id + ",\"date\":\"" + tue + "\",\"startHour\":10,\"hours\":1.5,\"category\":\"동행\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andExpect(jsonPath("$.alternatives.length()").value(1)).andExpect(jsonPath("$.alternatives[0].date").value(tue.plusDays(2).toString()))
                .andExpect(jsonPath("$.alternatives[0].dow").value("목")).andExpect(jsonPath("$.alternatives[0].count").value(1))
                .andExpect(jsonPath("$.alternatives[0].candidates[0].volunteerId").value(v2.id))
                .andExpect(jsonPath("$.postDraft.elderId").value(e.id)).andExpect(jsonPath("$.postDraft.date").value(tue.toString()))
                .andExpect(jsonPath("$.postDraft.startHour").value(10.0)).andExpect(jsonPath("$.postDraft.endHour").value(11.5))
                .andExpect(jsonPath("$.message", containsString("목요일"))).andExpect(jsonPath("$.message", containsString("공고")));
        // 다른 요일에도 없으면 공고 올리기만 제안
        mvc.perform(post("/api/org/schedule/suggest").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":" + e.id + ",\"date\":\"" + tue + "\",\"startHour\":10,\"hours\":1,\"category\":\"생활 정리\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0)).andExpect(jsonPath("$.alternatives.length()").value(0))
                .andExpect(jsonPath("$.message", containsString("공고를 올려")));
    }

    @Test
    void O12_규칙_모드의_추천_이유는_코드가_만든_근거_그대로다() {
        ScheduleAi.Text t = scheduleAi.explain("11/17(화) 10:00 동행 1시간", List.of(List.of("단골(이 대상을 3회 도움)", "같은 동(행복동)")), "안내");
        assertEquals("rule", t.source());
        assertEquals(List.of("단골(이 대상을 3회 도움) · 같은 동(행복동)"), t.reasons());
        assertEquals("안내", t.message());
    }

    // ---------- 기능 O-13 ----------
    @Test
    void O13_빈_칸_여러_개에_대리_공고를_한꺼번에_올리고_실패한_칸은_이유를_돌려준다() throws Exception {
        Elder other = elder("남의대상", mgr2);
        req(e1, v1, nextMon.plusDays(3), 10, ReqStatus.CONFIRMED); // 목요일 10시는 이미 일정이 있음
        String body = "{\"items\":["
                + "{\"elderId\":" + e1.id + ",\"date\":\"" + tue + "\",\"startHour\":10,\"endHour\":11,\"category\":\"동행\"},"
                + "{\"elderId\":" + e1.id + ",\"date\":\"" + nextMon.plusDays(2) + "\",\"startHour\":14,\"endHour\":15.5,\"category\":\"말동무·정서\",\"detail\":\"안부 방문\"},"
                + "{\"elderId\":" + e1.id + ",\"date\":\"" + nextMon.plusDays(3) + "\",\"startHour\":10,\"endHour\":11,\"category\":\"동행\"},"
                + "{\"elderId\":" + e1.id + ",\"date\":\"" + nextMon.plusDays(4) + "\",\"startHour\":10,\"endHour\":11,\"category\":\"가벼운 집안일\",\"detail\":\"목욕 도와 주세요\"},"
                + "{\"elderId\":" + other.id + ",\"date\":\"" + tue + "\",\"startHour\":10,\"endHour\":11,\"category\":\"동행\"},"
                + "{\"elderId\":" + e1.id + ",\"date\":\"" + nextMon.plusDays(5) + "\",\"startHour\":10,\"endHour\":11,\"category\":\"없는 종류\"}"
                + "]}";
        mvc.perform(post("/api/org/schedule/bulk-posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(2)).andExpect(jsonPath("$.failedCount").value(4))
                .andExpect(jsonPath("$.created[0].index").value(0)).andExpect(jsonPath("$.created[0].postId").isNumber())
                .andExpect(jsonPath("$.created[1].index").value(1))
                .andExpect(jsonPath("$.failed[0].index").value(2)).andExpect(jsonPath("$.failed[0].code").value("SLOT_TAKEN"))
                .andExpect(jsonPath("$.failed[1].index").value(3)).andExpect(jsonPath("$.failed[1].code").value("FORBIDDEN_REQUEST"))
                .andExpect(jsonPath("$.failed[1].reason", containsString("목욕")))
                .andExpect(jsonPath("$.failed[2].index").value(4)).andExpect(jsonPath("$.failed[2].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.failed[3].index").value(5)).andExpect(jsonPath("$.failed[3].code").value("INVALID_CATEGORY"));
        List<Post> mine = posts.findByElderId(e1.id);
        assertEquals(2, mine.size());
        Post p = mine.stream().filter(x -> x.date.equals(tue)).findFirst().orElseThrow();
        assertEquals(PostType.NEED, p.type);
        assertEquals(PostStatus.OPEN, p.status);
        assertEquals(mgr.id, p.createdBy);
        assertNull(p.ownerId);
        assertEquals("행복동", p.area);
        assertEquals(List.of("동행"), p.categories);
        assertEquals(1.0, p.estHours);
        assertTrue(posts.findByElderId(other.id).isEmpty());
        // 같은 칸을 다시 올리면 겹쳐 올라가지 않는다
        mvc.perform(post("/api/org/schedule/bulk-posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"elderId\":" + e1.id + ",\"date\":\"" + tue + "\",\"startHour\":10,\"endHour\":11,\"category\":\"동행\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(0)).andExpect(jsonPath("$.failed[0].code").value("SLOT_TAKEN"));
        // 올린 공고는 편성표에 열려 있는 공고로 보인다
        mvc.perform(get("/api/org/schedule").param("week", nextMon.toString()).param("elderId", String.valueOf(e1.id)).header("Authorization", tok(mgr)))
                .andExpect(jsonPath("$.elders[0].cells[1].items[0].state").value("OPEN")).andExpect(jsonPath("$.elders[0].cells[1].items[0].postId").value(p.id));
        // 빈 목록·너무 많은 칸·사용자 계정
        mvc.perform(post("/api/org/schedule/bulk-posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ITEMS_REQUIRED"));
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i <= CareScheduleService.MAX_BULK; i++) many.append(i == 0 ? "" : ",").append("{\"elderId\":").append(e1.id).append("}");
        mvc.perform(post("/api/org/schedule/bulk-posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(many + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY"));
        mvc.perform(post("/api/org/schedule/bulk-posts").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ---------- 기능 O-10 ----------
    @Test
    void O10_리포트는_신청_내역의_거절_만료까지_세어_안부_확인_순서에_반영한다() throws Exception {
        Elder calm = elder("정말순", mgr);
        req(calm, v1, today.minusDays(3), 10, ReqStatus.DONE);
        req(e1, v1, today.minusDays(5), 10, ReqStatus.DONE);
        req(e1, v2, today.plusDays(2), 10, ReqStatus.REJECTED);
        req(e1, v2, today.plusDays(3), 10, ReqStatus.DECLINED);
        req(e1, v3, today.plusDays(4), 10, ReqStatus.EXPIRED);
        req(e1, v3, today.plusDays(5), 10, ReqStatus.CANCELLED);
        req(e1, v4, today.plusDays(6), 10, ReqStatus.SENT);
        Post closed = mkPost(PostType.NEED, null, e1, today.minusDays(2), 9, 11, "동행"); // 봉사자를 못 구하고 마감
        closed.status = PostStatus.CLOSED;
        mvc.perform(get("/api/org/change-report").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].elderId").value(e1.id)).andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].apply.total").value(6)).andExpect(jsonPath("$.items[0].apply.matched").value(1))
                .andExpect(jsonPath("$.items[0].apply.rejected").value(2)).andExpect(jsonPath("$.items[0].apply.expired").value(1))
                .andExpect(jsonPath("$.items[0].apply.cancelled").value(1)).andExpect(jsonPath("$.items[0].apply.waiting").value(1))
                .andExpect(jsonPath("$.items[0].apply.failed").value(3)).andExpect(jsonPath("$.items[0].apply.unmatchedPosts").value(1))
                .andExpect(jsonPath("$.items[0].applyText", containsString("신청 6건"))).andExpect(jsonPath("$.items[0].applyText", containsString("만료 1")))
                .andExpect(jsonPath("$.items[0].reasons", hasItem(containsString("6건 중 3건이 맞춰지지 않았어요"))))
                .andExpect(jsonPath("$.items[0].reasons", hasItem(containsString("마감된 공고가 1건"))))
                .andExpect(jsonPath("$.items[0].checkFirst").value(true)).andExpect(jsonPath("$.items[0].level").value("MID"))
                .andExpect(jsonPath("$.items[1].elderId").value(calm.id)).andExpect(jsonPath("$.items[1].apply.failed").value(0))
                .andExpect(jsonPath("$.items[1].checkFirst").value(false))
                .andExpect(jsonPath("$.notice").value(ElderChangeService.NOTICE)).andExpect(jsonPath("$.source").value("rule"));
    }

    @Test
    void O10_신청_점수_규칙() {
        ElderChangeService.Stats quiet = new ElderChangeService.Stats(1, 1, 0, 3, today.minusDays(3), 0, 0, List.of(), List.of(), List.of(), false, false, true);
        assertEquals(0, ElderChangeService.score(quiet, ElderChangeService.Apply.NONE).score());
        assertEquals(5, ElderChangeService.score(quiet, new ElderChangeService.Apply(2, 1, 0, 1, 0, 0, 0, 0)).score());
        assertEquals(15, ElderChangeService.score(quiet, new ElderChangeService.Apply(3, 1, 0, 1, 1, 0, 0, 0)).score());
        // 노쇼 1건: 맞춰지지 않은 신청 1건(5) + 노쇼(10) + 못 구한 공고(10)
        ElderChangeService.Scored s = ElderChangeService.score(quiet, new ElderChangeService.Apply(1, 0, 0, 0, 0, 0, 1, 1));
        assertEquals(25, s.score());
        assertEquals("MID", s.level());
        // 취소만 있으면 점수가 오르지 않는다
        assertEquals(0, ElderChangeService.score(quiet, new ElderChangeService.Apply(2, 0, 0, 0, 0, 2, 0, 0)).score());
        assertTrue(ElderChangeService.applyText(ElderChangeService.Apply.NONE).contains("신청이 없었어요"));
    }

    // ---------- 기능 O-09 ----------
    @Test
    void O09_행사에_정원을_적으면_사용자_목록과_상세에_나온다() throws Exception {
        String d = today.plusDays(7).toString();
        String base = "{\"title\":\"스마트폰 교실\",\"date\":\"" + d + "\",\"startHour\":14,\"place\":\"행복동 주민센터 1층\",\"url\":\"https://example.org/class\",\"ogTitle\":\"안내\"";
        String json = mvc.perform(post("/api/events").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(base + ",\"capacity\":20}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(20)).andExpect(jsonPath("$.applyType").value("LINK"))
                .andExpect(jsonPath("$.place").value("행복동 주민센터 1층")).andExpect(jsonPath("$.url").value("https://example.org/class"))
                .andReturn().getResponse().getContentAsString();
        long id = Views.OM.readTree(json).path("id").asLong();
        // 근처 사용자에게 보인다(목록·상세에 정원)
        mvc.perform(get("/api/events/" + id).header("Authorization", tok(usr))).andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(20))
                .andExpect(jsonPath("$.near").value(true));
        mvc.perform(get("/api/news").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key=='ORG-" + id + "')].capacity", contains(20)));
        // 정원을 비우면 제한 없음(null) · 잘못된 정원은 막는다
        mvc.perform(post("/api/events").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(base + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(nullValue()));
        mvc.perform(post("/api/events").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(base + ",\"capacity\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CAPACITY"));
    }

    // ---------- 기능 O-04 ----------
    @Test
    void O04_관리_대상_특이사항은_담당자와_확정된_봉사자에게만_보인다() throws Exception {
        Post p = mkPost(PostType.NEED, null, e1, tue, 10, 12, "동행");
        p.issueNote = "순한 반려견이 있어요";
        // 담당자: 항상 보임
        mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.elderIssue").value("순한 반려견이 있어요")).andExpect(jsonPath("$.elderCaution").value("순한 반려견이 있어요"))
                .andExpect(jsonPath("$.elderIssueLocked").value(false));
        // 아직 관계없는 봉사자: 공고 상세·목록 어디에도 없다
        String card = mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(v1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.elderIssue").value(nullValue())).andExpect(jsonPath("$.elderCaution").value(nullValue()))
                .andExpect(jsonPath("$.elderIssueLocked").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(card.contains("반려견"), card);
        assertFalse(card.contains("귀가 어두"), card);
        String list = mvc.perform(get("/api/posts").param("type", "NEED").header("Authorization", tok(v1))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("반려견"), list);
        assertFalse(list.contains("귀가 어두"), list);
        // 제안을 보낸 뒤(확정 전)에도 안 보인다
        String rj = mvc.perform(post("/api/requests").header("Authorization", tok(v1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROPOSAL\",\"postId\":" + p.id + ",\"content\":\"동행해 드릴게요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elder.caution").value(nullValue())).andExpect(jsonPath("$.elder.cautionLocked").value(true))
                .andReturn().getResponse().getContentAsString();
        long rid = Views.OM.readTree(rj).path("id").asLong();
        assertFalse(rj.contains("반려견"), rj);
        mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(v1))).andExpect(jsonPath("$.elderIssueLocked").value(true))
                .andExpect(jsonPath("$.myRequest.elder.caution").value(nullValue()));
        // 담당자가 수락(확정)하면 그 봉사자에게만 보인다
        mvc.perform(post("/api/requests/" + rid + "/accept").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.elder.caution").value("순한 반려견이 있어요"));
        mvc.perform(get("/api/requests/" + rid).header("Authorization", tok(v1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.elder.caution").value("순한 반려견이 있어요")).andExpect(jsonPath("$.elder.cautionLocked").value(false))
                .andExpect(jsonPath("$.elder.baseCaution").value("귀가 어두우셔서 천천히 크게 말해 주세요"));
        mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(v1))).andExpect(jsonPath("$.elderIssue").value("순한 반려견이 있어요"))
                .andExpect(jsonPath("$.elderIssueLocked").value(false));
        // 다른 봉사자에게는 여전히 안 보인다
        mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(v2))).andExpect(jsonPath("$.elderIssue").value(nullValue()))
                .andExpect(jsonPath("$.elderIssueLocked").value(true));
        assertTrue(Views.issueVisible(ReqStatus.CONFIRMED) && Views.issueVisible(ReqStatus.DONE));
        assertFalse(Views.issueVisible(ReqStatus.SENT) || Views.issueVisible(ReqStatus.VOL_ACCEPTED) || Views.issueVisible(ReqStatus.CANCELLED));
    }

    // ---------- 기능 O-01 ----------
    @Test
    void O01_동의_기록이_없는_예전_대상은_수정할_때_동의를_남길_수_있다() throws Exception {
        assertNull(e1.serviceConsentAt);
        mvc.perform(get("/api/org/elders/" + e1.id).header("Authorization", tok(mgr))).andExpect(jsonPath("$.serviceConsent").value(false));
        mvc.perform(put("/api/org/elders/" + e1.id).header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serviceConsent").value(true)).andExpect(jsonPath("$.serviceConsentAt").isNotEmpty());
        Elder e = elders.findById(e1.id).orElseThrow();
        assertEquals(mgr.id, e.serviceConsentBy);
        // 주소·연락처는 받지 않는다
        mvc.perform(put("/api/org/elders/" + e1.id).header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"caution\":\"연락처 010-1234-5678\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PII_NOT_ALLOWED"));
    }
}
