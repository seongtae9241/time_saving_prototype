package com.timesavings.service;

import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/** 공고(B-02~B-11, B-20, B-21, C-04). */
@Service
@Transactional
public class PostService {
    private final PostRepo posts;
    private final RequestRepo requests;
    private final ElderRepo elders;
    private final InterestAlertRepo alerts;
    private final NotificationService noti;
    private final Views views;

    public PostService(PostRepo posts, RequestRepo requests, ElderRepo elders, InterestAlertRepo alerts, NotificationService noti, Views views) {
        this.posts = posts; this.requests = requests; this.elders = elders; this.alerts = alerts; this.noti = noti; this.views = views;
    }

    public record PostCmd(PostType type, LocalDate date, Double startHour, Double endHour, String area, List<String> categories,
                          String title, String detail, String note, Double estHours, Boolean weekly, Integer weeks,
                          Long elderId, Level difficulty, String difficultyReason) {
        /** 도움 요청 공고에 활동 시간이 없으면 시간 범위(최대 4시간)로 채운다. */
        PostCmd withDefaultHours() {
            if (type != PostType.NEED || estHours != null || startHour == null || endHour == null || endHour <= startHour) return this;
            return new PostCmd(type, date, startHour, endHour, area, categories, title, detail, note,
                    Math.min(4.0, endHour - startHour), weekly, weeks, elderId, difficulty, difficultyReason);
        }
    }

    public Post get(Long id) { return posts.findById(id).orElseThrow(() -> ApiException.notFound("공고를 찾을 수 없어요")); }

    public boolean canEdit(Post p, User u) {
        if (u.id.equals(p.ownerId)) return true;
        return p.elderId != null && views.isManagerOf(u, p.elderId);
    }

