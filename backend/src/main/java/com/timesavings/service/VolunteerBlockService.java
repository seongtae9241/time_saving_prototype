package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * O1 담당자의 봉사자 거부·"이 봉사자 받지 않기"(회의반영 3장).
 * 담당 대상 건의 요청·제안(SENT·VOL_ACCEPTED)과 수락된 일정(CONFIRMED)만 거부할 수 있다. 활동이 시작된 뒤에는 안 된다.
 */
@Service
@Transactional
public class VolunteerBlockService {
    private final RequestRepo requests;
    private final PostRepo posts;
    private final ElderRepo elders;
    private final ElderBlockRepo blocks;
    private final MessageRepo messages;
    private final NotificationService noti;
    private final Views views;

    public VolunteerBlockService(RequestRepo requests, PostRepo posts, ElderRepo elders, ElderBlockRepo blocks, MessageRepo messages,
                                 NotificationService noti, Views views) {
        this.requests = requests; this.posts = posts; this.elders = elders; this.blocks = blocks; this.messages = messages;
        this.noti = noti; this.views = views;
    }

    private Elder myElder(User u, Long elderId) {
        Elder e = elderId == null ? null : elders.findById(elderId).orElse(null);
        if (e == null || u.role != Role.ORG || !u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상의 건만 거부할 수 있어요");
        return e;
    }

    public Request rejectVolunteer(User me, Long requestId, String reason, boolean block) {
        if (reason == null || reason.isBlank()) throw ApiException.bad("REASON_REQUIRED", "거부 사유를 적어 주세요");
        String why = reason.trim();
        if (why.length() > 300) throw ApiException.bad("REASON_TOO_LONG", "사유는 300자까지 적어 주세요");
        Request r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
        Elder e = myElder(me, r.elderId);
        if (!(r.status.pending() || r.status == ReqStatus.CONFIRMED))
            throw ApiException.conflict("INVALID_STATE", "요청·제안 단계나 확정된 일정만 거부할 수 있어요 (지금 " + r.status + ")");
        ReqStatus before = r.status;
        r.status = before == ReqStatus.SENT && r.kind == Kind.PROPOSAL ? ReqStatus.REJECTED
                : before == ReqStatus.VOL_ACCEPTED ? ReqStatus.DECLINED : ReqStatus.CANCELLED;
        r.rejectReason = "담당자 거부: " + why;
        r.deadlineAt = null;
        reopenPost(r, before);
        Message m = new Message();
        m.requestId = r.id;
        m.systemMsg = true;
        m.senderLabel = "시스템";
        m.text = "담당자가 이 봉사를 거부했어요. 사유: " + why;
        messages.save(m);
        String label = Rules.maskAlias(e.alias);
        if (block && !blocks.existsByElderIdAndVolunteerId(e.id, r.volunteerId)) {
            ElderBlock b = new ElderBlock();
            b.elderId = e.id;
            b.volunteerId = r.volunteerId;
            b.reason = why;
            b.managerId = me.id;
            b.requestId = r.id;
            blocks.save(b);
        }
        noti.toVolunteer(r, "MANAGER_REJECTED", "담당자가 " + label + " 봉사를 거부했어요",
                "사유: " + why + (block ? " · 앞으로 이 분의 공고에는 신청할 수 없어요" : ""));
        return r;
    }

    /** 담당자 거부로 끝난 건의 공고는 아직 날짜가 남았으면 다시 연다(다른 봉사자를 받을 수 있게). */
    private void reopenPost(Request r, ReqStatus before) {
        if (r.postId == null || before == ReqStatus.SENT) return;
        posts.findById(r.postId).ifPresent(p -> {
            if (p.status == PostStatus.MATCHED && !p.date.isBefore(LocalDate.now())) p.status = PostStatus.OPEN;
        });
    }

    public List<Map<String, Object>> list(User me, Long elderId) {
        myElder(me, elderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ElderBlock b : blocks.findByElderIdOrderByIdDesc(elderId))
            out.add(Views.map("id", b.id, "elderId", b.elderId, "volunteer", views.userBrief(noti.user(b.volunteerId)),
                    "reason", b.reason, "requestId", b.requestId, "createdAt", b.createdAt));
        return out;
    }

    public void unblock(User me, Long blockId) {
        ElderBlock b = blocks.findById(blockId).orElseThrow(() -> ApiException.notFound("받지 않기 기록을 찾을 수 없어요"));
        myElder(me, b.elderId);
        blocks.delete(b);
    }

    public boolean blocked(Long elderId, Long volunteerId) {
        return elderId != null && volunteerId != null && blocks.existsByElderIdAndVolunteerId(elderId, volunteerId);
    }
}
