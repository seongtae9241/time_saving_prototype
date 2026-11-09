package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 동네 클래스(거꾸로 수업, 계약서 3.3·5.9). */
@Service
@Transactional
public class ClassService {
    private final NeighborClassRepo classes;
    private final ClassEnrollmentRepo enrolls;
    private final SpaceRepo spaces;
    private final UserRepo users;
    private final ElderRepo elders;
    private final BankService bank;
    private final NotificationService noti;
    private final AuthService auth;
    private final Views views;
    private final LedgerRepo ledger;

    public ClassService(NeighborClassRepo classes, ClassEnrollmentRepo enrolls, SpaceRepo spaces, UserRepo users, ElderRepo elders,
                        BankService bank, NotificationService noti, AuthService auth, Views views, LedgerRepo ledger) {
        this.classes = classes; this.enrolls = enrolls; this.spaces = spaces; this.users = users; this.elders = elders;
        this.bank = bank; this.noti = noti; this.auth = auth; this.views = views; this.ledger = ledger;
    }

    public record Step(String t, Integer min) {}

    public record Slot(LocalDate date, Double hour) {}

    public record ClassCmd(String kind, String title, String intro, List<Step> steps, String items, String caution,
                           LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId,
                           String mode, String bio, List<String> tags, List<String> qs, List<Slot> slots, String meet, Integer weeklyCap,
                           String place, Long spaceId) {
        public ClassCmd(String kind, String title, String intro, List<Step> steps, String items, String caution,
                        LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId) {
            this(kind, title, intro, steps, items, caution, date, startHour, hours, capacity, teacherElderId, null, null, null, null, null, null, null, null, null);
        }
        public ClassCmd(String kind, String title, String intro, List<Step> steps, String items, String caution,
                        LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId,
                        String mode, String bio, List<String> tags, List<String> qs, List<Slot> slots, String meet, Integer weeklyCap) {
            this(kind, title, intro, steps, items, caution, date, startHour, hours, capacity, teacherElderId, mode, bio, tags, qs, slots, meet, weeklyCap, null, null);
        }
    }

    /** 같이 해 보기 정원 상한(황수빈 v2) */
    public static final int GROUP_MAX = 4;
    private static final Set<String> ONE_ACTIVE = Set.of("WAIT", "OK", "MET");
    /** 집·자택처럼 닫힌 곳은 1:1 만남 장소로 받지 않는다 */
    private static final java.util.regex.Pattern PRIVATE_PLACE = java.util.regex.Pattern.compile("우리\\s*집|저희\\s*집|자택|댁|원룸|오피스텔|빌라|아파트|\\d+\\s*호|모텔|숙소");

    public NeighborClass get(Long id) { return classes.findById(id).orElseThrow(() -> ApiException.notFound("수업을 찾을 수 없어요")); }

    private int joined(Long classId) {
        return (int) enrolls.findByClassId(classId).stream().filter(e -> e.status != EnrollStatus.CANCELLED).count();
    }

    private List<ClassEnrollment> oneEnrolls(Long classId) {
        return enrolls.findByClassId(classId).stream().filter(e -> e.oneStatus != null && !"CANCELLED".equals(e.oneStatus))
                .sorted(Comparator.comparing((ClassEnrollment e) -> e.id)).toList();
    }

    /** 이번 주 남은 자리 = 주당 정원 − (진행 중 신청 + 최근 7일 안에 끝난 만남) */
    public int left(NeighborClass c) {
        int cap = c.weeklyCap == null ? 2 : c.weeklyCap;
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long used = oneEnrolls(c.id).stream().filter(e -> ONE_ACTIVE.contains(e.oneStatus)
                || ("DONE".equals(e.oneStatus) && e.checkOutAt != null && e.checkOutAt.isAfter(weekAgo))).count();
        return (int) Math.max(0, cap - used);
    }

    private static final String[] WD = {"월", "화", "수", "목", "금", "토", "일"};
    public static String slotText(LocalDate d, Double h) {
        double hr = h == null ? 0 : h;
        int H = (int) hr;
        String t = (H < 12 ? "오전 " : "오후 ") + (H > 12 ? H - 12 : H) + "시" + (hr - H >= 0.5 ? " 30분" : "");
        return d.getMonthValue() + "/" + d.getDayOfMonth() + "(" + WD[d.getDayOfWeek().getValue() - 1] + ") " + t;
    }

    private String displayTeacher(NeighborClass c, User viewer) {
        Elder e = c.teacherElderId == null ? null : elders.findById(c.teacherElderId).orElse(null);
        boolean manager = e != null && viewer != null && viewer.id.equals(e.managerId);
        if (manager) return e.alias;
        if (c.teacherLabel != null && !c.teacherLabel.isBlank()) return c.teacherLabel;
        if (c.teacherId != null) return users.findById(c.teacherId).map(u -> u.name).orElse("선생님");
        return e == null ? "선생님" : Rules.maskAlias(e.alias).replace(" 님", "");
    }

