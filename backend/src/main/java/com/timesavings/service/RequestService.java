package com.timesavings.service;

import com.timesavings.ai.AiClient;
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
import java.util.regex.Pattern;

/** 요청·제안·단골 요청과 활동 진행(계약서 3.2). 상태 흐름은 모두 여기서 강제한다. */
@Service
@Transactional
public class RequestService {
    public static final Pattern CONTACT = Pattern.compile("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}|카톡|카카오톡\\s*아이디|아이디\\s*(는|알려)|계좌|송금|입금|용돈|현금|기프티콘");
    private final RequestRepo repo;
    private final PostRepo posts;
    private final UserRepo users;
    private final ElderRepo elders;
    private final MessageRepo messages;
    private final RatingRepo ratings;
    private final DifficultyAppealRepo appeals;
    private final BankService bank;
    private final NotificationService noti;
    private final Views views;
    private final AiClient ai;
    private final AuthService auth;
    /** O1 담당자 "이 봉사자 받지 않기" 검사용(생성자를 바꾸지 않으려고 필드 주입). */
    @org.springframework.beans.factory.annotation.Autowired private ElderBlockRepo blocks;
    /** 7.14 C1 전달 메모 정리·생활 변화 신호(일지 제출 뒤 한 번). */
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private CareSignalService careSignals;
    public static final int CARE_MEMO_MAX = 500;
    /** 7.13 LLM 추가 B 훅(브리핑·메시지 분류·판단 보조). 실패해도 본 흐름은 그대로. */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ActivityAiService activityAi;

    public RequestService(RequestRepo repo, PostRepo posts, UserRepo users, ElderRepo elders, MessageRepo messages,
                          RatingRepo ratings, DifficultyAppealRepo appeals, BankService bank, NotificationService noti,
                          Views views, AiClient ai, AuthService auth) {
        this.repo = repo; this.posts = posts; this.users = users; this.elders = elders; this.messages = messages;
        this.ratings = ratings; this.appeals = appeals; this.bank = bank; this.noti = noti; this.views = views;
        this.ai = ai; this.auth = auth;
    }

    public record CreateCmd(Kind kind, Long postId, Long volunteerId, Long elderId, LocalDate date, Double startHour,
                            Double hours, String category, String content, String memo) {}

    // ======================= 조회·권한 =======================
    public Request get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요")); }

    public String requireRole(Request r, User u) {
        String role = views.roleOf(r, u);
        if (role == null && u.role != Role.ADMIN) throw ApiException.forbidden("이 요청에 접근할 수 없어요");
        return role;
    }

    private void requireRecipientSide(String role) {
        if (!"RECIPIENT".equals(role) && !"MANAGER".equals(role)) throw ApiException.forbidden("도움 받는 쪽(또는 담당자)만 할 수 있어요");
    }

    private void requireVolunteer(String role) {
        if (!"VOLUNTEER".equals(role)) throw ApiException.forbidden("봉사하는 사용자만 할 수 있어요");
    }

    private static void requireStatus(Request r, ReqStatus... allowed) {
        for (ReqStatus s : allowed) if (r.status == s) return;
        throw ApiException.conflict("INVALID_STATE", "지금 상태(" + r.status + ")에서는 할 수 없어요");
    }

    private void sys(Request r, String text) {
        Message m = new Message();
        m.requestId = r.id;
        m.systemMsg = true;
        m.senderLabel = "시스템";
        m.text = text;
        messages.save(m);
    }

    private String hhmm(double h) {
        int H = (int) h;
        return H + ":" + (h - H >= 0.5 ? "30" : "00");
    }

    private String when(Request r) {
        return r.date.getMonthValue() + "/" + r.date.getDayOfMonth() + " " + hhmm(r.startHour);
    }

    /** 기능 U-34: 봉사 자격 검사는 VolunteerGate 한 곳에서 한다(신분증 + 첫 활동 교육). */
    @org.springframework.beans.factory.annotation.Autowired private VolunteerGate volunteerGate;

    private void checkVolunteerEligible(User v, boolean self) { volunteerGate.require(v, self); }

