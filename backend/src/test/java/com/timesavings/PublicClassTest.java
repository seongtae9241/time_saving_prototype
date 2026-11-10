package com.timesavings;

import com.timesavings.ai.PublicClassAi;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 기관 클래스 추천: AI 검색 결과 검증(주소 없음 버림·열지 못하면 숨김), 공공데이터 CSV(UTF-8·CP949·열 이름 변형·끝난 강좌 제외),
 * 추천 점수 순서, 하루 캐시, 권한. 외부 네트워크·AI 를 부르지 않는다(LinkPreviewService 는 가짜).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pctest;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PublicClassTest {
    @Autowired MockMvc mvc;
    @Autowired PublicClassService svc;
    @Autowired PublicClassRepo repo;
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired OrganizationRepo orgs;
    @Autowired OrgEventRepo orgEvents;
    @Autowired AuthService auth;
    @MockitoBean LinkPreviewService preview;

    final LocalDate today = LocalDate.now();
    static int seq = 0;

    User user(String area, String age, Role role) {
        User u = new User();
        u.loginId = "pc" + (++seq) + System.nanoTime(); u.name = "이웃" + seq; u.passwordHash = auth.hash("1234"); u.role = role; u.area = area; u.ageGroup = age;
        return users.save(u);
    }

    String token(User u) { return "Bearer " + auth.issueToken(u, false); }

    PublicClass pc(String title, String region, int inDays, String target, boolean verified) {
        PublicClass c = new PublicClass();
        c.source = "PUBLIC_DATA"; c.title = title; c.orgName = "테스트구 평생학습관"; c.region = region; c.place = region + " 센터";
        c.startDate = today.plusDays(inDays); c.endDate = today.plusDays(inDays + 10); c.target = target;
        c.category = PublicClassService.classify(title); c.verified = verified;
        return repo.save(c);
    }

    @BeforeEach
    void clean() { repo.deleteAll(); orgEvents.deleteAll(); }

    // ---------------- ① AI 검색 결과 저장·검증 ----------------
    @Test
    void 주소_없는_후보는_버리고_열지_못한_주소는_숨긴다() {
        when(preview.fetch(eq("https://ok.example.org/class"))).thenReturn(new LinkPreviewService.Preview("https://ok.example.org/class", "스마트폰 교실 안내", null, null, null));
        when(preview.fetch(eq("https://bad.example.org/x"))).thenThrow(ApiException.bad("PREVIEW_FAILED", "페이지를 열 수 없어요 (404)"));
        when(preview.fetch(eq("https://notitle.example.org/x"))).thenReturn(new LinkPreviewService.Preview("https://notitle.example.org/x", null, null, null, null));
        String d = today.plusDays(5).toString();
        var res = new PublicClassAi.SearchResult(List.of(
                new PublicClassAi.Candidate("스마트폰 교실", "테스트구 평생학습관", "테스트동 주민센터", d, "", "목 10:00", "50세 이상", "0", "https://ok.example.org/class", "휴대폰 기초", "{}"),
                new PublicClassAi.Candidate("주소 없는 강좌", "어딘가", "", d, "", "", "", "", "", "", "{}"),
                new PublicClassAi.Candidate("지어낸 주소 강좌", "어딘가", "", d, "", "", "", "", "https://bad.example.org/x", "", "{}"),
                new PublicClassAi.Candidate("제목 없는 페이지", "어딘가", "", d, "", "", "", "", "https://notitle.example.org/x", "", "{}")),
                List.of("https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"));
        Map<String, Object> r = svc.saveSearchResult("테스트구 테스트동", "테스트구 테스트동", res);
        assertEquals(1, r.get("dropped"));
        assertEquals(1, r.get("verified"));
        assertEquals(2, r.get("hidden"));
        List<PublicClass> saved = repo.findBySourceAndSearchKey("AI_SEARCH", "테스트구 테스트동");
        assertEquals(3, saved.size());
        PublicClass ok = saved.stream().filter(c -> c.verified).findFirst().orElseThrow();
        assertEquals("스마트폰 교실 안내", ok.pageTitle);
        assertEquals("무료", ok.feeText);
        assertEquals("디지털", ok.category);
        assertTrue(PublicClassService.visible(ok, today));
        assertTrue(saved.stream().filter(c -> !c.verified).allMatch(c -> c.hidden && !PublicClassService.visible(c, today)));
        // 다시 저장하면 같은 지역의 예전 AI 항목은 바뀐다
        svc.saveSearchResult("테스트구 테스트동", "테스트구 테스트동", new PublicClassAi.SearchResult(List.of(), List.of()));
        assertTrue(repo.findBySourceAndSearchKey("AI_SEARCH", "테스트구 테스트동").isEmpty());
    }

    @Test
    void 키가_없으면_검색은_꺼짐() throws Exception {
        Map<String, Object> r = svc.search("행복동", null, null, true);
        assertEquals(false, r.get("searchAvailable"));
        assertEquals(false, r.get("searched"));
        User u = user("행복동", "70대", Role.USER);
        mvc.perform(get("/api/public-classes/status").header("Authorization", token(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.searchAvailable").value(false)).andExpect(jsonPath("$.counts.visible").exists());
        mvc.perform(post("/api/public-classes/search").header("Authorization", token(u)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.searchAvailable").value(false));
    }

    // ---------------- ② CSV ----------------
    static final String HEADER = "강좌명,강사명,교육시작일자,교육종료일자,교육시작시각,교육종료시각,교육대상,교육방법,수강정원,교육비,교육장소,교육장도로명주소,운영기관명,접수방법,홈페이지주소,위도,경도,데이터기준일자";

    String row(String title, LocalDate s, LocalDate e, String fee) {
        return title + ",김강사," + s + "," + e + ",10:00,12:00,50세 이상 구민,오프라인,20," + fee + ",독산동 주민센터,\"서울특별시 금천구 독산동 123, 2층\",금천구 평생학습관,방문 접수,https://example.org/a,37.46,126.90," + today;
    }

    @Test
    void CSV_UTF8_BOM과_끝난_강좌_제외() {
        String csv = "﻿" + HEADER + "\n" + row("스마트폰 사진 정리", today.plusDays(3), today.plusDays(30), "0") + "\n"
                + row("지난 요가 교실", today.minusDays(40), today.minusDays(2), "10000") + "\n"
                + ",,,,,,,,,,,,,,,,," + "\n";
        Map<String, Object> r = svc.importCsv(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals("UTF-8", r.get("encoding"));
        assertEquals(1, r.get("imported"));
        assertEquals(1, r.get("skippedEnded"));
        assertEquals(1, r.get("skippedInvalid"));
        assertTrue(((List<?>) r.get("unmapped")).contains("데이터기준일자"));
        PublicClass c = repo.findBySource("PUBLIC_DATA").get(0);
        assertEquals("금천구 독산동", c.region);
        assertEquals("무료", c.feeText);
        assertEquals("20명", c.capacityText);
        assertEquals("10:00~12:00", c.scheduleText);
        assertEquals("디지털", c.category);
        assertTrue(c.verified);
        assertTrue(c.rawJson.contains("데이터기준일자"));
        assertEquals(37.46, c.lat);
        // 같은 파일을 다시 올리면 새로 넣지 않고 고친다
        Map<String, Object> again = svc.importCsv(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, again.get("imported"));
        assertEquals(1, again.get("updated"));
    }

    @Test
    void CSV_CP949와_비슷한_열_이름() {
        String csv = "강좌 명,교육 시작일자(YYYY-MM-DD),교육종료일자,교육 대상,수강료,강좌장소,소재지도로명주소,운영기관,신청방법,홈페이지\r\n"
                + "웃음 건강 체조," + today.plusDays(2).toString().replace("-", ".") + "," + today.plusDays(20).toString().replace("-", "") + ",65세 이상,\"12,000\",복지관 강당,경기도 행복시 행복구 사랑동 5,행복복지관,전화,www.example.org/b\r\n";
        Map<String, Object> r = svc.importCsv(csv.getBytes(Charset.forName("MS949")));
        assertEquals("CP949", r.get("encoding"));
        assertEquals(1, r.get("imported"));
        @SuppressWarnings("unchecked") Map<String, String> mapped = (Map<String, String>) r.get("mapped");
        assertEquals("강좌 명", mapped.get("title"));
        assertEquals("교육 시작일자(YYYY-MM-DD)", mapped.get("startDate"));
        assertEquals("수강료", mapped.get("fee"));
        assertEquals("운영기관", mapped.get("orgName"));
        PublicClass c = repo.findBySource("PUBLIC_DATA").get(0);
        assertEquals("웃음 건강 체조", c.title);
        assertEquals(today.plusDays(2), c.startDate);
        assertEquals(today.plusDays(20), c.endDate);
        assertEquals("12,000원", c.feeText);
        assertEquals("행복시 행복구 사랑동", c.region);
        assertEquals("https://www.example.org/b", c.applyUrl);
        assertEquals("정서·건강", c.category);
        assertThrows(ApiException.class, () -> svc.importCsv("이름,나이\n홍길동,70\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 운영자만_CSV를_올린다() throws Exception {
        User admin = user("행복동", "40대", Role.ADMIN), u = user("행복동", "70대", Role.USER);
        MockMultipartFile f = new MockMultipartFile("file", "lifelong.csv", "text/csv",
                (HEADER + "\n" + row("키오스크 연습", today.plusDays(4), today.plusDays(4), "0")).getBytes(Charset.forName("MS949")));
        mvc.perform(multipart("/api/admin/public-classes/import").file(f).header("Authorization", token(u))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/public-classes/import").file(f).header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.encoding").value("CP949"));
        mvc.perform(get("/api/admin/public-classes").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("키오스크 연습"));
    }

    // ---------------- 추천 ----------------
    @Test
    void 추천_점수_순서() {
        PublicClass farLater = pc("요가 교실", "다른구 먼동", 25, "누구나", true);
        PublicClass nearPhone = pc("스마트폰 기초", "테스트구 행복동", 3, "60세 이상", true);
        PublicClass nearCook = pc("반찬 요리", "테스트구 행복동", 3, "누구나", true);
        PublicClass kids = pc("어린이 코딩", "테스트구 행복동", 2, "초등학생", true);
        PublicClass unverified = pc("확인 안 된 스마트폰", "테스트구 행복동", 1, "60세 이상", false);
        PublicClass ended = pc("끝난 스마트폰", "테스트구 행복동", -30, "60세 이상", true);
        ended.endDate = today.minusDays(1); repo.save(ended);
        var p = new PublicClassService.Profile("행복동", "70대", Set.of());
        List<PublicClass> top = PublicClassService.rank(repo.findAll(), p, today, 10);
        List<Long> ids = top.stream().map(c -> c.id).toList();
        assertEquals(nearPhone.id, ids.get(0));
        assertEquals(nearCook.id, ids.get(1));
        assertTrue(ids.indexOf(kids.id) > ids.indexOf(farLater.id), "아동 대상은 70대에게 뒤로");
        assertFalse(ids.contains(unverified.id));
        assertFalse(ids.contains(ended.id));
        // 관심 분야(생활)가 있으면 생활 강좌가 정서·건강 강좌보다 올라온다
        PublicClass song = pc("노래 교실", "먼구 먼동", 10, "누구나", true);
        PublicClass money = pc("금융 사기 예방", "먼구 먼동", 10, "누구나", true);
        List<Long> plain = PublicClassService.rank(repo.findAll(), p, today, 10).stream().map(c -> c.id).toList();
        assertTrue(plain.indexOf(song.id) < plain.indexOf(money.id));
        var p2 = new PublicClassService.Profile("행복동", "70대", Set.of("생활"));
        List<Long> withInterest = PublicClassService.rank(repo.findAll(), p2, today, 10).stream().map(c -> c.id).toList();
        assertTrue(withInterest.indexOf(money.id) < withInterest.indexOf(song.id));
    }

    @Test
    void 추천은_하루_캐시하고_데이터가_바뀌면_새로_만든다() throws Exception {
        User u = user("행복동", "70대", Role.USER);
        pc("스마트폰 기초", "테스트구 행복동", 3, "60세 이상", true);
        mvc.perform(get("/api/public-classes/recommend").header("Authorization", token(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false)).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.items[0].title").value("스마트폰 기초"))
                .andExpect(jsonPath("$.items[0].reason").value(org.hamcrest.Matchers.startsWith("같은 동네 · 휴대폰 사용법")));
        mvc.perform(get("/api/public-classes/recommend").header("Authorization", token(u)))
                .andExpect(jsonPath("$.cached").value(true));
        svc.importCsv((HEADER + "\n" + row("웃음 체조", today.plusDays(1), today.plusDays(9), "0")).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/public-classes/recommend").header("Authorization", token(u)))
                .andExpect(jsonPath("$.cached").value(false)).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 기관_소식도_추천에_들어가고_마감하면_빠진다() {
        Organization o = new Organization(); o.name = "행복동 주민센터"; o.status = OrgStatus.APPROVED; orgs.save(o);
        OrgEvent e = new OrgEvent(); e.orgId = o.id; e.title = "휴대폰 영상통화 교실"; e.eventDate = today.plusDays(2); e.startHour = 14.0; e.place = "행복동 주민센터 1층";
        orgEvents.save(e);
        User u = user("행복동", "70대", Role.USER);
        Map<String, Object> r = svc.recommend(u);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertEquals("ORG", items.get(0).get("source"));
        assertEquals(e.id, items.get(0).get("orgEventId"));
        e.status = "CLOSED"; orgEvents.save(e);
        @SuppressWarnings("unchecked") List<Map<String, Object>> after = (List<Map<String, Object>>) svc.recommend(u).get("items");
        assertTrue(after.isEmpty());
    }

    @Test
    void 상세_요약과_동행_문장_그리고_담당자_대상_추천() throws Exception {
        PublicClass c = pc("스마트폰 기초", "테스트구 행복동", 3, "60세 이상", true);
        c.scheduleText = "화·목 14:00~16:00"; c.feeText = "무료"; repo.save(c);
        User u = user("행복동", "70대", Role.USER);
        mvc.perform(get("/api/public-classes/" + c.id).header("Authorization", token(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.easySummary").value(org.hamcrest.Matchers.containsString("수강료는 무료")))
                .andExpect(jsonPath("$.companionText").value(org.hamcrest.Matchers.containsString("오후 2시부터 오후 4시까지 테스트구 행복동 센터에서 하는 '스마트폰 기초' 강좌에 같이 가 주세요")))
                .andExpect(jsonPath("$.timeNote").exists());
        PublicClass hidden = pc("숨긴 강좌", "테스트구 행복동", 3, "", true);
        hidden.hidden = true; repo.save(hidden);
        mvc.perform(get("/api/public-classes/" + hidden.id).header("Authorization", token(u))).andExpect(status().isNotFound());

        Organization o = new Organization(); o.name = "행복동 주민센터"; o.status = OrgStatus.APPROVED; orgs.save(o);
        User mgr = user("행복동", "40대", Role.ORG); mgr.orgId = o.id; users.save(mgr);
        User other = user("행복동", "40대", Role.ORG); other.orgId = o.id; users.save(other);
        Elder el = new Elder(); el.orgId = o.id; el.managerId = mgr.id; el.alias = "김할머니"; el.dong = "행복동"; el.ageGroup = "80대"; el.needs = new ArrayList<>(List.of("디지털 도움"));
        elders.save(el);
        mvc.perform(get("/api/org/elders/" + el.id + "/public-classes").header("Authorization", token(mgr)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("스마트폰 기초"))
                .andExpect(jsonPath("$.basis.interests[0]").value("디지털"));
        mvc.perform(get("/api/org/elders/" + el.id + "/public-classes").header("Authorization", token(other))).andExpect(status().isForbidden());
    }

    @Test
    void 운영자_숨기기_삭제() throws Exception {
        User admin = user("행복동", "40대", Role.ADMIN);
        PublicClass c = pc("스마트폰 기초", "테스트구 행복동", 3, "", true);
        mvc.perform(post("/api/admin/public-classes/" + c.id + "/hide").header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        assertTrue(repo.findById(c.id).orElseThrow().hidden);
        mvc.perform(delete("/api/admin/public-classes/" + c.id).header("Authorization", token(admin))).andExpect(status().isOk());
        assertTrue(repo.findById(c.id).isEmpty());
    }

    @Test
    void 날짜_표시() {
        LocalDate mon = LocalDate.of(2026, 11, 9); // 월요일
        assertEquals("이번 주 목요일", PublicClassService.whenLabel(mon.plusDays(3), null, mon));
        assertEquals("다음 주 화요일", PublicClassService.whenLabel(mon.plusDays(8), null, mon));
        assertEquals("내일 (화요일)", PublicClassService.whenLabel(mon.plusDays(1), null, mon));
        assertEquals("11월 30일 (월)", PublicClassService.whenLabel(mon.plusDays(21), null, mon));
        assertTrue(PublicClassService.whenLabel(mon.minusDays(3), mon.plusDays(3), mon).startsWith("진행 중"));
        assertEquals("금천구 독산동", PublicClassCsv.region("서울특별시 금천구 독산동 291-1"));
        assertEquals("금천구", PublicClassCsv.region("서울 금천구 시흥대로73길 70"));
        assertEquals("수원시 장안구 정자동", PublicClassCsv.region("경기도 수원시 장안구 정자동 1"));
    }
}