    private Map<String, Object> enrollView(ClassEnrollment e, boolean withUser) {
        Map<String, Object> m = Views.map("id", e.id, "status", e.oneStatus, "slotDate", e.slotDate == null ? null : e.slotDate.toString(),
                "slotHour", e.slotHour, "slotText", e.slotDate == null ? null : slotText(e.slotDate, e.slotHour), "question", e.question,
                "proposed", Boolean.TRUE.equals(e.proposed), "note", e.note,
                "checkInAt", e.checkInAt == null ? null : e.checkInAt.toString(), "checkOutAt", e.checkOutAt == null ? null : e.checkOutAt.toString(),
                "checkMethod", e.checkMethod, "checkOutMethod", e.checkOutMethod);
        if (withUser) {
            User u = users.findById(e.userId).orElse(null);
            m.put("userName", u == null ? "" : u.name);
            m.put("userAgeGroup", u == null ? null : u.ageGroup);
        }
        return m;
    }

    private ClassEnrollment myLatestOne(Long classId, User me) {
        if (me == null) return null;
        return oneEnrolls(classId).stream().filter(e -> e.userId.equals(me.id)).reduce((a, b) -> b).orElse(null);
    }

    public boolean isHost(NeighborClass c, User u) {
        if (u.id.equals(c.teacherId) || u.id.equals(c.createdBy)) return true;
        return c.teacherElderId != null && views.isManagerOf(u, c.teacherElderId);
    }

    public Map<String, Object> view(NeighborClass c, User viewer) {
        User t = c.teacherId == null ? null : users.findById(c.teacherId).orElse(null);
        Elder e = c.teacherElderId == null ? null : elders.findById(c.teacherElderId).orElse(null);
        boolean manager = e != null && viewer != null && viewer.id.equals(e.managerId);
        List<ClassEnrollment> list = enrolls.findByClassId(c.id);
        ClassEnrollment mine = viewer == null ? null : list.stream().filter(x -> x.userId.equals(viewer.id) && x.status != EnrollStatus.CANCELLED).findFirst().orElse(null);
        double fee = c.hours; // 1시간 = 1시간(평등 정산)
        String ageG = t != null ? t.ageGroup : (e != null ? e.ageGroup : null);
        boolean elderT = e != null || (ageG != null && ageG.matches("^(6|7|8|9)0대.*"));
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (ClassEnrollment x : list) if (x.review != null) reviews.add(Views.map("name", users.findById(x.userId).map(u -> u.name).orElse(""), "text", x.review));
        boolean one = c.one();
        boolean host = viewer != null && isHost(c, viewer);
        Map<String, Object> m = Views.map("id", c.id, "mode", one ? "ONE" : "GROUP", "kind", c.kind, "title", c.title, "intro", c.intro,
                "teacherId", c.teacherId, "teacherElderId", c.teacherElderId,
                "teacherName", displayTeacher(c, viewer),
                "teacherAgeGroup", ageG, "teacherAge", c.teacherAgeLabel != null && !c.teacherAgeLabel.isBlank() ? c.teacherAgeLabel : ageG,
                "elderTeacher", elderT, "byOrg", e != null,
                "createdBy", c.createdBy,
                "steps", Views.parse(c.stepsJson, new TypeReference<List<Map<String, Object>>>() {}, List.of()),
                "items", c.items, "caution", c.caution, "level", c.level, "levelReason", c.levelReason,
                "date", c.date.toString(), "startHour", c.startHour, "hours", c.hours, "place", c.place, "spaceId", c.spaceId,
                "capacity", c.capacity, "joined", joined(c.id), "status", c.status,
                "fee", fee, "teacherEarn", c.hours, "teacherEarnIfFull", Rules.classTeacherEarn(one ? 1 : c.capacity, c.hours),
                "kindOpen", Rules.CLASS_KINDS.contains(c.kind),
                "myEnrollment", mine == null ? null : Views.map("id", mine.id, "status", mine.status, "review", mine.review,
                        "checkMethod", mine.checkMethod, "checkInAt", mine.checkInAt == null ? null : mine.checkInAt.toString()),
                "host", host,
                "note", Views.parse(c.noteJson, new TypeReference<Map<String, Object>>() {}, null),
                "reviews", reviews);
        if (e != null && e.managerId != null) m.put("managerName", users.findById(e.managerId).map(u -> u.name).orElse(null));
        if (one) {
            List<ClassEnrollment> ones = oneEnrolls(c.id);
            ClassEnrollment mine1 = myLatestOne(c.id, viewer);
            m.put("myEnrollment", null);
            m.put("bio", c.bio);
            m.put("tags", c.tags == null ? List.of() : c.tags);
            m.put("met", c.met == null ? 0 : c.met);
            m.put("weeklyCap", c.weeklyCap == null ? 2 : c.weeklyCap);
            m.put("left", left(c));
            m.put("qs", Views.parse(c.qsJson, new TypeReference<List<String>>() {}, List.of()));
            m.put("slots", Views.parse(c.slotsJson, new TypeReference<List<Map<String, Object>>>() {}, List.of()));
            m.put("meet", c.meet);
            m.put("myOne", mine1 == null ? null : enrollView(mine1, false));
            if (host) {
                List<Map<String, Object>> rq = new ArrayList<>();
                for (ClassEnrollment x : ones) if (ONE_ACTIVE.contains(x.oneStatus)) rq.add(enrollView(x, true));
                m.put("requests", rq);
                m.put("newRequests", ones.stream().filter(x -> "WAIT".equals(x.oneStatus) && !Boolean.TRUE.equals(x.proposed)).count());
            }
        }
        return m;
    }

