package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 기능 U-09 정기 요청. 반복 주기(매주 요일·시간)와 기간을 정해 두면 회차마다 도움 요청 공고가 자동으로 생긴다.
 * - 회차 공고는 한꺼번에 만들지 않는다. 등록할 때와 1분마다 도는 스케줄러가 "오늘부터 7일 안" 회차만 만든다(일시정지·해지가 쉽다).
 * - 회차 공고는 보통 공고와 똑같은 길(PostService.create)로 만들어 검사·알림·"공고 1개에 요청 1건" 규칙이 그대로다.
 * - 수락·거절은 회차마다 사람이 누른다. 자동으로 봉사자를 정하지 않는다.
 */
@Service
@Transactional
public class RecurringService {
    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);
    /** 며칠 앞 회차까지 미리 공고로 만들어 두는지 */
    public static final int AHEAD_DAYS = 7;
    /** 한 번에 정할 수 있는 가장 긴 기간(회차 수) */
    public static final int MAX_WEEKS = 26;
    /** 한 사람이 진행·일시정지로 둘 수 있는 정기 요청 수 */
    public static final int MAX_OPEN_RULES = 5;
    private static final String[] DAY = {"월", "화", "수", "목", "금", "토", "일"};

    private final RecurringPostRepo repo;
    private final PostRepo postRepo;
    private final PostService posts;
    private final RequestRepo requests;
    private final UserRepo users;
    private final ElderRepo elders;
    private final NotificationService noti;
    private final Views views;

    public RecurringService(RecurringPostRepo repo, PostRepo postRepo, PostService posts, RequestRepo requests, UserRepo users, ElderRepo elders,
                            NotificationService noti, Views views) {
        this.repo = repo; this.postRepo = postRepo; this.posts = posts; this.requests = requests; this.users = users; this.elders = elders;
        this.noti = noti; this.views = views;
    }

    /** weekday: 1=월 … 7=일(없으면 startDate 의 요일). 기간은 weeks(회차 수) 또는 endDate 로 정한다. */
    public record Cmd(Integer weekday, LocalDate startDate, LocalDate endDate, Integer weeks, Double startHour, Double endHour, Double estHours,
                      String area, List<String> categories, String title, String detail, String note, Long elderId, Long forUserId, String issueNote) {}

    public record Created(RecurringPost rule, List<Post> posts) {}

    public static String dayLabel(int weekday) { return "매주 " + DAY[Math.floorMod(weekday - 1, 7)] + "요일"; }

    /** from 이후(그날 포함) 첫 번째 그 요일 */
    static LocalDate firstOn(LocalDate from, int weekday) {
        return from.plusDays(Math.floorMod(weekday - from.getDayOfWeek().getValue(), 7));
    }

    /** 이 정기 요청의 모든 회차 날짜 */
    public static List<LocalDate> occurrences(RecurringPost r) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = firstOn(r.startDate, r.weekday); !d.isAfter(r.endDate); d = d.plusDays(7)) out.add(d);
        return out;
    }

    public RecurringPost get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("정기 요청을 찾을 수 없어요")); }

    /** 볼 수 있고 멈추거나 해지할 수 있는 사람: 도움 받는 본인, 등록한 사람(가족 대신), 그 대상의 지금 담당자. */
    public boolean canManage(RecurringPost r, User u) {
        if (u == null) return false;
        if (r.elderId != null) return views.isManagerOf(u, r.elderId);
        return u.id.equals(r.ownerId) || u.id.equals(r.createdBy);
    }

    private RecurringPost mine(User u, Long id) {
        RecurringPost r = get(id);
        if (!canManage(r, u)) throw ApiException.forbidden("내 정기 요청만 바꿀 수 있어요");
        return r;
    }

    // ---------------- 등록 ----------------
    public Created create(User me, Cmd c, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate start = c.startDate() != null ? c.startDate() : today.plusDays(1);
        if (start.isBefore(today)) throw ApiException.bad("INVALID_DATE", "시작 날짜는 오늘 이후로 골라 주세요");
        int weekday = c.weekday() != null ? c.weekday() : start.getDayOfWeek().getValue();
        if (weekday < 1 || weekday > 7) throw ApiException.bad("INVALID_WEEKDAY", "요일을 골라 주세요");
        LocalDate first = firstOn(start, weekday);
        LocalDate last;
        if (c.weeks() != null) {
            if (c.weeks() < 2 || c.weeks() > MAX_WEEKS) throw ApiException.bad("INVALID_PERIOD", "정기 요청은 2회부터 " + MAX_WEEKS + "회까지 정할 수 있어요");
            last = first.plusWeeks(c.weeks() - 1);
        } else {
            if (c.endDate() == null) throw ApiException.bad("INVALID_PERIOD", "언제까지 반복할지 기간을 정해 주세요");
            if (c.endDate().isBefore(first.plusWeeks(1))) throw ApiException.bad("INVALID_PERIOD", "정기 요청은 2회 이상이어야 해요. 끝 날짜를 더 뒤로 골라 주세요");
            if (c.endDate().isAfter(first.plusWeeks(MAX_WEEKS - 1).plusDays(6)))
                throw ApiException.bad("INVALID_PERIOD", "기간은 최대 " + MAX_WEEKS + "주까지 정할 수 있어요");
            long n = java.time.temporal.ChronoUnit.WEEKS.between(first, c.endDate());
            last = first.plusWeeks(n);
        }
        // 공고 한 건을 올릴 때와 같은 검사(첫 회차 날짜로)
        posts.precheck(me, cmd(c, first), c.forUserId());
        long open = repo.findAll().stream().filter(r -> me.id.equals(r.createdBy) && ("ACTIVE".equals(r.status) || "PAUSED".equals(r.status))).count();
        if (open >= MAX_OPEN_RULES) throw ApiException.conflict("TOO_MANY_RECURRING", "정기 요청은 " + MAX_OPEN_RULES + "개까지 둘 수 있어요. 안 쓰는 것을 해지해 주세요");

        // 같은 사람에게 같은 요일·시작 시각으로 기간이 겹치는 정기 요청이 이미 있으면 막는다(같은 날 공고가 두 건씩 올라가는 것을 막음)
        Long ownerId = c.elderId() != null ? null : c.forUserId() != null ? c.forUserId() : me.id;
        final LocalDate f0 = first, l0 = last;
        boolean dup = repo.findAll().stream().anyMatch(r -> ("ACTIVE".equals(r.status) || "PAUSED".equals(r.status))
                && Objects.equals(r.elderId, c.elderId()) && Objects.equals(r.ownerId, ownerId) && r.weekday == weekday
                && Math.abs(r.startHour - c.startHour()) < 1e-9 && !r.endDate.isBefore(f0) && !r.startDate.isAfter(l0));
        if (dup) throw ApiException.conflict("DUPLICATE_RECURRING", "같은 요일·시간의 정기 요청이 이미 있어요. '내 정기 요청'에서 확인해 주세요");

        PostService.PostCmd pc = cmd(c, first).withDefaultHours();
        RecurringPost r = new RecurringPost();
        r.elderId = c.elderId();
        r.ownerId = ownerId;
        r.createdBy = me.id;
        r.weekday = weekday;
        r.startHour = pc.startHour();
        r.endHour = pc.endHour();
        r.estHours = pc.estHours();
        r.area = pc.area().trim();
        r.categories = new ArrayList<>(new LinkedHashSet<>(pc.categories()));
        r.title = pc.title() == null || pc.title().isBlank() ? null : pc.title().trim();
        r.detail = pc.detail();
        r.note = pc.note();
        r.issueNote = c.elderId() == null || c.issueNote() == null || c.issueNote().isBlank() ? null : c.issueNote().trim();
        r.startDate = first;
        r.endDate = last;
        repo.save(r);
        List<Post> made = generateFor(r, now, false);
        return new Created(r, made);
    }

    private static PostService.PostCmd cmd(Cmd c, LocalDate date) {
        return new PostService.PostCmd(PostType.NEED, date, c.startHour(), c.endHour(), c.area(), c.categories(), c.title(), c.detail(), c.note(),
                c.estHours(), false, 1, c.elderId(), null, null);
    }

    // ---------------- 회차 공고 만들기 ----------------
    /** 스케줄러: 진행 중인 정기 요청마다 7일 안 회차 공고를 만들고, 기간이 끝난 것은 닫는다. 만든 공고 수를 돌려준다. */
    public int generateDue(LocalDateTime now) {
        int n = 0;
        for (RecurringPost r : repo.findByStatus("ACTIVE")) n += generateFor(r, now, true).size();
        for (RecurringPost r : repo.findByStatus("PAUSED")) endIfOver(r, now.toLocalDate());
        return n;
    }

    private void endIfOver(RecurringPost r, LocalDate today) {
        if (today.isAfter(r.endDate) && !"ENDED".equals(r.status) && !"CANCELLED".equals(r.status)) {
            r.status = "ENDED";
            r.updatedAt = LocalDateTime.now();
        }
    }

    /** 회차 공고를 만들 사람: 독거노인 건은 지금 담당자, 그 밖은 등록한 사람. */
    private User actorOf(RecurringPost r) {
        if (r.elderId != null) {
            Long mgr = elders.findById(r.elderId).map(e -> e.managerId).orElse(null);
            return mgr == null ? null : users.findById(mgr).orElse(null);
        }
        return users.findById(r.createdBy).orElse(null);
    }

    private List<Post> generateFor(RecurringPost r, LocalDateTime now, boolean notify) {
        List<Post> made = new ArrayList<>();
        LocalDate today = now.toLocalDate();
        endIfOver(r, today);
        if (!"ACTIVE".equals(r.status)) return made;
        double nowHour = now.getHour() + now.getMinute() / 60.0;
        LocalDate from = today.isAfter(r.startDate) ? today : r.startDate;
        if (r.lastGenerated != null && !r.lastGenerated.isBefore(from)) from = r.lastGenerated.plusDays(1);
        LocalDate to = today.plusDays(AHEAD_DAYS);
        if (to.isAfter(r.endDate)) to = r.endDate;
        for (LocalDate d = firstOn(from, r.weekday); !d.isAfter(to); d = d.plusDays(7)) {
            if (d.equals(today) && nowHour >= r.startHour) { r.lastGenerated = d; continue; } // 오늘 시작 시각이 이미 지난 회차는 건너뛴다
            User actor = actorOf(r);
            Long forUser = actor != null && r.elderId == null && r.ownerId != null && !r.ownerId.equals(actor.id) ? r.ownerId : null;
            PostService.PostCmd c = new PostService.PostCmd(PostType.NEED, d, r.startHour, r.endHour, r.area, new ArrayList<>(r.categories), r.title,
                    r.detail, r.note, r.estHours, false, 1, r.elderId, null, null);
            // 올릴 수 없는 상태(가족 연결 끊김, 담당 종료, 계정 정지 등)면 멈추고 까닭을 알린다. 다시 시작은 사람이 누른다.
            String why = actor == null || actor.status != UserStatus.ACTIVE ? "등록한 계정을 지금 쓸 수 없어요" : posts.whyCannotPost(actor, c, forUser);
            if (why != null) {
                r.status = "PAUSED";
                r.stopReason = why.length() > 190 ? why.substring(0, 190) : why;
                r.updatedAt = LocalDateTime.now();
                noti.send(r.createdBy, "RECURRING_STOPPED", "정기 요청이 멈췄어요", (r.title == null ? dayLabel(r.weekday) : r.title) + " · " + r.stopReason, "/recurring");
                log.info("정기 요청 {} 자동 멈춤", r.id);
                break;
            }
            Post p = posts.createOccurrence(actor, c, forUser, dayLabel(r.weekday), r.seriesId(), r.issueNote);
            r.lastGenerated = d;
            made.add(p);
            if (notify)
                noti.send(actor.id, "RECURRING_POST", "정기 요청의 이번 회차 공고가 올라갔어요",
                        p.title + " · " + Rules.md(d) + " · 봉사 제안이 오면 수락·거절을 직접 눌러 주세요", "/post/" + p.id);
        }
        return made;
    }

    // ---------------- 일시정지·다시 시작·해지 ----------------
    public RecurringPost pause(User u, Long id) {
        RecurringPost r = mine(u, id);
        if (!"ACTIVE".equals(r.status)) throw ApiException.conflict("INVALID_STATE", "진행 중인 정기 요청만 일시정지할 수 있어요");
        r.status = "PAUSED";
        r.stopReason = null;
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    public Created resume(User u, Long id, LocalDateTime now) {
        RecurringPost r = mine(u, id);
        if (!"PAUSED".equals(r.status)) throw ApiException.conflict("INVALID_STATE", "일시정지한 정기 요청만 다시 시작할 수 있어요");
        if (now.toLocalDate().isAfter(r.endDate)) throw ApiException.conflict("PERIOD_OVER", "기간이 끝난 정기 요청이에요. 새로 등록해 주세요");
        r.status = "ACTIVE";
        r.stopReason = null;
        r.updatedAt = LocalDateTime.now();
        return new Created(r, generateFor(r, now, false));
    }

    /**
     * 해지: 더는 회차 공고를 만들지 않는다. 이미 올라간 앞으로의 회차 공고 가운데 아직 수락된 요청이 없는 것은 함께 내린다
     * (받아 둔 요청·제안은 보통 공고 삭제와 같이 취소되고 보낸 사람에게 알림). 수락된 회차는 그대로 진행한다.
     */
    public Map<String, Object> cancel(User u, Long id, LocalDate today) {
        RecurringPost r = mine(u, id);
        if ("CANCELLED".equals(r.status)) throw ApiException.conflict("INVALID_STATE", "이미 해지한 정기 요청이에요");
        r.status = "CANCELLED";
        r.stopReason = null;
        r.updatedAt = LocalDateTime.now();
        int removed = 0, kept = 0;
        for (Post p : postRepo.findBySeriesId(r.seriesId())) {
            if (p.status == PostStatus.DELETED || p.date.isBefore(today)) continue;
            User editor = p.elderId != null ? actorOf(r) : p.ownerId == null ? null : users.findById(p.ownerId).orElse(null);
            if (editor != null && posts.deletable(p, editor)) { posts.delete(editor, p.id); removed++; }
            else kept++; // 수락된 회차는 그대로 둔다
        }
        Map<String, Object> v = view(r, u, today);
        v.put("removedPosts", removed);
        v.put("keptPosts", kept);
        return v;
    }

    // ---------------- 조회 ----------------
    public List<Map<String, Object>> listFor(User u, Long elderId, LocalDate today) {
        return repo.findAll().stream()
                .filter(r -> canManage(r, u))
                .filter(r -> elderId == null || elderId.equals(r.elderId))
                .sorted(Comparator.comparing((RecurringPost r) -> rank(r.status)).thenComparing(r -> r.id, Comparator.reverseOrder()))
                .map(r -> view(r, u, today)).toList();
    }

    private static int rank(String st) { return "ACTIVE".equals(st) ? 0 : "PAUSED".equals(st) ? 1 : 2; }

    /** 화면용 한 건: 다음 회차·남은 회차는 여기서(코드가) 센다. */
    public Map<String, Object> view(RecurringPost r, User viewer, LocalDate today) {
        List<LocalDate> all = occurrences(r);
        boolean live = "ACTIVE".equals(r.status) || "PAUSED".equals(r.status);
        List<LocalDate> left = live ? all.stream().filter(d -> !d.isBefore(today)).toList() : List.of();
        LocalDate next = left.isEmpty() ? null : left.get(0);
        List<Post> series = r.id == null ? List.of() : postRepo.findBySeriesId(r.seriesId()).stream().filter(p -> p.status != PostStatus.DELETED)
                .sorted(Comparator.comparing((Post p) -> p.date).reversed()).toList();
        Post nextPost = next == null ? null : series.stream().filter(p -> p.date.equals(next)).findFirst().orElse(null);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Post p : series.stream().limit(6).toList()) {
            long pending = requests.findByPostId(p.id).stream().filter(q -> q.status.pending()).count();
            recent.add(Views.map("id", p.id, "date", p.date.toString(), "status", p.status, "requestCount", pending));
        }
        Elder elder = r.elderId == null ? null : elders.findById(r.elderId).orElse(null);
        User owner = r.ownerId == null ? null : users.findById(r.ownerId).orElse(null);
        boolean proxy = r.elderId == null && r.ownerId != null && !r.ownerId.equals(r.createdBy);
        double w = Rules.weight(Rules.maxLevel(r.categories));
        Map<String, Object> m = Views.map("id", r.id, "weekday", r.weekday, "repeatLabel", dayLabel(r.weekday),
                "startHour", r.startHour, "endHour", r.endHour, "estHours", r.estHours, "area", r.area, "categories", r.categories,
                "title", r.title != null ? r.title : r.categories.get(0) + " 도움이 필요해요", "detail", r.detail, "note", r.note,
                "startDate", r.startDate.toString(), "endDate", r.endDate.toString(), "status", r.status, "stopReason", r.stopReason,
                "totalCount", all.size(), "remainingCount", left.size(), "passedCount", (int) all.stream().filter(d -> d.isBefore(today)).count(),
                "nextDate", next == null ? null : next.toString(), "nextPostId", nextPost == null ? null : nextPost.id,
                "nextPostAt", next == null || nextPost != null || !"ACTIVE".equals(r.status) ? null : (next.minusDays(AHEAD_DAYS).isAfter(today) ? next.minusDays(AHEAD_DAYS).toString() : today.toString()),
                "posts", recent, "estEarnPerVisit", r.estHours == null ? null : Rules.earn(r.estHours, w),
                "elderId", r.elderId, "elderDisplayName", elder == null ? null : elder.alias + " 님",
                "ownerId", r.ownerId, "ownerName", owner == null ? null : owner.name, "proxy", proxy,
                "proxyMine", proxy && viewer != null && viewer.id.equals(r.createdBy),
                "createdByName", users.findById(r.createdBy).map(x -> x.name).orElse(null), "createdAt", r.createdAt);
        List<String> actions = new ArrayList<>();
        if ("ACTIVE".equals(r.status)) { actions.add("pause"); actions.add("cancel"); }
        if ("PAUSED".equals(r.status)) { actions.add("resume"); actions.add("cancel"); }
        m.put("actions", actions);
        return m;
    }
}