    private void validate(PostCmd c, User me, Elder elder) {
        if (c.type() == null) throw ApiException.bad("INVALID_TYPE", "공고 종류를 골라 주세요");
        if (c.date() == null || c.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
        if (c.startHour() == null || c.endHour() == null || !Rules.halfStep(c.startHour()) || !Rules.halfStep(c.endHour())
                || c.startHour() < 0 || c.endHour() > 24 || c.endHour() <= c.startHour())
            throw ApiException.bad("INVALID_TIME", "시간 범위를 30분 단위로 정해 주세요");
        if (c.categories() == null || c.categories().isEmpty()) throw ApiException.bad("INVALID_CATEGORY", "카테고리를 하나 이상 골라 주세요");
        for (String cat : c.categories()) Rules.categoryLevel(cat);
        if (c.area() == null || c.area().isBlank()) throw ApiException.bad("AREA_REQUIRED", "지역(동)을 적어 주세요");
        if (c.type() == PostType.NEED) {
            if (c.estHours() == null) throw ApiException.bad("INVALID_HOURS", "필요한 활동 시간을 정해 주세요");
            Rules.checkHours(c.startHour(), c.estHours());
            if (c.estHours() > c.endHour() - c.startHour() + 1e-9) throw ApiException.bad("INVALID_HOURS", "활동 시간이 시간 범위보다 길어요");
        }
        if (me.role == Role.ORG) {
            if (elder == null) throw ApiException.bad("ELDER_REQUIRED", "어느 도움 대상의 공고인지 골라 주세요");
            if (c.type() != PostType.NEED) throw ApiException.bad("INVALID_TYPE", "담당자는 도움 요청 공고만 대신 올릴 수 있어요 (C-04)");
        } else if (me.role != Role.USER) throw ApiException.forbidden("공고를 올릴 수 없는 계정이에요");
        if (c.type() == PostType.OFFER && me.idStatus != VerifyStatus.APPROVED)
            throw ApiException.bad("ID_NOT_VERIFIED", "신분증 인증이 끝나야 봉사 가능 공고를 올릴 수 있어요 (B-01)");
        String text = String.join(" ", Objects.toString(c.title(), ""), Objects.toString(c.detail(), ""), Objects.toString(c.note(), ""));
        RuleEngine.FilterResult f = RuleEngine.filter(text);
        if (f.level() == FilterLevel.BLOCK) throw ApiException.bad("FORBIDDEN_REQUEST", f.reason());
    }

    private void applyDifficulty(Post p, PostCmd c) {
        Level base = Rules.maxLevel(p.categories);
        String text = String.join(" ", Objects.toString(p.title, ""), Objects.toString(p.detail, ""), Objects.toString(p.note, ""));
        RuleEngine.DifficultyResult d = RuleEngine.difficulty(text, p.categories);
        Level lv = d.level();
        String reason = d.reason();
        if (c.difficulty() != null) { // 화면에서 AI 제안을 받아 확정한 등급(카테고리 기본 등급보다 낮출 수 없음)
            lv = Rules.max(base, c.difficulty());
            if (c.difficultyReason() != null && !c.difficultyReason().isBlank()) reason = c.difficultyReason();
        }
        p.difficulty = lv;
        p.difficultyReason = reason;
        p.weight = Rules.weight(lv);
    }

    public List<Post> create(User me, PostCmd c) {
        Elder elder = null;
        if (c.elderId() != null) {
            elder = elders.findById(c.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!views.isManagerOf(me, elder.id)) throw ApiException.forbidden("내가 담당하는 대상만 대신 올릴 수 있어요");
            if (elder.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        }
        c = c.withDefaultHours();
        validate(c, me, elder);
        int weeks = Boolean.TRUE.equals(c.weekly()) ? Math.max(1, Math.min(12, c.weeks() == null ? 4 : c.weeks())) : 1;
        String series = weeks > 1 ? UUID.randomUUID().toString() : null;
        String[] dayKo = {"월", "화", "수", "목", "금", "토", "일"};
        List<Post> out = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            Post p = new Post();
            p.type = c.type();
            p.ownerId = elder == null ? me.id : null;
            p.elderId = elder == null ? null : elder.id;
            p.createdBy = me.id;
            p.date = c.date().plusWeeks(i);
            p.startHour = c.startHour();
            p.endHour = c.endHour();
            p.area = c.area().trim();
            p.categories = new ArrayList<>(new LinkedHashSet<>(c.categories()));
            p.title = blankTo(c.title(), defaultTitle(c));
            p.detail = c.detail();
            p.note = c.note();
            p.estHours = c.type() == PostType.NEED ? c.estHours() : null;
            p.seriesId = series;
            p.repeatRule = series == null ? null : "매주 " + dayKo[c.date().getDayOfWeek().getValue() - 1] + "요일";
            applyDifficulty(p, c);
            posts.save(p);
            out.add(p);
        }
        notifyInterest(out.get(0), me);
        return out;
    }

    private static String blankTo(String s, String d) { return s == null || s.isBlank() ? d : s.trim(); }

    private static String defaultTitle(PostCmd c) {
        String cat = c.categories().get(0);
        return c.type() == PostType.OFFER ? cat + " 도와드려요" : cat + " 도움이 필요해요";
    }

    /** B-21 관심 조건 알림. */
    private void notifyInterest(Post p, User author) {
        boolean weekend = p.date.getDayOfWeek() == DayOfWeek.SATURDAY || p.date.getDayOfWeek() == DayOfWeek.SUNDAY;
        Set<Long> sent = new HashSet<>();
        for (InterestAlert a : alerts.findByEnabledTrue()) {
            if (a.userId.equals(author.id) || sent.contains(a.userId)) continue;
            if (a.area != null && !a.area.isBlank() && !a.area.equals(p.area)) continue;
            if (!a.categories.isEmpty() && a.categories.stream().noneMatch(p.categories::contains)) continue;
            if ("주말".equals(a.dayType) && !weekend) continue;
            if ("평일".equals(a.dayType) && weekend) continue;
            noti.send(a.userId, "INTEREST", "관심 조건에 맞는 새 공고가 올라왔어요",
                    p.area + " · " + String.join("·", p.categories) + " · " + p.date, "/posts/" + p.id);
            sent.add(a.userId);
        }
    }

    private void requireEditable(Post p, User me) {
        if (!canEdit(p, me)) throw ApiException.forbidden("내 공고만 바꿀 수 있어요");
        if (p.status != PostStatus.OPEN) throw ApiException.conflict("POST_LOCKED", "수락된(또는 마감된) 공고는 바꿀 수 없어요 (B-11)");
        boolean accepted = requests.findByPostId(p.id).stream()
                .anyMatch(r -> r.status != ReqStatus.SENT && r.status != ReqStatus.REJECTED && r.status != ReqStatus.EXPIRED
                        && r.status != ReqStatus.CANCELLED && r.status != ReqStatus.DECLINED);
        if (accepted) throw ApiException.conflict("POST_LOCKED", "수락된 요청이 있어 바꿀 수 없어요 (B-11)");
    }

    public Post update(User me, Long id, PostCmd c) {
        Post p = get(id);
        requireEditable(p, me);
        PostCmd merged = new PostCmd(p.type, c.date() != null ? c.date() : p.date, c.startHour() != null ? c.startHour() : p.startHour,
                c.endHour() != null ? c.endHour() : p.endHour, c.area() != null ? c.area() : p.area,
                c.categories() != null && !c.categories().isEmpty() ? c.categories() : p.categories,
                c.title() != null ? c.title() : p.title, c.detail() != null ? c.detail() : p.detail, c.note() != null ? c.note() : p.note,
                c.estHours() != null ? c.estHours() : p.estHours, false, 1, p.elderId, c.difficulty(), c.difficultyReason());
        Elder elder = p.elderId == null ? null : elders.findById(p.elderId).orElse(null);
        validate(merged, me, elder);
        p.date = merged.date(); p.startHour = merged.startHour(); p.endHour = merged.endHour(); p.area = merged.area();
        p.categories = new ArrayList<>(merged.categories()); p.title = merged.title(); p.detail = merged.detail(); p.note = merged.note();
        p.estHours = p.type == PostType.NEED ? merged.estHours() : null;
        applyDifficulty(p, merged);
        return p;
    }

    public void delete(User me, Long id) {
        Post p = get(id);
        requireEditable(p, me);
        p.status = PostStatus.DELETED;
        for (Request r : requests.findByPostId(p.id)) {
            if (!r.status.pending()) continue;
            r.status = ReqStatus.CANCELLED;
            r.rejectReason = "공고가 삭제됨";
            r.deadlineAt = null;
            noti.send(r.senderId, "POST_DELETED", "공고가 삭제되어 요청이 취소됐어요", p.title, "/requests/" + r.id);
        }
    }

    /** B-11 공고 날짜가 지나면 자동 마감. */
    public int closePast(LocalDate today) {
        int n = 0;
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (!p.date.isBefore(today)) continue;
            p.status = PostStatus.CLOSED;
            n++;
        }
        return n;
    }

    public List<Map<String, Object>> list(User viewer, PostType type, String area, LocalDate date, String cat, String sort) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (type != null && p.type != type) continue;
            if (area != null && !area.isBlank() && !area.equals(p.area)) continue;
            if (date != null && !date.equals(p.date)) continue;
            if (cat != null && !cat.isBlank() && !p.categories.contains(cat)) continue;
            if (p.date.isBefore(LocalDate.now())) continue;
            out.add(views.postCard(p, viewer));
        }
        Comparator<Map<String, Object>> rating = Comparator.comparingDouble(m -> {
            @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) m.get("owner");
            return o == null ? 0.0 : -((Number) o.get("ratingAvg")).doubleValue();
        });
        if ("new".equals(sort)) {
            out.sort(Comparator.<Map<String, Object>, java.time.LocalDateTime>comparing(m -> (java.time.LocalDateTime) m.get("createdAt"), Comparator.reverseOrder()).thenComparing(rating));
        } else { // near: 가까운 순, 같은 거리면 평가 높은 순(B-40)
            out.sort(Comparator.<Map<String, Object>>comparingDouble(m -> ((Number) m.get("distanceKm")).doubleValue()).thenComparing(rating));
        }
        return out;
    }
}
