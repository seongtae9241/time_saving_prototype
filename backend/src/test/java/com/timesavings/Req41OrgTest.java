package com.timesavings;

import com.timesavings.ai.ManagerMemoAi;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 요구사항 41판 담당자·운영자·기관 소식 범위(C-01·C-02·C-03·C-12·C-13·C-16, D-04·D-08·D-11, B-64, A-04).
 * AI 키 없이(규칙 모드) 돈다. 외부 네트워크를 부르지 않는다.
 */
@SpringBootTest(properties = "app.upload-dir=target/test-uploads")
@AutoConfigureMockMvc
@Transactional
class Req41OrgTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired ElderMemoRepo memos;
    @Autowired RequestRepo requests;
    @Autowired PostRepo posts;
    @Autowired InquiryRepo inquiries;
    @Autowired CareNoteRepo careNotes;
    @Autowired PublicClassRepo classes;
    @Autowired OrgEventRepo events;
    @Autowired AuthService auth;

    Organization org;
    User mgr, mgr2, admin, vol, usr;
    final LocalDate today = LocalDate.now();

    User user(String id, Role role) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "60대";
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

    /** 일지까지 낸 활동 한 건(daysAgo 일 전). signals 가 있으면 전달 메모 정리(care_notes)에 신호를 넣는다. */
    Request done(Elder e, int daysAgo, String category, String log, String... signals) {
        Request r = new Request();
        r.kind = Kind.PROPOSAL; r.senderId = vol.id; r.volunteerId = vol.id; r.elderId = e.id; r.date = today.minusDays(daysAgo);
        r.startHour = 10; r.hours = 1; r.category = category; r.content = category; r.weight = 1; r.status = ReqStatus.DONE;
        r.logText = log; r.logSubmittedAt = r.date.atTime(12, 0); r.doneAt = r.logSubmittedAt; r.createdAt = r.date.atTime(9, 0);
        requests.save(r);
        if (signals.length > 0) {
            CareNote n = new CareNote();
            n.requestId = r.id; n.subjectType = SubjectType.ELDER; n.subjectId = e.id; n.activityDate = r.date;
            n.signals = List.of(signals); n.lifeChanges = log; n.source = "rule";
            careNotes.save(n);
        }
        return r;
    }

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.name = "행복동 주민센터"; org.orgType = "주민센터"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        mgr = user("r41mgr", Role.ORG);
        mgr2 = user("r41mgr2", Role.ORG);
        admin = user("r41admin", Role.ADMIN);
        vol = user("r41vol", Role.USER);
        usr = user("r41usr", Role.USER);
    }

    // ---------- C-01 · D-01 ----------
    @Test
    void C01_기관_신청은_재직_증빙이_있어야_하고_운영자가_열어_볼_수_있다() throws Exception {
        // 증빙 없이 JSON 으로 신청 → 막힘
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"미래동 주민센터\",\"managerName\":\"김담당\",\"memo\":\"재직증명서: 나중에\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROOF_REQUIRED"));
        // 없는 파일 주소 → 막힘
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"미래동 주민센터\",\"managerName\":\"김담당\",\"proofFileUrl\":\"/api/files/00000000000000000000000000000000.pdf\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROOF_REQUIRED"));
        // 파일 없이 폼으로 신청 → 막힘
        mvc.perform(multipart("/api/orgs/apply").param("name", "미래동 주민센터").param("managerName", "김담당"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROOF_REQUIRED"));
        // 사진·PDF 가 아닌 파일 → 막힘
        mvc.perform(multipart("/api/orgs/apply").file(new MockMultipartFile("proof", "proof.exe", "application/octet-stream", new byte[]{1}))
                        .param("name", "미래동 주민센터").param("managerName", "김담당"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TYPE"));
        long before = orgs.count();
        // 로그인 없이 신청과 증빙을 한 번에
        mvc.perform(multipart("/api/orgs/apply").file(new MockMultipartFile("proof", "재직증명서.pdf", "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8)))
                        .param("name", "미래동 주민센터").param("orgType", "주민센터").param("managerName", "김담당").param("managerPhone", "02-000-0000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(before + 1, orgs.count());
        Organization o = orgs.findAll().stream().filter(x -> "미래동 주민센터".equals(x.name)).findFirst().orElseThrow();
        assertNotNull(o.proofFileUrl);
        assertTrue(o.proofFileUrl.matches("/api/files/[0-9a-f]{32}\\.pdf"), o.proofFileUrl);
        // 운영자 승인 목록에 증빙 주소가 나오고, 그 주소로 파일이 열린다
        mvc.perform(get("/api/admin/orgs").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='미래동 주민센터')].proofFileUrl", contains(o.proofFileUrl)));
        mvc.perform(get(o.proofFileUrl)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // 올린 주소로 JSON 신청도 된다
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"희망동 복지관\",\"managerName\":\"이담당\",\"proofFileUrl\":\"" + o.proofFileUrl + "\"}"))
                .andExpect(status().isOk());
    }

    // ---------- C-02 ----------
    @Test
    void C02_도움_대상_등록은_동의_확인이_있어야_하고_기록된다() throws Exception {
        String body = "{\"alias\":\"솔바람\",\"dong\":\"행복동\",\"ageGroup\":\"80대\",\"needs\":[\"동행\"],\"caution\":\"천천히 말해 주세요\"";
        mvc.perform(post("/api/org/elders").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(body + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        mvc.perform(post("/api/org/elders").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(body + ",\"consent\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertTrue(elders.findByManagerId(mgr.id).isEmpty());
        mvc.perform(post("/api/org/elders").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(body + ",\"consent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alias").value("솔바람")).andExpect(jsonPath("$.serviceConsentAt").isNotEmpty());
        Elder e = elders.findByManagerId(mgr.id).get(0);
        assertNotNull(e.serviceConsentAt);
        assertEquals(mgr.id, e.serviceConsentBy);
        // 수정에는 동의 체크가 다시 필요 없다
        mvc.perform(put("/api/org/elders/" + e.id).header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"caution\":\"반려견이 있어요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caution").value("반려견이 있어요"));
    }

    // ---------- C-13 ----------
    @Test
    void C13_담당자_메모는_원문을_두고_정리본을_따로_만든다() throws Exception {
        Elder e = elder("이순례", mgr);
        String raw = "오늘 방문했더니 표정이 밝으셨음.   손녀 얘기를 오래 하심. 다음 주에 경로당 같이 가 보기로 함";
        mvc.perform(post("/api/org/elders/" + e.id + "/memos").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + raw + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value(raw.trim()))
                .andExpect(jsonPath("$[0].source").value("rule")).andExpect(jsonPath("$[0].lines.length()").value(3))
                .andExpect(jsonPath("$[0].lines[0]").value("오늘 방문했더니 표정이 밝으셨음."))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
        ElderMemo m = memos.findByElderIdOrderByCreatedAtDesc(e.id).get(0);
        assertEquals(raw.trim(), m.text); // 원문 보존
        assertEquals(3, m.tidyText.split("\n").length);
        // 봉사자에게 가는 주의사항은 메모와 따로 — 메모를 써도 바뀌지 않는다
        assertEquals("귀가 어두우셔서 천천히 크게 말해 주세요", elders.findById(e.id).orElseThrow().caution);
        mvc.perform(get("/api/org/elders/" + e.id).header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.memos[0].tidy").isNotEmpty()).andExpect(jsonPath("$.caution").value("귀가 어두우셔서 천천히 크게 말해 주세요"));
        // 연락처가 들어간 메모는 받지 않는다 · 다른 담당자는 쓸 수 없다
        mvc.perform(post("/api/org/elders/" + e.id + "/memos").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"아드님 번호 010-1234-5678\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/org/elders/" + e.id + "/memos").header("Authorization", tok(mgr2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"메모\"}")).andExpect(status().isForbidden());
        // 규칙 정리: 같은 줄은 한 번만, 최대 5줄
        assertEquals(List.of("가", "나"), ManagerMemoAi.ruleLines("가\n나\n가"));
        assertEquals(ManagerMemoAi.MAX_LINES, ManagerMemoAi.ruleLines("1번. 2번. 3번. 4번. 5번. 6번. 7번.").size());
    }

    // ---------- C-12 ----------
    @Test
    void C12_담당자_홈에_이번_달_활동_건수가_나온다() throws Exception {
        Elder e = elder("이순례", mgr);
        Request a = done(e, 0, "동행", "병원에 다녀왔어요.");
        a.date = today.withDayOfMonth(1);
        Request old = done(e, 0, "동행", "지난달 활동");
        old.date = today.withDayOfMonth(1).minusDays(1);
        done(elder("남의대상", mgr2), 0, "동행", "다른 담당자 대상");
        mvc.perform(get("/api/org/home").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.monthDone").value(1)).andExpect(jsonPath("$.counts.elders").value(1));
    }

    // ---------- C-16 ----------
    @Test
    void C16_변화_리포트는_이전_기록과_비교하고_살펴볼_순서를_준다() throws Exception {
        Elder calm = elder("김평온", mgr), changed = elder("이순례", mgr), quiet = elder("박조용", mgr), fresh = elder("새대상", mgr);
        elder("남의대상", mgr2);
        // 평온: 이전 2회 · 최근 2회, 신호 없음
        done(calm, 40, "말동무·정서", "이야기를 나눴어요."); done(calm, 35, "말동무·정서", "산책했어요.");
        done(calm, 10, "말동무·정서", "사진첩을 봤어요."); done(calm, 3, "말동무·정서", "차를 마셨어요.");
        // 변화: 이전 3회(신호 없음) → 최근 1회 + 새 신호 2건
        done(changed, 50, "동행", "병원에 다녀왔어요."); done(changed, 45, "동행", "약국에 들렀어요."); done(changed, 38, "말동무·정서", "이야기 나눔.");
        done(changed, 6, "말동무·정서", "입맛이 없다며 점심을 거르셨대요.", "식사 거름");
        Request t = done(changed, 0, "장보기·심부름", "기운이 없다고 하셔서 장만 봐 드렸어요.", "기운 없음");
        t.logSubmittedAt = LocalDateTime.now();
        // 조용: 40일 전 활동이 마지막
        done(quiet, 40, "동행", "은행에 다녀왔어요.");

        String json = mvc.perform(get("/api/org/change-report").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4)).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.notice").value(ElderChangeService.NOTICE))
                // 1순위: 신호가 새로 생기고 활동이 줄어든 대상
                .andExpect(jsonPath("$.items[0].alias").value("이순례")).andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].level").value("HIGH"))
                .andExpect(jsonPath("$.items[0].stats.recentCount").value(2)).andExpect(jsonPath("$.items[0].stats.prevCount").value(3))
                .andExpect(jsonPath("$.items[0].stats.signalCount").value(2)).andExpect(jsonPath("$.items[0].stats.todayCount").value(1))
                .andExpect(jsonPath("$.items[0].stats.newSignals", containsInAnyOrder("식사 거름", "기운 없음")))
                .andExpect(jsonPath("$.items[0].reasons[0]").value(containsString("확인을 권해요")))
                .andExpect(jsonPath("$.items[0].changes[0]").value(containsString("이전 기록에 없던 표현")))
                .andExpect(jsonPath("$.items[0].summary").value(containsString("활동 2회")))
                // 2순위: 오래 활동이 없는 대상, 그다음 새 대상, 마지막이 변화 없는 대상
                .andExpect(jsonPath("$.items[1].alias").value("박조용")).andExpect(jsonPath("$.items[1].stats.daysSinceLast").value(40))
                .andExpect(jsonPath("$.items[2].alias").value("새대상"))
                .andExpect(jsonPath("$.items[3].alias").value("김평온")).andExpect(jsonPath("$.items[3].level").value("LOW"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 진단·건강 판단 낱말을 만들지 않는다
        for (String w : List.of("진단", "우울증", "치매", "질환", "증상")) assertFalse(json.replace(ElderChangeService.NOTICE, "").contains(w), w);
        // 오늘 리포트: 오늘 일지가 들어온 대상만(순위는 전체 기준 그대로)
        mvc.perform(get("/api/org/change-report?range=today").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].alias").value("이순례"))
                .andExpect(jsonPath("$.items[0].rank").value(1)).andExpect(jsonPath("$.total").value(4));
        // 담당자만
        mvc.perform(get("/api/org/change-report").header("Authorization", tok(usr))).andExpect(status().isForbidden());

        // 점수 규칙(코드): 숫자만으로 정해진다
        ElderChangeService.Scored s = ElderChangeService.score(new ElderChangeService.Stats(1, 3, 0, 5, today.minusDays(5), 0, 0,
                List.of(), List.of(), List.of(), false, false, true));
        assertEquals(20, s.score());
        assertEquals("MID", s.level());
        assertEquals(0, ElderChangeService.score(new ElderChangeService.Stats(2, 2, 0, 3, today.minusDays(3), 0, 0,
                List.of(), List.of(), List.of(), false, false, true)).score());
    }

    // ---------- D-04 · D-11 ----------
    Post mkPost(PostType type, String area, String cat, LocalDateTime at) {
        Post p = new Post();
        p.type = type; p.ownerId = usr.id; p.date = today.plusDays(3); p.startHour = 10; p.endHour = 12; p.area = area;
        p.categories = List.of(cat); p.title = cat; p.createdAt = at;
        return posts.save(p);
    }

    @Test
    void D04_D11_지역별_부족_도움과_계절별_수요_공급() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<Post> ps = List.of(mkPost(PostType.NEED, "행복동", "동행", now), mkPost(PostType.NEED, "행복동", "동행", now),
                mkPost(PostType.OFFER, "행복동", "말동무·정서", now), mkPost(PostType.NEED, "미래동", "디지털 도움", now),
                mkPost(PostType.OFFER, "미래동", "디지털 도움", now), mkPost(PostType.NEED, "행복동", "동행", now.minusDays(400)));
        List<Map<String, Object>> areas = AnalyticsService.areaShortage(ps, List.of(), now.minusDays(180), r -> null);
        assertEquals("행복동", areas.get(0).get("area")); // 부족이 큰 지역이 위
        assertEquals(2, areas.get(0).get("demand"));
        assertEquals(1, areas.get(0).get("supply"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> sh = (List<Map<String, Object>>) areas.get(0).get("short");
        assertEquals("동행", sh.get(0).get("category"));
        assertEquals(2, sh.get(0).get("gap"));
        assertEquals(0, areas.get(1).get("shortTotal")); // 미래동: 수요 1 · 공급 1

        LocalDateTime jan = LocalDateTime.of(today.getYear(), 1, 15, 10, 0), jul = LocalDateTime.of(today.getYear(), 7, 15, 10, 0);
        List<Post> sp = List.of(mkPost(PostType.NEED, "행복동", "동행", jan), mkPost(PostType.OFFER, "행복동", "동행", jul), mkPost(PostType.NEED, "행복동", "말동무·정서", jul));
        List<Map<String, Object>> seasons = AnalyticsService.seasonSupplyDemand(sp, List.of(), jan.minusDays(1));
        assertEquals(List.of("봄", "여름", "가을", "겨울"), seasons.stream().map(m -> m.get("season")).toList());
        Map<String, Object> summer = seasons.get(1), winter = seasons.get(3);
        assertEquals(1, summer.get("demand")); assertEquals(1, summer.get("supply"));
        assertEquals(1, winter.get("demand")); assertEquals(0, winter.get("supply"));

        mvc.perform(get("/api/admin/analytics").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.areaShortage").isArray()).andExpect(jsonPath("$.seasonSupplyDemand.length()").value(4))
                .andExpect(jsonPath("$.seasonSupplyDemand[0].categories.length()").value(6))
                .andExpect(jsonPath("$.byRecipientAge").isArray()).andExpect(jsonPath("$.supplyDemand").isArray());
    }

    // ---------- D-08 ----------
    @Test
    void D08_문의_처리_상태를_따로_바꾼다() throws Exception {
        Inquiry q = new Inquiry();
        q.userId = usr.id; q.type = "이용 방법"; q.title = "적립이 안 됐어요"; q.content = "어제 활동 시간이 안 들어왔어요";
        inquiries.save(q);
        String a = tok(admin);
        mvc.perform(get("/api/admin/inquiries").header("Authorization", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + q.id + ")].stage", contains("RECEIVED")));
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CHECKING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("CHECKING")).andExpect(jsonPath("$.status").value("RECEIVED"));
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANSWERED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ANSWER_REQUIRED"));
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WRONG\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        // 사용자 쪽에도 단계가 보인다
        mvc.perform(get("/api/inquiries/mine").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stage").value("CHECKING")).andExpect(jsonPath("$[0].status").value("RECEIVED"));
        mvc.perform(post("/api/admin/inquiries/" + q.id + "/answer").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"확인해서 넣어 드렸어요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ANSWERED")).andExpect(jsonPath("$.status").value("ANSWERED"));
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("CLOSED")).andExpect(jsonPath("$.status").value("ANSWERED"));
        mvc.perform(get("/api/admin/dashboard").header("Authorization", a)).andExpect(status().isOk()).andExpect(jsonPath("$.pending.inquiries").value(0));
        // 운영자만
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- B-64 ----------
    PublicClass found(String title, String source, boolean verified, String url) {
        PublicClass c = new PublicClass();
        c.source = source; c.title = title; c.orgName = "행복구 평생학습관"; c.region = "행복구 행복동"; c.place = "행복동 학습관 2층";
        c.startDate = today.plusDays(2); c.endDate = today.plusDays(9); c.category = "디지털"; c.verified = verified; c.hidden = !verified; c.applyUrl = url;
        return classes.save(c);
    }

    @Test
    void B64_소식_한_목록에_담당자_소식과_확인된_AI_소식이_함께_나온다() throws Exception {
        OrgEvent e = new OrgEvent();
        e.orgId = org.id; e.createdBy = mgr.id; e.title = "행복동 사진 교실"; e.eventDate = today.plusDays(5); e.startHour = 14.0; e.place = "주민센터 1층";
        e.applyType = "LINK"; e.url = "https://example.org/photo";
        events.save(e);
        found("AI가 찾은 스마트폰 교실", "AI_SEARCH", true, "https://example.org/ai-class");
        found("확인 안 된 강좌", "AI_SEARCH", false, "https://example.org/unverified");
        found("링크 없는 강좌", "PUBLIC_DATA", true, null);
        mvc.perform(get("/api/news").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("행복동 사진 교실", "AI가 찾은 스마트폰 교실")))
                .andExpect(jsonPath("$.items[?(@.kind=='ORG')].url", contains("https://example.org/photo")))
                .andExpect(jsonPath("$.items[?(@.kind=='ORG')].place", contains("주민센터 1층")))
                .andExpect(jsonPath("$.items[?(@.kind=='AI')].url", contains("https://example.org/ai-class")))
                .andExpect(jsonPath("$.items[?(@.kind=='AI')].aiFound", contains(true)))
                .andExpect(jsonPath("$.items[?(@.kind=='AI')].notice", contains(PublicClassService.AI_NOTICE)))
                .andExpect(jsonPath("$.aiNotice").value(PublicClassService.AI_NOTICE));
        mvc.perform(get("/api/news?area=미래동").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ---------- A-04 · C-03 ----------
    @Test
    void A04_담당자_운영자_탈퇴_조건과_C03_이관_뒤_권한() throws Exception {
        Elder e = elder("이순례", mgr);
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = mgr.id; r.volunteerId = vol.id; r.elderId = e.id; r.date = today.plusDays(2);
        r.startHour = 10; r.hours = 1; r.category = "동행"; r.content = "병원 동행"; r.weight = 1.5; r.status = ReqStatus.CONFIRMED;
        requests.save(r);
        // 담당 대상의 진행 건이 탈퇴 안내에 잡힌다
        mvc.perform(get("/api/me/withdraw-check").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canWithdraw").value(false)).andExpect(jsonPath("$.activeRequests.length()").value(1));
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(mgr))).andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("MANAGER"));

        // 다른 담당자에게 이관 → 예전에 요청을 보낸 담당자는 더 볼 수 없고, 새 담당자가 담당자 권한을 가진다
        e.managerId = mgr2.id;
        elders.save(e);
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(mgr2))).andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("MANAGER"));
        // 이관하고 나면 예전 담당자는 탈퇴할 수 있다
        mvc.perform(get("/api/me/withdraw-check").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canWithdraw").value(true)).andExpect(jsonPath("$.activeRequests.length()").value(0));
        // 새 담당자: 진행 건을 끝내도 담당 대상이 남아 있으면 탈퇴할 수 없다
        r.status = ReqStatus.DONE;
        requests.save(r);
        mvc.perform(get("/api/me/withdraw-check").header("Authorization", tok(mgr2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canWithdraw").value(false)).andExpect(jsonPath("$.message").value(containsString("담당 중인 도움 대상 1명")));
        mvc.perform(post("/api/me/withdraw").header("Authorization", tok(mgr2)).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"1234\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WITHDRAW_BLOCKED"));
        mvc.perform(post("/api/me/withdraw").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk());
        assertEquals(UserStatus.WITHDRAWN, users.findById(mgr.id).orElseThrow().status);

        // 운영자: 마지막 한 명은 탈퇴할 수 없다
        users.findByRole(Role.ADMIN).forEach(x -> { if (!x.id.equals(admin.id)) x.status = UserStatus.SUSPENDED; });
        mvc.perform(get("/api/me/withdraw-check").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canWithdraw").value(false));
        user("r41admin2", Role.ADMIN);
        mvc.perform(get("/api/me/withdraw-check").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canWithdraw").value(true));
    }
}
