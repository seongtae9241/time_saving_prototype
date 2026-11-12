package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.Role;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** 동네 기관 소식(B-64·C-15): 담당자가 올리는 교실·모임·행사 소식. 제목·일시·장소·기관 페이지 링크로 알리고, 신청은 링크로만. */
@Service
@Transactional
public class EventService {
    private final OrgEventRepo events;
    private final OrganizationRepo orgs;
    private final LinkPreviewService preview;

    public EventService(OrgEventRepo events, OrganizationRepo orgs, LinkPreviewService preview) {
        this.events = events; this.orgs = orgs; this.preview = preview;
    }

    public record EventCmd(String title, String content, LocalDate date, Double startHour, String place,
                           String url, String ogTitle, String ogImage, String ogDesc, Integer capacity) {
        /** 정원 없이 만드는 예전 호출용 */
        public EventCmd(String title, String content, LocalDate date, Double startHour, String place,
                        String url, String ogTitle, String ogImage, String ogDesc) {
            this(title, content, date, startHour, place, url, ogTitle, ogImage, ogDesc, null);
        }
    }

    public OrgEvent get(Long id) { return events.findById(id).orElseThrow(() -> ApiException.notFound("소식을 찾을 수 없어요")); }

    private boolean owner(OrgEvent e, User u) {
        return u != null && u.role == Role.ORG && u.orgId != null && u.orgId.equals(e.orgId);
    }

    /**
     * B-64·C-15: 소식은 제목·일시·장소·기관 페이지 링크만 알린다. 자세한 내용과 신청은 링크로 간 기관 페이지에서 한다
     * (앱 안 신청·정원·신청자 목록은 41판에서 없앴다. applyType 은 예전 화면을 위해 항상 "LINK" 로 준다).
     */
    public Map<String, Object> view(OrgEvent e, User viewer) {
        String orgName = e.orgId == null ? null : orgs.findById(e.orgId).map(o -> o.name).orElse(null);
        Map<String, Object> m = Views.map("id", e.id, "orgId", e.orgId, "orgName", orgName, "title", e.title, "content", e.content,
                "date", e.eventDate == null ? null : e.eventDate.toString(), "startHour", e.startHour, "place", e.place,
                "applyType", "LINK", "url", e.url,
                // 기능 O-09: 정원은 안내용 숫자다(0 = 제한 없음 → null). 앱 안 신청·남은 자리 계산은 없다
                "capacity", e.capacity > 0 ? e.capacity : null,
                "preview", e.ogTitle == null && e.ogImage == null ? null : Views.map("title", e.ogTitle, "image", e.ogImage, "description", e.ogDesc),
                "status", e.status, "owner", owner(e, viewer), "createdAt", e.createdAt == null ? null : e.createdAt.toString());
        String area = areaOf(e, orgName);
        m.put("area", area);
        m.put("near", viewer != null && viewer.area != null && viewer.area.equals(area));
        m.put("distanceKm", area == null || viewer == null ? null : Views.distanceKm(viewer.area, area, e.id == null ? 0 : e.id));
        return m;
    }

    private static final java.util.regex.Pattern DONG = java.util.regex.Pattern.compile("([가-힣]{1,6}동)(?![가-힣])");

