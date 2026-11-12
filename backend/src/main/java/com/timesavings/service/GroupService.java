package com.timesavings.service;

import com.timesavings.ai.RuleEngine;
import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.GroupParticipant;
import com.timesavings.domain.GroupPost;
import com.timesavings.domain.User;
import com.timesavings.repo.GroupParticipantRepo;
import com.timesavings.repo.GroupPostRepo;
import com.timesavings.repo.OrganizationRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 단체 봉사(기능 U-10 목록·참여, U-11 사용자 단체 공고, O-07 담당자 단체 공고).
 * 1:1 공고·요청 흐름과 섞지 않는다. 규칙:
 * - 모집형(RECRUIT)은 기관 담당자만 올린다(받는 쪽이 기관이라 시간 차감이 없다). 제공형(OFFER)은 사용자(신분증 인증)·담당자가 올린다.
 * - 인원은 2명 이상. 참여는 정원까지 선착순이고 신분증 인증이 된 사용자만 한다.
 * - 제공형은 기관 담당자가 "우리 기관에서 받기"를 눌러야 진행된다. 참석 확인·완료는 항상 기관 담당자(모집형=올린 기관, 제공형=받은 기관)가 누른다.
 * - 완료하면 참석한 사람마다 자기 통장에 활동 시간 × 가중치 1.0 이 적립된다(1시간=1시간). 차감은 없다.
 */
@Service
@Transactional
public class GroupService {
    public static final String RECRUIT = "RECRUIT", OFFER = "OFFER";
    public static final int MIN_PEOPLE = 2, MAX_PEOPLE = 50;
    public static final double WEIGHT = 1.0;
    private static final String NEED_ID = "신분증 인증이 끝난 뒤 참여할 수 있어요";

    private final GroupPostRepo posts;
    private final GroupParticipantRepo parts;
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final BankService bank;
    private final NotificationService noti;

    public GroupService(GroupPostRepo posts, GroupParticipantRepo parts, UserRepo users, OrganizationRepo orgs, BankService bank, NotificationService noti) {
        this.posts = posts; this.parts = parts; this.users = users; this.orgs = orgs; this.bank = bank; this.noti = noti;
    }

    public record Cmd(String kind, LocalDate date, Double startHour, Double hours, String area, String place, String category,
                      String title, String detail, Integer capacity, String groupName) {}

    public GroupPost get(Long id) { return posts.findById(id).orElseThrow(() -> ApiException.notFound("단체 공고를 찾을 수 없어요")); }

    // ---------------- 권한 ----------------
    private static boolean isOrg(User u) { return u != null && u.role == Role.ORG && u.orgId != null; }

    /** 올린 쪽: 올린 사람 본인, 또는 담당자가 올린 공고면 같은 기관 담당자. */
    boolean ownerSide(GroupPost g, User u) {
        if (u == null) return false;
        return u.id.equals(g.ownerId) || (g.orgId != null && isOrg(u) && g.orgId.equals(u.orgId));
    }

    /** 참석 확인·완료를 누를 수 있는 기관 담당자: 모집형은 올린 기관, 제공형은 받은 기관. */
    boolean confirmSide(GroupPost g, User u) {
        if (!isOrg(u)) return false;
        return RECRUIT.equals(g.kind) ? u.orgId.equals(g.orgId) : u.orgId.equals(g.hostOrgId);
    }

    private List<GroupParticipant> active(GroupPost g) {
        return parts.findByGroupPostId(g.id).stream().filter(p -> !"LEFT".equals(p.status)).sorted(Comparator.comparing((GroupParticipant p) -> p.id)).toList();
    }

    private GroupParticipant mine(GroupPost g, User u) {
        return u == null ? null : parts.findByGroupPostId(g.id).stream().filter(p -> p.userId.equals(u.id) && !"LEFT".equals(p.status)).findFirst().orElse(null);
    }

    private static boolean past(GroupPost g) { return g.date.isBefore(LocalDate.now()); }

