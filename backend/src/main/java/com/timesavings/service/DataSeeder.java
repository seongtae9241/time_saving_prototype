package com.timesavings.service;

import com.timesavings.ai.AiClient;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * DB가 비어 있을 때만 데모 데이터를 넣는다(계약서 6장).
 * 채택 프로토타입의 기준일(11/8)을 오늘로 옮겨 상대 날짜로 넣는다.
 */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final LocalDate PROTO_TODAY = LocalDate.of(2026, 11, 8);

    private final UserRepo users; private final OrganizationRepo orgs; private final ElderRepo elders; private final ElderMemoRepo memos;
    private final PostRepo posts; private final RequestRepo requests; private final MessageRepo messages; private final LedgerRepo ledger;
    private final RatingRepo ratings; private final NotificationRepo notis; private final InterestAlertRepo alerts; private final ReportRepo reports;
    private final InquiryRepo inquiries; private final NoticeRepo notices; private final FamilyLinkRepo family; private final DifficultyAppealRepo appeals;
    @org.springframework.beans.factory.annotation.Autowired private CareSignalService careSignals;
    private final PublicServiceRepo services; private final RoleplayPersonaRepo personas;
    private final EduProgressRepo edu;
    private final AuthService auth; private final LifeReportService lifeReports; private final AiClient ai; private final TransactionTemplate tx;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired private OrgEventRepo orgEvents; // 동네 소식(2026-11-09)
    @org.springframework.beans.factory.annotation.Autowired private EventApplicationRepo eventApps;
    @org.springframework.beans.factory.annotation.Autowired private PublicClassRepo publicClasses; // 기관 클래스 추천 샘플(2026-11-10)

    private LocalDate today;
    private final Map<String, User> U = new HashMap<>();
    private final Map<String, Elder> E = new HashMap<>();
    private String pwHash;

    public DataSeeder(UserRepo users, OrganizationRepo orgs, ElderRepo elders, ElderMemoRepo memos, PostRepo posts, RequestRepo requests,
                      MessageRepo messages, LedgerRepo ledger, RatingRepo ratings, NotificationRepo notis, InterestAlertRepo alerts,
                      ReportRepo reports, InquiryRepo inquiries, NoticeRepo notices, FamilyLinkRepo family, DifficultyAppealRepo appeals,
                      PublicServiceRepo services, RoleplayPersonaRepo personas,
                      EduProgressRepo edu, AuthService auth, LifeReportService lifeReports, AiClient ai,
                      TransactionTemplate tx, @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.orgs = orgs; this.elders = elders; this.memos = memos; this.posts = posts; this.requests = requests;
        this.messages = messages; this.ledger = ledger; this.ratings = ratings; this.notis = notis; this.alerts = alerts; this.reports = reports;
        this.inquiries = inquiries; this.notices = notices; this.family = family; this.appeals = appeals; this.services = services;
        this.personas = personas; this.edu = edu; this.auth = auth;
        this.lifeReports = lifeReports; this.ai = ai; this.tx = tx; this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || users.count() > 0) return;
        ai.ruleOnly(() -> tx.executeWithoutResult(s -> seed()));
        log.info("데모 데이터를 넣었어요 (yujin / org1 / admin, 비밀번호 1234)");
    }

    /** 프로토타입 날짜(11월 d일) → 오늘 기준 날짜 */
    private LocalDate p(int month, int day) { return today.plusDays(LocalDate.of(2026, month, day).toEpochDay() - PROTO_TODAY.toEpochDay()); }

    private LocalDateTime at(LocalDate d, double hour) {
        int h = (int) hour;
        return d.atTime(Math.min(h, 23), hour - h >= 0.5 ? 30 : 0);
    }

    public void seed() {
        today = LocalDate.now();
        pwHash = auth.hash("1234");

        // ---------------- 기관·계정 ----------------
        Organization o1 = org("행복동 주민센터", "주민센터", OrgStatus.APPROVED, "박지원", "010-2000-0001", today.minusMonths(6));
        org("희망동 노인복지관", "노인복지관", OrgStatus.PENDING, "김민수", "010-2000-0002", p(11, 7)).memo = "재직증명서 첨부";
        org("사랑동 주민센터", "주민센터", OrgStatus.PENDING, "이수정", "010-2000-0003", p(11, 6)).memo = "재직증명서 첨부";

        user("yujin", "손유진", "010-1111-0001", "행복동", "20대", "퇴근 후·주말에 시간 있어요. 폰·키오스크 설명 자신 있어요", "디지털 9회 · 말동무 6회",
                List.of("디지털 도움", "말동무·정서", "장보기·심부름"), List.of("평일 저녁", "주말 오후"), VerifyStatus.APPROVED, VerifyStatus.NONE, false);
        user("sky", "김하늘", "010-1111-0002", "행복동", "20대", "간호학과 학생이에요. 병원 동행·폰 설정 자신 있어요", "동행 12회 · 디지털 9회",
                List.of("동행", "디지털 도움"), List.of("평일 오후"), VerifyStatus.APPROVED, VerifyStatus.APPROVED, true);
        user("mikyung", "최미경", "010-1111-0003", "행복동", "60대", "은퇴 후 이웃 돕기를 시작했어요. 산책·말벗 좋아해요", "동행 18회 · 말동무 11회",
                List.of("말동무·정서", "동행"), List.of("평일 오전", "평일 저녁"), VerifyStatus.APPROVED, VerifyStatus.APPROVED, true);
        user("doyun", "이도윤", "010-1111-0004", "희망동", "30대", "주말에 시간이 많은 직장인이에요", "장보기 8회 · 집안일 5회",
                List.of("장보기·심부름", "가벼운 집안일", "생활 정리"), List.of("주말 오전"), VerifyStatus.APPROVED, VerifyStatus.NONE, true);
        user("bok", "김복남", "010-1111-0005", "행복동", "70대", "행복동 8통에 살아요. 김치는 50년 담갔어요", "도움 받기 6회",
                List.of("디지털 도움", "말동무·정서"), List.of("평일 오후"), VerifyStatus.APPROVED, VerifyStatus.NONE, false);
        user("mom", "이순자", "010-1111-0006", "행복동", "50대", "손유진 엄마예요. 장보기 같이 해요", "장보기 3회",
                List.of("장보기·심부름"), List.of("평일 오전"), VerifyStatus.APPROVED, VerifyStatus.NONE, true);
        user("dain", "정다은", "010-1111-0007", "사랑동", "20대", "주말 봉사를 시작하려고 해요", null,
                List.of("디지털 도움"), List.of("주말 오후"), VerifyStatus.PENDING, VerifyStatus.PENDING, true).idFileUrl = "/api/files/demo-id.png";
        user("sehun", "오세훈", "010-1111-0008", "미래동", "40대", "동네 어르신 장보기 도와드릴게요", null,
                List.of("장보기·심부름"), List.of("주말 오전"), VerifyStatus.PENDING, VerifyStatus.NONE, true).idFileUrl = "/api/files/demo-id2.png";
        U.get("dain").crimeFileUrl = "/api/files/demo-crime.pdf";
        User org1 = staff("org1", "박지원", Role.ORG, o1.id, "010-2000-0001");
        staff("org2", "최서연", Role.ORG, o1.id, "010-2000-0011");
        staff("admin", "김운영", Role.ADMIN, null, "010-9000-0000");
        U.get("yujin").voiceAlert = false;

        // ---------------- 도움 대상(독거노인, 별칭만) ----------------
        elder("e1", "이순례", "행복동", "80대", List.of("말동무·정서", "동행"), "귀가 어두우셔서 천천히 크게 말해 주세요. 계단이 가팔라요.", true, org1, LocalDate.of(2026, 6, 10));
        elder("e2", "정말순", "희망동", "80대", List.of("장보기·심부름", "생활 정리"), "순한 반려견이 있어요. 현관 출입 방법은 담당자가 따로 안내해요", true, org1, LocalDate.of(2026, 8, 3));
        elder("e3", "한영자", "행복동", "70대", List.of("디지털 도움"), "영상통화 설정을 여러 번 물어보세요. 천천히 반복해 주세요", false, org1, LocalDate.of(2026, 9, 5));
        elder("e4", "오덕수", "사랑동", "80대", List.of("가벼운 집안일", "동행"), "오전에는 주무시는 경우가 많아요", true, org1, LocalDate.of(2026, 10, 2));
        memo("e1", p(11, 3), "다음 주 정형외과 재진 예정이라고 하심. 동행 요청 필요");
        memo("e1", p(10, 27), "손녀 이야기를 자주 하심");
        memo("e2", p(11, 1), "냉장고가 자주 비어 있음. 장보기 주기 확인");

        seedServices();
        seedPersonas();
        seedHistory();
        seedCurrent(org1);
        seedEvents(o1, org1);
        PublicClassSeed.seed(publicClasses, today); // 기관 클래스 추천 "(예시)" 샘플
        seedMisc(org1);
        seedInsights();
        if (careSignals != null) careSignals.seedDemo(); // 7.14 C1 전달 메모 정리·생활 변화 신호 데모
        lifeReports.generate(YearMonth.from(today).minusMonths(1));
    }

    // ======================= 회의반영 4·5장: 전달 메모, 계절·나이대 추이, 카테고리 밖 요청 =======================
    private void memoOn(java.util.function.Predicate<Request> who, int nth, String memo) {
        List<Request> list = requests.findAll().stream().filter(r -> r.status == ReqStatus.DONE && who.test(r))
                .sorted(Comparator.comparing((Request r) -> r.date).reversed()).toList();
        if (list.size() > nth) list.get(nth).careMemo = memo;
    }

    private void seedInsights() {
        Long e1 = E.get("e1").id, e3 = E.get("e3").id, yujin = U.get("yujin").id, bok = U.get("bok").id;
        memoOn(r -> e1.equals(r.elderId), 0, "손녀가 다음 주에 온다고 기뻐하셨어요. 다음 방문 때 사진첩을 같이 보기로 했어요.");
        memoOn(r -> e1.equals(r.elderId) && "동행".equals(r.category), 0, "보건소에서 다음 달 정기 검진 날짜를 안내받았어요. 접수증은 냉장고에 붙여 두었어요.");
        memoOn(r -> e1.equals(r.elderId), 2, "현관 계단 손잡이가 흔들려요. 담당자님께 확인 부탁드려요.");
        memoOn(r -> e3.equals(r.elderId), 0, "영상통화 버튼을 바탕화면 맨 앞으로 옮겼어요. 다음에는 사진 보내기를 한 번 더 연습해요.");
        memoOn(r -> bok.equals(r.recipientId) && "동행".equals(r.category), 0, "내과에서 일주일 뒤 다시 오라고 했어요. 약 봉투에 먹는 시간을 크게 적어 드렸어요.");
        memoOn(r -> yujin.equals(r.recipientId), 0, "산책 코스를 공원 둘레길로 바꿨어요. 다음엔 조금 일찍 만나기로 했어요.");

        // 계절별 추이(6~11달 전): 겨울 동행·여름 집안일·봄 말동무·가을 장보기가 많게
        String[] vols = {"sky", "mikyung", "doyun", "mom", "bok"};
        String[] eks = {"e1", "e2", "e3", "e4"};
        for (int k = 11; k >= 6; k--) {
            YearMonth ym = YearMonth.from(today).minusMonths(k);
            String main = switch (ym.getMonthValue()) {
                case 12, 1, 2 -> "동행"; case 6, 7, 8 -> "가벼운 집안일"; case 3, 4, 5 -> "말동무·정서"; default -> "장보기·심부름";
            };
            String[] cats = {main, main, main, main, "디지털 도움", "생활 정리"};
            for (int i = 0; i < cats.length; i++) {
                String c = cats[i];
                double h = "동행".equals(c) ? 2 : 1.5;
                int day = Math.min(ym.lengthOfMonth(), 3 + i * 4);
                done(vols[(i + k) % vols.length], null, eks[(i + k) % eks.length], ym.atDay(day), 10 + (i % 3) * 2, h, c,
                        c + " 도움", c + "을(를) 함께했어요.", null, i == 0);
            }
        }

        // 동행 수요 > 공급: 확정되지 못한 동행 요청 공고(지난 석 달)
        for (int m = 1; m <= 3; m++) {
            LocalDate d = today.minusDays(20L * m);
            post(PostType.NEED, null, E.get(m % 2 == 0 ? "e4" : "e1"), d, 9, 12, m % 2 == 0 ? "사랑동" : "행복동", List.of("동행"),
                    "정형외과 진료 같이 가 주세요", "오전 진료라 9시 전에 출발해야 해요", null, 2.0, PostStatus.CLOSED, d.minusDays(5).atTime(9, 0));
        }

        // 6개 카테고리에 잘 맞지 않는 요청(새 카테고리 후보 분석용)
        Object[][] odd = {
                {"e1", "가벼운 집안일", "거실 형광등 교체", "거실 형광등이 나가서 갈아 주실 분 찾아요"},
                {"e4", "가벼운 집안일", "욕실 전구 교체", "욕실 전구가 깜빡거려요. 교체 부탁드려요"},
                {"e2", "가벼운 집안일", "방충망 고정", "방충망이 떨어졌어요. 고정해 주실 분이 필요해요"},
                {"e2", "생활 정리", "고양이 모래 갈기", "고양이 모래를 갈아 주실 분이 필요해요"},
                {"e3", "생활 정리", "화분 분갈이", "베란다 화분 분갈이를 같이 해 주세요"},
                {"e4", "디지털 도움", "민원 신청 도움", "주민센터 민원 신청서 쓰는 걸 도와주세요"}};
        for (int i = 0; i < odd.length; i++) {
            Elder el = E.get((String) odd[i][0]);
            LocalDate d = today.plusDays(3 + i);
            post(PostType.NEED, null, el, d, 14, 17, el.dong, List.of((String) odd[i][1]), (String) odd[i][2], (String) odd[i][3], null, 1.0,
                    PostStatus.OPEN, today.minusDays(i + 1L).atTime(10, 0));
        }
        post(PostType.NEED, U.get("bok"), null, today.plusDays(5), 15, 17, "행복동", List.of("가벼운 집안일"), "강아지 돌봄 부탁",
                "강아지 밥 주고 동네 한 바퀴 걸어 주실 분", null, 1.0, PostStatus.OPEN, today.minusDays(2).atTime(11, 0));
    }

    // ======================= 기본 객체 =======================
    private Organization org(String name, String type, OrgStatus st, String mgr, String phone, LocalDate created) {
        Organization o = new Organization();
        o.name = name; o.orgType = type; o.status = st; o.managerName = mgr; o.managerPhone = phone; o.phone = "02-000-0000";
        o.address = name.substring(0, 3) + " (동 단위)"; o.managerEmail = "manager@example.org"; o.createdAt = created.atTime(10, 0);
        return orgs.save(o);
    }

    private User user(String id, String name, String phone, String area, String age, String intro, String exp, List<String> cats,
                      List<String> slots, VerifyStatus idSt, VerifyStatus crime, boolean eduDone) {
        User u = new User();
        u.loginId = id; u.passwordHash = pwHash; u.name = name; u.phone = phone; u.area = area; u.ageGroup = age; u.intro = intro;
        u.experience = exp; u.categories = new ArrayList<>(cats); u.timeSlots = new ArrayList<>(slots); u.idStatus = idSt; u.crimeStatus = crime;
        u.eduDone = eduDone; u.role = Role.USER; u.createdAt = today.minusMonths(7).atTime(9, 0);
        users.save(u);
        U.put(id, u);
        return u;
    }

    private User staff(String id, String name, Role role, Long orgId, String phone) {
        User u = new User();
        u.loginId = id; u.passwordHash = pwHash; u.name = name; u.role = role; u.orgId = orgId; u.phone = phone;
        u.createdAt = today.minusMonths(6).atTime(9, 0);
        users.save(u);
        U.put(id, u);
        return u;
    }

    private void elder(String key, String alias, String dong, String age, List<String> needs, String caution, boolean consent, User mgr, LocalDate since) {
        Elder e = new Elder();
        e.alias = alias; e.dong = dong; e.ageGroup = age; e.needs = new ArrayList<>(needs); e.caution = caution; e.reportConsent = consent;
        e.orgId = mgr.orgId; e.managerId = mgr.id; e.createdAt = since.atTime(10, 0);
        elders.save(e);
        E.put(key, e);
    }

    private void memo(String e, LocalDate d, String text) {
        ElderMemo m = new ElderMemo();
        m.elderId = E.get(e).id; m.text = text; m.createdAt = d.atTime(15, 0);
        memos.save(m);
    }

    private void led(User u, Elder elder, LedgerType t, double amount, Double base, Double w, Long reqId, String label, LocalDateTime at) {
        Ledger l = new Ledger();
        l.userId = u == null ? null : u.id; l.elderId = elder == null ? null : elder.id; l.type = t; l.amount = amount; l.baseHours = base;
        l.weight = w; l.requestId = reqId; l.label = label; l.createdAt = at;
        ledger.save(l);
    }

    private Post post(PostType type, User owner, Elder elder, LocalDate date, double s, double e, String area, List<String> cats, String title,
                      String detail, String note, Double est, PostStatus st, LocalDateTime created) {
        Post x = new Post();
        x.type = type; x.ownerId = owner == null ? null : owner.id; x.elderId = elder == null ? null : elder.id;
        x.createdBy = owner != null ? owner.id : U.get("org1").id; x.date = date; x.startHour = s; x.endHour = e; x.area = area;
        x.categories = new ArrayList<>(cats); x.title = title; x.detail = detail; x.note = note; x.estHours = est;
        Level lv = Rules.maxLevel(cats);
        x.difficulty = lv; x.weight = Rules.weight(lv); x.difficultyReason = String.join("·", cats) + " 기본 " + Rules.levelKo(lv) + " 등급";
        x.status = st; x.createdAt = created;
        return posts.save(x);
    }

    private Request req(Kind kind, Post post, User sender, User vol, User rec, Elder elder, LocalDate date, double start, double hours, String cat,
                        String content, String memo, ReqStatus st, LocalDateTime created) {
        Request r = new Request();
        r.kind = kind; r.postId = post == null ? null : post.id; r.senderId = sender.id; r.volunteerId = vol.id;
        r.recipientId = rec == null ? null : rec.id; r.elderId = elder == null ? null : elder.id; r.date = date; r.startHour = start;
        r.hours = hours; r.category = cat; r.content = content; r.memo = memo; r.status = st; r.createdAt = created;
        Level lv = Rules.categoryLevel(cat);
        r.difficulty = lv; r.weight = Rules.weight(lv); r.estEarn = Rules.earn(hours, lv);
        return requests.save(r);
    }

    private static final String[][] TAGS = {{"시간 약속을 잘 지켜요", "설명이 친절해요"}, {"편안해요", "대화가 즐거워요"}, {"꼼꼼해요"}, {"설명이 친절해요"}, {"시간 약속을 잘 지켜요", "꼼꼼해요"}};
    private int seq = 0;

    /** 끝난 활동 하나: 요청 DONE + 통장(적립·차감) + 평가. */
    private Request done(String vol, String recUser, String elderKey, LocalDate date, double start, double hours, String cat, String content,
                         String logText, Level level, boolean withPost) {
        User v = U.get(vol), rec = recUser == null ? null : U.get(recUser);
        Elder el = elderKey == null ? null : E.get(elderKey);
        User sender = rec != null ? rec : U.get("org1");
        LocalDateTime created = date.minusDays(3).atTime(10, 0);
        Post pst = null;
        Kind kind = Kind.REGULAR;
        if (withPost) {
            boolean offer = seq % 2 == 0;
            String area = el != null ? el.dong : (rec != null ? rec.area : v.area);
            pst = offer ? post(PostType.OFFER, v, null, date, Math.max(8, start - 1), Math.min(21, start + hours + 1), area, List.of(cat), cat + " 도와드려요", null, null, null, PostStatus.MATCHED, date.minusDays(5).atTime(9, 0))
                    : post(PostType.NEED, rec, el, date, start, start + hours, area, List.of(cat), content, content, null, hours, PostStatus.MATCHED, date.minusDays(5).atTime(9, 0));
            kind = offer ? Kind.REQUEST : Kind.PROPOSAL;
            if (!offer) sender = v;
        }
        Request r = req(kind, pst, sender, v, rec, el, date, start, hours, cat, content, null, ReqStatus.DONE, created);
        if (level != null) { r.difficulty = level; r.weight = Rules.weight(level); r.estEarn = Rules.earn(hours, level); }
        r.earned = Rules.earn(hours, r.weight);
        r.checkCode = String.format("%06d", 100000 + (seq * 7919) % 900000);
        r.checkInAt = at(date, start).minusMinutes(3); r.checkOutAt = at(date, start + hours).plusMinutes(2); r.checkMethod = "qr";
        r.logRaw = logText.split("\n")[0]; r.logText = logText; r.logSubmittedAt = r.checkOutAt.plusMinutes(20);
        r.confirmedAt = created.plusHours(5); r.doneAt = r.logSubmittedAt.plusHours(3);
        String recLabel = el != null ? Rules.maskAlias(el.alias) + " (기관)" : rec.name + " 님";
        led(v, null, LedgerType.EARN, r.earned, hours, r.weight, r.id, cat + " · " + recLabel, r.doneAt);
        if (rec != null) {
            led(rec, null, LedgerType.HOLD, r.estEarn, hours, r.weight, r.id, cat + " 예약 보류", r.confirmedAt);
            led(rec, null, LedgerType.RELEASE, r.estEarn, hours, r.weight, r.id, cat + " 보류 해제", r.doneAt);
            led(rec, null, LedgerType.USE, -r.earned, hours, r.weight, r.id, cat + " · " + v.name + " 님에게 받음", r.doneAt);
        }
        Rating x = new Rating();
        x.requestId = r.id; x.raterId = rec != null ? rec.id : U.get("org1").id; x.targetUserId = v.id; x.stars = seq % 6 == 5 ? 4 : 5;
        x.tags = new ArrayList<>(List.of(TAGS[seq % TAGS.length])); x.createdAt = r.doneAt.plusHours(1);
        ratings.save(x);
        seq++;
        return requests.save(r);
    }

    // ======================= 지난 활동 기록 =======================
    private void seedHistory() {
        // 단골 관계(3회 이상): 최미경→손유진, 최미경→이순례, 김하늘→손유진, 김하늘→한영자
        String walk = "저녁 7시부터 행복공원 한 바퀴 산책했어요.\n요즘 회사 일 이야기를 나눴어요.";
        done("mikyung", "yujin", null, p(8, 22), 19, 1, "말동무·정서", "저녁 산책 같이 하기", walk, null, false);
        done("mikyung", "yujin", null, p(9, 19), 19, 1, "말동무·정서", "저녁 산책 같이 하기", walk, null, false);
        done("mikyung", "yujin", null, p(10, 17), 19, 1, "말동무·정서", "저녁 산책 같이 하기", walk, null, false);
        done("mikyung", null, "e1", p(8, 14), 10, 1.5, "말동무·정서", "안부 방문, 동네 한 바퀴", "고향 이야기를 들으며 동네를 천천히 한 바퀴 돌았어요.\n다음 방문 때 사진첩을 보기로 했어요.", null, true);
        done("mikyung", null, "e1", p(9, 11), 10, 1.5, "말동무·정서", "안부 방문", "손녀 사진을 같이 보며 이야기 나눴어요.\n귀가 어두우셔서 천천히 말씀드렸어요.", null, false);
        done("mikyung", null, "e1", p(10, 9), 9.5, 2, "동행", "보건소 동행", "보건소 접수와 대기를 함께했어요.\n돌아오는 길에 약국에 들렀어요.", null, false);
        done("mikyung", null, "e1", p(10, 30), 10, 1, "말동무·정서", "안부 방문", "날씨 이야기와 손녀 이야기를 나눴어요.", null, false);
        done("sky", "yujin", null, p(7, 30), 14, 2, "동행", "치과 진료 동행", "치과 접수와 수납을 함께했어요.\n다음 예약 날짜를 달력에 적어 드렸어요.", null, true);
        done("sky", "yujin", null, p(9, 3), 19, 1, "디지털 도움", "새 휴대폰 설정", "연락처와 사진을 새 폰으로 옮겼어요.", null, false);
        done("sky", "yujin", null, p(10, 8), 19, 1, "디지털 도움", "은행 앱 알림 설정", "앱 알림을 켜고 글씨를 크게 바꿨어요.", null, false);
        done("sky", null, "e3", p(8, 28), 16, 1, "디지털 도움", "영상통화 연습", "카카오톡 영상통화 버튼 위치를 알려 드렸어요.", null, true);
        done("sky", null, "e3", p(9, 25), 16, 1, "디지털 도움", "영상통화 연습", "손녀와 영상통화를 두 번 연습했어요.", null, false);
        done("sky", null, "e3", p(10, 23), 16, 1, "디지털 도움", "사진 보내기", "카카오톡으로 사진 보내는 법을 같이 해 봤어요.", null, false);

        // 손유진의 봉사 기록(프로토타입 통장 D.L 포함)
        done("yujin", null, "e1", p(7, 12), 15, 1, "말동무·정서", "안부 방문", "고향 전라도 이야기를 들었어요.", null, false);
        done("yujin", null, "e1", p(7, 5), 9, 2, "동행", "보건소 동행", "보건소 접수와 수납을 함께했어요.", null, false);
        done("yujin", null, "e3", p(7, 19), 16, 1, "디지털 도움", "문자 보내기 연습", "문자로 사진 보내는 법을 연습했어요.", null, false);
        done("yujin", null, "e1", p(8, 2), 9, 2, "동행", "내과 동행", "내과 접수와 대기를 함께했어요.", null, false);
        done("yujin", null, "e3", p(8, 16), 16, 1, "디지털 도움", "글씨 크기 설정", "휴대폰 글씨를 크게 바꿨어요.", null, false);
        done("yujin", null, "e4", p(7, 26), 14, 1.5, "가벼운 집안일", "거실 청소", "거실과 현관을 쓸고 닦았어요.", null, true);
        done("yujin", null, "e2", p(8, 9), 14, 2, "장보기·심부름", "일주일치 장보기", "행복마트에서 쌀과 반찬거리를 샀어요.\n영수증과 잔돈을 함께 확인했어요.", null, false);
        done("yujin", null, "e1", p(8, 30), 9, 2, "동행", "정형외과 동행", "정형외과 접수와 대기를 함께했어요.", null, true);
        done("yujin", null, "e4", p(9, 13), 14, 1.5, "가벼운 집안일", "베란다 정리", "베란다 화분과 빈 상자를 정리했어요.", null, false);
        done("yujin", null, "e1", p(9, 27), 15, 1, "말동무·정서", "안부 방문", "추석 이야기를 나눴어요.", null, false);
        done("yujin", null, "e2", p(10, 4), 14, 2, "장보기·심부름", "장보기", "쌀 10kg와 계란을 샀어요.", null, false);
        done("yujin", null, "e1", p(10, 11), 9, 2, "동행", "보건소 동행", "보건소 접수와 수납을 함께했어요.", null, false);
        done("yujin", null, "e4", p(10, 18), 14, 1.5, "가벼운 집안일", "주방 정리", "설거지와 싱크대 정리를 했어요.", null, false);
        done("yujin", "bok", null, p(10, 25), 15, 1, "말동무·정서", "말벗", "옛날 망원시장 이야기를 들었어요.", null, false);
        done("yujin", null, "e2", p(10, 29), 14, 1.5, "장보기·심부름", "장보기", "반찬거리와 우유를 샀어요.", null, true);
        done("yujin", "bok", null, p(11, 3), 14, 2, "동행", "내과 진료 동행", "내과 접수와 대기를 함께했어요.\n약국에서 처방전 약을 받아 드렸어요.", null, false);
        done("yujin", null, "e3", p(11, 5), 16, 1.5, "디지털 도움", "큰 글씨 설정", "휴대폰 글씨를 크게 바꾸고 영상통화를 연습했어요.", null, false);

        // 이순자(엄마)·김복남 기록
        done("mom", null, "e2", p(9, 20), 10, 2, "장보기·심부름", "장보기", "시장에서 채소와 두부를 샀어요.", null, true);
        done("mom", null, "e2", p(10, 18), 10, 2, "장보기·심부름", "장보기", "쌀과 반찬거리를 샀어요.", null, false);
        done("mom", null, "e4", p(10, 31), 10, 2, "가벼운 집안일", "이불 빨래", "이불을 빨아 널어 드렸어요.", null, false);
        done("mikyung", "mom", null, p(9, 5), 10, 1, "말동무·정서", "산책", "공원에서 산책하며 이야기 나눴어요.", null, false);
        done("mikyung", "mom", null, p(10, 10), 10, 1, "말동무·정서", "산책", "단풍 구경을 했어요.", null, false);
        done("bok", "mom", null, p(10, 2), 15, 1, "디지털 도움", "문자 사진 보내기", "문자로 사진 보내는 법을 알려 줬어요.", null, false);
        done("bok", null, "e4", p(10, 20), 15, 1, "말동무·정서", "말벗", "옛날 노래 이야기를 나눴어요.", null, false);

        // 이상 적립 패턴(D-06): 이도윤→김복남 짧은 활동 반복
        done("doyun", "bok", null, today.minusDays(9), 10, 0.5, "장보기·심부름", "우유 사 오기", "편의점에서 우유를 사 왔어요.", null, false);
        done("doyun", "bok", null, today.minusDays(6), 10, 0.5, "장보기·심부름", "두부 사 오기", "두부를 사 왔어요.", null, false);
        done("doyun", "bok", null, today.minusDays(4), 10, 0.5, "장보기·심부름", "라면 사 오기", "라면을 사 왔어요.", null, false);
        // 난이도 이의 대상
        Request ap = done("doyun", null, "e2", p(11, 4), 14, 1.5, "생활 정리", "봄날님 장보기 겸 냉장고 정리", "쌀 20kg와 생수 2묶음을 4층까지 날랐어요.\n냉장고 정리도 했어요.", Level.BASIC, false);
        DifficultyAppeal a = new DifficultyAppeal();
        a.requestId = ap.id; a.userId = U.get("doyun").id; a.reason = "쌀 20kg와 생수 2묶음을 4층까지 날랐어요"; a.requestedLevel = Level.EFFORT;
        a.createdAt = p(11, 6).atTime(11, 0);
        appeals.save(a);

        // 대시보드용 지난 몇 달 활동(기관 대상·이웃 사이)
        String[][] pairs = {
                {"sky", "mikyung", null, "디지털 도움", "1", "폰 사진 정리"}, {"mikyung", null, "e4", "말동무·정서", "1.5", "안부 방문"},
                {"doyun", null, "e2", "장보기·심부름", "1.5", "장보기"}, {"sky", null, "e1", "동행", "2", "병원 동행"},
                {"doyun", "sky", null, "가벼운 집안일", "1.5", "자취방 청소"}, {"mikyung", null, "e2", "생활 정리", "1", "옷장 정리"},
                {"mom", null, "e1", "장보기·심부름", "1", "장보기"}, {"sky", "doyun", null, "디지털 도움", "1", "키오스크 연습"}};
        int[] counts = {7, 9, 10, 12, 13}; // 5달 전 → 1달 전
        for (int k = 5; k >= 1; k--) {
            YearMonth ym = YearMonth.from(today).minusMonths(k);
            int n = counts[5 - k];
            for (int i = 0; i < n; i++) {
                String[] pr = pairs[(i + k) % pairs.length];
                int day = Math.min(ym.lengthOfMonth(), 2 + (i * 7 + k * 3) % 26);
                done(pr[0], pr[1], pr[2], ym.atDay(day), 10 + (i % 4) * 2, Double.parseDouble(pr[4]), pr[3], pr[5],
                        pr[5] + "을(를) 함께했어요.", null, i % 3 == 0);
            }
            // 매칭 안 된 공고·요청(지역별 매칭률)
            LocalDate d = ym.atDay(Math.min(ym.lengthOfMonth(), 15));
            post(PostType.NEED, null, E.get("e2"), d, 14, 17, "희망동", List.of("장보기·심부름"), "장보기 도움", "장보기", null, 1.5, PostStatus.CLOSED, d.minusDays(4).atTime(9, 0));
            post(PostType.NEED, null, E.get("e4"), d, 10, 12, "사랑동", List.of("가벼운 집안일"), "집안일 도움", "청소", null, 1.0, PostStatus.CLOSED, d.minusDays(4).atTime(9, 0));
            Post off = post(PostType.OFFER, U.get("doyun"), null, d.plusDays(1), 9, 12, "희망동", List.of("장보기·심부름"), "장보기 도와드려요", null, null, null, PostStatus.CLOSED, d.minusDays(3).atTime(9, 0));
            Request ex = req(Kind.REQUEST, off, U.get("org1"), U.get("doyun"), null, E.get("e2"), d.plusDays(1), 10, 1, "장보기·심부름", "장보기", null, ReqStatus.EXPIRED, d.minusDays(2).atTime(9, 0));
            ex.rejectReason = null;
            Request ex2 = req(Kind.REGULAR, null, U.get("org1"), U.get("mikyung"), null, E.get("e2"), d.plusDays(3), 14, 1, "생활 정리", "정리", null, ReqStatus.EXPIRED, d.minusDays(2).atTime(11, 0));
            ex2.rejectReason = null;
            Request rj = req(Kind.REGULAR, null, U.get("bok"), U.get("sky"), U.get("bok"), null, d.plusDays(2), 15, 1, "디지털 도움", "폰 설정", null, ReqStatus.REJECTED, d.minusDays(1).atTime(9, 0));
            rj.rejectReason = "그 시간에는 어려워요";
        }
    }

    // ======================= 지금 진행 중(프로토타입 D.P / D.R / D.RE) =======================
    private void seedCurrent(User org1) {
        User yujin = U.get("yujin"), sky = U.get("sky"), mk = U.get("mikyung"), doyun = U.get("doyun"), bok = U.get("bok");
        LocalDateTime now = LocalDateTime.now();
        Post p1 = post(PostType.OFFER, sky, null, p(11, 12), 13, 17, "행복동", List.of("동행", "디지털 도움"), "병원 동행·폰 설정 도와드려요", null, "병원 동행·폰 설정 도와드려요", null, PostStatus.MATCHED, now.minusDays(2));
        String series = UUID.randomUUID().toString();
        for (int w = 0; w < 4; w++) {
            Post p2 = post(PostType.OFFER, mk, null, p(11, 13).plusWeeks(w), 10, 13, "행복동", List.of("말동무·정서", "동행"), "말벗·동행 도와드려요", null, "오전에 산책·말벗 가능해요", null, PostStatus.OPEN, now.minusDays(1).minusHours(3));
            p2.seriesId = series; p2.repeatRule = "매주 " + "월화수목금토일".charAt(p2.date.getDayOfWeek().getValue() - 1) + "요일";
        }
        post(PostType.OFFER, doyun, null, p(11, 15), 9, 12, "희망동", List.of("장보기·심부름", "가벼운 집안일"), "장보기·집안일 도와드려요", null, "주말 오전 장보기 대신 해드려요", null, PostStatus.OPEN, now.minusHours(20));
        Post p5 = post(PostType.NEED, bok, null, p(11, 13), 15, 18, "행복동", List.of("디지털 도움"), "카페 키오스크 주문 연습", "동네 카페 키오스크 쓰는 법을 배우고 싶어요", null, 1.0, PostStatus.OPEN, now.minusHours(30));
        p5.difficultyReason = "키오스크 사용법 설명(전문성 낮음) → 기본 등급";
        Post p4 = post(PostType.NEED, null, E.get("e2"), p(11, 14), 14, 17, "희망동", List.of("장보기·심부름"), "일주일치 장보기", "쌀, 반찬거리. 무거운 것 위주", null, 1.5, PostStatus.OPEN, now.minusHours(26));
        p4.difficultyReason = "장보기·심부름은 기본 수고 등급, 무거운 짐(신체 부담) → 수고 등급(×1.2)을 제안해요.";
        Post p6 = post(PostType.NEED, null, E.get("e1"), p(11, 18), 9, 13, "행복동", List.of("동행"), "정형외과 재진 동행", "햇살정형외과 진료 후 약국 들르기", "장소: 햇살정형외과", 2.5, PostStatus.OPEN, now.minusHours(5));
        p6.difficultyReason = "동행은 기본 책임 등급 → 책임 등급(×1.5)을 제안해요.";
        Post p7 = post(PostType.OFFER, yujin, null, p(11, 14), 13, 17, "행복동", List.of("디지털 도움", "말동무·정서", "장보기·심부름"), "디지털·말벗 도와드려요", null, "금요일 오후 비어요", null, PostStatus.OPEN, now.minusDays(1));
        Post p8 = post(PostType.NEED, yujin, null, p(11, 16), 10, 14, "행복동", List.of("생활 정리"), "이사 후 옷장 정리", "혼자 하기엔 양이 많아요", null, 2.0, PostStatus.OPEN, now.minusDays(1).plusHours(2));
        Post p9 = post(PostType.OFFER, yujin, null, p(11, 9), 9, 13, "희망동", List.of("생활 정리", "장보기·심부름"), "주말 오전 정리 도와드려요", null, null, null, PostStatus.MATCHED, now.minusDays(4));

        Request r1 = req(Kind.REQUEST, p7, bok, yujin, bok, null, p(11, 14), 14, 2, "장보기·심부름", "행복마트에서 쌀 10kg, 반찬거리 장보기", "쌀이 무거워서 부탁해요. 엘리베이터 있어요.", ReqStatus.SENT, now.minusMinutes(10));
        r1.deadlineAt = now.plusHours(18);
        Request r2 = req(Kind.REQUEST, p7, org1, yujin, null, E.get("e1"), p(11, 14), 15, 1, "말동무·정서", "안부 방문, 같이 이야기 나누기", "고향(전라도) 이야기를 좋아하세요.", ReqStatus.SENT, now.minusHours(3));
        r2.deadlineAt = now.plusHours(21);
        Request r3 = req(Kind.REQUEST, p1, yujin, sky, yujin, null, p(11, 12), 14, 2, "동행", "정형외과 진료 동행, 약국 들르기", "진료가 길어질 수 있어요.", ReqStatus.VOL_ACCEPTED, now.minusHours(6));
        r3.deadlineAt = now.plusHours(20);
        sys(r3, "김하늘 님이 수락했어요. 최종 수락을 기다려요.", now.minusHours(1));
        Request r4 = req(Kind.PROPOSAL, p8, doyun, doyun, yujin, null, p(11, 16), 11, 2, "생활 정리", "옷장 정리, 안 입는 옷 분류", "정리 수납 자격증 있어요. 박스 몇 개 가져갈게요.", ReqStatus.SENT, now.minusHours(2));
        r4.deadlineAt = now.plusHours(15);
        Request r5 = req(Kind.REQUEST, p9, org1, yujin, null, E.get("e2"), p(11, 9), 10, 1, "생활 정리", "냉장고 정리, 유통기한 지난 음식 버리기", null, ReqStatus.CONFIRMED, now.minusDays(3));
        r5.confirmedAt = now.minusDays(2); r5.checkCode = "482915";
        sys(r5, "일정이 확정됐어요.", now.minusDays(2));
        msg(r5, org1, "담당자 박지원", "안녕하세요, 행복동 주민센터 박지원입니다. 방문 장소는 희망동 ○○아파트 102동이에요. 도착하시면 경비실에 말씀해 주세요.", now.minusDays(1).minusHours(2));
        msg(r5, yujin, "손유진", "네, 10시까지 가겠습니다!", now.minusDays(1).minusHours(1));
        Request r6 = req(Kind.REGULAR, null, yujin, mk, yujin, null, p(11, 7), 19, 1, "말동무·정서", "저녁 산책 같이 하기", null, ReqStatus.LOG_SUBMITTED, p(11, 5).atTime(9, 0));
        r6.confirmedAt = p(11, 5).atTime(12, 0); r6.checkCode = "736104"; r6.checkInAt = at(p(11, 7), 19).minusMinutes(2);
        r6.checkOutAt = at(p(11, 7), 20).plusMinutes(1); r6.checkMethod = "qr";
        r6.logRaw = "저녁 7시부터 행복공원 한 바퀴 산책"; r6.logText = "저녁 7시부터 행복공원 한 바퀴 산책했어요.\n요즘 회사 일 이야기를 나눴어요.\n다음 주에도 같이 걷기로 했어요.";
        r6.logSubmittedAt = now.minusHours(2);
        led(yujin, null, LedgerType.HOLD, r6.estEarn, 1.0, 1.0, r6.id, "말동무·정서 예약 보류", r6.confirmedAt);
        sys(r6, "일정이 확정됐어요.", r6.confirmedAt);
        msg(r6, mk, "최미경", "공원 입구에서 만나요 :)", at(p(11, 7), 18.5));

        // 담당자 화면용(D.RE)
        Request q1 = req(Kind.REGULAR, null, org1, mk, null, E.get("e1"), p(11, 13), 10.5, 1.5, "말동무·정서", "안부 방문, 동네 한 바퀴 산책", "무릎이 아프셔서 천천히 걸어 주세요", ReqStatus.VOL_ACCEPTED, now.minusHours(8));
        q1.deadlineAt = now.plusHours(19);
        sys(q1, "최미경 님이 수락했어요. 담당자가 대상에게 확인 후 최종 수락/거부를 입력해요.", now.minusMinutes(30));
        Request q2 = req(Kind.PROPOSAL, p4, doyun, doyun, null, E.get("e2"), p(11, 14), 15, 1.5, "장보기·심부름", "행복마트 장보기, 무거운 것 위주", "차가 있어서 쌀도 괜찮아요", ReqStatus.SENT, now.minusHours(1));
        q2.deadlineAt = now.plusHours(22);
        q2.difficulty = Level.EFFORT; q2.weight = 1.2; q2.estEarn = Rules.earn(1.5, 1.2);
        Request q3 = req(Kind.REGULAR, null, org1, sky, null, E.get("e3"), p(11, 6), 16, 1, "디지털 도움", "손녀와 영상통화 설정", null, ReqStatus.LOG_SUBMITTED, p(11, 3).atTime(10, 0));
        q3.confirmedAt = p(11, 3).atTime(15, 0); q3.checkCode = "205871"; q3.checkInAt = at(p(11, 6), 16).minusMinutes(1);
        q3.checkOutAt = at(p(11, 6), 17); q3.checkMethod = "qr"; q3.logRaw = "카카오톡 영상통화 버튼 위치 알려 드림";
        q3.logText = "카카오톡 영상통화 버튼 위치를 알려 드렸어요.\n손녀와 실제로 통화 연습을 2번 했어요.\n큰 글씨 설정도 같이 했어요.";
        q3.logSubmittedAt = now.minusHours(20);

        // 손유진 → 엄마 시간 선물(11/1)
        LocalDateTime g = p(11, 1).atTime(20, 0);
        led(yujin, null, LedgerType.GIFT_OUT, -2.0, null, null, null, "엄마 이순자 님에게 선물", g);
        led(U.get("mom"), null, LedgerType.GIFT_IN, 2.0, null, null, null, "손유진 님이 보낸 선물", g);

        // 알림(D.N, D.NO)
        noti(yujin, "NEW_REQUEST", "김복남 님이 내 공고에 도움을 요청했어요", "11/14(금) 오후 2시 · 장보기", "/requests/" + r1.id, now.minusMinutes(10), false);
        noti(yujin, "VOL_ACCEPTED", "김하늘 님이 요청을 수락했어요. 최종 수락해 주세요", "24시간 안에 응답하지 않으면 자동 만료돼요", "/requests/" + r3.id, now.minusHours(1), false);
        noti(yujin, "NEW_PROPOSAL", "이도윤 님이 봉사를 제안했어요", "이사 후 옷장 정리", "/requests/" + r4.id, now.minusHours(2), false);
        noti(yujin, "LOG_SUBMITTED", "최미경 님이 활동 일지를 올렸어요. 확인해 주세요", "확인하면 시간이 정산돼요", "/requests/" + r6.id, now.minusHours(2), false);
        noti(yujin, "NEW_REQUEST", "이○례 님이 도움을 요청했어요", "안부 방문 · 말동무", "/requests/" + r2.id, now.minusHours(3), true);
        noti(yujin, "REMIND", "내일 오전 10시 봉사 일정이 있어요", "정○순 님 · 냉장고 정리 (리마인드)", "/requests/" + r5.id, now.minusHours(14), true);
        r5.reminded = true;
        noti(yujin, "INTEREST", "관심 조건에 맞는 새 공고가 올라왔어요", "행복동 · 디지털 도움 · 주말", "/posts", now.minusDays(2), true);
        noti(yujin, "INQUIRY", "1:1 문의에 답변이 등록됐어요", "적립 시간이 안 들어왔어요", "/my/inquiries", now.minusDays(3), true);
        noti(org1, "VOL_ACCEPTED", "최미경 님이 이순례 님 요청을 수락했어요", "전화로 확인 후 최종 수락/거부를 입력해 주세요", "/requests/" + q1.id, now.minusMinutes(30), false);
        noti(org1, "NEW_PROPOSAL", "이도윤 님이 정말순 님 공고에 제안했어요", "일주일치 장보기", "/requests/" + q2.id, now.minusHours(1), false);
        noti(org1, "LOG_SUBMITTED", "김하늘 님이 한영자 님 활동 일지를 올렸어요", "대신 확인하고 평가해 주세요", "/requests/" + q3.id, now.minusHours(20), false);
        noti(sky, "NEW_REQUEST", "손유진 님이 도움을 요청했어요", "정형외과 진료 동행", "/requests/" + r3.id, now.minusHours(6), true);
        noti(doyun, "INFO", "제안을 보냈어요", "이사 후 옷장 정리 · 응답을 기다려요", "/requests/" + r4.id, now.minusHours(2), true);
    }

    private void sys(Request r, String text, LocalDateTime at) {
        Message m = new Message();
        m.requestId = r.id; m.systemMsg = true; m.senderLabel = "시스템"; m.text = text; m.createdAt = at;
        messages.save(m);
    }

    private void msg(Request r, User u, String label, String text, LocalDateTime at) {
        Message m = new Message();
        m.requestId = r.id; m.senderId = u.id; m.senderLabel = label; m.text = text; m.createdAt = at;
        messages.save(m);
    }

    private void noti(User u, String type, String title, String body, String link, LocalDateTime at, boolean read) {
        Notification n = new Notification();
        n.userId = u.id; n.type = type; n.title = title; n.body = body; n.link = link; n.createdAt = at; n.isRead = read;
        notis.save(n);
    }

    // ======================= 공지·문의·신고·가족·교육 =======================
    private void seedMisc(User org1) {
        User yujin = U.get("yujin");
        notice("11월 안전 교육 일정 안내", "11월 첫 활동 교육은 앱에서 언제든 들을 수 있어요. 규칙 카드 → 상황 퀴즈 → AI 역할극 순서로 25분이면 끝나요.", p(11, 3));
        notice("시간 통장 월별 결산 기능이 생겼어요", "시간 통장 화면에서 달마다 들어오고 나간 시간을 한눈에 볼 수 있어요.", p(10, 28));
        notice("추석 연휴 고객센터 운영 안내", "연휴 기간에는 1:1 문의 답변이 늦어질 수 있어요.", p(9, 30));

        Inquiry q1 = new Inquiry();
        q1.userId = yujin.id; q1.type = "적립 오류"; q1.title = "적립 시간이 안 들어왔어요"; q1.content = "지난주 활동했는데 시간이 안 들어왔어요.";
        q1.status = InquiryStatus.ANSWERED; q1.answer = "활동 일지가 제출되지 않아 확정이 보류됐어요. 일지를 올리면 바로 적립돼요.";
        q1.createdAt = p(11, 3).atTime(20, 0); q1.answeredAt = p(11, 4).atTime(10, 0);
        inquiries.save(q1);
        Inquiry q2 = new Inquiry();
        q2.userId = yujin.id; q2.type = "이용 방법"; q2.title = "가족 연결은 몇 명까지 되나요?"; q2.content = "할머니도 연결하고 싶어요.";
        q2.createdAt = p(11, 6).atTime(21, 0);
        inquiries.save(q2);

        Report r1 = new Report();
        r1.reporterId = yujin.id; r1.targetType = ReportTarget.USER; r1.targetId = U.get("doyun").id; r1.targetLabel = "이○○ (봉사)";
        r1.type = "노쇼"; r1.content = "약속 시간에 오지 않았어요."; r1.status = ReportStatus.DONE; r1.action = ReportAction.WARNING; r1.result = "경고 1회";
        r1.createdAt = p(10, 30).atTime(18, 0); r1.handledAt = p(10, 31).atTime(11, 0);
        reports.save(r1);
        U.get("doyun").warningCount = 1;
        Report r2 = new Report();
        r2.reporterId = yujin.id; r2.targetType = ReportTarget.MESSAGE; r2.targetLabel = "메시지"; r2.type = "연락처 교환·금전 요구";
        r2.content = "카톡 아이디를 계속 물어봐요."; r2.status = ReportStatus.REVIEWING; r2.createdAt = p(11, 7).atTime(19, 0);
        reports.save(r2);

        FamilyLink f = new FamilyLink();
        f.requesterId = yujin.id; f.targetId = U.get("mom").id; f.relation = "엄마"; f.status = LinkStatus.ACCEPTED;
        f.shareReport = true; f.targetShareReport = true; f.createdAt = today.minusMonths(2).atTime(20, 0);
        family.save(f);

        InterestAlert a = new InterestAlert();
        a.userId = yujin.id; a.area = "행복동"; a.categories = new ArrayList<>(List.of("디지털 도움")); a.dayType = "주말";
        alerts.save(a);

        for (String k : List.of("sky", "mikyung", "doyun", "mom")) {
            EduProgress e = new EduProgress();
            e.userId = U.get(k).id; e.cardsDone = true; e.quizScore = 5;
            e.roleplayJson = Views.json(Map.of("1", Views.map("passed", true, "comm", 100, "emp", 100, "safe", true)));
            e.completedAt = today.minusMonths(3).atTime(21, 0);
            edu.save(e);
        }
        EduProgress y = new EduProgress();
        y.userId = yujin.id;
        y.diagJson = Views.json(Views.map("cats", yujin.categories, "who", "상관없어요", "exp", "조금 있어요"));
        edu.save(y);
    }

    private void notice(String t, String c, LocalDate d) {
        Notice n = new Notice();
        n.title = t; n.content = c; n.createdAt = d.atTime(9, 0);
        notices.save(n);
    }

    // ======================= 공공 서비스 (실제 존재하는 이름만, 세부는 보수적으로) =======================
    private void svc(String name, String target, String content, String apply, String contact, String... keywords) {
        PublicService s = new PublicService();
        s.name = name; s.target = target; s.content = content; s.applyHow = apply; s.contact = contact; s.region = "전국";
        s.keywords = new ArrayList<>(List.of(keywords));
        services.save(s);
    }

    private void seedServices() {
        svc("노인맞춤돌봄서비스", "일상생활에 도움이 필요한 만 65세 이상 어르신 (세부 기준은 기관 확인 필요)",
                "안부 확인, 생활 교육, 가사 지원 등 필요한 돌봄을 연계해 주는 서비스예요.", "주소지 읍·면·동 행정복지센터에 신청",
                "가까운 행정복지센터 (기관 확인 필요)", "돌봄", "독거", "안부", "가사", "외로", "말벗", "혼자");
        svc("노인장기요양보험 (방문요양·방문목욕)", "노화나 노인성 질병으로 혼자 일상생활이 어려운 분 (등급 판정 필요)",
                "등급을 받으면 요양보호사가 집으로 와서 신체활동·목욕 등을 도와요.", "국민건강보험공단에 장기요양인정 신청",
                "국민건강보험공단 (기관 확인 필요)", "신체", "장기요양", "방문요양", "방문목욕", "목욕", "기저귀", "요양");
        svc("장기요양 방문간호", "장기요양 등급을 받은 분 중 간호가 필요한 분 (기관 확인 필요)",
                "간호사 등이 집으로 방문해 의사 지시에 따른 간호를 해요.", "장기요양기관·국민건강보험공단 문의",
                "국민건강보험공단 (기관 확인 필요)", "의료", "방문간호", "주사", "투약", "간호", "상처");
        svc("보건소 방문건강관리", "건강관리가 필요한 취약계층 가구 (세부 기준은 보건소 확인 필요)",
                "보건소 전문 인력이 집으로 찾아가 건강 상태를 살피고 관리 방법을 안내해요.", "관할 보건소에 문의",
                "관할 보건소 (기관 확인 필요)", "의료", "방문건강", "보건소", "건강", "혈압", "약");
        svc("응급안전안심서비스", "혼자 사는 어르신·장애인 등 응급 상황 대비가 필요한 분 (기관 확인 필요)",
                "집에 화재·활동 감지 장비를 달아 응급 상황이 생기면 119와 담당 기관에 알려요.", "주소지 읍·면·동 행정복지센터에 신청",
                "가까운 행정복지센터 (기관 확인 필요)", "안전", "응급", "쓰러", "혼자", "화재", "위급");
        svc("치매안심센터", "치매가 걱정되는 분과 그 가족", "치매 조기검진, 상담, 가족 지원 프로그램을 안내해요.",
                "관할 보건소의 치매안심센터 방문 또는 전화", "관할 치매안심센터 (기관 확인 필요)", "의료", "치매", "기억", "깜빡");
        svc("치매공공후견사업", "의사결정 지원이 필요한 치매 어르신 중 후견인이 없는 분 (기관 확인 필요)",
                "공공후견인이 재산 관리·서비스 이용 같은 의사결정을 도와요.", "관할 치매안심센터에 상담",
                "관할 치매안심센터 (기관 확인 필요)", "금전", "후견", "재산", "통장", "돈");
        svc("주거급여 수선유지급여", "주거급여 대상 자가 가구 (기관 확인 필요)", "노후 주택의 수리를 지원해요.",
                "주소지 읍·면·동 행정복지센터에 문의", "가까운 행정복지센터 (기관 확인 필요)", "주거", "집수리", "수리", "수선", "보일러", "지붕");
        svc("노인일자리 및 사회활동 지원사업", "사회활동을 원하는 어르신 (유형별 기준은 기관 확인 필요)",
                "공익활동·사회서비스형 등 어르신이 참여할 수 있는 일자리와 사회활동을 연결해요.", "행정복지센터·노인복지관·시니어클럽에 문의",
                "가까운 행정복지센터 (기관 확인 필요)", "일자리", "사회활동", "소일거리");
        svc("디지털배움터", "디지털 기기 사용이 어려운 누구나 (운영 장소는 지역마다 다름)",
                "스마트폰·키오스크 사용법 등 디지털 교육을 받을 수 있어요.", "디지털배움터 누리집 또는 지역 운영 기관 문의",
                "지역 운영 기관 (기관 확인 필요)", "디지털", "휴대폰", "스마트폰", "키오스크", "컴퓨터");
    }

    // ======================= 역할극 상대 (RP) =======================
    private void persona(String name, String init, String age, String fit, String trait, String goal, String open, List<Map<String, Object>> turns) {
        RoleplayPersona p = new RoleplayPersona();
        p.name = name; p.initial = init; p.ageLabel = age; p.fitCategory = fit; p.trait = trait; p.goal = goal; p.opening = open;
        p.turnsJson = Views.json(turns);
        personas.save(p);
    }

    private static Map<String, Object> o(String t, String q, String f) { Map<String, Object> m = Views.map("t", t, "q", q); if (f != null) m.put("f", f); return m; }
    private static Map<String, Object> turn(String next, Map<String, Object>... opts) { return Views.map("options", List.of(opts), "next", next); }

    @SuppressWarnings("unchecked")
    private void seedPersonas() {
        persona("이○례 님", "례", "82세 · 어르신", "말동무·정서", "귀가 어두우시고 같은 이야기를 자주 하세요", "천천히, 짜증 내지 않고 대화해 보세요", "(문을 열며) 누구여? 뭐라고?", List.of(
                turn("아이고 그려, 들어와. 우리 손녀가 서울 사는데… 손녀가 서울 살아. 내가 말했던가?",
                        o("(천천히, 크게) 안녕하세요! 시간 적금에서 온 손유진이에요. 이야기 나누러 왔어요.", "good", null), o("아까 문자 드렸잖아요. 봉사하러 왔다고요.", "poor", null), o("안녕하세요~ 들어가도 될까요?", "ok", null)),
                turn("추석에 왔다 갔지. 근데 다리가 쑤셔서 그러는데, 저기 파스 좀 붙여 줄 텨?",
                        o("아, 서울 사는 손녀분이요? 자주 보러 오세요?", "good", null), o("네, 방금 말씀하셨어요.", "poor", null), o("그렇구나~", "ok", null)),
                turn("그려그려, 고마워. 다음에 또 와.",
                        o("파스 붙여 드리는 건 제가 할 수 없어요. 손 닿기 쉬운 곳에 꺼내 드릴게요. 많이 아프시면 담당자님께 꼭 알려 드릴게요.", "good", null), o("네, 제가 붙여 드릴게요. 어디가 아프세요?", "bad", "신체 돌봄"), o("음… 그건 좀 그래요.", "ok", null))));
        persona("정○순 님", "순", "85세 · 어르신", "장보기·심부름", "고마운 마음을 돈이나 음식으로 표현하세요", "마음은 받되 규칙은 지켜 보세요", "아이고, 그 무거운 걸 다 들고 왔네. 고마워서 어쩌나.", List.of(
                turn("(봉투를 내밀며) 이거 얼마 안 되지만 받아. 차비라도 해.",
                        o("별말씀을요! 냉장고에 넣을 것부터 정리해 드릴게요. 영수증이랑 잔돈도 같이 확인해 주세요.", "good", null), o("네, 좀 무겁긴 했어요. 다음엔 조금만 사세요.", "poor", null), o("어디에 둘까요?", "ok", null)),
                turn("참 착하네. 그럼 다음에도 자기가 와 줄 수 있어? 전화번호 하나 알려 줘.",
                        o("마음만 감사히 받을게요. 여기서는 돈을 받으면 안 되는 규칙이 있어요. 대신 제 통장에 시간이 쌓여요!", "good", null), o("그럼 이번만 받을게요. 감사합니다!", "bad", "금전·선물"), o("아니에요, 괜찮아요.", "ok", null)),
                turn("그려, 담당자한테 말해 둘게. 조심히 가.",
                        o("앱에서 저를 단골로 요청해 주시면 제가 받을 수 있어요. 담당자님께도 말씀드려 둘게요.", "good", null), o("네! 제 번호는 010-1234-5678이에요. 언제든 연락 주세요.", "bad", "연락처"), o("번호는 좀 어려워요.", "ok", null))));
        persona("정민지 님", "민", "27세 · 1인 가구", "디지털 도움", "바쁘고 직설적이에요. 고마우면 바로 선물하려 해요", "젊은 상대에게도 같은 규칙을 지켜 보세요", "안녕하세요… 사실 정부24 앱 본인인증이 계속 안 돼서요. 이거 한 시간째예요.", List.of(
                turn("와, 됐다! 진짜 감사해요. 다음에도 물어볼 거 있으면 카톡 아이디 알려 주실래요?",
                        o("어디서 막히는지 같이 볼게요. 화면 보여 주실래요? 비밀번호랑 인증번호는 직접 눌러 주세요.", "good", null), o("폰 주세요. 비밀번호 알려 주시면 제가 할게요.", "bad", "개인정보"), o("앱을 지웠다 다시 깔아 보세요.", "poor", null)),
                turn("아 그렇구나. 그럼 커피 기프티콘이라도 보낼게요!",
                        o("앱에서 저한테 단골 요청을 보내 주시면 돼요. 개인 연락처는 주고받지 않는 게 규칙이에요.", "good", null), o("네! 제 아이디는 yujin_s예요.", "bad", "연락처"), o("음, 그건 안 될 것 같아요.", "ok", null)),
                turn("오케이, 일지 바로 확인할게요. 오늘 진짜 살았어요!",
                        o("마음만 받을게요! 대신 활동 일지 확인 꼭 눌러 주세요. 그래야 제 시간이 쌓여요.", "good", null), o("오, 감사해요. 잘 마실게요!", "bad", "금전·선물"), o("괜찮아요~", "ok", null))));
    }

    // ======================= 동네 소식: 기관 행사 홍보 (2026-11-09 회의 2장) =======================
    private OrgEvent event(Organization o, User by, String title, String content, LocalDate d, Double h, String place, int cap, String type, String url, String ogTitle) {
        OrgEvent e = new OrgEvent();
        e.orgId = o.id; e.createdBy = by.id; e.title = title; e.content = content; e.eventDate = d; e.startHour = h; e.place = place;
        e.capacity = cap; e.applyType = type; e.url = url; e.ogTitle = ogTitle; e.createdAt = today.minusDays(3).atTime(10, 0);
        return orgEvents.save(e);
    }

    private void seedEvents(Organization o1, User org1) {
        OrgEvent a = event(o1, org1, "스마트폰 사진 정리 교실", "휴대폰에 쌓인 사진을 앨범으로 정리하고, 가족에게 보내는 법까지 같이 해 봐요. 휴대폰만 가져오세요.",
                p(11, 18), 14.0, "행복동 주민센터 1층 다목적실", 12, "APP", null, null);
        event(o1, org1, "가을 경로당 노래자랑", "경로당 어르신과 이웃이 함께하는 노래자랑이에요. 구경만 오셔도 좋아요. 떡과 차를 준비해요.",
                p(11, 22), 13.0, "행복동 경로당", 30, "APP", null, null);
        event(o1, org1, "치매 예방 건강 강좌", "보건소 선생님이 기억력 지키는 생활 습관을 알려 드려요. 신청은 아래 링크에서 받아요.",
                p(11, 25), 10.0, "행복구 보건소 3층 강당", 40, "LINK", "https://example.org/health-class", "치매 예방 건강 강좌 안내");
        event(o1, org1, "행복동 김장 나눔 행사", "동네가 같이 김장해서 혼자 사는 어르신 댁에 나눠요. 자원봉사 신청은 링크에서 받아요.",
                p(11, 29), 9.0, "행복동 주민센터 앞마당", 0, "LINK", "https://example.org/kimjang", null);
        for (String k : List.of("bok", "mom")) {
            EventApplication ap = new EventApplication();
            ap.eventId = a.id; ap.userId = U.get(k).id; ap.createdAt = today.minusDays(1).atTime(9, 0);
            eventApps.save(ap);
        }
    }
}