    public List<Map<String, Object>> list(User viewer, String kind, String status) { return list(viewer, kind, status, null); }

    public List<Map<String, Object>> list(User viewer, String kind, String status, String mode) {
        ClassStatus st = status == null || status.isBlank() ? ClassStatus.OPEN : ClassStatus.valueOf(status);
        List<Map<String, Object>> out = new ArrayList<>();
        classes.findAll().stream()
                .filter(c -> c.status == st && (kind == null || kind.isBlank() || "전체".equals(kind) || kind.equals(c.kind)))
                .filter(c -> mode == null || mode.isBlank() || mode.equalsIgnoreCase(c.one() ? "ONE" : "GROUP"))
                .sorted(Comparator.comparing((NeighborClass c) -> c.date))
                .forEach(c -> out.add(view(c, viewer)));
        // 1:1은 연 순서대로, 같이 해 보기는 어르신 선생님 먼저
        if (st == ClassStatus.OPEN) out.sort(Comparator.comparing((Map<String, Object> m) -> !"ONE".equals(m.get("mode")))
                .thenComparing(m -> "ONE".equals(m.get("mode")) ? 0L : (Boolean) m.get("elderTeacher") ? 1L : 2L)
                .thenComparing(m -> "ONE".equals(m.get("mode")) ? ((Number) m.get("id")).longValue() : 0L));
        return out;
    }

    /** 클래스 주제 확인(수업 열기 인터뷰 첫 단계, 규칙만). */
    public Map<String, Object> checkTopic(String text) {
        RuleEngine.FilterResult f = RuleEngine.classFilter(text);
        return Views.map("level", f.level(), "reason", f.reason(), "category", f.blockCategory(), "source", "rule");
    }