    // ======================= 생성 (B-14~B-16, C-05, C-06) =======================
    public Request create(User me, CreateCmd c) {
        if (c.kind() == null) throw ApiException.bad("INVALID_KIND", "요청 종류를 골라 주세요");
        if (c.content() == null || c.content().isBlank()) throw ApiException.bad("CONTENT_REQUIRED", "도움 내용을 적어 주세요");
        if (me.role == Role.ADMIN) throw ApiException.forbidden("운영자 계정으로는 요청할 수 없어요");
        Elder elder = null;
        if (me.role == Role.ORG) {
            if (c.elderId() == null) throw ApiException.bad("ELDER_REQUIRED", "어느 도움 대상의 요청인지 골라 주세요");
            elder = elders.findById(c.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!me.id.equals(elder.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 대신 요청할 수 있어요");
            if (elder.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
            if (c.kind() == Kind.PROPOSAL) throw ApiException.bad("INVALID_KIND", "담당자는 봉사 제안을 보낼 수 없어요");
        }
        Request r = new Request();
        r.kind = c.kind();
        r.senderId = me.id;
        r.content = c.content().trim();
        r.memo = c.memo() == null ? null : c.memo().trim();
        Post post = null;
        switch (c.kind()) {
            case REQUEST -> {
                post = openPost(c.postId(), PostType.OFFER);
                r.volunteerId = post.ownerId;
                if (elder != null) r.elderId = elder.id; else r.recipientId = me.id;
            }
            case PROPOSAL -> {
                post = openPost(c.postId(), PostType.NEED);
                if (post.elderId == null && me.id.equals(post.ownerId)) throw ApiException.bad("OWN_POST", "내 공고에는 제안할 수 없어요");
                if (post.elderId == null && me.id.equals(post.createdBy)) throw ApiException.bad("OWN_POST", "가족 대신 올린 공고에는 직접 제안할 수 없어요"); // B-57
                r.volunteerId = me.id;
                r.recipientId = post.ownerId;
                r.elderId = post.elderId;
            }
            case REGULAR -> {
                if (c.volunteerId() == null) throw ApiException.bad("VOLUNTEER_REQUIRED", "단골 봉사자를 골라 주세요");
                r.volunteerId = c.volunteerId();
                if (elder != null) r.elderId = elder.id; else r.recipientId = me.id;
                if (!views.isRegular(r.volunteerId, r.recipientId, r.elderId))
                    throw ApiException.bad("NOT_REGULAR", "단골(3회 이상 도와준 봉사자)에게만 직접 요청할 수 있어요");
            }
        }
        if (r.volunteerId == null || r.volunteerId.equals(r.recipientId) || (post != null && post.elderId == null && me.id.equals(post.ownerId) && c.kind() == Kind.REQUEST))
            throw ApiException.bad("OWN_POST", "내 공고에는 요청할 수 없어요");
        if (r.elderId != null && blocks != null && blocks.existsByElderIdAndVolunteerId(r.elderId, r.volunteerId))
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "VOLUNTEER_BLOCKED",
                    me.id.equals(r.volunteerId) ? "담당자가 이 대상의 봉사를 받지 않기로 했어요. 다른 공고를 찾아봐 주세요" : "이 대상에게 받지 않기로 한 봉사자예요");
        checkVolunteerEligible(users.findById(r.volunteerId).orElse(null), me.id.equals(r.volunteerId));

        // 일정 (B-10, B-22)
        r.date = c.date() != null ? c.date() : (post != null ? post.date : null);
        if (r.date == null) throw ApiException.bad("DATE_REQUIRED", "날짜를 정해 주세요");
        double hours = c.hours() != null ? c.hours() : (post != null && post.estHours != null ? post.estHours : 1.0);
        double start = c.startHour() != null ? c.startHour() : (post != null ? post.startHour : -1);
        Rules.checkHours(start, hours);
        if (r.date.isBefore(LocalDate.now())) throw ApiException.bad("PAST_DATE", "지난 날짜에는 요청할 수 없어요");
        if (post != null) {
            if (!r.date.equals(post.date)) throw ApiException.bad("OUT_OF_RANGE", "공고 날짜에만 요청할 수 있어요");
            if (start < post.startHour || start + hours > post.endHour + 1e-9)
                throw ApiException.bad("OUT_OF_RANGE", "공고 시간 범위(" + hhmm(post.startHour) + "~" + hhmm(post.endHour) + ") 안에서 정해 주세요");
        }
        r.startHour = start;
        r.hours = hours;

        // 카테고리·난이도
        String cat = c.category();
        if (cat == null || cat.isBlank()) cat = post != null && !post.categories.isEmpty() ? post.categories.get(0) : null;
        if (cat == null || !Rules.isCategory(cat)) throw ApiException.bad("INVALID_CATEGORY", "도움 종류(카테고리)를 골라 주세요");
        if (post != null && post.type == PostType.OFFER && !post.categories.contains(cat))
            throw ApiException.bad("INVALID_CATEGORY", "이 공고에서 가능한 도움 종류가 아니에요");
        r.category = cat;
        String text = r.content + " " + (r.memo == null ? "" : r.memo);
        RuleEngine.FilterResult f = RuleEngine.filter(text);
        if (f.level() == FilterLevel.BLOCK) throw ApiException.bad("FORBIDDEN_REQUEST", f.reason());
        Level lv = RuleEngine.difficulty(text, List.of(cat)).level();
        if (post != null && post.type == PostType.NEED && post.difficulty != null) lv = Rules.max(lv, post.difficulty);
        r.difficulty = lv;
        r.weight = Rules.weight(lv);
        r.estEarn = Rules.earn(hours, r.weight);

        // 받는 쪽 일반 사용자가 직접 보내는 요청이면 잔액·본인 사용 조건 확인 (B-30, B-33)
        if (r.recipientId != null && r.recipientId.equals(me.id)) bank.requireUsable(me.id, r.estEarn);
        if (post != null) {
            Long pid = post.id;
            boolean dup = repo.findByPostId(pid).stream().anyMatch(x -> x.senderId.equals(me.id) && x.status.pending());
            if (dup) throw ApiException.conflict("DUPLICATE", "이미 이 공고에 보낸 요청이 있어요");
        }
        r.postId = post == null ? null : post.id;
        r.deadlineAt = LocalDateTime.now().plusHours(24);
        repo.save(r);

        String recLabel = noti.recipientLabel(r, false);
        if (r.kind == Kind.PROPOSAL) {
            noti.toRecipientSide(r, "NEW_PROPOSAL", me.name + " 님이 봉사를 제안했어요",
                    (post != null ? post.title + " · " : "") + when(r) + " · 24시간 안에 응답해 주세요");
        } else {
            noti.toVolunteer(r, r.kind == Kind.REGULAR ? "NEW_REGULAR" : "NEW_REQUEST",
                    recLabel + "이 도움을 요청했어요" + (r.kind == Kind.REGULAR ? " (단골 요청)" : ""),
                    when(r) + " · " + r.category + " · 24시간 안에 응답해 주세요");
        }
        return r;
    }

    private Post openPost(Long postId, PostType type) {
        if (postId == null) throw ApiException.bad("POST_REQUIRED", "공고를 골라 주세요");
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("공고를 찾을 수 없어요"));
        if (p.type != type) throw ApiException.bad("INVALID_KIND", type == PostType.OFFER ? "봉사 가능 공고에만 요청할 수 있어요" : "도움 요청 공고에만 제안할 수 있어요");
        if (p.status != PostStatus.OPEN) throw ApiException.conflict("POST_CLOSED", "마감된 공고예요");
        return p;
    }

    // ======================= 수락·거절 (B-17~B-19, C-07) =======================
    public Request accept(User me, Long id) {
        Request r = get(id);
        String role = requireRole(r, me);
        if (r.status == ReqStatus.SENT && r.kind != Kind.PROPOSAL) {
            requireVolunteer(role);
            checkVolunteerEligible(me, true);
            r.status = ReqStatus.VOL_ACCEPTED;
            r.deadlineAt = LocalDateTime.now().plusHours(24);
            closePostFor(r);
            boolean elder = r.elderId != null;
            noti.toRecipientSide(r, "VOL_ACCEPTED",
                    me.name + " 님이 " + (elder ? noti.recipientLabel(r, true) + " 요청을" : "요청을") + " 수락했어요. 최종 수락해 주세요",
                    elder ? "대상에게 전화로 확인한 뒤 최종 수락/거부를 입력해 주세요 (24시간 안)" : "24시간 안에 응답하지 않으면 자동 만료돼요");
            sys(r, me.name + " 님이 수락했어요. " + (elder ? "담당자가 대상에게 확인 후 최종 수락/거부를 입력해요." : "최종 수락을 기다려요."));
            return r;
        }
        if ((r.status == ReqStatus.SENT && r.kind == Kind.PROPOSAL) || r.status == ReqStatus.VOL_ACCEPTED) {
            requireRecipientSide(role);
            if (r.kind == Kind.PROPOSAL) closePostFor(r);
            confirmMatch(r);
            return r;
        }
        throw ApiException.conflict("INVALID_STATE", "지금 상태에서는 수락할 수 없어요");
    }

    /** 1차 수락으로 공고를 닫고 같은 공고의 다른 요청은 자동 거절(B-17). */
    private void closePostFor(Request r) {
        if (r.postId == null) return;
        Post p = posts.findById(r.postId).orElse(null);
        if (p == null) return;
        if (p.status != PostStatus.OPEN) throw ApiException.conflict("POST_CLOSED", "이미 다른 요청이 수락된 공고예요");
        p.status = PostStatus.MATCHED;
        for (Request o : repo.findByPostId(p.id)) {
            if (o.id.equals(r.id) || !o.status.pending()) continue;
            o.status = ReqStatus.REJECTED;
            o.rejectReason = "다른 요청이 수락됨";
            o.deadlineAt = null;
            noti.send(o.senderId, "AUTO_REJECTED", "요청이 마감됐어요", (p.title != null ? p.title : "공고") + " · 다른 요청이 수락됐어요", "/requests/" + o.id);
        }
    }

    private void confirmMatch(Request r) {
        if (r.recipientId != null) {
            bank.requireUsable(r.recipientId, r.estEarn);
            bank.add(r.recipientId, LedgerType.HOLD, r.estEarn, r.hours, r.weight, r.id, r.category + " 예약 보류");
        }
        r.status = ReqStatus.CONFIRMED;
        r.confirmedAt = LocalDateTime.now();
        r.deadlineAt = null;
        r.checkCode = auth.sixDigits(); // 예전 자료용으로 남긴다. 확인은 아래 비밀값으로 만든 30초 코드로 한다(B-51)
        r.qrSecret = auth.randomHex(16);
        if (r.elderId != null) elders.findById(r.elderId).ifPresent(this::ensureCard); // 독거노인 건은 현관 QR 카드(C-11)
        sys(r, "일정이 확정됐어요. " + when(r) + " · " + Rules.fmtHours(r.hours) + "시간");
        if (activityAi != null) activityAi.onConfirmed(r); // 7.13 방문 전 브리핑
        noti.toVolunteer(r, "CONFIRMED", "일정이 확정됐어요", noti.recipientLabel(r, false) + " · " + when(r) + " · " + r.category);
        noti.toRecipientSide(r, "CONFIRMED", "일정이 확정됐어요", noti.name(r.volunteerId) + " 님 · " + when(r)
                + (r.elderId != null ? " · 방문 장소를 메시지로 안내해 주세요" : ""));
    }

    public Request reject(User me, Long id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.bad("REASON_REQUIRED", "거절 사유를 골라 주세요");
        Request r = get(id);
        String role = requireRole(r, me);
        requireStatus(r, ReqStatus.SENT);
        boolean volResponds = r.kind != Kind.PROPOSAL;
        if (volResponds) requireVolunteer(role); else requireRecipientSide(role);
        r.status = ReqStatus.REJECTED;
        r.rejectReason = reason;
        r.deadlineAt = null;
        noti.send(r.senderId, "REJECTED", (volResponds ? noti.name(r.volunteerId) + " 님이 요청을" : "제안이") + " 거절됐어요", "사유: " + reason, "/requests/" + r.id);
        return r;
    }

    public Request decline(User me, Long id, String reason) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireStatus(r, ReqStatus.VOL_ACCEPTED);
        requireRecipientSide(role);
        r.status = ReqStatus.DECLINED;
        r.rejectReason = reason == null || reason.isBlank() ? "최종 거부" : reason;
        r.deadlineAt = null;
        closeMatchedPost(r);
        noti.toVolunteer(r, "DECLINED", noti.recipientLabel(r, false) + " 쪽에서 최종 거부했어요", "사유: " + r.rejectReason);
        return r;
    }

