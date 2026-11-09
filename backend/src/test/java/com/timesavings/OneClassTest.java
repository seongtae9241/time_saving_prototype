package com.timesavings;

import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1:1 클래스(황수빈 v2): 신청 → 수락 → 시작 QR → 마침 QR 정산, 취소 반환, 금지 주제. AI 없이 규칙 모드. */
@SpringBootTest
@Transactional
class OneClassTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired LedgerRepo ledger;
    @Autowired NeighborClassRepo classes;
    @Autowired ClassService svc;
    @Autowired BankService bank;
    @Autowired AuthService auth;

    User teacher, student, mgr;
    Elder elder;
    final LocalDate d1 = LocalDate.now().plusDays(3), d2 = LocalDate.now().plusDays(5);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "70대";
        u.idStatus = VerifyStatus.APPROVED;
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, null, "초기 적립");
        return u;
    }

    @BeforeEach
    void setUp() {
        teacher = user("t1", Role.USER, 0);
        student = user("s1", Role.USER, 20);
        mgr = user("m1", Role.ORG, 0);
        elder = new Elder();
        elder.alias = "정말순"; elder.dong = "희망동"; elder.managerId = mgr.id; elder.needs = List.of("장보기·심부름"); elder.ageGroup = "80대";
        elders.save(elder);
    }

    NeighborClass open(User by, Long elderId, String title) {
        return svc.create(by, new ClassService.ClassCmd("일·경험", title, "경험을 들려 드려요", List.of(), null, null,
                null, null, 1.0, null, elderId, "ONE", "해외영업 30년", List.of("이직 고민"), List.of("이직할지 어떻게 정하셨어요?"),
                List.of(new ClassService.Slot(d1, 19.0), new ClassService.Slot(d2, 10.0)), "행복동 작은도서관 1층 북카페", 2));
    }

    double sum(User u, LedgerType t) {
        return ledger.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(l -> l.type == t).mapToDouble(l -> l.amount).sum();
    }

    @Test
    void 일대일_신청_수락_시작_끝_정산() {
        NeighborClass c = open(teacher, null, "첫 직장 3년 차, 버틸지 옮길지");
        assertEquals(ClassStatus.OPEN, c.status); // 공간 배정 없이 바로 열림
        assertEquals(2, svc.left(c));
        double before = bank.summary(student.id).balance();

        // 궁금한 점은 필수, 시간은 선생님이 정한 것 중에서
        assertEquals("QUESTION_REQUIRED", assertThrows(ApiException.class, () -> svc.applyOne(student, c.id, d1, 19.0, " ")).code);
        assertEquals("INVALID_SLOT", assertThrows(ApiException.class, () -> svc.applyOne(student, c.id, d1, 13.0, "질문")).code);
        assertThrows(ApiException.class, () -> svc.applyOne(teacher, c.id, d1, 19.0, "내 수업")); // 내 수업

        ClassEnrollment e = svc.applyOne(student, c.id, d1, 19.0, "이직할지 버틸지 어떻게 정하셨어요?");
        assertEquals("WAIT", e.oneStatus);
        assertEquals(1.0, sum(student, LedgerType.HOLD)); // 1시간 = 1시간 보류
        assertEquals(1, svc.left(c));
        assertEquals("DUPLICATE", assertThrows(ApiException.class, () -> svc.applyOne(student, c.id, d2, 10.0, "또")).code);

        // 수락 전에는 시작할 수 없음
        assertThrows(ApiException.class, () -> svc.startOne(student, c.id, c.checkCode));
        assertThrows(ApiException.class, () -> svc.acceptOne(student, e.id)); // 선생님만
        svc.acceptOne(teacher, e.id);
        assertEquals("OK", e.oneStatus);

        assertEquals("WRONG_CODE", assertThrows(ApiException.class, () -> svc.startOne(student, c.id, "000000x")).code);
        svc.startOne(student, c.id, c.checkCode, "qr");
        assertEquals("MET", e.oneStatus);
        assertEquals("qr", e.checkMethod); // QR 확인으로 기록
        assertEquals("TS1|ONE|" + c.id + "|" + c.checkCode, svc.qr(teacher, c.id).get("qrText"));
        assertEquals(-1.0, sum(student, LedgerType.CLASS_FEE));
        assertEquals(1.0, sum(student, LedgerType.RELEASE));
        assertEquals(1.0, sum(teacher, LedgerType.CLASS_EARN)); // 가중치 없이 +1
        assertEquals(before - 1.0, bank.summary(student.id).balance());
        assertThrows(ApiException.class, () -> svc.cancelOne(student, c.id)); // 시작한 만남은 취소 불가

        svc.endOne(student, c.id, c.checkCode, "code");
        assertEquals("DONE", e.oneStatus);
        assertEquals("code", e.checkOutMethod); // 번호 확인으로 기록
        assertEquals(1, classes.findById(c.id).orElseThrow().met);
        svc.noteOne(student, c.id, "옮길지 말지보다 3년 뒤 뭘 하고 싶은지 먼저");
        Map<String, Object> v = svc.view(c, student);
        @SuppressWarnings("unchecked") Map<String, Object> my = (Map<String, Object>) v.get("myOne");
        assertEquals("DONE", my.get("status"));
        assertEquals("옮길지 말지보다 3년 뒤 뭘 하고 싶은지 먼저", my.get("note"));
        assertEquals(1, v.get("left")); // 이번 주 끝난 만남도 자리를 차지

        // 선생님 다시 만나기 → 새 신청
        ClassEnrollment again = svc.applyOne(student, c.id, d2, 10.0, "한 번 더 여쭤볼게요");
        assertEquals("WAIT", again.oneStatus);
        assertEquals(0, svc.left(c));
    }

    @Test
    void 취소하면_보류_반환_당일_확정은_취소_불가() {
        NeighborClass c = open(teacher, null, "공부 습관 이야기");
        double usable = bank.summary(student.id).available();
        svc.applyOne(student, c.id, d1, 19.0, "숙제를 매일 미뤄요");
        assertEquals(usable - 1.0, bank.summary(student.id).available());
        svc.cancelOne(student, c.id);
        assertEquals(usable, bank.summary(student.id).available());
        assertEquals(2, svc.left(c));

        // 다른 시간 제안 → 학생 수락 → 확정
        ClassEnrollment e = svc.applyOne(student, c.id, d1, 19.0, "사춘기 대화");
        svc.proposeOne(teacher, e.id, d2, 10.0);
        assertTrue(e.proposed);
        assertThrows(ApiException.class, () -> svc.acceptOne(teacher, e.id)); // 학생 답을 기다림
        svc.acceptProposal(student, c.id);
        assertEquals("OK", e.oneStatus);
        assertEquals(d2, e.slotDate);

        // 당일 확정된 약속은 취소 불가
        e.slotDate = LocalDate.now();
        assertEquals("LATE_CANCEL", assertThrows(ApiException.class, () -> svc.cancelOne(student, c.id)).code);
    }

    @Test
    void 어르신_선생님은_담당자가_수락하고_기관이_대신_적립() {
        NeighborClass c = open(mgr, elder.id, "제철 재료 고르는 법");
        Map<String, Object> pub = svc.view(c, student);
        assertEquals("정○순", pub.get("teacherName"));
        assertEquals(true, pub.get("byOrg"));
        ClassEnrollment e = svc.applyOne(student, c.id, d1, 19.0, "무는 어떤 걸 골라야 맛있어요?");
        assertEquals(1, svc.orgOneClasses(mgr).size());
        svc.acceptOne(mgr, e.id);
        svc.startOne(student, c.id, c.checkCode);
        double elderEarn = ledger.findAll().stream().filter(l -> elder.id.equals(l.elderId) && l.type == LedgerType.CLASS_EARN).mapToDouble(l -> l.amount).sum();
        assertEquals(1.0, elderEarn);
    }

    @Test
    void 금지_주제와_닫힌_장소는_막는다() {
        assertEquals(FilterLevel.BLOCK, RuleEngine.classFilter("침 놓는 법 알려 주고 싶어").level());
        assertEquals(FilterLevel.BLOCK, RuleEngine.classFilter("주식 투자로 돈 버는 법").level());
        assertEquals(FilterLevel.BLOCK, RuleEngine.classFilter("교회 전도 같이 해요").level());
        assertEquals(FilterLevel.ALLOW, RuleEngine.classFilter("아침 산책 같이 하며 사진 찍기").level()); // '아침'은 침이 아님
        assertEquals(FilterLevel.ALLOW, RuleEngine.classFilter("회사 생활 30년 노하우 나누기").level());
        ApiException ex = assertThrows(ApiException.class, () -> svc.create(teacher, new ClassService.ClassCmd("생활 지혜", "쑥뜸 뜨는 법", null, List.of(), null, null,
                null, null, 1.0, null, null, "ONE", null, null, null, List.of(new ClassService.Slot(d1, 10.0)), "행복동 작은도서관", 2)));
        assertEquals("FORBIDDEN_REQUEST", ex.code);
        assertTrue(ex.getMessage().contains("민간요법"));
        assertEquals("MEET_NOT_PUBLIC", assertThrows(ApiException.class, () -> svc.create(teacher, new ClassService.ClassCmd("디지털", "카톡 배우기", null, List.of(), null, null,
                null, null, 1.0, null, null, "ONE", null, null, null, List.of(new ClassService.Slot(d1, 10.0)), "우리 집 거실", 2))).code);
        // 같이 해 보기는 4명까지
        assertEquals("INVALID_CAPACITY", assertThrows(ApiException.class, () -> svc.create(teacher, new ClassService.ClassCmd("요리", "김치 담그기", null, List.of(), null, null,
                d1, 10.0, 2.0, 6, null))).code);
    }
}