    public NeighborClass create(User me, ClassCmd c) {
        boolean one = "ONE".equalsIgnoreCase(c.mode());
        if (c.kind() == null || !Rules.CLASS_KINDS.contains(c.kind()))
            throw ApiException.bad("INVALID_KIND", "이웃 클래스는 말벗·이야기, 전자기기 사용법 두 가지만 열 수 있어요");
        if (c.title() == null || c.title().isBlank()) throw ApiException.bad("TITLE_REQUIRED", "수업 이름을 적어 주세요");
        List<Slot> slots = new ArrayList<>();
        double hours;
        int cap;
        LocalDate date;
        double startHour;
        if (one) {
            hours = c.hours() == null ? 1 : c.hours();
            if (c.slots() == null || c.slots().isEmpty()) throw ApiException.bad("SLOTS_REQUIRED", "가능한 시간을 하나 이상 정해 주세요");
            for (Slot sl : c.slots()) {
                if (sl == null || sl.date() == null || sl.hour() == null) throw ApiException.bad("INVALID_SLOT", "가능한 시간을 다시 골라 주세요");
                if (sl.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
                Rules.checkHours(sl.hour(), hours);
                if (slots.stream().noneMatch(x -> x.date().equals(sl.date()) && x.hour().equals(sl.hour()))) slots.add(sl);
            }
            if (slots.size() > 6) throw ApiException.bad("INVALID_SLOT", "가능한 시간은 6개까지 정할 수 있어요");
            slots.sort(Comparator.comparing(Slot::date).thenComparing(Slot::hour));
            if (c.meet() == null || c.meet().isBlank()) throw ApiException.bad("MEET_REQUIRED", "만나는 곳을 적어 주세요");
            if (PRIVATE_PLACE.matcher(c.meet()).find()) throw ApiException.bad("MEET_NOT_PUBLIC", "1:1 수업은 도서관·라운지·시장처럼 열린 곳에서만 만나요");
            cap = 1;
            date = slots.get(0).date();
            startHour = slots.get(0).hour();
        } else {
            if (c.date() == null || c.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
            if (c.startHour() == null || c.hours() == null) throw ApiException.bad("INVALID_TIME", "시간을 정해 주세요");
            Rules.checkHours(c.startHour(), c.hours());
            hours = c.hours();
            if (me.role == Role.USER && c.capacity() != null && c.capacity() > GROUP_MAX)
                throw ApiException.bad("INVALID_CAPACITY", "여럿이 하는 수업은 " + GROUP_MAX + "명까지 함께할 수 있어요");
            cap = c.capacity() == null ? GROUP_MAX : c.capacity();
            if (cap < 1 || cap > GROUP_MAX) throw ApiException.bad("INVALID_CAPACITY", "같이 해 보기는 " + GROUP_MAX + "명까지 함께할 수 있어요");
            date = c.date();
            startHour = c.startHour();
        }
        NeighborClass k = new NeighborClass();
        if (me.role == Role.ORG) {
            if (c.teacherElderId() == null) throw ApiException.bad("ELDER_REQUIRED", "어느 어르신의 수업인지 골라 주세요");
            if (!views.isManagerOf(me, c.teacherElderId())) throw ApiException.forbidden("내가 담당하는 대상만 대신 수업을 열 수 있어요");
            k.teacherElderId = c.teacherElderId();
        } else if (me.role == Role.USER) {
            k.teacherId = me.id;
        } else throw ApiException.forbidden("수업을 열 수 없는 계정이에요");
        String text = String.join(" ", c.title(), Objects.toString(c.intro(), ""), Objects.toString(c.items(), ""),
                c.steps() == null ? "" : String.join(" ", c.steps().stream().map(s -> Objects.toString(s.t(), "")).toList()),
                Objects.toString(c.bio(), ""), c.tags() == null ? "" : String.join(" ", c.tags()), c.qs() == null ? "" : String.join(" ", c.qs()));
        RuleEngine.FilterResult f = RuleEngine.classFilter(text);
        if (f.level() == FilterLevel.BLOCK) throw ApiException.bad("FORBIDDEN_REQUEST", f.reason());
        k.level = f.level() == FilterLevel.CAUTION ? "주의" : "허용";
        k.levelReason = f.level() == FilterLevel.CAUTION ? f.reason() : null;
        k.createdBy = me.id;
        k.kind = c.kind();
        k.title = c.title().trim();
        k.intro = c.intro();
        k.stepsJson = Views.json(c.steps() == null ? List.of() : c.steps());
        k.items = c.items();
        k.caution = c.caution();
        k.date = date;
        k.startHour = startHour;
        k.hours = hours;
        k.capacity = cap;
        k.checkCode = auth.sixDigits();
        if (one) {
            k.mode = "ONE";
            k.bio = c.bio() == null || c.bio().isBlank() ? (me.role == Role.USER ? me.intro : "담당자가 대신 올렸어요") : c.bio().trim();
            k.tags = c.tags() == null || c.tags().isEmpty() ? new ArrayList<>(List.of(c.kind())) : new ArrayList<>(c.tags().stream().filter(x -> x != null && !x.isBlank()).limit(5).toList());
            k.met = 0;
            int wc = c.weeklyCap() == null ? 2 : c.weeklyCap();
            if (wc < 1 || wc > 10) throw ApiException.bad("INVALID_CAPACITY", "한 주에 1~10명까지 받을 수 있어요");
            k.weeklyCap = wc;
            k.qsJson = Views.json(c.qs() == null ? List.of() : c.qs().stream().filter(x -> x != null && !x.isBlank()).limit(5).toList());
            List<Map<String, Object>> sl = new ArrayList<>();
            for (Slot x : slots) sl.add(Views.map("date", x.date().toString(), "hour", x.hour()));
            k.slotsJson = Views.json(sl);
            k.meet = c.meet().trim();
            k.place = k.meet;
            k.status = ClassStatus.OPEN; // 1:1은 공간 배정 없이 바로 신청을 받는다
            classes.save(k);
            return k;
        }
        k.mode = "GROUP";
        // 여럿이 하는 수업은 기관이 장소를 정할 때만 열린다: 담당자가 장소와 함께 열면 바로 모집, 사용자가 열면 기관 배정 대기
        if (me.role == Role.ORG) {
            Space s = null;
            if (c.spaceId() != null) {
                s = spaces.findById(c.spaceId()).orElseThrow(() -> ApiException.notFound("공간을 찾을 수 없어요"));
                if (!s.orgId.equals(me.orgId)) throw ApiException.forbidden("우리 기관 공간만 정할 수 있어요");
            }
            String place = c.place() != null && !c.place().isBlank() ? c.place().trim() : s == null ? null : s.name;
            if (place == null) throw ApiException.bad("SPACE_REQUIRED", "여럿이 하는 수업은 장소를 정해야 열 수 있어요");
            k.spaceId = s == null ? null : s.id;
            k.place = place;
            if (s != null && s.capacity > 0 && k.capacity > s.capacity) k.capacity = s.capacity;
            k.status = ClassStatus.OPEN;
            classes.save(k);
            return k;
        }
        k.status = ClassStatus.SPACE_PENDING;
        classes.save(k);
        for (User m : users.findByRole(Role.ORG))
            if (m.status == UserStatus.ACTIVE)
                noti.send(m.id, "CLASS_SPACE", "공간 배정이 필요한 수업이 있어요", k.title + " · " + k.date, "/org/spaces");
        return k;
    }

    private static void requireGroup(NeighborClass c) {
        if (c.one()) throw ApiException.bad("ONE_CLASS", "1:1 수업은 시간을 골라 신청해 주세요");
    }

    public ClassEnrollment apply(User me, Long id) {
        NeighborClass c = get(id);
        requireGroup(c);
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정으로 신청해 주세요");
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("CLASS_CLOSED", "모집 중인 수업이 아니에요");
        if (isHost(c, me)) throw ApiException.bad("OWN_CLASS", "내 수업에는 신청할 수 없어요");
        if (enrolls.findByClassId(id).stream().anyMatch(e -> e.userId.equals(me.id) && e.status != EnrollStatus.CANCELLED))
            throw ApiException.conflict("DUPLICATE", "이미 신청한 수업이에요");
        if (joined(id) >= c.capacity) throw ApiException.conflict("CLASS_FULL", "정원이 다 찼어요");
        double fee = c.hours;
        bank.requireUsable(me.id, fee);
        ClassEnrollment e = new ClassEnrollment();
        e.classId = id;
        e.userId = me.id;
        enrolls.save(e);
        bank.add(me.id, LedgerType.HOLD, fee, c.hours, 1.0, null, c.id, "클래스 수강 보류 · " + c.title);
        Long host = c.teacherId != null ? c.teacherId : c.createdBy;
        noti.send(host, "CLASS_APPLY", me.name + " 님이 수업을 신청했어요", c.title + " · " + joined(id) + "/" + c.capacity + "명", "/classes/" + c.id);
        return e;
    }

    private ClassEnrollment myEnroll(Long classId, User me) {
        return enrolls.findByClassId(classId).stream().filter(e -> e.userId.equals(me.id) && e.status != EnrollStatus.CANCELLED)
                .findFirst().orElseThrow(() -> ApiException.notFound("신청 내역이 없어요"));
    }

    public void cancel(User me, Long id) {
        NeighborClass c = get(id);
        if (c.one()) { cancelOne(me, id); return; }
        ClassEnrollment e = myEnroll(id, me);
        if (e.status != EnrollStatus.APPLIED) throw ApiException.conflict("INVALID_STATE", "출석한 수업은 취소할 수 없어요");
        e.status = EnrollStatus.CANCELLED;
        bank.add(me.id, LedgerType.RELEASE, c.hours, c.hours, 1.0, null, c.id, "클래스 신청 취소 · " + c.title);
    }

    public ClassEnrollment attend(User me, Long id, String code) { return attend(me, id, code, null); }

    public ClassEnrollment attend(User me, Long id, String code, String method) {
        NeighborClass c = get(id);
        requireGroup(c);
        ClassEnrollment e = myEnroll(id, me);
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("INVALID_STATE", "출석할 수 없는 수업이에요");
        if (e.status == EnrollStatus.ATTENDED) return e;
        if (code == null || !code.trim().equals(c.checkCode)) throw ApiException.bad("WRONG_CODE", "출석 번호가 맞지 않아요");
        double fee = c.hours;
        e.status = EnrollStatus.ATTENDED;
        e.checkMethod = checkMethod(method);
        e.checkInAt = LocalDateTime.now();
        bank.add(me.id, LedgerType.RELEASE, fee, c.hours, 1.0, null, c.id, "클래스 보류 해제 · " + c.title);
        bank.add(me.id, LedgerType.CLASS_FEE, -fee, c.hours, 1.0, null, c.id, "클래스 수강 · " + c.title);
        return e;
    }

    public Map<String, Object> qr(User me, Long id) {
        NeighborClass c = get(id);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 볼 수 있어요");
        return Views.map("classId", c.id, "code", c.checkCode, "qrText", Rules.qrText(c.one() ? "ONE" : "CLS", c.id, c.checkCode));
    }

    public Map<String, Object> finish(User me, Long id, List<String> steps, String tip) {
        NeighborClass c = get(id);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 마칠 수 있어요");
        requireGroup(c);
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("INVALID_STATE", "진행 중인 수업이 아니에요");
        c.status = ClassStatus.DONE;
        c.noteJson = Views.json(Views.map("steps", steps == null ? List.of() : steps, "tip", tip));
        double fee = c.hours;
        int attended = 0;
        for (ClassEnrollment e : enrolls.findByClassId(id)) {
            if (e.status == EnrollStatus.ATTENDED) {
                attended++;
                noti.send(e.userId, "CLASS_NOTE", "배움 노트가 도착했어요", c.title, "/classes/" + c.id);
            } else if (e.status == EnrollStatus.APPLIED) { // 오지 않은 수강생: 보류만 풀고 차감하지 않음
                e.status = EnrollStatus.CANCELLED;
                bank.add(e.userId, LedgerType.RELEASE, fee, c.hours, 1.0, null, c.id, "클래스 불참 · 보류 해제");
            }
        }
        // 평등 정산: 참석 인원 × 수업 시간 (준비 시간·공동 적립 없음)
        double teacherEarn = Rules.classTeacherEarn(attended, c.hours);
        if (teacherEarn > 0) {
            String label = "클래스 선생님 (" + attended + "명 × " + Rules.fmtHours(c.hours) + "시간) · " + c.title;
            if (c.teacherId != null) {
                bank.add(c.teacherId, LedgerType.CLASS_EARN, teacherEarn, c.hours, 1.0, null, c.id, label);
            } else {
                Ledger l = new Ledger();
                l.elderId = c.teacherElderId;
                l.type = LedgerType.CLASS_EARN;
                l.amount = teacherEarn;
                l.baseHours = c.hours;
                l.weight = 1.0;
                l.classId = c.id;
                l.label = "어르신 선생님 적립 (기관이 대신 받음) · " + label;
                ledger.save(l);
            }
        }
        return Views.map("attended", attended, "teacherEarn", teacherEarn, "fee", fee, "class", view(c, me));
    }

    public void review(User me, Long id, String text) {
        ClassEnrollment e = myEnroll(id, me);
        if (e.status != EnrollStatus.ATTENDED) throw ApiException.conflict("INVALID_STATE", "출석한 수업에만 후기를 남길 수 있어요");
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "후기를 적어 주세요");
        e.review = text.trim();
    }

    public Map<String, Object> mine(User me) {
        List<Map<String, Object>> teaching = new ArrayList<>(), applied = new ArrayList<>(), past = new ArrayList<>();
        Set<Long> myClassIds = new HashSet<>();
        for (ClassEnrollment e : enrolls.findByUserId(me.id)) if (e.status != EnrollStatus.CANCELLED) myClassIds.add(e.classId);
        for (NeighborClass c : classes.findAll()) {
            boolean host = isHost(c, me);
            boolean enrolled = myClassIds.contains(c.id);
            if (c.status == ClassStatus.DONE) { if (host || enrolled) past.add(view(c, me)); }
            else if (c.status != ClassStatus.CANCELLED) {
                if (host) teaching.add(view(c, me));
                if (enrolled) applied.add(view(c, me));
            }
        }
        return Views.map("teaching", teaching, "applied", applied, "past", past);
    }

    // ---- 1:1 클래스 (황수빈 v2) ----
    private NeighborClass oneClass(Long id) {
        NeighborClass c = get(id);
        if (!c.one()) throw ApiException.bad("NOT_ONE_CLASS", "1:1 수업이 아니에요");
        return c;
    }

    private ClassEnrollment enrollment(Long enrollmentId) {
        return enrolls.findById(enrollmentId).orElseThrow(() -> ApiException.notFound("신청 내역이 없어요"));
    }

    private ClassEnrollment myActiveOne(Long classId, User me) {
        ClassEnrollment e = myLatestOne(classId, me);
        if (e == null || !ONE_ACTIVE.contains(e.oneStatus)) throw ApiException.notFound("진행 중인 신청이 없어요");
        return e;
    }

    /** 선생님(사용자) 또는 어르신 선생님의 담당자에게 알린다. */
    private void notifyHost(NeighborClass c, String type, String title, String body, String link) {
        if (c.teacherId != null) { noti.send(c.teacherId, type, title, body, link); return; }
        Elder e = c.teacherElderId == null ? null : elders.findById(c.teacherElderId).orElse(null);
        if (e != null && e.managerId != null) noti.send(e.managerId, type, title, body, "/o/space");
        else if (c.createdBy != null) noti.send(c.createdBy, type, title, body, link);
    }

    private boolean slotOffered(NeighborClass c, LocalDate d, Double h) {
        List<Map<String, Object>> sl = Views.parse(c.slotsJson, new TypeReference<List<Map<String, Object>>>() {}, List.of());
        return sl.stream().anyMatch(x -> d.toString().equals(String.valueOf(x.get("date"))) && h != null
                && Math.abs(((Number) x.get("hour")).doubleValue() - h) < 1e-9);
    }

    public ClassEnrollment applyOne(User me, Long id, LocalDate slotDate, Double slotHour, String question) {
        NeighborClass c = oneClass(id);
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정으로 신청해 주세요");
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("CLASS_CLOSED", "신청을 받는 수업이 아니에요");
        if (isHost(c, me)) throw ApiException.bad("OWN_CLASS", "내 수업에는 신청할 수 없어요");
        ClassEnrollment prev = myLatestOne(id, me);
        if (prev != null && ONE_ACTIVE.contains(prev.oneStatus)) throw ApiException.conflict("DUPLICATE", "이미 신청한 수업이에요");
        if (left(c) <= 0) throw ApiException.conflict("CLASS_FULL", "이번 주 자리가 다 찼어요. 다음 주에 다시 열려요");
        if (question == null || question.isBlank()) throw ApiException.bad("QUESTION_REQUIRED", "궁금한 점을 한 줄만 적어 주세요");
        if (question.length() > 300) throw ApiException.bad("QUESTION_TOO_LONG", "궁금한 점은 300자 안으로 적어 주세요");
        if (slotDate == null || slotHour == null || !slotOffered(c, slotDate, slotHour))
            throw ApiException.bad("INVALID_SLOT", "선생님이 정한 시간 중에서 골라 주세요");
        if (slotDate.isBefore(LocalDate.now())) throw ApiException.bad("INVALID_SLOT", "지난 시간은 고를 수 없어요");
        bank.requireUsable(me.id, c.hours);
        ClassEnrollment e = new ClassEnrollment();
        e.classId = id;
        e.userId = me.id;
        e.status = EnrollStatus.APPLIED;
        e.oneStatus = "WAIT";
        e.slotDate = slotDate;
        e.slotHour = slotHour;
        e.question = question.trim();
        e.proposed = false;
        enrolls.save(e);
        bank.add(me.id, LedgerType.HOLD, c.hours, c.hours, 1.0, null, c.id, "1:1 클래스 신청 보류 · " + c.title);
        String slot = slotText(slotDate, slotHour);
        if (c.teacherId == null)
            notifyHost(c, "CLASS_ONE_APPLY", displayTeacher(c, null) + " 어르신 1:1 수업에 신청이 왔어요", me.name + " 님 · " + slot + " · 어르신께 여쭤보고 수락해 주세요", "/o/space");
        else
            notifyHost(c, "CLASS_ONE_APPLY", c.title + " · 새 1:1 신청이 왔어요", me.name + " 님 · " + slot + " · 수락하면 확정돼요", "/cls/" + c.id);
        return e;
    }

    public ClassEnrollment acceptOne(User me, Long enrollmentId) {
        ClassEnrollment e = enrollment(enrollmentId);
        NeighborClass c = oneClass(e.classId);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 수락할 수 있어요");
        if (!"WAIT".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "수락을 기다리는 신청이 아니에요");
        if (Boolean.TRUE.equals(e.proposed)) throw ApiException.conflict("INVALID_STATE", "제안한 시간에 대한 답을 기다리고 있어요");
        e.oneStatus = "OK";
        noti.send(e.userId, "CLASS_ONE_ACCEPT", displayTeacher(c, null) + " 선생님이 1:1 신청을 수락했어요", slotText(e.slotDate, e.slotHour) + " · " + c.meet, "/cls/" + c.id);
        return e;
    }

    public ClassEnrollment proposeOne(User me, Long enrollmentId, LocalDate date, Double hour) {
        ClassEnrollment e = enrollment(enrollmentId);
        NeighborClass c = oneClass(e.classId);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 제안할 수 있어요");
        if (!"WAIT".equals(e.oneStatus) && !"OK".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "시간을 바꿀 수 없는 신청이에요");
        if (date == null || hour == null || date.isBefore(LocalDate.now())) throw ApiException.bad("INVALID_SLOT", "오늘 이후 시간을 골라 주세요");
        Rules.checkHours(hour, c.hours);
        if (date.equals(e.slotDate) && hour.equals(e.slotHour)) throw ApiException.bad("INVALID_SLOT", "지금과 다른 시간을 골라 주세요");
        e.slotDate = date;
        e.slotHour = hour;
        e.oneStatus = "WAIT";
        e.proposed = true;
        noti.send(e.userId, "CLASS_ONE_PROPOSE", displayTeacher(c, null) + " 선생님이 다른 시간을 제안했어요", slotText(date, hour) + " · 괜찮으면 수락해 주세요", "/cls/" + c.id);
        return e;
    }

    public ClassEnrollment acceptProposal(User me, Long id) {
        NeighborClass c = oneClass(id);
        ClassEnrollment e = myActiveOne(id, me);
        if (!"WAIT".equals(e.oneStatus) || !Boolean.TRUE.equals(e.proposed)) throw ApiException.conflict("INVALID_STATE", "제안받은 시간이 없어요");
        e.proposed = false;
        e.oneStatus = "OK";
        notifyHost(c, "CLASS_ONE_ACCEPT", me.name + " 님이 제안한 시간을 받아들였어요", c.title + " · " + slotText(e.slotDate, e.slotHour), "/cls/" + c.id);
        return e;
    }

    public void cancelOne(User me, Long id) {
        NeighborClass c = oneClass(id);
        ClassEnrollment e = myActiveOne(id, me);
        if ("MET".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "이미 시작한 만남은 취소할 수 없어요");
        if ("OK".equals(e.oneStatus) && !e.slotDate.isAfter(LocalDate.now()))
            throw ApiException.conflict("LATE_CANCEL", "약속 당일에는 취소할 수 없어요. 하루 전까지만 무료로 취소돼요");
        e.oneStatus = "CANCELLED";
        e.status = EnrollStatus.CANCELLED;
        bank.add(me.id, LedgerType.RELEASE, c.hours, c.hours, 1.0, null, c.id, "1:1 클래스 신청 취소 · " + c.title);
        notifyHost(c, "CLASS_ONE_CANCEL", me.name + " 님이 1:1 신청을 취소했어요", c.title + " · " + slotText(e.slotDate, e.slotHour), "/cls/" + c.id);
    }

    /** 만나서 시작 QR(6자리): 보류 해제 → 배우는 사람 −h, 선생님 +h (어르신 선생님은 기관이 대신 받는 기록). 1시간 = 1시간. */
    private static String checkMethod(String method) { return Rules.checkMethod(method); }

    public ClassEnrollment startOne(User me, Long id, String code) { return startOne(me, id, code, null); }

    public ClassEnrollment startOne(User me, Long id, String code, String method) {
        NeighborClass c = oneClass(id);
        ClassEnrollment e = myActiveOne(id, me);
        if ("MET".equals(e.oneStatus)) return e;
        if (!"OK".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "선생님이 수락한 뒤에 시작할 수 있어요");
        if (code == null || !code.trim().equals(c.checkCode)) throw ApiException.bad("WRONG_CODE", "QR 번호가 맞지 않아요");
        e.oneStatus = "MET";
        e.status = EnrollStatus.ATTENDED;
        e.checkInAt = LocalDateTime.now();
        e.checkMethod = checkMethod(method);
        bank.add(me.id, LedgerType.RELEASE, c.hours, c.hours, 1.0, null, c.id, "1:1 클래스 보류 해제 · " + c.title);
        bank.add(me.id, LedgerType.CLASS_FEE, -c.hours, c.hours, 1.0, null, c.id, "1:1 클래스 · " + c.title);
        if (c.teacherId != null) {
            bank.add(c.teacherId, LedgerType.CLASS_EARN, c.hours, c.hours, 1.0, null, c.id, "1:1 클래스 선생님 · " + c.title);
        } else {
            Ledger l = new Ledger();
            l.elderId = c.teacherElderId;
            l.type = LedgerType.CLASS_EARN;
            l.amount = c.hours;
            l.baseHours = c.hours;
            l.weight = 1.0;
            l.classId = c.id;
            l.label = "어르신 선생님 적립 (기관이 대신 받음) · 1:1 · " + c.title;
            ledger.save(l);
        }
        return e;
    }

    public ClassEnrollment endOne(User me, Long id, String code) { return endOne(me, id, code, null); }

    public ClassEnrollment endOne(User me, Long id, String code, String method) {
        NeighborClass c = oneClass(id);
        ClassEnrollment e = myLatestOne(id, me);
        if (e != null && "DONE".equals(e.oneStatus)) return e;
        if (e == null || !"MET".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "시작 QR을 먼저 찍어 주세요");
        if (code == null || !code.trim().equals(c.checkCode)) throw ApiException.bad("WRONG_CODE", "QR 번호가 맞지 않아요");
        e.oneStatus = "DONE";
        e.checkOutAt = LocalDateTime.now();
        e.checkOutMethod = checkMethod(method);
        c.met = (c.met == null ? 0 : c.met) + 1;
        notifyHost(c, "CLASS_ONE_DONE", "1:1 만남이 끝났어요", me.name + " 님 · " + c.title + " · " + c.hours + "시간 적립", "/cls/" + c.id);
        return e;
    }

    public ClassEnrollment noteOne(User me, Long id, String text) {
        NeighborClass c = oneClass(id);
        ClassEnrollment e = myLatestOne(id, me);
        if (e == null || !"DONE".equals(e.oneStatus)) throw ApiException.conflict("INVALID_STATE", "만남이 끝난 뒤에 남길 수 있어요");
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "한 줄만 적어 주세요");
        if (text.length() > 300) throw ApiException.bad("TEXT_TOO_LONG", "300자 안으로 적어 주세요");
        e.note = text.trim();
        notifyHost(c, "CLASS_ONE_NOTE", me.name + " 님이 오늘 배운 한 줄을 남겼어요", "“" + e.note + "”", "/cls/" + c.id);
        return e;
    }

