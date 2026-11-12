package com.timesavings.web;

import com.timesavings.ai.NearbyServiceAi;
import com.timesavings.ai.TidyAi;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Elder;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.service.EventService;
import com.timesavings.service.LinkPreviewService;
import com.timesavings.service.PublicClassService;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 2026-11-09 회의 반영: 동네 소식(기관 행사 홍보, 링크 미리보기), 위치 기반 공공 서비스(U8), 대리 공고 AI 정리(O2).
 */
@RestController
@RequestMapping("/api")
public class NewsController {
    private final EventService events;
    private final LinkPreviewService preview;
    private final NearbyServiceAi nearby;
    private final TidyAi tidy;
    private final ElderRepo elders;
    /** B-64 AI가 찾은 소식을 같은 목록에 넣기 위해(생성자 인자를 늘리지 않으려고 필드 주입) */
    @org.springframework.beans.factory.annotation.Autowired private PublicClassService classes;
    /** 기능 U-30 */
    @org.springframework.beans.factory.annotation.Autowired private com.timesavings.ai.VolunteerGuideAi volunteerGuide;

    public NewsController(EventService events, LinkPreviewService preview, NearbyServiceAi nearby, TidyAi tidy, ElderRepo elders) {
        this.events = events; this.preview = preview; this.nearby = nearby; this.tidy = tidy; this.elders = elders;
    }

    public record EventBody(String title, String content, LocalDate date, Double startHour, String place,
                            String url, String ogTitle, String ogImage, String ogDesc, Integer capacity) {}
    public record UrlBody(String url) {}
    public record NearbyBody(String question, Double lat, Double lng, String area) {}
    public record TidyBody(String text, Long elderId) {}

    // ---------------- 동네 소식 ----------------
    @GetMapping("/events")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@CurrentUser User u, @RequestParam(required = false) String area,
                                          @RequestParam(required = false) Boolean nearby) {
        // 기능 U-31: nearby=true 면 내 주소(사는 동네) 근처 기관 행사만(홈 섹션)
        return Boolean.TRUE.equals(nearby) ? events.nearby(u) : events.list(u, area);
    }

    @GetMapping("/events/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) { return events.view(events.get(id), u); }

    @PostMapping("/events")
    @Transactional
    public Map<String, Object> create(@CurrentUser User u, @RequestBody EventBody b) {
        return events.view(events.create(u, new EventService.EventCmd(b.title(), b.content(), b.date(), b.startHour(), b.place(),
                b.url(), b.ogTitle(), b.ogImage(), b.ogDesc(), b.capacity())), u);
    }

    /**
     * B-64 동네 기관 소식 한 목록: 담당자가 올린 소식(ORG)과 AI가 찾은 소식(AI, 출처 링크를 서버가 열어 확인한 것만)·공공데이터 강좌(PUBLIC)를 함께 준다.
     * 항목마다 제목·일시·장소·기관 페이지 링크. 신청은 링크로 간 기관 페이지에서 한다. area 를 주면 그 동만.
     */
    @GetMapping("/news")
    @Transactional
    public Map<String, Object> news(@CurrentUser User u, @RequestParam(required = false) String area) {
        String a = area == null || area.isBlank() ? null : area.trim();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> e : events.list(u, a)) {
            Map<String, Object> m = Views.map("key", "ORG-" + e.get("id"), "kind", "ORG", "id", e.get("id"), "title", e.get("title"),
                    "orgName", e.get("orgName"), "date", e.get("date"), "startHour", e.get("startHour"), "when", null, "place", e.get("place"),
                    "area", e.get("area"), "near", e.get("near"), "url", e.get("url"), "capacity", e.get("capacity"), "image", e.get("preview") instanceof Map<?, ?> p ? p.get("image") : null,
                    "aiFound", false, "notice", null);
            items.add(m);
        }
        items.addAll(classes.newsItems(a, u.area));
        // 내 동네 먼저, 그 안에서는 날짜 빠른 순(날짜 없는 것은 뒤)
        items.sort(Comparator.<Map<String, Object>>comparingInt(m -> Boolean.TRUE.equals(m.get("near")) ? 0 : 1)
                .thenComparing(m -> m.get("date") == null ? "9999" : String.valueOf(m.get("date"))));
        return Views.map("items", items, "area", a, "searchAvailable", classes.searchAvailable(), "aiNotice", PublicClassService.AI_NOTICE);
    }

    @PostMapping("/events/{id}/close")
    @Transactional
    public Map<String, Object> close(@CurrentUser User u, @PathVariable Long id) { events.close(u, id); return events.view(events.get(id), u); }

    @GetMapping("/org/events")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orgList(@CurrentUser User u) { return events.orgList(u); }

    /** URL 미리보기(SSRF 방지는 LinkPreviewService). 저장하지 않는다. */
    @PostMapping("/link-preview")
    public Map<String, Object> linkPreview(@CurrentUser User u, @RequestBody UrlBody b) {
        if (u.role == Role.ADMIN) throw ApiException.forbidden("기관 담당자·사용자만 쓸 수 있어요");
        LinkPreviewService.Preview p = preview.fetch(b == null ? null : b.url());
        return Views.map("url", p.url(), "title", p.title(), "image", p.image(), "description", p.description(), "siteName", p.siteName());
    }

    // ---------------- U8 내 위치로 공공 서비스 찾기 ----------------
    @PostMapping("/ai/public-services/nearby")
    public Map<String, Object> nearby(@CurrentUser User u, @RequestBody NearbyBody b) {
        if ((b.lat() != null || b.lng() != null) && !NearbyServiceAi.validCoord(b.lat(), b.lng()))
            throw ApiException.bad("INVALID_LOCATION", "위치 값이 맞지 않아요");
        String area = b.area() != null && !b.area().isBlank() ? b.area() : u.area;
        Map<String, Object> r = nearby.find(b.question(), b.lat(), b.lng(), area, u.ageGroup);
        r.put("volunteer", volunteerGuide.guide(b.question())); // 기능 U-30: 봉사로 가능한 일인지와 봉사 기준
        return r;
    }

    // ---------------- O2 대리 공고 AI로 정리하기 ----------------
    @PostMapping("/ai/tidy-post")
    public Map<String, Object> tidyPost(@CurrentUser User u, @RequestBody TidyBody b) {
        if (b.text() == null || b.text().isBlank()) throw ApiException.bad("TEXT_REQUIRED", "정리할 글을 적어 주세요");
        String caution = null;
        if (b.elderId() != null) {
            Elder e = elders.findById(b.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 정리할 수 있어요");
            caution = e.caution;
        }
        return tidy.tidy(b.text(), caution);
    }
}
