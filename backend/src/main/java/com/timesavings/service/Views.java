package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 엔티티 → 계약서 5장 응답 모양(PostCard, RequestView 등). 평가·단골 집계도 여기서. */
@Service
public class Views {
    public static final ObjectMapper OM = new ObjectMapper();
    private final UserRepo users;
    private final ElderRepo elders;
    private final RatingRepo ratings;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final MessageRepo messages;
    private final RegularMarkRepo marks;
    private final OrganizationRepo orgs;

    public Views(UserRepo users, ElderRepo elders, RatingRepo ratings, RequestRepo requests, PostRepo posts, MessageRepo messages, RegularMarkRepo marks, OrganizationRepo orgs) {
        this.orgs = orgs;
        this.marks = marks;
        this.users = users;
        this.elders = elders;
        this.ratings = ratings;
        this.requests = requests;
        this.posts = posts;
        this.messages = messages;
    }

    // ---------------- 공통 ----------------
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String json(Object o) {
        try { return OM.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T parse(String s, TypeReference<T> t, T def) {
        if (s == null || s.isBlank()) return def;
        try { return OM.readValue(s, t); } catch (Exception e) { return def; }
    }

    // ---------------- 평가·단골 ----------------
    /**
     * 기능 U-18 "평가는 상대에게 공개되지 않음": 평가가 몇 건 안 되면 평균 별점·칭찬이 곧 그 한두 건이라 누가 무엇을 줬는지 드러난다.
     * 그래서 공개 평가가 이 수보다 적으면 평균 별점과 자주 받은 칭찬을 보여 주지 않는다("평가가 더 모이면 보여요").
     */
    public static final int MIN_PUBLIC_RATINGS = 3;

    /** 화면에 보이는 평균 별점 [평균, 건수]. 공개 평가가 MIN_PUBLIC_RATINGS 건 미만이면 [0, 0](아직 보여 주지 않음). */
    public double[] rating(Long userId) {
        double[] raw = ratingRaw(userId);
        return raw[1] < MIN_PUBLIC_RATINGS ? new double[]{0, 0} : raw;
    }

    /** 평가가 있지만 아직 모자라 가려 둔 상태인가(화면 안내용). */
    public boolean ratingPending(Long userId) {
        double n = ratingRaw(userId)[1];
        return n > 0 && n < MIN_PUBLIC_RATINGS;
    }

    /** 가리지 않은 평균 [평균, 건수] — 운영자 화면에서만 쓴다. */
    public double[] ratingRaw(Long userId) {
        List<Rating> list = ratings.findByTargetUserId(userId).stream().filter(r -> !r.isPrivate).toList();
        if (list.isEmpty()) return new double[]{0, 0};
        double avg = list.stream().mapToInt(r -> r.stars).average().orElse(0);
        return new double[]{Math.round(avg * 10) / 10.0, list.size()};
    }

    public List<String> topTags(Long userId, int n) {
        if (ratingRaw(userId)[1] < MIN_PUBLIC_RATINGS) return List.of(); // 평가가 모자라면 칭찬도 가린다(기능 U-18)
        Map<String, Long> c = ratings.findByTargetUserId(userId).stream().filter(r -> !r.isPrivate)
                .flatMap(r -> r.tags.stream()).collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        return c.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(n).map(Map.Entry::getKey).toList();
    }

    /** 봉사자 기준 DONE 3회 이상인 받는 사람(B-41). key "U{id}" 또는 "E{id}" → 횟수 */
    public Map<String, Long> regularCounts(Long volunteerId) {
        return requests.findAll().stream()
                .filter(r -> volunteerId.equals(r.volunteerId) && r.status == ReqStatus.DONE)
                .collect(Collectors.groupingBy(r -> r.elderId != null ? "E" + r.elderId : "U" + r.recipientId, Collectors.counting()));
    }

    /** 직접 등록(해지되지 않은 것) */
    public boolean manualRegular(Long recipientUserId, Long volunteerId) {
        return recipientUserId != null && marks.findByUserIdAndVolunteerId(recipientUserId, volunteerId).map(m -> !m.isRemoved()).orElse(false);
    }

    /** 41판 B-65: 어느 한쪽이 해지한 사이(자동 3회 단골도 풀린다). 독거노인(별칭)은 해지 기록이 없다. */
    public boolean regularRemoved(Long recipientUserId, Long volunteerId) {
        return recipientUserId != null && marks.findByUserIdAndVolunteerId(recipientUserId, volunteerId).map(RegularMark::isRemoved).orElse(false);
    }

    public boolean isRegular(Long volunteerId, Long recipientUserId, Long elderId) {
        if (elderId == null && regularRemoved(recipientUserId, volunteerId)) return false;
        Long n = regularCounts(volunteerId).get(elderId != null ? "E" + elderId : "U" + recipientUserId);
        if (n != null && n >= 3) return true;
        // 직접 단골(B-65): 받는 사람이 직접 단골로 등록한 봉사자
        return elderId == null && manualRegular(recipientUserId, volunteerId);
    }

    /**
     * 41판 B-42: 내가 받은 단골 배지 — 누구를 몇 회 도왔는지. 자동(3회 이상)과 상대가 직접 등록한 것을 합치고 해지된 것은 뺀다.
     * [{key, kind: USER|ELDER, userId|elderId, name, label, count, auto, manual}] 횟수 많은 순.
     */
    public List<Map<String, Object>> regularBadgeList(Long volunteerId, Long viewerId) {
        Map<String, Long> cnt = regularCounts(volunteerId);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (var e : cnt.entrySet()) {
            if (e.getValue() < 3) continue;
            String k = e.getKey();
            Long id = Long.parseLong(k.substring(1));
            if (k.startsWith("E")) {
                elders.findById(id).ifPresent(el -> {
                    String nm = viewerId != null && viewerId.equals(el.managerId) ? el.alias + " 님" : Rules.maskAlias(el.alias);
                    out.put(k, map("key", k, "kind", "ELDER", "elderId", id, "name", nm, "label", nm + "의 단골", "count", e.getValue(), "auto", true, "manual", false));
                });
            } else if (!regularRemoved(id, volunteerId)) {
                users.findById(id).ifPresent(u -> out.put(k, map("key", k, "kind", "USER", "userId", id, "name", u.name + " 님", "label", u.name + " 님의 단골",
                        "count", e.getValue(), "auto", true, "manual", manualRegular(id, volunteerId))));
            }
        }
        for (RegularMark rm : marks.findByVolunteerId(volunteerId)) {
            String k = "U" + rm.userId;
            if (rm.isRemoved() || out.containsKey(k)) continue;
            users.findById(rm.userId).filter(u -> u.status == UserStatus.ACTIVE).ifPresent(u -> out.put(k, map("key", k, "kind", "USER", "userId", rm.userId,
                    "name", u.name + " 님", "label", u.name + " 님의 단골", "count", cnt.getOrDefault(k, 0L), "auto", false, "manual", true)));
        }
        List<Map<String, Object>> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("count")).longValue()));
        return list;
    }

    /** 프로필·소개 카드용 단골 배지 문구(3회 이상 도운 분만). 담당자에게는 별칭 원문. */
    public List<String> regularBadges(Long volunteerId, Long viewerId) {
        return regularBadgeList(volunteerId, viewerId).stream().filter(m -> Boolean.TRUE.equals(m.get("auto"))).map(m -> (String) m.get("label")).toList();
    }

    /** 받는 사람(사용자 또는 독거노인)의 단골 봉사자 목록(B-14, C-06). */
    public List<Map<String, Object>> regularsOf(Long recipientUserId, Long elderId) {
        String key = elderId != null ? "E" + elderId : "U" + recipientUserId;
        Map<Long, Long> cnt = requests.findAll().stream()
                .filter(r -> r.status == ReqStatus.DONE && key.equals(r.elderId != null ? "E" + r.elderId : "U" + r.recipientId))
                .collect(Collectors.groupingBy(r -> r.volunteerId, Collectors.counting()));
        Set<Long> manual = new HashSet<>(), removed = new HashSet<>();
        if (elderId == null && recipientUserId != null)
            for (RegularMark rm : marks.findByUserId(recipientUserId)) (rm.isRemoved() ? removed : manual).add(rm.volunteerId);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        cnt.forEach((vid, n) -> { if (n >= 3 && !removed.contains(vid)) ids.add(vid); });
        ids.addAll(manual);
        for (Long vid : ids) {
            User v = users.findById(vid).orElse(null);
            if (v == null || v.status != UserStatus.ACTIVE) continue;
            Map<String, Object> m = volunteerCard(v, true);
            long n = cnt.getOrDefault(vid, 0L);
            m.put("doneCount", n);
            m.put("auto", n >= 3);
            m.put("manual", manual.contains(vid));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> userBrief(User u) {
        if (u == null) return null;
        return map("id", u.id, "name", u.name);
    }

    /** 소개 카드(B-12). */
    public Map<String, Object> volunteerCard(User v, boolean isRegular) {
        double[] r = rating(v.id);
        return map("id", v.id, "name", v.name, "ageGroup", v.ageGroup, "photoUrl", v.photoUrl,
                "ratingAvg", r[0], "ratingCount", (int) r[1], "intro", v.intro, "experience", v.experience,
                "tags", topTags(v.id, 3), "crime", v.crimeStatus == VerifyStatus.APPROVED,
                "isRegular", isRegular, "ratingPending", ratingPending(v.id));
    }

    /** 공고에 적은 "관리 대상 이슈"가 있으면 그 요청에서는 그것을 주의사항으로 보여 준다(대상 기본 주의사항은 baseCaution 으로 남김). */
    private static Map<String, Object> withIssue(Map<String, Object> brief, String issue) {
        if (brief == null || issue == null || issue.isBlank()) return brief;
        brief.put("baseCaution", brief.get("caution"));
        brief.put("caution", issue);
        return brief;
    }

    /** 기능 O-04: 봉사자가 특이사항을 볼 수 있는 요청 상태 — 일정이 확정된 뒤(확정·진행·일지·완료). 확정 전(요청·수락 대기)과 거절·취소된 건은 볼 수 없다. */
    public static boolean issueVisible(ReqStatus s) { return s != null && (s.active() || s == ReqStatus.DONE); }

    /** 기능 O-04: 볼 수 없는 사람에게는 특이사항(caution·baseCaution)을 빼고 cautionLocked=true 만 준다. */
    private static Map<String, Object> lockIssue(Map<String, Object> brief, boolean open) {
        if (brief == null) return null;
        if (!open) { brief.put("caution", null); brief.remove("baseCaution"); }
        brief.put("cautionLocked", !open);
        return brief;
    }

    public Map<String, Object> elderBrief(Elder e, boolean manager) {
        if (e == null) return null;
        // B-13: 봉사자에게는 별칭(가림)·동·주의사항까지만. 나이대와 별칭 원문은 담당자에게만.
        Map<String, Object> m = map("id", e.id, "displayName", manager ? e.alias + " 님" : Rules.maskAlias(e.alias),
                "dong", e.dong, "caution", e.caution);
        if (manager) { m.put("alias", e.alias); m.put("ageGroup", e.ageGroup); }
        return m;
    }

    public boolean isManagerOf(User u, Long elderId) {
        if (elderId == null || u.role != Role.ORG) return false;
        return elders.findById(elderId).map(e -> u.id.equals(e.managerId)).orElse(false);
    }

    // ---------------- 공고 ----------------
    private static final Map<String, double[]> DONG_XY = Map.of(
            "행복동", new double[]{0, 0}, "희망동", new double[]{1.0, 0.4}, "사랑동", new double[]{-0.8, 1.1},
            "미래동", new double[]{0.6, -1.3}, "소망동", new double[]{-1.5, -0.6});

    public static double distanceKm(String from, String to, long seed) {
        double[] a = from == null ? null : DONG_XY.get(from), b = to == null ? null : DONG_XY.get(to);
        double base = (a == null || b == null) ? (Objects.equals(from, to) ? 0 : 2.5) : Math.hypot(a[0] - b[0], a[1] - b[1]);
        return Math.round((base + 0.3 + (seed % 7) * 0.1) * 10) / 10.0;
    }

    public Map<String, Object> postCard(Post p, User viewer) {
        User owner = p.ownerId == null ? null : users.findById(p.ownerId).orElse(null);
        Elder elder = p.elderId == null ? null : elders.findById(p.elderId).orElse(null);
        boolean manager = elder != null && viewer != null && viewer.id.equals(elder.managerId);
        double w = p.weight != null ? p.weight : Rules.weight(p.difficulty != null ? p.difficulty : Rules.maxLevel(p.categories));
        Double estEarn = p.type == PostType.NEED && p.estHours != null ? Rules.earn(p.estHours, w) : null;
        // 기능 O-04: 관리 대상 특이사항(이슈 메모·방문 주의사항)은 담당자와 일정이 확정된 봉사자에게만 준다
        boolean issueOpen = manager || (elder != null && viewer != null && requests.findByPostId(p.id).stream()
                .anyMatch(r -> viewer.id.equals(r.volunteerId) && issueVisible(r.status)));
        Map<String, Object> m = map("id", p.id, "type", p.type, "ownerId", p.ownerId,
                "ownerName", owner != null ? owner.name : (elder != null ? (manager ? elder.alias + " 님" : Rules.maskAlias(elder.alias)) : null),
                "elderId", p.elderId, "elderDisplayName", elder == null ? null : (manager ? elder.alias + " 님" : Rules.maskAlias(elder.alias)),
                "elderCaution", elder == null || !issueOpen ? null : (p.issueNote != null && !p.issueNote.isBlank() ? p.issueNote : elder.caution),
                "elderIssue", elder == null || !issueOpen ? null : p.issueNote,
                "elderIssueLocked", elder != null && !issueOpen,
                "date", p.date.toString(), "startHour", p.startHour, "endHour", p.endHour, "area", p.area,
                "categories", p.categories, "title", p.title, "detail", p.detail, "note", p.note,
                "estHours", p.estHours, "difficulty", p.difficulty, "difficultyReason", p.difficultyReason, "weight", w, "estEarn", estEarn,
                "repeatRule", p.repeatRule, "seriesId", p.seriesId, "status", p.status,
                "distanceKm", distanceKm(viewer == null ? null : viewer.area, p.area, p.id),
                "createdAt", p.createdAt);
        if (owner != null) {
            double[] r = rating(owner.id);
            m.put("owner", map("id", owner.id, "name", owner.name, "ratingAvg", r[0], "ratingCount", (int) r[1], "ageGroup", owner.ageGroup,
                    "crime", owner.crimeStatus == VerifyStatus.APPROVED, "intro", owner.intro, "photoUrl", owner.photoUrl));
        } else {
            m.put("owner", null);
        }
        // 공고 상세의 '담당자' 칸: 관리 대상 공고는 담당 기관과 담당자 이름을 보여 준다(연락처는 주지 않는다)
        if (elder != null && elder.managerId != null) {
            User mgr = users.findById(elder.managerId).orElse(null);
            Organization org = mgr == null || mgr.orgId == null ? null : orgs.findById(mgr.orgId).orElse(null);
            m.put("manager", mgr == null ? null : map("name", mgr.name, "orgName", org == null ? null : org.name, "orgType", org == null ? null : org.orgType));
        } else {
            m.put("manager", null);
        }
        boolean mine = viewer != null && (viewer.id.equals(p.ownerId) || manager);
        m.put("mine", mine);
        return m;
    }

    // ---------------- 요청 ----------------
    public String roleOf(Request r, User u) {
        if (u.id.equals(r.volunteerId)) return "VOLUNTEER";
        if (r.recipientId != null && u.id.equals(r.recipientId)) return "RECIPIENT";
        if (r.elderId != null && isManagerOf(u, r.elderId)) return "MANAGER";
        // C-03: 독거노인 건의 담당자 권한은 지금 담당자에게만 있다(이관 뒤 예전에 요청을 보냈던 담당자는 권한이 없다)
        if (u.id.equals(r.senderId)) return r.elderId != null ? null : "RECIPIENT";
        return null;
    }

    public static String leftText(LocalDateTime deadline) {
        if (deadline == null) return null;
        long min = Duration.between(LocalDateTime.now(), deadline).toMinutes();
        if (min <= 0) return "기한 지남";
        if (min < 60) return min + "분";
        return (min / 60) + "시간";
    }

    /** 이 사용자가 지금 누를 수 있는 버튼. */
    public List<String> actions(Request r, String role, boolean rated) { return actions(r, role, rated, false); }

    public List<String> actions(Request r, String role, boolean rated, boolean sender) {
        List<String> a = new ArrayList<>();
        if (role == null) return a;
        boolean vol = role.equals("VOLUNTEER");
        boolean rec = !vol; // RECIPIENT 또는 MANAGER
        ReqStatus s = r.status;
        switch (s) {
            case SENT -> {
                boolean volResponds = r.kind != Kind.PROPOSAL;
                if (vol == volResponds && !sender) { a.add("accept"); a.add("reject"); }
                if (sender) a.add("cancel");
            }
            case VOL_ACCEPTED -> {
                if (rec) { a.add("accept"); a.add("decline"); }
                a.add("cancel"); a.add("message");
            }
            case CONFIRMED -> {
                if (vol) a.add("checkIn"); else a.add("showCode");
                a.add("cancel"); a.add("message");
                if (rec && !LocalDateTime.now().isBefore(r.startAt().plusMinutes(Rules.NOSHOW_MIN))) a.add("noshow");
            }
            case IN_PROGRESS -> {
                if (vol) { a.add("checkOut"); a.add("adjust"); } else a.add("showCode");
                a.add("message");
            }
            case CHECKED_OUT -> {
                if (vol) { a.add("log"); a.add("adjust"); }
                a.add("message");
            }
            case LOG_SUBMITTED -> {
                if (rec) a.add("confirm");
                if (vol) a.add("adjust");
                a.add("message");
            }
            case DONE -> {
                boolean canRate = rec || r.elderId == null; // 봉사자는 독거노인을 평가하지 않음(B-39)
                if (canRate && !rated) a.add("rate");
                if (vol) a.add("appeal");
            }
            default -> { }
        }
        if (rec && "PENDING".equals(r.adjustStatus)) a.add("adjustAnswer");
        a.add("report");
        return a;
    }

    public Map<String, Object> requestView(Request r, User viewer) {
        String role = roleOf(r, viewer);
        User vol = users.findById(r.volunteerId).orElse(null);
        User rec = r.recipientId == null ? null : users.findById(r.recipientId).orElse(null);
        User sender = users.findById(r.senderId).orElse(null);
        Elder elder = r.elderId == null ? null : elders.findById(r.elderId).orElse(null);
        Post post = r.postId == null ? null : posts.findById(r.postId).orElse(null);
        boolean manager = "MANAGER".equals(role);
        boolean rated = ratings.findByRequestId(r.id).stream().anyMatch(x -> x.raterId.equals(viewer.id));
        Map<String, Object> vc = vol == null ? null : volunteerCard(vol, isRegular(r.volunteerId, r.recipientId, r.elderId));
        if (vc != null && r.matchReason != null && !r.matchReason.isBlank()) vc.put("matchReason", r.matchReason); // LLM 추가 A(7.12)
        Map<String, Object> m = map("id", r.id, "kind", r.kind, "postId", r.postId, "postTitle", post == null ? null : post.title,
                "status", r.status, "date", r.date.toString(), "startHour", r.startHour, "hours", r.hours,
                "category", r.category, "content", r.content, "memo", r.memo, "difficulty", r.difficulty, "weight", r.weight,
                "estEarn", r.estEarn, "deadlineAt", r.deadlineAt, "leftText", r.status.pending() ? leftText(r.deadlineAt) : null,
                "rejectReason", r.rejectReason,
                "volunteer", vc,
                "recipient", rec == null ? null : userBrief(rec),
                "elder", lockIssue(withIssue(elderBrief(elder, manager), elder != null && post != null && post.elderId != null ? post.issueNote : null),
                        manager || ("VOLUNTEER".equals(role) && issueVisible(r.status))),
                "sender", userBrief(sender),
                "myRole", role,
                "actions", actions(r, role, rated, viewer.id.equals(r.senderId)),
                "checkInAt", r.checkInAt, "checkOutAt", r.checkOutAt, "checkMethod", r.checkMethod,
                "logRaw", r.logRaw, "logText", r.logText, "logPhotoUrl", r.logPhotoUrl, "logSubmittedAt", r.logSubmittedAt,
                "earned", r.earned, "rated", rated, "anomalyHold", r.anomalyHold,
                "adjust", r.adjustHours == null ? null : map("hours", r.adjustHours, "status", r.adjustStatus, "reason", r.adjustReason),
                "createdAt", r.createdAt, "doneAt", r.doneAt);
        // 전달 메모: 이 건의 당사자(봉사자 본인·받은 사람·담당자)만. 운영자 조회(role 없음)에는 넣지 않는다.
        if (role != null) m.put("careMemo", r.careMemo);
        m.put("canRejectVolunteer", "MANAGER".equals(role) && (r.status.pending() || r.status == ReqStatus.CONFIRMED)); // O1
        // B-51: 시작·종료 기록과 예정의 차이(분), 자동 마감 여부(B-53)
        m.put("autoClosed", r.autoClosed);
        m.put("gapMinutes", RequestService.gapMinutes(r));
        if (role != null && !role.equals("VOLUNTEER") && (r.status == ReqStatus.CONFIRMED || r.status == ReqStatus.IN_PROGRESS)) {
            if (elder != null) {
                // 독거노인 건: 도움 대상별 현관 QR 카드(C-11). 담당자에게만 간다.
                m.put("elderCard", true);
                m.put("checkCode", elder.qrCode);
                m.put("qrText", elder.qrCode == null ? null : Rules.qrText("ELD", elder.id, elder.qrCode));
                m.put("qrIssuedAt", elder.qrIssuedAt);
            } else {
                // 일반 건: 30초마다 바뀌는 코드. 화면은 GET /api/requests/{id}/qr 로 다시 받아 간다.
                long now = System.currentTimeMillis();
                String code = Rules.rotatingCode(r.qrKey(), Rules.qrWindow(now));
                m.put("checkCode", code);
                m.put("qrText", Rules.qrText("REQ", r.id, code));
                m.put("qrLeftSec", Rules.qrLeftSec(now));
            }
        }
        return m;
    }

    public List<Map<String, Object>> messagesOf(Long requestId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message x : messages.findByRequestIdOrderByIdAsc(requestId))
            out.add(map("id", x.id, "senderId", x.senderId, "senderLabel", x.senderLabel, "systemMsg", x.systemMsg,
                    "text", x.text, "warn", x.warn, "createdAt", x.createdAt));
        return out;
    }

    public static List<Object> typeRefList(String s) {
        return parse(s, new TypeReference<List<Object>>() {}, new ArrayList<>());
    }
}
