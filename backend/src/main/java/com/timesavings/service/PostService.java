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
    private final UserRepo users;
    private final FamilyLinkRepo family;
    /** 기능 U-34 봉사 자격 검사(생성자를 바꾸지 않으려고 필드 주입) */
    @org.springframework.beans.factory.annotation.Autowired private VolunteerGate volunteerGate;

    public PostService(PostRepo posts, RequestRepo requests, ElderRepo elders, InterestAlertRepo alerts, NotificationService noti, Views views,
                       UserRepo users, FamilyLinkRepo family) {
        this.posts = posts; this.requests = requests; this.elders = elders; this.alerts = alerts; this.noti = noti; this.views = views;
        this.users = users; this.family = family;
    }

    /** weekly·weeks 는 예전 자동 반복 등록의 칸이다. 41판 B-06 에서 자동 반복을 없애 더는 쓰지 않는다(값이 와도 한 건만 만든다). */
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
        // B-57: 가족 대신 올린 사람은 읽기만 한다(수정·삭제·다시 올리기는 공고 주인인 가족 본인만).
        return p.elderId != null && views.isManagerOf(u, p.elderId);
    }

    /** B-57: 가족 대신 올린 공고의 작성자인가(공고 주인은 가족). 요청의 수락·거부 권한은 없다. */
    public boolean isProxyAuthor(Post p, User u) {
        return p.ownerId != null && p.elderId == null && p.createdBy != null && u.id.equals(p.createdBy) && !u.id.equals(p.ownerId);
    }

    /** 받은 요청·제안 목록을 볼 수 있는 사람: 공고 주인, 담당자, 대신 올린 사람(읽기만). */
    public boolean canSeeRequests(Post p, User u) { return canEdit(p, u) || isProxyAuthor(p, u); }

    /** 공고 카드에 "대신 올림" 정보를 붙인다(B-57). */
    public Map<String, Object> decorate(Map<String, Object> card, Post p, User viewer) {
        boolean proxy = p.ownerId != null && p.elderId == null && p.createdBy != null && !p.createdBy.equals(p.ownerId);
        card.put("proxy", proxy);
        if (proxy) {
            card.put("proxyByName", users.findById(p.createdBy).map(x -> x.name).orElse(null));
            card.put("proxyMine", viewer != null && viewer.id.equals(p.createdBy));
        }
        return card;
    }

    /** B-57: 연결(수락)된 가족인지 확인하고 그 가족을 돌려준다. */
    private User linkedFamily(User me, Long forUserId) {
        if (me.role != Role.USER) throw ApiException.forbidden("가족 대신 공고는 사용자 계정만 올릴 수 있어요");
        if (forUserId.equals(me.id)) throw ApiException.bad("INVALID_FAMILY", "내 공고는 대신 올리기가 아니라 그냥 올려 주세요");
        boolean linked = family.findAll().stream().anyMatch(f -> f.status == LinkStatus.ACCEPTED
                && ((f.requesterId.equals(me.id) && f.targetId.equals(forUserId)) || (f.requesterId.equals(forUserId) && f.targetId.equals(me.id))));
        if (!linked) throw ApiException.forbidden("연결된 가족만 대신 올릴 수 있어요. 마이페이지에서 가족 연결을 먼저 해 주세요 (B-57)");
        User fam = users.findById(forUserId).orElseThrow(() -> ApiException.notFound("가족을 찾을 수 없어요"));
        if (fam.role != Role.USER || fam.status != UserStatus.ACTIVE) throw ApiException.bad("INVALID_FAMILY", "지금 도움을 요청할 수 없는 계정이에요");
        return fam;
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
        if (c.type() == PostType.OFFER) volunteerGate.require(me, true); // 기능 U-34: 첫 활동 교육(필수일 때) 이수 검사
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
            lv = Rules.max(base, Rules.current(c.difficulty())); // 책임 등급으로 들어오면 수고로 받는다(7.21)
            if (c.difficultyReason() != null && !c.difficultyReason().isBlank()) reason = c.difficultyReason();
        }
        p.difficulty = lv;
        p.difficultyReason = reason;
        p.weight = Rules.weight(lv);
    }

    public List<Post> create(User me, PostCmd c) { return create(me, c, null); }

    /**
     * 공고 등록. 항상 한 건만 만든다(B-06: 자동 반복 등록 없음 — 돌려주는 목록의 크기는 늘 1).
     * forUserId 가 있으면 연결된 가족 대신 올리는 도움 요청 공고(B-57): 공고 주인=가족, 작성자=나.
     */
    public List<Post> create(User me, PostCmd c, Long forUserId) {
        Elder elder = null;
        if (c.elderId() != null) {
            elder = elders.findById(c.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!views.isManagerOf(me, elder.id)) throw ApiException.forbidden("내가 담당하는 대상만 대신 올릴 수 있어요");
            if (elder.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        }
        User fam = null;
        if (forUserId != null) {
            if (elder != null) throw ApiException.bad("INVALID_FAMILY", "도움 대상 공고와 가족 대신 공고를 함께 고를 수 없어요");
            fam = linkedFamily(me, forUserId);
            if (c.type() != PostType.NEED) throw ApiException.bad("INVALID_TYPE", "가족 대신으로는 도움 요청 공고만 올릴 수 있어요 (B-57)");
        }
        c = c.withDefaultHours();
        validate(c, me, elder);
        Post p = new Post();
        p.type = c.type();
        p.ownerId = elder != null ? null : fam != null ? fam.id : me.id;
        p.elderId = elder == null ? null : elder.id;
        p.createdBy = me.id;
        p.date = c.date();
        p.startHour = c.startHour();
        p.endHour = c.endHour();
        p.area = c.area().trim();
        p.categories = new ArrayList<>(new LinkedHashSet<>(c.categories()));
        p.title = blankTo(c.title(), defaultTitle(c));
        p.detail = c.detail();
        p.note = c.note();
        p.estHours = c.type() == PostType.NEED ? c.estHours() : null;
        applyDifficulty(p, c);
        posts.save(p);
        if (fam != null)
            noti.send(fam.id, "FAMILY_POST", me.name + " 님이 대신 도움 요청 공고를 올렸어요",
                    p.title + " · " + Rules.md(p.date) + " · 봉사 제안이 오면 최종 수락·거부는 직접 해 주세요", "/posts/" + p.id);
        notifyInterest(p, me);
        return new ArrayList<>(List.of(p));
    }

    /**
     * B-06 다시 올리기: 지난 공고를 같은 내용으로 불러와 날짜만 바꿔 새 공고 1건을 만든다.
     * 내 공고, 내가 담당하는 독거노인의 공고(담당자)에 쓸 수 있다(가족 대신 올린 사람은 읽기만이라 못 한다). 원래 공고는 그대로 둔다.
     */
    public Post repost(User me, Long id, LocalDate date) {
        Post src = get(id);
        if (src.status == PostStatus.DELETED) throw ApiException.notFound("삭제된 공고예요");
        if (!canEdit(src, me)) throw ApiException.forbidden("내가 올린 공고만 다시 올릴 수 있어요");
        if (date == null) throw ApiException.bad("INVALID_DATE", "다시 올릴 날짜를 골라 주세요");
        PostCmd c = new PostCmd(src.type, date, src.startHour, src.endHour, src.area, new ArrayList<>(src.categories), src.title, src.detail, src.note,
                src.estHours, false, 1, src.elderId, src.difficulty, src.difficultyReason);
        Post p = create(me, c, null).get(0);
        if (src.elderId != null) p.issueNote = src.issueNote; // 관리 대상 이슈도 같이 가져온다
        return p;
    }

    /**
     * 기능 U-09 정기 요청: 등록하기 전에 공고 한 건을 올릴 때와 똑같은 검사(대상·가족 연결·시간·카테고리·금지 요청)만 하고 저장하지 않는다.
     * c.date 에는 첫 회차 날짜를 넣는다.
     */
    @Transactional(readOnly = true)
    public void precheck(User me, PostCmd c, Long forUserId) {
        Elder elder = null;
        if (c.elderId() != null) {
            elder = elders.findById(c.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!views.isManagerOf(me, elder.id)) throw ApiException.forbidden("내가 담당하는 대상만 대신 올릴 수 있어요");
            if (elder.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        }
        if (forUserId != null) {
            if (elder != null) throw ApiException.bad("INVALID_FAMILY", "도움 대상 공고와 가족 대신 공고를 함께 고를 수 없어요");
            linkedFamily(me, forUserId);
            if (c.type() != PostType.NEED) throw ApiException.bad("INVALID_TYPE", "가족 대신으로는 도움 요청 공고만 올릴 수 있어요 (B-57)");
        }
        validate(c.withDefaultHours(), me, elder);
    }

    /**
     * 기능 U-09: 지금 이 공고를 올릴 수 없는 까닭(올릴 수 있으면 null). 스케줄러가 회차 공고를 만들기 전에 물어본다.
     * 예외를 밖으로 던지지 않으므로 부른 쪽의 트랜잭션을 깨지 않는다.
     */
    public String whyCannotPost(User me, PostCmd c, Long forUserId) {
        try {
            Elder elder = null;
            if (c.elderId() != null) {
                elder = elders.findById(c.elderId()).orElse(null);
                if (elder == null) return "도움 대상을 찾을 수 없어요";
                if (!views.isManagerOf(me, elder.id)) return "담당자가 바뀌었어요";
                if (elder.status != ElderStatus.ACTIVE) return "지원이 종료된 대상이에요";
            }
            if (forUserId != null) linkedFamily(me, forUserId);
            validate(c.withDefaultHours(), me, elder);
            return null;
        } catch (ApiException e) {
            return e.getMessage() == null ? "공고를 올릴 수 없어요" : e.getMessage();
        }
    }

    /** 기능 U-09: 이 공고를 지금 내릴(삭제할) 수 있는지. 수락된 요청이 있거나 마감됐으면 false. 예외를 던지지 않는다. */
    public boolean deletable(Post p, User me) {
        try { requireEditable(p, me); return true; } catch (ApiException e) { return false; }
    }

    /** 기능 U-09 정기 요청의 회차 공고 한 건: 보통 등록과 같은 길로 만들고 반복 표시(repeatRule·seriesId)만 더한다. */
    public Post createOccurrence(User me, PostCmd c, Long forUserId, String repeatRule, String seriesId, String issueNote) {
        Post p = create(me, c, forUserId).get(0);
        p.repeatRule = repeatRule;
        p.seriesId = seriesId;
        if (p.elderId != null && issueNote != null && !issueNote.isBlank()) p.issueNote = issueNote;
        return posts.save(p);
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
            if (a.userId.equals(author.id) || a.userId.equals(p.ownerId) || sent.contains(a.userId)) continue;
            if (a.area != null && !a.area.isBlank() && !a.area.equals(p.area)) continue;
            if (!a.categories.isEmpty() && a.categories.stream().noneMatch(p.categories::contains)) continue;
            if ("주말".equals(a.dayType) && !weekend) continue;
            if ("평일".equals(a.dayType) && weekend) continue;
            if (a.dateFrom != null && p.date.isBefore(a.dateFrom)) continue; // B-21 날짜 조건(기간, 하루면 시작=끝)
            if (a.dateTo != null && p.date.isAfter(a.dateTo)) continue;
            noti.send(a.userId, "INTEREST", "관심 조건에 맞는 새 공고가 올라왔어요",
                    p.area + " · " + String.join("·", p.categories) + " · " + Rules.md(p.date), "/posts/" + p.id);
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
        p.easyText = null;    // LLM 추가 A(7.12): 쉬운 말은 다음에 들을 때 다시 만든다
        p.fixTipsJson = null; // 고치기 제안은 고친 뒤 숨긴다(fixCheckedAt 은 남겨 다시 만들지 않음)
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
        return list(viewer, type, area, date, cat, sort, null, null, null, null);
    }

    /**
     * 공고 목록(B-20). dateFrom~dateTo 는 날짜 범위 필터. lat·lng 가 오면 그 좌표에서 공고 동네까지의 거리로 바꿔 계산한다(B-59).
     * 좌표는 이 계산에만 쓰고 저장하지 않는다. 좌표가 없으면 예전처럼 프로필 동네 기준 거리.
     */
    public List<Map<String, Object>> list(User viewer, PostType type, String area, LocalDate date, String cat, String sort,
                                          LocalDate dateFrom, LocalDate dateTo, Double lat, Double lng) {
        boolean geo = Geo.valid(lat, lng);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Post p : posts.findByStatus(PostStatus.OPEN)) {
            if (type != null && p.type != type) continue;
            if (area != null && !area.isBlank() && !area.equals(p.area)) continue;
            if (date != null && !date.equals(p.date)) continue;
            if (cat != null && !cat.isBlank() && !p.categories.contains(cat)) continue;
            if (dateFrom != null && p.date.isBefore(dateFrom)) continue;
            if (dateTo != null && p.date.isAfter(dateTo)) continue;
            if (p.date.isBefore(LocalDate.now())) continue;
            Map<String, Object> card = decorate(views.postCard(p, viewer), p, viewer);
            Double km = geo ? Geo.kmToDong(lat, lng, p.area) : null;
            if (km != null) card.put("distanceKm", km);
            card.put("distanceBy", km != null ? "location" : "profile");
            out.add(card);
        }
        Comparator<Map<String, Object>> rating = Comparator.comparingDouble(m -> {
            @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) m.get("owner");
            return o == null ? 0.0 : -((Number) o.get("ratingAvg")).doubleValue();
        });
        if ("new".equals(sort)) {
            out.sort(Comparator.<Map<String, Object>, java.time.LocalDateTime>comparing(m -> (java.time.LocalDateTime) m.get("createdAt"), Comparator.reverseOrder()).thenComparing(rating));
        } else if ("time".equals(sort)) { // 기능 U-02 시간순: 활동 날짜·시작 시각이 빠른 순
            out.sort(Comparator.<Map<String, Object>, String>comparing(m -> String.valueOf(m.get("date")))
                    .thenComparingDouble(m -> ((Number) m.get("startHour")).doubleValue()).thenComparing(rating));
        } else { // near: 가까운 순, 같은 거리면 평가 높은 순(B-40)
            out.sort(Comparator.<Map<String, Object>>comparingDouble(m -> ((Number) m.get("distanceKm")).doubleValue()).thenComparing(rating));
        }
        return out;
    }
}