    // ---------------- 올리기 (U-11, O-07) ----------------
    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public GroupPost create(User me, Cmd c) {
        if (c == null) throw ApiException.bad("INVALID_INPUT", "내용을 채워 주세요");
        String kind = c.kind() == null ? "" : c.kind();
        if (!RECRUIT.equals(kind) && !OFFER.equals(kind)) throw ApiException.bad("INVALID_KIND", "단체 모집인지, 단체 봉사 가능인지 골라 주세요");
        if (me.role == Role.USER) {
            if (me.status != UserStatus.ACTIVE) throw ApiException.forbidden("지금은 공고를 올릴 수 없는 계정이에요");
            if (RECRUIT.equals(kind)) throw ApiException.forbidden("단체 모집 공고는 기관 담당자만 올릴 수 있어요. 함께 봉사할 단체가 있으면 '단체 봉사 가능' 공고로 올려 주세요");
            if (me.idStatus != VerifyStatus.APPROVED) throw ApiException.bad("ID_NOT_VERIFIED", "신분증 인증이 끝나야 단체 봉사 공고를 올릴 수 있어요");
        } else if (!isOrg(me)) throw ApiException.forbidden("단체 공고를 올릴 수 없는 계정이에요");
        int cap = c.capacity() == null ? 0 : c.capacity();
        if (cap < MIN_PEOPLE) throw ApiException.bad("INVALID_CAPACITY", "단체 공고는 2명 이상이어야 해요. 혼자 하는 봉사는 일반 공고로 올려 주세요");
        if (cap > MAX_PEOPLE) throw ApiException.bad("INVALID_CAPACITY", "인원은 " + MAX_PEOPLE + "명까지 적을 수 있어요");
        if (c.date() == null || c.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
        if (c.startHour() == null || c.hours() == null) throw ApiException.bad("INVALID_TIME", "시작 시각과 활동 시간을 정해 주세요");
        Rules.checkHours(c.startHour(), c.hours());
        if (c.category() == null || c.category().isBlank()) throw ApiException.bad("INVALID_CATEGORY", "봉사 종류를 골라 주세요");
        Rules.categoryLevel(c.category());
        if (clean(c.area()) == null) throw ApiException.bad("AREA_REQUIRED", "지역(동)을 골라 주세요");
        String groupName = clean(c.groupName()), title = clean(c.title()), detail = clean(c.detail()), place = clean(c.place());
        if (OFFER.equals(kind) && groupName == null) throw ApiException.bad("GROUP_NAME_REQUIRED", "단체 이름을 적어 주세요 (예: 행복동 청년 봉사단)");
        if (groupName != null && groupName.length() > 50) throw ApiException.bad("TOO_LONG", "단체 이름은 50자 안으로 적어 주세요");
        if (title != null && title.length() > 100) throw ApiException.bad("TOO_LONG", "제목은 100자 안으로 적어 주세요");
        if (detail != null && detail.length() > 1000) throw ApiException.bad("TOO_LONG", "내용은 1000자 안으로 적어 주세요");
        if (place != null && place.length() > 100) throw ApiException.bad("TOO_LONG", "모이는 곳은 100자 안으로 적어 주세요");
        String text = String.join(" ", Objects.toString(title, ""), Objects.toString(detail, ""), Objects.toString(place, ""), Objects.toString(groupName, ""));
        // 금지 요청(신체 돌봄·의료·금전·위험 작업)은 단체 공고에도 똑같이 적용한다
        RuleEngine.FilterResult f = RuleEngine.filter(text);
        if (f.level() == FilterLevel.BLOCK) throw ApiException.bad("FORBIDDEN_REQUEST", f.reason());
        if (!VoiceSafety.scrub(text).removed().isEmpty())
            throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요. 모이는 곳은 기관·공공장소 이름만 적어 주세요");
        GroupPost g = new GroupPost();
        g.kind = kind;
        g.ownerId = me.id;
        g.orgId = isOrg(me) ? me.orgId : null;
        g.groupName = groupName;
        g.date = c.date();
        g.startHour = c.startHour();
        g.hours = c.hours();
        g.area = c.area().trim();
        g.place = place;
        g.category = c.category();
        g.title = title != null ? title : c.category() + (RECRUIT.equals(kind) ? " 단체 봉사자 모집" : " 단체 봉사 가능");
        g.detail = detail;
        g.capacity = cap;
        posts.save(g);
        if (me.role == Role.USER) addParticipant(g, me); // 올린 사람도 단체의 한 명
        return g;
    }

    private GroupParticipant addParticipant(GroupPost g, User u) {
        GroupParticipant p = parts.findByGroupPostId(g.id).stream().filter(x -> x.userId.equals(u.id)).findFirst().orElseGet(GroupParticipant::new);
        p.groupPostId = g.id;
        p.userId = u.id;
        p.status = "JOINED";
        p.earned = null;
        p.createdAt = LocalDateTime.now();
        return parts.save(p);
    }

    // ---------------- 참여 신청·취소 (U-10) ----------------
    /** 참여할 수 없는 까닭(없으면 null). 화면 안내와 서버 검사에 같은 문장을 쓴다. */
    String joinBlock(GroupPost g, User u) {
        if (u == null || u.role != Role.USER) return "사용자 계정만 참여할 수 있어요";
        if (u.status != UserStatus.ACTIVE) return "지금은 참여할 수 없는 계정이에요";
        if (!"OPEN".equals(g.status)) return "마감된 단체 공고예요";
        if (past(g)) return "날짜가 지난 단체 공고예요";
        if (mine(g, u) != null) return "이미 참여 신청했어요";
        if (u.idStatus != VerifyStatus.APPROVED) return NEED_ID;
        if (active(g).size() >= g.capacity) return "모집 인원이 다 찼어요";
        return null;
    }

    public GroupParticipant join(User me, Long id) {
        GroupPost g = get(id);
        String why = joinBlock(g, me);
        if (NEED_ID.equals(why)) throw ApiException.bad("ID_NOT_VERIFIED", why);
        if (why != null) throw ApiException.conflict("CANNOT_JOIN", why);
        // 같은 시간에 이미 참여하기로 한 다른 단체 봉사가 있으면 막는다
        for (GroupParticipant p : parts.findByUserId(me.id)) {
            if (!"JOINED".equals(p.status)) continue;
            GroupPost o = posts.findById(p.groupPostId).orElse(null);
            if (o == null || !"OPEN".equals(o.status) || !o.date.equals(g.date)) continue;
            if (o.startHour < g.startHour + g.hours && g.startHour < o.startHour + o.hours)
                throw ApiException.conflict("TIME_OVERLAP", "같은 시간에 참여하기로 한 단체 봉사가 있어요: " + o.title);
        }
        GroupParticipant p = addParticipant(g, me);
        int n = active(g).size();
        noti.send(g.ownerId, "GROUP", me.name + " 님이 단체 봉사에 참여 신청했어요", g.title + " · " + Rules.md(g.date) + " · " + n + "/" + g.capacity + "명", "/group/" + g.id);
        return p;
    }

    public void leave(User me, Long id) {
        GroupPost g = get(id);
        GroupParticipant p = mine(g, me);
        if (p == null || !"JOINED".equals(p.status) || !"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "취소할 수 있는 참여 신청이 없어요");
        if (me.id.equals(g.ownerId)) throw ApiException.conflict("OWNER_CANNOT_LEAVE", "내가 올린 단체 공고예요. 그만두려면 공고를 내려 주세요");
        p.status = "LEFT";
        noti.send(g.ownerId, "GROUP", me.name + " 님이 단체 봉사 참여를 취소했어요", g.title + " · " + Rules.md(g.date), "/group/" + g.id);
    }

    // ---------------- 제공형 받기 (O-07) ----------------
    public void host(User me, Long id) {
        GroupPost g = get(id);
        if (!isOrg(me)) throw ApiException.forbidden("기관 담당자만 단체 봉사를 받을 수 있어요");
        if (!OFFER.equals(g.kind)) throw ApiException.bad("INVALID_KIND", "단체 봉사 가능 공고만 받을 수 있어요");
        if (!"OPEN".equals(g.status) || past(g)) throw ApiException.conflict("INVALID_STATE", "마감됐거나 날짜가 지난 공고예요");
        if (g.hostOrgId != null) throw ApiException.conflict("ALREADY_HOSTED", "이미 다른 기관이 받기로 한 단체 봉사예요");
        g.hostOrgId = me.orgId;
        g.hostBy = me.id;
        g.hostedAt = LocalDateTime.now();
        String org = orgName(me.orgId);
        Set<Long> to = new LinkedHashSet<>();
        to.add(g.ownerId);
        active(g).forEach(p -> to.add(p.userId));
        to.remove(me.id);
        for (Long uid : to)
            noti.send(uid, "GROUP", org + "에서 단체 봉사를 받기로 했어요", g.title + " · " + Rules.md(g.date) + " · 당일 담당자가 참석을 확인하면 시간이 적립돼요", "/group/" + g.id);
    }

    public void unhost(User me, Long id) {
        GroupPost g = get(id);
        if (!OFFER.equals(g.kind) || g.hostOrgId == null || !isOrg(me) || !me.orgId.equals(g.hostOrgId)) throw ApiException.forbidden("우리 기관이 받기로 한 단체 봉사만 취소할 수 있어요");
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝난 단체 봉사예요");
        String org = orgName(g.hostOrgId);
        g.hostOrgId = null; g.hostBy = null; g.hostedAt = null;
        if (!me.id.equals(g.ownerId)) noti.send(g.ownerId, "GROUP", org + "에서 단체 봉사 받기를 취소했어요", g.title + " · " + Rules.md(g.date), "/group/" + g.id);
    }

    // ---------------- 내리기 ----------------
    public void cancel(User me, Long id) {
        GroupPost g = get(id);
        if (!ownerSide(g, me)) throw ApiException.forbidden("내가 올린 단체 공고만 내릴 수 있어요");
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 내린 공고예요");
        g.status = "CANCELLED";
        for (GroupParticipant p : active(g)) {
            if (p.userId.equals(me.id)) continue;
            noti.send(p.userId, "GROUP", "단체 봉사 공고가 취소됐어요", g.title + " · " + Rules.md(g.date), "/group/" + g.id);
        }
    }

    // ---------------- 참석 확인·완료 → 개인별 적립 ----------------
    public Map<String, Object> complete(User me, Long id, List<Long> attended) {
        GroupPost g = get(id);
        if (!confirmSide(g, me)) {
            if (OFFER.equals(g.kind) && g.hostOrgId == null) throw ApiException.conflict("NOT_HOSTED", "아직 이 단체 봉사를 받기로 한 기관이 없어요");
            throw ApiException.forbidden(RECRUIT.equals(g.kind) ? "공고를 올린 기관의 담당자만 참석을 확인할 수 있어요" : "단체 봉사를 받은 기관의 담당자만 참석을 확인할 수 있어요");
        }
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 내린 공고예요");
        if (g.date.isAfter(LocalDate.now())) throw ApiException.conflict("TOO_EARLY", "활동하는 날(" + Rules.md(g.date) + ")부터 참석을 확인할 수 있어요");
        if (attended == null) throw ApiException.bad("ATTENDED_REQUIRED", "참석한 사람을 골라 주세요");
        Set<Long> came = new HashSet<>(attended);
        List<GroupParticipant> list = active(g);
        Set<Long> joined = new HashSet<>();
        list.forEach(p -> joined.add(p.userId));
        if (!joined.containsAll(came)) throw ApiException.bad("NOT_PARTICIPANT", "참여 신청하지 않은 사람이 들어 있어요");
        double earn = Rules.earn(g.hours, WEIGHT);
        String from = RECRUIT.equals(g.kind) ? orgName(g.orgId) : orgName(g.hostOrgId);
        int n = 0;
        for (GroupParticipant p : list) {
            if (came.contains(p.userId)) {
                p.status = "ATTENDED";
                p.earned = earn;
                bank.add(p.userId, LedgerType.EARN, earn, g.hours, WEIGHT, null, "단체 봉사 · " + g.title + (from == null ? "" : " · " + from));
                noti.send(p.userId, "GROUP", "단체 봉사 " + Rules.fmtHours(earn) + "시간이 적립됐어요", g.title + " · " + Rules.md(g.date) + " · 참석이 확인됐어요", "/bank");
                n++;
            } else {
                p.status = "ABSENT";
                noti.send(p.userId, "GROUP", "단체 봉사에 참석하지 않은 것으로 기록됐어요", g.title + " · " + Rules.md(g.date) + " · 사실과 다르면 문의해 주세요", "/group/" + g.id);
            }
        }
        g.status = "DONE";
        g.doneBy = me.id;
        g.doneAt = LocalDateTime.now();
        return Views.map("ok", true, "attended", n, "absent", list.size() - n, "earnedEach", earn, "totalEarned", Rules.earn(g.hours * n, WEIGHT));
    }

    // ---------------- 보기 ----------------
    private String orgName(Long orgId) { return orgId == null ? null : orgs.findById(orgId).map(o -> o.name).orElse(null); }

    public Map<String, Object> card(GroupPost g, User viewer) {
        List<GroupParticipant> list = active(g);
        GroupParticipant my = viewer == null ? null : list.stream().filter(p -> p.userId.equals(viewer.id)).findFirst().orElse(null);
        boolean open = "OPEN".equals(g.status), past = past(g), mineSide = ownerSide(g, viewer);
        String orgName = orgName(g.orgId);
        String ownerName = orgName != null ? orgName : users.findById(g.ownerId).map(u -> u.name).orElse(null);
        String block = joinBlock(g, viewer);
        Map<String, Object> m = Views.map("id", g.id, "kind", g.kind, "kindLabel", RECRUIT.equals(g.kind) ? "단체 모집" : "단체 봉사 가능",
                "title", g.title, "detail", g.detail, "category", g.category, "date", g.date.toString(), "startHour", g.startHour, "hours", g.hours,
                "endHour", g.startHour + g.hours, "area", g.area, "place", g.place, "groupName", g.groupName,
                "capacity", g.capacity, "joined", list.size(), "left", Math.max(0, g.capacity - list.size()), "full", list.size() >= g.capacity,
                "status", g.status, "past", past, "ownerId", g.ownerId, "ownerName", ownerName, "byOrg", g.orgId != null, "orgName", orgName,
                "hosted", g.hostOrgId != null, "hostOrgName", orgName(g.hostOrgId),
                "weight", WEIGHT, "estEarn", Rules.earn(g.hours, WEIGHT), "mine", mineSide,
                "myStatus", my == null ? null : my.status, "myEarned", my == null ? null : my.earned,
                "distanceKm", viewer == null || viewer.area == null ? null : Views.distanceKm(viewer.area, g.area, g.id),
                "createdAt", g.createdAt == null ? null : g.createdAt.toString());
        m.put("canJoin", block == null);
        m.put("joinBlock", viewer != null && viewer.role == Role.USER && my == null && open && !past ? block : null);
        m.put("canLeave", my != null && "JOINED".equals(my.status) && open && !viewer.id.equals(g.ownerId));
        m.put("canCancel", mineSide && open);
        m.put("canHost", isOrg(viewer) && OFFER.equals(g.kind) && open && !past && g.hostOrgId == null);
        m.put("canUnhost", isOrg(viewer) && OFFER.equals(g.kind) && open && g.hostOrgId != null && viewer.orgId.equals(g.hostOrgId));
        m.put("canComplete", confirmSide(g, viewer) && open && !g.date.isAfter(LocalDate.now()));
        m.put("confirmSide", confirmSide(g, viewer));
        return m;
    }

    /** 상세: 참여자 이름은 올린 쪽과 참석을 확인하는 기관 담당자에게만 보인다(다른 사람에게는 인원 수만). */
    public Map<String, Object> detail(GroupPost g, User viewer) {
        Map<String, Object> m = card(g, viewer);
        if (ownerSide(g, viewer) || confirmSide(g, viewer)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GroupParticipant p : active(g)) {
                User u = users.findById(p.userId).orElse(null);
                rows.add(Views.map("userId", p.userId, "name", u == null ? "탈퇴한 사용자" : u.name, "status", p.status, "earned", p.earned,
                        "owner", p.userId.equals(g.ownerId)));
            }
            m.put("participants", rows);
        }
        return m;
    }

    /** 목록(U-10): 열려 있고 날짜가 지나지 않은 단체 공고, 날짜·시각 순. */
    public List<Map<String, Object>> list(User viewer, String kind, String area, String cat) {
        List<Map<String, Object>> out = new ArrayList<>();
        posts.findByStatus("OPEN").stream()
                .filter(g -> !past(g))
                .filter(g -> kind == null || kind.isBlank() || kind.equals(g.kind))
                .filter(g -> area == null || area.isBlank() || area.equals(g.area))
                .filter(g -> cat == null || cat.isBlank() || cat.equals(g.category))
                .sorted(Comparator.comparing((GroupPost g) -> g.date).thenComparingDouble(g -> g.startHour).thenComparing(g -> g.id))
                .forEach(g -> out.add(card(g, viewer)));
        return out;
    }

    /** 내 단체 봉사: 사용자는 올렸거나 참여한 것, 담당자는 우리 기관이 올렸거나 받기로 한 것. 최근 날짜부터. */
    public List<Map<String, Object>> mine(User me) {
        Set<Long> joined = new HashSet<>();
        parts.findByUserId(me.id).stream().filter(p -> !"LEFT".equals(p.status)).forEach(p -> joined.add(p.groupPostId));
        List<Map<String, Object>> out = new ArrayList<>();
        posts.findAll().stream()
                .filter(g -> ownerSide(g, me) || joined.contains(g.id) || (isOrg(me) && me.orgId.equals(g.hostOrgId)))
                .sorted(Comparator.comparing((GroupPost g) -> g.date).reversed().thenComparing(g -> -g.id))
                .forEach(g -> out.add(card(g, me)));
        return out;
    }

    /**
     * 플래너 달력·내 활동에 넣을 내 단체 일정(그 달, 참여 신청했거나 참석이 확인된 것). 플래너 일정과 같은 모양:
     * {kind:"give", group:true, date, startHour, hours, category, title, who, amount, groupPostId, status}
     */
    public List<Map<String, Object>> schedule(User me, YearMonth ym) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GroupParticipant p : parts.findByUserId(me.id)) {
            if (!"JOINED".equals(p.status) && !"ATTENDED".equals(p.status)) continue;
            GroupPost g = posts.findById(p.groupPostId).orElse(null);
            if (g == null || "CANCELLED".equals(g.status) || (ym != null && !YearMonth.from(g.date).equals(ym))) continue;
            String who = RECRUIT.equals(g.kind) ? orgName(g.orgId) : g.hostOrgId != null ? orgName(g.hostOrgId) : g.groupName;
            out.add(Views.map("kind", "give", "group", true, "date", g.date.toString(), "startHour", g.startHour, "hours", g.hours,
                    "category", g.category, "title", "[단체] " + g.title, "who", (who == null ? "단체 봉사" : who) + " · " + g.capacity + "명 단체",
                    "amount", p.earned != null ? p.earned : Rules.earn(g.hours, WEIGHT), "groupPostId", g.id, "status", "ATTENDED".equals(p.status) ? "DONE" : "CONFIRMED"));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("date")).thenComparing(m -> ((Number) m.get("startHour")).doubleValue()));
        return out;
    }

    /** GET /api/me/planner 응답에 그 달의 단체 일정을 더한다(달력에 같이 보이게). 목표 계산(goal)은 건드리지 않는다. */
    @SuppressWarnings("unchecked")
    public void addToPlanner(User me, Map<String, Object> planner) {
        if (planner == null || !(planner.get("items") instanceof List<?> raw) || !(planner.get("month") instanceof String month)) return;
        List<Map<String, Object>> add = schedule(me, YearMonth.parse(month));
        if (add.isEmpty()) return;
        List<Map<String, Object>> items = new ArrayList<>((List<Map<String, Object>>) raw);
        items.addAll(add);
        items.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("date"))
                .thenComparing(m -> m.get("startHour") == null ? 0.0 : ((Number) m.get("startHour")).doubleValue()));
        planner.put("items", items);
    }
}