    /** 행사 지역(동): 기관 이름 → 기관 주소 → 행사 장소 순으로 처음 나오는 "○○동". 없으면 null(지역 미정). */
    String areaOf(OrgEvent e, String orgName) {
        String addr = e.orgId == null ? null : orgs.findById(e.orgId).map(o -> o.address).orElse(null);
        for (String t : new String[]{orgName, addr, e.place}) {
            if (t == null) continue;
            java.util.regex.Matcher m = DONG.matcher(t);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** 근처 지역 기준 목록: area 를 주면 그 동만, 아니면 내 동네 → 가까운 동 → 날짜 순. */
    public List<Map<String, Object>> list(User viewer, String area) {
        List<Map<String, Object>> all = list(viewer);
        if (area != null && !area.isBlank()) return all.stream().filter(m -> area.equals(m.get("area"))).toList();
        List<Map<String, Object>> out = new ArrayList<>(all);
        out.sort(Comparator.<Map<String, Object>>comparingInt(m -> Boolean.TRUE.equals(m.get("near")) ? 0 : 1)
                .thenComparingDouble(m -> m.get("distanceKm") == null ? 99 : ((Number) m.get("distanceKm")).doubleValue()));
        return out; // 정렬이 안정적이라 같은 거리 안에서는 날짜 순이 유지된다
    }

    /** 사용자 목록: 열린 소식, 날짜가 지나지 않은 것 먼저(날짜 순). */
    public List<Map<String, Object>> list(User viewer) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        events.findAll().stream()
                .filter(e -> "OPEN".equals(e.status) && (e.eventDate == null || !e.eventDate.isBefore(today)))
                .sorted(Comparator.comparing((OrgEvent e) -> e.eventDate == null ? LocalDate.MAX : e.eventDate).thenComparing(e -> -e.id))
                .forEach(e -> out.add(view(e, viewer)));
        return out;
    }

    public List<Map<String, Object>> orgList(User me) {
        List<Map<String, Object>> out = new ArrayList<>();
        events.findByOrgId(me.orgId).stream().sorted(Comparator.comparing((OrgEvent e) -> -e.id)).forEach(e -> out.add(view(e, me)));
        return out;
    }

    public OrgEvent create(User me, EventCmd c) {
        if (me.role != Role.ORG || me.orgId == null) throw ApiException.forbidden("기관 담당자만 동네 소식을 올릴 수 있어요");
        if (c.title() == null || c.title().isBlank()) throw ApiException.bad("TITLE_REQUIRED", "제목을 적어 주세요");
        if (c.title().length() > 100) throw ApiException.bad("TITLE_TOO_LONG", "제목은 100자 안으로 적어 주세요");
        if (c.content() != null && c.content().length() > 2000) throw ApiException.bad("CONTENT_TOO_LONG", "내용은 2000자 안으로 적어 주세요");
        if (c.date() != null && c.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
        if (c.startHour() != null && (c.startHour() < 0 || c.startHour() >= 24 || !Rules.halfStep(c.startHour())))
            throw ApiException.bad("INVALID_TIME", "시각은 30분 단위로 정해 주세요");
        if (c.capacity() != null && (c.capacity() < 0 || c.capacity() > 9999)) throw ApiException.bad("INVALID_CAPACITY", "정원은 1~9999명으로 적어 주세요 (제한이 없으면 비워 두세요)");
        String url = c.url() == null || c.url().isBlank() ? null : c.url().trim();
        // B-64: 자세한 내용과 신청은 기관 페이지에서 → 링크가 꼭 있어야 한다
        if (url == null) throw ApiException.bad("URL_REQUIRED", "기관 페이지 링크를 넣어 주세요. 자세한 내용과 신청은 그 페이지에서 해요");
        LinkPreviewService.checkStatic(url); // 저장 전에 주소 형식·내부 주소 검사(가져올 때 DNS까지 다시 검사)
        OrgEvent e = new OrgEvent();
        e.orgId = me.orgId;
        e.createdBy = me.id;
        e.title = c.title().trim();
        e.content = c.content();
        e.eventDate = c.date();
        e.startHour = c.startHour();
        e.place = c.place() == null ? null : c.place().trim();
        e.capacity = c.capacity() == null ? 0 : c.capacity(); // 기능 O-09: 정원(표시용). 0 = 제한 없음
        e.applyType = "LINK";
        e.url = url;
        {
            // 화면에서 미리 받아 온 미리보기가 있으면 그대로 쓰고, 없으면 한 번 가져온다(실패해도 글은 올린다)
            if (c.ogTitle() != null || c.ogImage() != null) {
                e.ogTitle = clip(c.ogTitle(), 200);
                e.ogImage = c.ogImage() != null && c.ogImage().matches("^https?://.*") ? clip(c.ogImage(), 500) : null;
                e.ogDesc = clip(c.ogDesc(), 300);
            } else {
                try {
                    LinkPreviewService.Preview p = preview.fetch(url);
                    e.ogTitle = p.title(); e.ogImage = p.image(); e.ogDesc = p.description();
                } catch (ApiException ignored) { /* 미리보기 없이 올린다 */ }
            }
        }
        return events.save(e);
    }

    private static String clip(String s, int n) { return s == null || s.isBlank() ? null : s.length() > n ? s.substring(0, n) : s; }

    public void close(User me, Long id) {
        OrgEvent e = get(id);
        if (!owner(e, me)) throw ApiException.forbidden("우리 기관 소식만 내릴 수 있어요");
        e.status = "CLOSED";
    }
}