    /** 담당자: 내가 맡은 어르신의 1:1 수업(신청 목록 포함). */
    public List<Map<String, Object>> orgOneClasses(User me) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NeighborClass c : classes.findAll())
            if (c.one() && c.teacherElderId != null && c.status == ClassStatus.OPEN && views.isManagerOf(me, c.teacherElderId)) out.add(view(c, me));
        return out;
    }

    // ---- 공간 (담당자) ----
    public NeighborClass assignSpace(User me, Long id, Long spaceId, String place) {
        NeighborClass c = get(id);
        if (c.status != ClassStatus.SPACE_PENDING) throw ApiException.conflict("INVALID_STATE", "공간 배정 대기 중인 수업이 아니에요");
        Space s = null;
        if (spaceId != null) {
            s = spaces.findById(spaceId).orElseThrow(() -> ApiException.notFound("공간을 찾을 수 없어요"));
            if (!s.orgId.equals(me.orgId)) throw ApiException.forbidden("우리 기관 공간만 배정할 수 있어요");
        }
        if (s == null && (place == null || place.isBlank())) throw ApiException.bad("SPACE_REQUIRED", "공간을 골라 주세요");
        c.spaceId = s == null ? null : s.id;
        c.place = place != null && !place.isBlank() ? place : s.name;
        if (s != null && s.capacity > 0 && c.capacity > s.capacity) c.capacity = s.capacity;
        c.status = ClassStatus.OPEN;
        Long host = c.teacherId != null ? c.teacherId : c.createdBy;
        noti.send(host, "CLASS_OPEN", "공간이 배정돼 모집이 시작됐어요", c.title + " · " + c.place, "/classes/" + c.id);
        return c;
    }
}
