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

    public record ClassCmd(String kind, String title, String intro, List<Step> steps, String items, String caution,
                           LocalDate date, Double startHour, Double hours, Integer capacity, Long teacherElderId) {}

    public NeighborClass get(Long id) { return classes.findById(id).orElseThrow(() -> ApiException.notFound("수업을 찾을 수 없어요")); }

    private int joined(Long classId) {
        return (int) enrolls.findByClassId(classId).stream().filter(e -> e.status != EnrollStatus.CANCELLED).count();
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
        double fee = Rules.classFee(c.hours);
        String ageG = t != null ? t.ageGroup : (e != null ? e.ageGroup : null);
        boolean elderT = e != null || (ageG != null && ageG.matches("^(6|7|8|9)0대.*"));
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (ClassEnrollment x : list) if (x.review != null) reviews.add(Views.map("name", users.findById(x.userId).map(u -> u.name).orElse(""), "text", x.review));
        Map<String, Object> m = Views.map("id", c.id, "kind", c.kind, "title", c.title, "intro", c.intro,
                "teacherId", c.teacherId, "teacherElderId", c.teacherElderId,
                "teacherName", t != null ? t.name : (e == null ? null : (manager ? e.alias : Rules.maskAlias(e.alias).replace(" 님", ""))),
                "teacherAgeGroup", ageG, "elderTeacher", elderT, "byOrg", e != null,
                "createdBy", c.createdBy,
                "steps", Views.parse(c.stepsJson, new TypeReference<List<Map<String, Object>>>() {}, List.of()),
                "items", c.items, "caution", c.caution, "level", c.level, "levelReason", c.levelReason,
                "date", c.date.toString(), "startHour", c.startHour, "hours", c.hours, "place", c.place, "spaceId", c.spaceId,
                "capacity", c.capacity, "joined", joined(c.id), "status", c.status,
                "fee", fee, "teacherEarn", c.hours + 1, "poolIfFull", Math.max(0, c.capacity * fee - (c.hours + 1)),
                "myEnrollment", mine == null ? null : Views.map("id", mine.id, "status", mine.status, "review", mine.review),
                "host", viewer != null && isHost(c, viewer),
                "note", Views.parse(c.noteJson, new TypeReference<Map<String, Object>>() {}, null),
                "reviews", reviews);
        return m;
    }

    public List<Map<String, Object>> list(User viewer, String kind, String status) {
        ClassStatus st = status == null || status.isBlank() ? ClassStatus.OPEN : ClassStatus.valueOf(status);
        List<Map<String, Object>> out = new ArrayList<>();
        classes.findAll().stream()
                .filter(c -> c.status == st && (kind == null || kind.isBlank() || "전체".equals(kind) || kind.equals(c.kind)))
                .sorted(Comparator.comparing((NeighborClass c) -> c.date))
                .forEach(c -> out.add(view(c, viewer)));
        if (st == ClassStatus.OPEN) out.sort(Comparator.comparing(m -> !((Boolean) m.get("elderTeacher")))); // 어르신 선생님 먼저
        return out;
    }

    public NeighborClass create(User me, ClassCmd c) {
        if (c.kind() == null || !Rules.CLASS_KINDS.contains(c.kind())) throw ApiException.bad("INVALID_KIND", "수업 종류를 골라 주세요");
        if (c.title() == null || c.title().isBlank()) throw ApiException.bad("TITLE_REQUIRED", "수업 이름을 적어 주세요");
        if (c.date() == null || c.date().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
        if (c.startHour() == null || c.hours() == null) throw ApiException.bad("INVALID_TIME", "시간을 정해 주세요");
        Rules.checkHours(c.startHour(), c.hours());
        int cap = c.capacity() == null ? 4 : c.capacity();
        if (cap < 1 || cap > 20) throw ApiException.bad("INVALID_CAPACITY", "정원은 1~20명으로 정해 주세요");
        NeighborClass k = new NeighborClass();
        if (me.role == Role.ORG) {
            if (c.teacherElderId() == null) throw ApiException.bad("ELDER_REQUIRED", "어느 어르신의 수업인지 골라 주세요");
            if (!views.isManagerOf(me, c.teacherElderId())) throw ApiException.forbidden("내가 담당하는 대상만 대신 수업을 열 수 있어요");
            k.teacherElderId = c.teacherElderId();
        } else if (me.role == Role.USER) {
            k.teacherId = me.id;
        } else throw ApiException.forbidden("수업을 열 수 없는 계정이에요");
        String text = String.join(" ", c.title(), Objects.toString(c.intro(), ""), Objects.toString(c.items(), ""),
                c.steps() == null ? "" : String.join(" ", c.steps().stream().map(s -> Objects.toString(s.t(), "")).toList()));
        RuleEngine.FilterResult f = RuleEngine.filter(text);
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
        k.date = c.date();
        k.startHour = c.startHour();
        k.hours = c.hours();
        k.capacity = cap;
        k.status = ClassStatus.SPACE_PENDING;
        k.checkCode = auth.sixDigits();
        classes.save(k);
        for (User m : users.findByRole(Role.ORG))
            if (m.status == UserStatus.ACTIVE)
                noti.send(m.id, "CLASS_SPACE", "공간 배정이 필요한 수업이 있어요", k.title + " · " + k.date, "/org/spaces");
        return k;
    }

    public ClassEnrollment apply(User me, Long id) {
        NeighborClass c = get(id);
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정으로 신청해 주세요");
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("CLASS_CLOSED", "모집 중인 수업이 아니에요");
        if (isHost(c, me)) throw ApiException.bad("OWN_CLASS", "내 수업에는 신청할 수 없어요");
        if (enrolls.findByClassId(id).stream().anyMatch(e -> e.userId.equals(me.id) && e.status != EnrollStatus.CANCELLED))
            throw ApiException.conflict("DUPLICATE", "이미 신청한 수업이에요");
        if (joined(id) >= c.capacity) throw ApiException.conflict("CLASS_FULL", "정원이 다 찼어요");
        double fee = Rules.classFee(c.hours);
        bank.requireUsable(me.id, fee);
        ClassEnrollment e = new ClassEnrollment();
        e.classId = id;
        e.userId = me.id;
        enrolls.save(e);
        bank.add(me.id, LedgerType.HOLD, fee, c.hours, 0.5, null, c.id, "클래스 수강 보류 · " + c.title);
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
        ClassEnrollment e = myEnroll(id, me);
        if (e.status != EnrollStatus.APPLIED) throw ApiException.conflict("INVALID_STATE", "출석한 수업은 취소할 수 없어요");
        e.status = EnrollStatus.CANCELLED;
        bank.add(me.id, LedgerType.RELEASE, Rules.classFee(c.hours), c.hours, 0.5, null, c.id, "클래스 신청 취소 · " + c.title);
    }

    public ClassEnrollment attend(User me, Long id, String code) {
        NeighborClass c = get(id);
        ClassEnrollment e = myEnroll(id, me);
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("INVALID_STATE", "출석할 수 없는 수업이에요");
        if (e.status == EnrollStatus.ATTENDED) return e;
        if (code == null || !code.trim().equals(c.checkCode)) throw ApiException.bad("WRONG_CODE", "출석 번호가 맞지 않아요");
        double fee = Rules.classFee(c.hours);
        e.status = EnrollStatus.ATTENDED;
        bank.add(me.id, LedgerType.RELEASE, fee, c.hours, 0.5, null, c.id, "클래스 보류 해제 · " + c.title);
        bank.add(me.id, LedgerType.CLASS_FEE, -fee, c.hours, 0.5, null, c.id, "클래스 수강 · " + c.title);
        return e;
    }

    public Map<String, Object> qr(User me, Long id) {
        NeighborClass c = get(id);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 볼 수 있어요");
        return Views.map("classId", c.id, "code", c.checkCode, "qrText", "TS-CLASS:" + c.id + ":" + c.checkCode);
    }

    public Map<String, Object> finish(User me, Long id, List<String> steps, String tip) {
        NeighborClass c = get(id);
        if (!isHost(c, me)) throw ApiException.forbidden("선생님이나 담당자만 마칠 수 있어요");
        if (c.status != ClassStatus.OPEN) throw ApiException.conflict("INVALID_STATE", "진행 중인 수업이 아니에요");
        c.status = ClassStatus.DONE;
        c.noteJson = Views.json(Views.map("steps", steps == null ? List.of() : steps, "tip", tip));
        double fee = Rules.classFee(c.hours);
        double teacherEarn = c.hours + 1;
        int attended = 0;
        for (ClassEnrollment e : enrolls.findByClassId(id)) {
            if (e.status == EnrollStatus.ATTENDED) {
                attended++;
                noti.send(e.userId, "CLASS_NOTE", "배움 노트가 도착했어요", c.title, "/classes/" + c.id);
            } else if (e.status == EnrollStatus.APPLIED) { // 오지 않은 수강생: 보류만 풀고 차감하지 않음
                e.status = EnrollStatus.CANCELLED;
                bank.add(e.userId, LedgerType.RELEASE, fee, c.hours, 0.5, null, c.id, "클래스 불참 · 보류 해제");
            }
        }
        if (c.teacherId != null) {
            bank.add(c.teacherId, LedgerType.CLASS_EARN, teacherEarn, c.hours, null, null, c.id, "클래스 선생님 (수업 + 준비 1시간) · " + c.title);
        } else {
            Ledger l = new Ledger();
            l.elderId = c.teacherElderId;
            l.type = LedgerType.CLASS_EARN;
            l.amount = teacherEarn;
            l.baseHours = c.hours;
            l.classId = c.id;
            l.label = "어르신 선생님 적립 (기관이 대신 받음) · " + c.title;
            ledger.save(l);
        }
        double pool = Rules.round05(attended * fee - teacherEarn);
        if (pool > 0) {
            Ledger l = new Ledger();
            l.type = LedgerType.POOL;
            l.amount = pool;
            l.classId = c.id;
            l.label = "공동 적립 · 수강 차액 · " + c.title;
            ledger.save(l);
        }
        return Views.map("attended", attended, "teacherEarn", teacherEarn, "fee", fee, "pool", Math.max(0, pool), "class", view(c, me));
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
