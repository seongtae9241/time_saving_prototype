package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.Role;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** 동네 소식(기관 행사·클래스 홍보, 2026-11-09 회의 2장·O3). */
@Service
@Transactional
public class EventService {
    private final OrgEventRepo events;
    private final EventApplicationRepo apps;
    private final OrganizationRepo orgs;
    private final UserRepo users;
    private final NotificationService noti;
    private final LinkPreviewService preview;

    public EventService(OrgEventRepo events, EventApplicationRepo apps, OrganizationRepo orgs, UserRepo users,
                        NotificationService noti, LinkPreviewService preview) {
        this.events = events; this.apps = apps; this.orgs = orgs; this.users = users; this.noti = noti; this.preview = preview;
    }

    public record EventCmd(String title, String content, LocalDate date, Double startHour, String place, Integer capacity,
                           String applyType, String url, String ogTitle, String ogImage, String ogDesc) {}

    public OrgEvent get(Long id) { return events.findById(id).orElseThrow(() -> ApiException.notFound("소식을 찾을 수 없어요")); }

    private List<EventApplication> active(Long eventId) {
        return apps.findByEventId(eventId).stream().filter(a -> "APPLIED".equals(a.status)).toList();
    }

    private boolean owner(OrgEvent e, User u) {
        return u != null && u.role == Role.ORG && u.orgId != null && u.orgId.equals(e.orgId);
    }

    public Map<String, Object> view(OrgEvent e, User viewer) {
        List<EventApplication> list = active(e.id);
        boolean mine = viewer != null && list.stream().anyMatch(a -> a.userId.equals(viewer.id));
        String orgName = e.orgId == null ? null : orgs.findById(e.orgId).map(o -> o.name).orElse(null);
        Map<String, Object> m = Views.map("id", e.id, "orgId", e.orgId, "orgName", orgName, "title", e.title, "content", e.content,
                "date", e.eventDate == null ? null : e.eventDate.toString(), "startHour", e.startHour, "place", e.place,
                "capacity", e.capacity, "applied", list.size(), "applyType", e.applyType, "url", e.url,
                "preview", e.ogTitle == null && e.ogImage == null ? null : Views.map("title", e.ogTitle, "image", e.ogImage, "description", e.ogDesc),
                "status", e.status, "myApplied", mine, "full", e.capacity > 0 && list.size() >= e.capacity,
                "owner", owner(e, viewer), "createdAt", e.createdAt == null ? null : e.createdAt.toString());
        if (owner(e, viewer)) {
            List<Map<String, Object>> who = new ArrayList<>();
            for (EventApplication a : list)
                users.findById(a.userId).ifPresent(u -> who.add(Views.map("userId", u.id, "name", u.name, "ageGroup", u.ageGroup, "appliedAt", a.createdAt.toString())));
            m.put("applicants", who);
        }
        return m;
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
        int cap = c.capacity() == null ? 0 : c.capacity();
        if (cap < 0 || cap > 500) throw ApiException.bad("INVALID_CAPACITY", "정원은 0~500명으로 정해 주세요 (0은 제한 없음)");
        String type = "LINK".equalsIgnoreCase(c.applyType()) ? "LINK" : "APP";
        String url = c.url() == null || c.url().isBlank() ? null : c.url().trim();
        if (url != null) LinkPreviewService.checkStatic(url); // 저장 전에 주소 형식·내부 주소 검사(가져올 때 DNS까지 다시 검사)
        if ("LINK".equals(type) && url == null) throw ApiException.bad("URL_REQUIRED", "외부 링크로 신청받으려면 링크 주소를 넣어 주세요");
        OrgEvent e = new OrgEvent();
        e.orgId = me.orgId;
        e.createdBy = me.id;
        e.title = c.title().trim();
        e.content = c.content();
        e.eventDate = c.date();
        e.startHour = c.startHour();
        e.place = c.place() == null ? null : c.place().trim();
        e.capacity = cap;
        e.applyType = type;
        e.url = url;
        if (url != null) {
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
        if (!owner(e, me)) throw ApiException.forbidden("우리 기관 소식만 마감할 수 있어요");
        e.status = "CLOSED";
    }

    public void apply(User me, Long id) {
        OrgEvent e = get(id);
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정으로 신청해 주세요");
        if (!"OPEN".equals(e.status)) throw ApiException.conflict("EVENT_CLOSED", "신청이 끝난 소식이에요");
        if (!"APP".equals(e.applyType)) throw ApiException.bad("EXTERNAL_APPLY", "이 행사는 안내된 링크에서 신청해 주세요");
        if (e.eventDate != null && e.eventDate.isBefore(LocalDate.now())) throw ApiException.conflict("EVENT_CLOSED", "지난 행사예요");
        List<EventApplication> list = active(id);
        if (list.stream().anyMatch(a -> a.userId.equals(me.id))) throw ApiException.conflict("DUPLICATE", "이미 신청했어요");
        if (e.capacity > 0 && list.size() >= e.capacity) throw ApiException.conflict("EVENT_FULL", "정원이 다 찼어요");
        EventApplication a = new EventApplication();
        a.eventId = id;
        a.userId = me.id;
        apps.save(a);
        if (e.createdBy != null) noti.send(e.createdBy, "EVENT_APPLY", me.name + " 님이 행사를 신청했어요", e.title + " · " + (list.size() + 1) + (e.capacity > 0 ? "/" + e.capacity : "") + "명", "/news/" + e.id);
    }

    public void cancel(User me, Long id) {
        EventApplication a = active(id).stream().filter(x -> x.userId.equals(me.id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("신청 내역이 없어요"));
        a.status = "CANCELLED";
    }
}
