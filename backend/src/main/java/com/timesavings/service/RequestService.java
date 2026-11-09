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
    public static final int CARE_MEMO_MAX = 500;

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

    private void checkVolunteerEligible(User v) {
        if (v == null || v.status != UserStatus.ACTIVE || v.role != Role.USER) throw ApiException.bad("VOLUNTEER_UNAVAILABLE", "지금 활동할 수 없는 봉사자예요");
        if (v.idStatus != VerifyStatus.APPROVED) throw ApiException.bad("ID_NOT_VERIFIED", "신분증 인증이 끝난 사용자만 봉사할 수 있어요 (B-01)");
    }

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
        checkVolunteerEligible(users.findById(r.volunteerId).orElse(null));

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
            checkVolunteerEligible(me);
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
            bank.add(r.recipientId, LedgerType.HOLD, r.estEarn, r.hours, r.weight, r.id, null, r.category + " 예약 보류");
        }
        r.status = ReqStatus.CONFIRMED;
        r.confirmedAt = LocalDateTime.now();
        r.deadlineAt = null;
        r.checkCode = auth.sixDigits();
        sys(r, "일정이 확정됐어요. " + when(r) + " · " + Rules.fmtHours(r.hours) + "시간");
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
        if (held && !released) bank.add(r.recipientId, LedgerType.RELEASE, r.estEarn, r.hours, r.weight, r.id, null, r.category + " 보류 해제");
    }

    // ======================= 취소·노쇼 (B-25) =======================
    public Request cancel(User me, Long id, String reason) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireStatus(r, ReqStatus.SENT, ReqStatus.VOL_ACCEPTED, ReqStatus.CONFIRMED);
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

    public Request noshow(User me, Long id) {
        Request r = get(id);
        String role = requireRole(r, me);
        requireRecipientSide(role);
        requireStatus(r, ReqStatus.CONFIRMED);
        if (LocalDateTime.now().isBefore(r.startAt().plusMinutes(15)))
            throw ApiException.conflict("TOO_EARLY", "시작 15분이 지나야 노쇼로 처리할 수 있어요");
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
        sys(r, "활동 시작 · " + hhmm(r.checkInAt.getHour() + (r.checkInAt.getMinute() >= 30 ? 0.5 : 0)) + " (" + methodKo(r.checkMethod) + ")");
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
        sys(r, "활동 종료 (" + methodKo(m) + "). 봉사자가 활동 일지를 올리면 확인해 주세요.");
        return r;
    }

    private static String methodKo(String m) {
        return switch (m) { case "qr" -> "QR 확인"; case "manual" -> "직접 입력 · 받는 분 확인 필요"; default -> "번호 확인"; };
    }

    private String verifyCode(Request r, String code, String method) {
        if ("manual".equals(method)) return "manual";
        if (code == null || !code.trim().equals(r.checkCode)) throw ApiException.bad("WRONG_CODE", "번호가 맞지 않아요. 받는 분 화면의 6자리 번호를 확인해 주세요");
        return "qr".equals(method) ? "qr" : "code";
    }

    // ======================= 활동 일지 (B-24) =======================
    public Request submitLog(User me, Long id, String raw, String text, String photoUrl, boolean photoConsent) {
        return submitLog(me, id, raw, text, photoUrl, photoConsent, null);
    }

    /** careMemo: 전달 메모(선택, 500자까지). null 이면 그대로 두고, 빈 문자열이면 지운다. */
    public Request submitLog(User me, Long id, String raw, String text, String photoUrl, boolean photoConsent, String careMemo) {
        Request r = get(id);
        requireVolunteer(requireRole(r, me));
        requireStatus(r, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED);
        if (raw == null || raw.isBlank()) throw ApiException.bad("LOG_REQUIRED", "무엇을 했는지 한 줄은 꼭 적어 주세요");
        String cm = careMemo == null ? null : careMemo.trim();
        if (cm != null && cm.length() > CARE_MEMO_MAX) throw ApiException.bad("MEMO_TOO_LONG", "전달 메모는 " + CARE_MEMO_MAX + "자까지 적을 수 있어요");
        if (cm != null) r.careMemo = cm.isEmpty() ? null : cm;
        r.logRaw = raw.trim();
        r.logText = text != null && !text.isBlank() ? text.trim() : (String) ai.logTidy(raw).get("text");
        r.photoConsent = photoConsent;
        r.logPhotoUrl = photoConsent ? photoUrl : null;
        boolean first = r.status == ReqStatus.CHECKED_OUT;
        r.status = ReqStatus.LOG_SUBMITTED;
        r.logSubmittedAt = LocalDateTime.now();
        if (first) {
            noti.toRecipientSide(r, "LOG_SUBMITTED", noti.name(r.volunteerId) + " 님이 " + (r.elderId != null ? noti.recipientLabel(r, true) + " " : "") + "활동 일지를 올렸어요. 확인해 주세요",
                    "확인하면 시간이 정산돼요. 24시간 동안 확인하지 않으면 자동 확정돼요");
        }
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
            bank.add(r.volunteerId, LedgerType.EARN, earned, hours, r.weight, r.id, null, r.category + " · " + recLabel);
        if (r.recipientId != null) {
            releaseHold(r);
            bank.add(r.recipientId, LedgerType.USE, -earned, hours, r.weight, r.id, null, r.category + " · " + noti.name(r.volunteerId) + " 님에게 받음");
        }
        sys(r, (auto ? "24시간이 지나 자동으로 확정됐어요. " : "확인이 끝났어요. ") + noti.name(r.volunteerId) + " 님에게 " + earned + "시간이 적립됐어요.");
        noti.toVolunteer(r, "EARNED", "+" + earned + "시간이 적립됐어요" + (r.anomalyHold ? " (점검 중이라 보류)" : ""),
                r.category + " · " + Rules.fmtHours(hours) + "시간 × " + r.weight);
        if (auto) noti.toRecipientSide(r, "AUTO_CONFIRMED", "활동이 자동 확정됐어요", when(r) + " · 일지 제출 후 24시간이 지났어요");
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
            noti.toVolunteer(r, "RATED", "새 평가를 받았어요 (★" + stars + ")", r.category + " · " + when(r));
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

    public int lateCheck(LocalDateTime now) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.CONFIRMED))) {
            if (r.lateNotified || r.checkInAt != null || now.isBefore(r.startAt().plusMinutes(15))) continue;
            r.lateNotified = true;
            String v = noti.name(r.volunteerId);
            sys(r, "약속 15분이 지나도 시작 체크인이 없어 " + (r.elderId != null ? "담당자" : "받는 분") + "에게 알렸어요.");
            noti.toRecipientSide(r, "LATE", v + " 님이 아직 도착하지 않았어요",
                    "약속 15분 경과 · 시작 체크인 없음. " + (r.elderId != null ? "봉사자에게 연락해 주세요" : "메시지를 보내거나 노쇼로 신고할 수 있어요"));
            noti.toVolunteer(r, "LATE", "약속 시간이 15분 지났어요", "도착했다면 시작 체크인을 해 주세요. 늦으면 메시지로 알려 주세요");
            n++;
        }
        return n;
    }

    public int remind(LocalDate today) {
        int n = 0;
        for (Request r : repo.findByStatusIn(List.of(ReqStatus.CONFIRMED))) {
            if (r.reminded || !r.date.equals(today.plusDays(1))) continue;
            r.reminded = true;
            noti.toVolunteer(r, "REMIND", "내일 " + hhmm(r.startHour) + " 봉사 일정이 있어요", noti.recipientLabel(r, false) + " · " + r.content);
            noti.toRecipientSide(r, "REMIND", "내일 " + hhmm(r.startHour) + " 도움 일정이 있어요", noti.name(r.volunteerId) + " 님 · " + r.content);
            n++;
        }
        return n;
    }
}