    /** 최종 거부·만료·취소로 끝나도 공고는 다시 열리지 않는다(B-19) → CLOSED. */
    private void closeMatchedPost(Request r) {
        if (r.postId == null) return;
        posts.findById(r.postId).ifPresent(p -> { if (p.status == PostStatus.MATCHED) p.status = PostStatus.CLOSED; });
    }

    private void releaseHold(Request r) {
        if (r.recipientId == null) return;
        boolean held = bank.entries(r.recipientId).stream().anyMatch(l -> r.id.equals(l.requestId) && l.type == LedgerType.HOLD);
        boolean released = bank.entries(r.recipientId).stream().anyMatch(l -> r.id.equals(l.requestId) && l.type == LedgerType.RELEASE);
        if (held && !released) bank.add(r.recipientId, LedgerType.RELEASE, r.estEarn, r.hours, r.weight, r.id, r.category + " 보류 해제");
    }

    // ======================= 취소·노쇼 (B-25) =======================
    public Request cancel(User me, Long id, String reason) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireStatus(r, ReqStatus.SENT, ReqStatus.VOL_ACCEPTED, ReqStatus.CONFIRMED);
        // B-25: 상대가 응답하기 전에는 보낸 사람만 취소한다(받은 쪽은 거절로 답한다)
        boolean senderSide = me.id.equals(r.senderId) || (r.kind == Kind.PROPOSAL ? "VOLUNTEER".equals(role) : role != null && !"VOLUNTEER".equals(role));
        if (r.status == ReqStatus.SENT && !senderSide && me.role != Role.ADMIN)
            throw ApiException.forbidden("보낸 쪽만 취소할 수 있어요. 받은 요청은 거절로 답해 주세요");
        boolean wasConfirmed = r.status == ReqStatus.CONFIRMED;
        boolean late = wasConfirmed && LocalDateTime.now().isAfter(r.startAt().minusHours(24));
        r.status = ReqStatus.CANCELLED;
        r.rejectReason = reason == null || reason.isBlank() ? "취소" : reason;
        r.deadlineAt = null;
        if (wasConfirmed) releaseHold(r);
        if (r.postId != null && !r.status.pending()) closeMatchedPost(r);
        if (late && "VOLUNTEER".equals(role)) {
            me.penaltyCount++;
            users.save(me);
            noti.send(me.id, "PENALTY", "시작 24시간 이내 취소로 패널티가 1회 기록됐어요", "누적 " + me.penaltyCount + "회 · 반복되면 이용이 제한될 수 있어요", "/requests/" + r.id);
        }
        sys(r, (role == null ? "운영자" : me.name + " 님") + "이 취소했어요. 사유: " + r.rejectReason);
        if (!"VOLUNTEER".equals(role)) noti.toVolunteer(r, "CANCELLED", "활동이 취소됐어요", when(r) + " · 사유: " + r.rejectReason);
        if ("VOLUNTEER".equals(role) || !me.id.equals(noti.recipientSideUser(r)))
            noti.toRecipientSide(r, "CANCELLED", "활동이 취소됐어요", when(r) + " · 사유: " + r.rejectReason);
        return r;
    }

    /**
     * D-02 퇴출: 그 사용자의 아직 끝나지 않은 요청(수락 대기·확정·진행 중)을 취소하고 보류 시간을 돌려준다. 열린 공고는 마감한다.
     * 이미 활동을 마친 건(종료 체크·일지 제출)은 상대의 적립이 걸려 있어 그대로 두고 평소처럼 정산된다. 정리한 요청 수를 돌려준다.
     */
    public int closeAllFor(User u, String reason) {
        int n = 0;
        for (Request r : repo.findAll()) {
            boolean mine = u.id.equals(r.volunteerId) || u.id.equals(r.recipientId) || (r.elderId == null && u.id.equals(r.senderId));
            if (!mine || !(r.status.pending() || r.status == ReqStatus.CONFIRMED || r.status == ReqStatus.IN_PROGRESS)) continue;
            boolean held = !r.status.pending();
            r.status = ReqStatus.CANCELLED;
            r.rejectReason = reason;
            r.deadlineAt = null;
            if (held) { releaseHold(r); closeMatchedPost(r); }
            sys(r, "운영자가 취소했어요. 사유: " + reason);
            if (!u.id.equals(r.volunteerId)) noti.toVolunteer(r, "CANCELLED", "활동이 취소됐어요", when(r) + " · 사유: " + reason);
            else noti.toRecipientSide(r, "CANCELLED", "활동이 취소됐어요", when(r) + " · 사유: " + reason);
            n++;
        }
        for (Post p : posts.findAll())
            if (p.elderId == null && u.id.equals(p.ownerId) && (p.status == PostStatus.OPEN || p.status == PostStatus.MATCHED)) p.status = PostStatus.CLOSED;
        return n;
    }

    public Request noshow(User me, Long id) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireRecipientSide(role);
        requireStatus(r, ReqStatus.CONFIRMED);
        if (LocalDateTime.now().isBefore(r.startAt().plusMinutes(Rules.NOSHOW_MIN)))
            throw ApiException.conflict("TOO_EARLY", "시작 " + Rules.NOSHOW_MIN + "분이 지나야 노쇼로 처리할 수 있어요");
        r.status = ReqStatus.NOSHOW;
        releaseHold(r);
        closeMatchedPost(r);
        User v = users.findById(r.volunteerId).orElseThrow();
        v.penaltyCount++;
        users.save(v);
        sys(r, "봉사자가 오지 않아 노쇼로 처리됐어요. 보류된 시간은 돌려드렸어요.");
        noti.toVolunteer(r, "NOSHOW", "노쇼로 기록됐어요 (패널티 1회)", when(r) + " 활동에 체크인 기록이 없어요. 누적 " + v.penaltyCount + "회");
        return r;
    }

    // ======================= 체크인·체크아웃 (B-22, 3.2) =======================
    public Request checkIn(User me, Long id, String code, String method) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.CONFIRMED);
        r.checkMethod = verifyCode(r, code, method);
        r.status = ReqStatus.IN_PROGRESS;
        r.checkInAt = LocalDateTime.now();
        sys(r, "활동 시작 · " + clock(r.checkInAt) + " (" + methodKo(r.checkMethod) + ")");
        noti.toRecipientSide(r, "CHECK_IN", noti.name(r.volunteerId) + " 님이 도착해 활동을 시작했어요", when(r));
        return r;
    }

    public Request checkOut(User me, Long id, String code, String method) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.IN_PROGRESS);
        String m = verifyCode(r, code, method);
        if (!"manual".equals(r.checkMethod)) r.checkMethod = m;
        r.status = ReqStatus.CHECKED_OUT;
        r.checkOutAt = LocalDateTime.now();
        sys(r, "활동 종료 · " + clock(r.checkOutAt) + " (" + methodKo(m) + "). 봉사자가 활동 일지를 올리면 확인해 주세요.");
        gapNotice(r);
        return r;
    }

    private static String clock(LocalDateTime t) { return String.format("%d:%02d", t.getHour(), t.getMinute()); }

    /** B-51: 기록된 활동 시간이 예정과 30분 이상 다르면 양쪽(독거노인 건은 담당자)에 한 번 안내. 적립은 예정 시간 기준 그대로다. */
    private void gapNotice(Request r) {
        Long gap = gapMinutes(r);
        if (r.gapNotified || gap == null || Math.abs(gap) < Rules.GAP_MIN) return;
        r.gapNotified = true;
        String diff = "기록된 시간이 예정(" + Rules.fmtHours(r.hours) + "시간)보다 " + Math.abs(gap) + "분 " + (gap > 0 ? "길어요" : "짧아요");
        sys(r, diff + ". 적립은 예정 시간 기준이에요. 실제와 다르면 봉사자가 시간 조정을 요청할 수 있어요.");
        noti.toVolunteer(r, "TIME_GAP", "기록된 시간이 예정과 달라요", diff + " · 적립은 예정 시간 기준이에요. 필요하면 시간 조정을 요청해 주세요");
        noti.toRecipientSide(r, "TIME_GAP", "기록된 시간이 예정과 달라요", diff + " · 봉사자가 시간 조정을 요청하면 확인해 주세요");
    }

    /** 기록된 활동 시간 − 예정 시간(분). 시작·종료 기록이 다 있어야 값이 있다. */
    public static Long gapMinutes(Request r) {
        if (r.checkInAt == null || r.checkOutAt == null) return null;
        return java.time.Duration.between(r.checkInAt, r.checkOutAt).toMinutes() - Math.round(r.hours * 60);
    }

    private static String methodKo(String m) {
        return switch (m) { case "qr" -> "QR 확인"; case "manual" -> "직접 입력 · 받는 분 확인 필요"; default -> "번호 확인"; };
    }

    private String verifyCode(Request r, String code, String method) {
        // 번호 없이 기록하는 길은 없앴다(2026-11-12 결정). QR 을 찍거나 6자리 번호를 넣어야 한다.
        if ("manual".equals(method))
            throw ApiException.bad("MANUAL_NOT_ALLOWED", "번호 없이는 시작·종료를 기록할 수 없어요. QR을 찍거나 번호 6자리를 넣어 주세요");
        String c = code == null ? "" : code.trim();
        if (r.elderId != null) {
            // 독거노인 건: 도움 대상별 현관 QR 카드(C-11). 다시 발급하면 이전 카드 번호는 여기서 걸린다.
            Elder e = elders.findById(r.elderId).orElse(null);
            String card = e == null ? null : ensureCard(e).qrCode;
            if (card == null || !card.equals(c))
                throw ApiException.bad("WRONG_CODE", "현관 QR 카드가 맞지 않아요. 카드가 다시 발급됐다면 새 카드를 찍어 주세요");
        } else {
            // 일반 건: 받는 분 화면의 30초 코드. 찍는 사이 바뀔 수 있어 직전 구간까지 받는다.
            long w = Rules.qrWindow(System.currentTimeMillis());
            if (!c.equals(Rules.rotatingCode(r.qrKey(), w)) && !c.equals(Rules.rotatingCode(r.qrKey(), w - 1)))
                throw ApiException.bad("WRONG_CODE", "번호가 맞지 않아요. 받는 분 화면의 QR·번호는 30초마다 바뀌니 지금 보이는 것으로 확인해 주세요");
        }
        return "qr".equals(method) ? "qr" : "code";
    }

    /** 지금 이 요청의 시작·종료에 맞는 번호: 일반 건은 30초 코드, 독거노인 건은 현관 카드 번호. */
    public String codeFor(Request r) {
        if (r.elderId != null) return elders.findById(r.elderId).map(e -> ensureCard(e).qrCode).orElse(null);
        return Rules.rotatingCode(r.qrKey(), Rules.qrWindow(System.currentTimeMillis()));
    }

    /** 받는 쪽 화면이 30초마다 다시 받아 가는 QR 정보. 독거노인 건은 바뀌지 않는 현관 카드라 남은 시간이 없다. */
    public Map<String, Object> qrInfo(User me, Long id) {
        Request r = get(id);
        requireRecipientSide(requireRole(r, me));
        boolean show = r.status == ReqStatus.CONFIRMED || r.status == ReqStatus.IN_PROGRESS;
        String code = show ? codeFor(r) : null;
        boolean elder = r.elderId != null;
        return Views.map("status", r.status, "elderCard", elder, "code", code,
                "qrText", code == null ? null : Rules.qrText(elder ? "ELD" : "REQ", elder ? r.elderId : r.id, code),
                "leftSec", elder || !show ? null : Rules.qrLeftSec(System.currentTimeMillis()), "stepSec", Rules.QR_STEP_SEC);
    }

    // ======================= 도움 대상 현관 QR 카드 (C-11) =======================
    /** 카드가 아직 없으면 만든다. */
    public Elder ensureCard(Elder e) {
        if (e.qrCode == null || e.qrCode.isBlank()) { e.qrCode = auth.sixDigits(); e.qrIssuedAt = LocalDateTime.now(); elders.save(e); }
        return e;
    }

    /** 다시 발급: 번호가 바뀌어 이전 카드는 쓸 수 없다. */
    public Elder reissueCard(Elder e) {
        String old = e.qrCode, next;
        do { next = auth.sixDigits(); } while (next.equals(old));
        e.qrCode = next;
        e.qrIssuedAt = LocalDateTime.now();
        return elders.save(e);
    }

    // ======================= 활동 일지 (B-24) =======================
    public Request submitLog(User me, Long id, String raw, String text, String photoUrl, boolean photoConsent) {
        return submitLog(me, id, raw, text, photoUrl, photoConsent, null);
    }

    /** careMemo: 전달 메모(선택, 500자까지). null 이면 그대로 두고, 빈 문자열이면 지운다. */
    public Request submitLog(User me, Long id, String raw, String text, String photoUrl, boolean photoConsent, String careMemo) {
        return submitLog(me, id, raw, text, photoUrl, photoConsent, careMemo, null);
    }

    /** 기능 U-16 사진 인증 규칙: 장보기·심부름은 영수증 사진(RECEIPT), 가벼운 집안일은 전후 사진 2장(BEFORE_AFTER), 그 밖은 선택(NONE). */
    public static String photoRule(String category) {
        if ("장보기·심부름".equals(category)) return "RECEIPT";
        if ("가벼운 집안일".equals(category)) return "BEFORE_AFTER";
        return "NONE";
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** photoUrl2: 가벼운 집안일의 "한 뒤" 사진(기능 U-16). 인증 사진이 필요한 카테고리는 사진이 없으면 제출할 수 없다. */
    public Request submitLog(User me, Long id, String raw, String text, String photoUrl, boolean photoConsent, String careMemo, String photoUrl2) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED);
        if (raw == null || raw.isBlank()) throw ApiException.bad("LOG_REQUIRED", "무엇을 했는지 한 줄은 꼭 적어 주세요");
        String cm = careMemo == null ? null : careMemo.trim();
        if (cm != null && cm.length() > CARE_MEMO_MAX) throw ApiException.bad("MEMO_TOO_LONG", "전달 메모는 " + CARE_MEMO_MAX + "자까지 적을 수 있어요");
        if (cm != null) r.careMemo = cm.isEmpty() ? null : cm;
        r.logRaw = raw.trim();
        r.logText = text != null && !text.isBlank() ? text.trim() : (String) ai.logTidy(raw).get("text");
        // 기능 U-16: 인증 사진(영수증·전후 사진). 고쳐서 다시 낼 때 새 사진이 없으면 먼저 올린 사진을 그대로 쓴다.
        String rule = photoRule(r.category);
        String p1 = blankToNull(photoUrl), p2 = blankToNull(photoUrl2);
        if (!"NONE".equals(rule)) {
            if (p1 == null) p1 = r.logPhotoUrl;
            if (p2 == null) p2 = r.logPhotoUrl2;
            if ("RECEIPT".equals(rule) && p1 == null)
                throw ApiException.bad("PHOTO_REQUIRED", "장보기·심부름은 영수증 사진을 올려야 일지를 제출할 수 있어요");
            if ("BEFORE_AFTER".equals(rule) && (p1 == null || p2 == null))
                throw ApiException.bad("PHOTO_REQUIRED", "가벼운 집안일은 하기 전·한 뒤 사진을 한 장씩 올려야 일지를 제출할 수 있어요");
        }
        r.photoConsent = photoConsent;
        if ("NONE".equals(rule)) { r.logPhotoUrl = photoConsent ? p1 : null; r.logPhotoUrl2 = null; }
        else { r.logPhotoUrl = p1; r.logPhotoUrl2 = "BEFORE_AFTER".equals(rule) ? p2 : null; }
        boolean first = r.status == ReqStatus.CHECKED_OUT;
        r.status = ReqStatus.LOG_SUBMITTED;
        r.logSubmittedAt = LocalDateTime.now();
        if (first) {
            noti.toRecipientSide(r, "LOG_SUBMITTED", noti.name(r.volunteerId) + " 님이 " + (r.elderId != null ? noti.recipientLabel(r, true) + " " : "") + "활동 일지를 올렸어요. 확인해 주세요",
                    "확인하면 시간이 정산돼요. 24시간 동안 확인하지 않으면 자동 확정돼요");
        }
        if (careSignals != null) careSignals.onLog(r);
        return r;
    }

    // ======================= 확인·정산 (B-23, B-26, B-30, B-31, C-10) =======================
    public Request confirm(User me, Long id) {
        Request r = get(id);
        requireRecipientSide(requireRole(r, me));
        requireStatus(r, ReqStatus.LOG_SUBMITTED);
        if ("PENDING".equals(r.adjustStatus)) throw ApiException.conflict("ADJUST_PENDING", "시간 조정 요청에 먼저 답해 주세요");
        settle(r, false);
        return r;
    }

    /** 정산: 봉사자 적립, 일반 사용자는 보류 해제 후 차감, 독거노인은 무차감. */
    public void settle(Request r, boolean auto) {
        double hours = "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours;
        double earned = Rules.earn(hours, r.weight);
        r.earned = earned;
        r.status = ReqStatus.DONE;
        r.doneAt = LocalDateTime.now();
        r.deadlineAt = null;
        String recLabel = noti.recipientLabel(r, false) + (r.elderId != null ? " (기관)" : "");
        if (!r.anomalyHold)
            bank.add(r.volunteerId, LedgerType.EARN, earned, hours, r.weight, r.id, r.category + " · " + recLabel);
        if (r.recipientId != null) {
            releaseHold(r);
            bank.add(r.recipientId, LedgerType.USE, -earned, hours, r.weight, r.id, r.category + " · " + noti.name(r.volunteerId) + " 님에게 받음");
        }
        sys(r, (auto ? "24시간이 지나 자동으로 확정됐어요. " : "확인이 끝났어요. ") + noti.name(r.volunteerId) + " 님에게 " + earned + "시간이 적립됐어요.");
        noti.toVolunteer(r, "EARNED", "+" + earned + "시간이 적립됐어요" + (r.anomalyHold ? " (점검 중이라 보류)" : ""),
                r.category + " · " + Rules.fmtHours(hours) + "시간 × " + r.weight);
        if (auto) noti.toRecipientSide(r, "AUTO_CONFIRMED", "활동이 자동 확정됐어요", when(r) + " · 일지 제출 후 24시간이 지났어요");
        regularNotice(r);
    }

    /** 기능 U-19: 같은 상대와 완료 3회째가 되면 양쪽 모두에게 단골이 됐다고 알린다(해지한 사이는 알리지 않는다). 한 번만. */
    private void regularNotice(Request r) {
        long n = repo.findAll().stream().filter(x -> x.status == ReqStatus.DONE && r.volunteerId.equals(x.volunteerId)
                && (r.elderId != null ? r.elderId.equals(x.elderId) : x.elderId == null && r.recipientId != null && r.recipientId.equals(x.recipientId))).count();
        if (n != 3 || !views.isRegular(r.volunteerId, r.recipientId, r.elderId)) return;
        noti.toVolunteer(r, "REGULAR", noti.recipientLabel(r, false) + "과 단골이 됐어요 ★", "세 번 함께했어요. 서로 단골로 표시되고, 상대가 공고 없이 바로 요청할 수 있어요");
        noti.toRecipientSide(r, "REGULAR", noti.name(r.volunteerId) + " 님과 단골이 됐어요 ★",
                (r.elderId != null ? noti.recipientLabel(r, true) + "을 세 번 도와준 분이에요. " : "세 번 함께했어요. ") + "공고 없이 바로 요청할 수 있어요");
    }

    // ======================= 시간 조정 (B-22) =======================
    public Request adjust(User me, Long id, Double hours, String reason) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.IN_PROGRESS, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED);
        if (hours == null) throw ApiException.bad("INVALID_HOURS", "조정할 시간을 정해 주세요");
        Rules.checkHours(r.startHour, hours);
        r.adjustHours = hours;
        r.adjustStatus = "PENDING";
        r.adjustReason = reason;
        if (activityAi != null) activityAi.onAdjust(r); // 7.13 판단 보조
        noti.toRecipientSide(r, "ADJUST", noti.name(r.volunteerId) + " 님이 활동 시간 조정을 요청했어요",
                Rules.fmtHours(r.hours) + "시간 → " + Rules.fmtHours(hours) + "시간" + (reason == null ? "" : " · " + reason));
        return r;
    }

    public Request adjustAnswer(User me, Long id, boolean accept) {
        Request r = get(id);
        requireRecipientSide(requireRole(r, me));
        if (!"PENDING".equals(r.adjustStatus)) throw ApiException.conflict("NO_ADJUST", "답할 시간 조정 요청이 없어요");
        if (accept && r.recipientId != null) {
            double extra = Rules.earn(r.adjustHours, r.weight) - r.estEarn;
            if (extra > 0) bank.requireUsable(r.recipientId, extra);
        }
        r.adjustStatus = accept ? "ACCEPTED" : "REJECTED";
        noti.toVolunteer(r, "ADJUST_ANSWER", "시간 조정 요청이 " + (accept ? "승인" : "거절") + "됐어요",
                accept ? Rules.fmtHours(r.adjustHours) + "시간으로 정산돼요" : "예정 시간 " + Rules.fmtHours(r.hours) + "시간으로 정산돼요");
        return r;
    }

    // ======================= 평가 (B-38, B-39) =======================
    private static final Pattern MONEY_WORD = Pattern.compile("돈|원$|용돈|현금|팁|사례금");

    public Rating rate(User me, Long id, int stars, List<String> tags, String comment) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireStatus(r, ReqStatus.DONE);
        if (stars < 1 || stars > 5) throw ApiException.bad("INVALID_STARS", "별점은 1~5개로 골라 주세요");
        if (ratings.findByRequestId(r.id).stream().anyMatch(x -> x.raterId.equals(me.id))) throw ApiException.conflict("ALREADY_RATED", "이미 평가했어요");
        Rating x = new Rating();
        x.requestId = r.id;
        x.raterId = me.id;
        x.stars = stars;
        x.tags = tags == null ? new ArrayList<>() : tags.stream().filter(t -> t != null && !t.isBlank() && !MONEY_WORD.matcher(t).find()).limit(5).toList();
        x.comment = comment;
        if ("VOLUNTEER".equals(role)) {
            if (r.elderId != null) throw ApiException.bad("NO_ELDER_RATING", "도움 대상(독거노인)은 평가하지 않아요");
            x.targetUserId = r.recipientId;
            x.isPrivate = true;
        } else {
            x.targetUserId = r.volunteerId;
            x.isPrivate = false;
            // 기능 U-18: 개별 평가(별점·좋았던 점·한마디)는 상대에게 알리지 않는다. 평가가 등록됐다는 것만 알린다.
            noti.toVolunteer(r, "RATED", "활동 평가가 등록됐어요", r.category + " · " + when(r) + " · 개별 평가 내용은 공개되지 않고 평균에만 반영돼요");
        }
        return ratings.save(x);
    }

    // ======================= 난이도 이의 신청 (B-28) =======================
    public DifficultyAppeal appeal(User me, Long id, String reason, Level requested) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.DONE);
        if (requested == null || requested.ordinal() <= r.difficulty.ordinal())
            throw ApiException.bad("INVALID_LEVEL", "지금 등급(" + Rules.levelKo(r.difficulty) + ")보다 높은 등급을 골라 주세요");
        if (reason == null || reason.isBlank()) throw ApiException.bad("REASON_REQUIRED", "이유를 적어 주세요");
        if (appeals.findByRequestId(r.id).stream().anyMatch(a -> a.status == AppealStatus.RECEIVED))
            throw ApiException.conflict("DUPLICATE", "이미 접수된 이의 신청이 있어요");
        DifficultyAppeal a = new DifficultyAppeal();
        a.requestId = r.id;
        a.userId = me.id;
        a.reason = reason;
        a.requestedLevel = requested;
        appeals.save(a);
        if (activityAi != null) activityAi.onAppeal(r, a); // 7.13 판단 보조
        for (User admin : users.findByRole(Role.ADMIN))
            noti.send(admin.id, "APPEAL", "난이도 이의 신청이 접수됐어요", me.name + " · " + Rules.levelKo(r.difficulty) + " → " + Rules.levelKo(requested), "/admin/appeals");
        return a;
    }

    // ======================= 메시지 (A-08, C-09) =======================
    public boolean canMessage(Request r) {
        return switch (r.status) {
            case VOL_ACCEPTED, CONFIRMED, IN_PROGRESS, CHECKED_OUT, LOG_SUBMITTED -> true;
            default -> false;
        };
    }

    public Message sendMessage(User me, Long id, String text) {
        Request r = get(id);
        String role = requireRole(r, me);
        if (role == null) throw ApiException.forbidden("이 요청에 메시지를 보낼 수 없어요");
        if (!canMessage(r)) throw ApiException.conflict("MESSAGE_CLOSED",
                r.status.terminal() ? "끝난 활동이라 메시지는 읽기만 할 수 있어요" : "상대가 수락한 뒤부터 메시지를 보낼 수 있어요");
        if (text == null || text.isBlank()) throw ApiException.bad("TEXT_REQUIRED", "메시지를 입력해 주세요");
        Message m = new Message();
        m.requestId = r.id;
        m.senderId = me.id;
        m.senderLabel = "MANAGER".equals(role) ? "담당자 " + me.name : me.name;
        m.text = text.trim();
        if (CONTACT.matcher(m.text).find()) m.warn = "연락처·계좌·돈 이야기는 주고받지 않아요. 불편한 요구를 받았다면 신고해 주세요.";
        messages.save(m);
        if (activityAi != null) activityAi.onMessage(r, m); // 7.13 안전 감지·답장·일정 변경
        Long recSide = noti.recipientSideUser(r);
        if (!me.id.equals(r.volunteerId)) noti.toVolunteer(r, "MESSAGE", "새 메시지: " + m.senderLabel, cut(m.text));
        if (recSide != null && !me.id.equals(recSide)) noti.send(recSide, "MESSAGE", "새 메시지: " + m.senderLabel, cut(m.text), "/requests/" + r.id);
        return m;
    }

    private static String cut(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    // ======================= 스케줄러 작업 =======================
    public int expireDue(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.SENT, ReqStatus.VOL_ACCEPTED))) {
            if (r.deadlineAt == null || r.deadlineAt.isAfter(now)) continue;
            boolean wasVolAccepted = r.status == ReqStatus.VOL_ACCEPTED;
            r.status = ReqStatus.EXPIRED;
            r.deadlineAt = null;
            if (wasVolAccepted) closeMatchedPost(r);
            noti.send(r.senderId, "EXPIRED", "응답 기한(24시간)이 지나 요청이 만료됐어요", when(r) + " · " + r.category, "/requests/" + r.id);
            n++;
        }
        return n;
    }

    public int autoConfirmDue(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.LOG_SUBMITTED))) {
            if (r.logSubmittedAt == null || r.logRaw == null || r.logSubmittedAt.plusHours(24).isAfter(now)) continue;
            if ("PENDING".equals(r.adjustStatus)) r.adjustStatus = "REJECTED";
            settle(r, true);
            n++;
        }
        return n;
    }

    /**
     * B-52 지각·노쇼 감지: 예정 시작 10분이 지나도 시작 기록이 없으면 지각 알림, 30분이 지나면 노쇼 의심 알림을
     * 양쪽에 보낸다(독거노인 건은 담당자가 받는다). 요청마다 단계별로 한 번씩만.
     */
    public int lateCheck(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.CONFIRMED))) {
            if (r.checkInAt != null) continue;
            LocalDateTime start = r.startAt();
            String v = noti.name(r.volunteerId);
            String side = r.elderId != null ? "담당자" : "받는 분";
            if (!r.noshowNotified && !now.isBefore(start.plusMinutes(Rules.NOSHOW_MIN))) {
                r.noshowNotified = true;
                r.lateNotified = true;
                if (now.isAfter(start.plusDays(1))) continue; // 하루 넘게 지난 예전 일정에는 뒤늦게 알리지 않는다
                sys(r, "약속 " + Rules.NOSHOW_MIN + "분이 지나도 시작 기록이 없어요(노쇼 의심). 봉사자와 " + side + "에게 알렸어요.");
                noti.toRecipientSide(r, "NOSHOW_SUSPECT", v + " 님이 오지 않은 것 같아요 (노쇼 의심)",
                        "약속 " + Rules.NOSHOW_MIN + "분 경과 · 시작 기록 없음. 더 기다리거나 노쇼로 신고하고 취소할 수 있어요");
                noti.toVolunteer(r, "NOSHOW_SUSPECT", "약속 시간이 " + Rules.NOSHOW_MIN + "분 지났어요 (노쇼 의심)",
                        "도착했다면 지금 시작 QR을 찍어 주세요. " + side + "이 노쇼로 신고하면 패널티가 기록돼요");
                n++;
            } else if (!r.lateNotified && !now.isBefore(start.plusMinutes(Rules.LATE_MIN))) {
                r.lateNotified = true;
                sys(r, "약속 " + Rules.LATE_MIN + "분이 지나도 시작 기록이 없어 봉사자와 " + side + "에게 알렸어요.");
                noti.toRecipientSide(r, "LATE", v + " 님이 아직 도착하지 않았어요",
                        "약속 " + Rules.LATE_MIN + "분 경과 · 시작 기록 없음. " + Rules.NOSHOW_MIN + "분이 지나면 다시 알려 드려요");
                noti.toVolunteer(r, "LATE", "약속 시간이 " + Rules.LATE_MIN + "분 지났어요", "도착했다면 시작 QR을 찍어 주세요. 늦으면 \"늦는다고 알리기\"로 알려 주세요");
                n++;
            }
        }
        return n;
    }

    /**
     * B-53 활동 종료 자동 마감: 종료 QR 기록이 없으면 예정 종료 30분 뒤에 예정 종료 시각으로 마감하고,
     * 봉사자에게 일지 작성 알림을 보낸다.
     */
    public int autoCloseDue(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.IN_PROGRESS))) {
            LocalDateTime end = r.endAt();
            if (now.isBefore(end.plusMinutes(Rules.AUTO_CLOSE_MIN))) continue;
            r.status = ReqStatus.CHECKED_OUT;
            r.checkOutAt = r.checkInAt != null && r.checkInAt.isAfter(end) ? r.checkInAt : end; // 시작이 예정 종료보다 늦었으면 0분으로
            r.autoClosed = true;
            sys(r, "종료 QR 기록이 없어 예정 종료 시각(" + clock(end) + ")으로 자동 마감됐어요. 봉사자가 활동 일지를 올리면 확인해 주세요.");
            noti.toVolunteer(r, "AUTO_CLOSED", "활동이 자동으로 마감됐어요. 일지를 써 주세요",
                    "종료 QR 기록이 없어 예정 종료 시각 " + clock(end) + "으로 기록했어요. 실제와 다르면 시간 조정을 요청할 수 있어요");
            noti.toRecipientSide(r, "AUTO_CLOSED", "활동이 예정 종료 시각으로 마감됐어요",
                    noti.name(r.volunteerId) + " 님 · 종료 QR 기록이 없어 " + clock(end) + "으로 기록했어요");
            gapNotice(r);
            n++;
        }
        return n;
    }

    public int remind(LocalDate today) { return remindAt(today.atTime(java.time.LocalTime.now())); }

    /** 41판 B-58: 시작 1시간 전 리마인드(시작까지 1시간 이하로 남았고 아직 시작 전인 확정 일정, 한 번만). */
    public int remindAt(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.CONFIRMED))) {
            if (r.reminded || now.isBefore(r.startAt().minusHours(1)) || !now.isBefore(r.startAt())) continue;
            r.reminded = true;
            noti.toVolunteer(r, "REMIND", "곧 " + hhmm(r.startHour) + " 봉사 일정이 있어요 (1시간 전 알림)", noti.recipientLabel(r, false) + " · " + r.content);
            noti.toRecipientSide(r, "REMIND", "곧 " + hhmm(r.startHour) + " 도움 일정이 있어요 (1시간 전 알림)", noti.name(r.volunteerId) + " 님 · " + r.content);
            n++;
        }
        return n;
    }
}
