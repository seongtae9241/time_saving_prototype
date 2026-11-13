package com.timesavings.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Enums.OrgStatus;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.Enums.UserStatus;
import com.timesavings.domain.Organization;
import com.timesavings.domain.PublicReferral;
import com.timesavings.domain.User;
import com.timesavings.repo.OrganizationRepo;
import com.timesavings.repo.PublicReferralRepo;
import com.timesavings.repo.UserRepo;
import com.timesavings.service.NotificationService;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 공공 서비스 연계. 사용자가 전달하겠다고 표시했을 때만(동의 필수) 그 대화와 AI가 찾은 서비스 기록이 담당자에게 간다.
 * 담당자는 자기 기관에 온 것만 보고 상태를 바꾼다(새 요청 → 연락함 → 신청 도움 중 → 완료 / 대상이 아니에요). 바꿀 때마다 사용자에게 알림.
 */
@RestController
@RequestMapping("/api")
@Transactional
public class PublicReferralController {
    private static final Map<String, String> LABEL = Map.of(PublicReferral.NEW, "새 요청", PublicReferral.CONTACTED, "연락함",
            PublicReferral.HELPING, "신청 도움 중", PublicReferral.DONE, "완료", PublicReferral.NOT_ELIGIBLE, "대상이 아니에요");
    private final PublicReferralRepo refs;
    private final OrganizationRepo orgs;
    private final UserRepo users;
    private final NotificationService noti;

    public PublicReferralController(PublicReferralRepo refs, OrganizationRepo orgs, UserRepo users, NotificationService noti) {
        this.refs = refs; this.orgs = orgs; this.users = users; this.noti = noti;
    }

    public record SendBody(Long orgId, String question, String answer, List<Map<String, Object>> services, String note, Boolean consent) {}
    public record StatusBody(String status, String memo) {}

    private static String cut(String s, int n) { if (s == null) return null; s = s.trim(); return s.length() > n ? s.substring(0, n) : s; }

    /** 전달할 수 있는 기관: 승인됐고 활동 중인 담당자가 있는 기관. 내 동네 이름이 주소에 있으면 먼저. */
    @GetMapping("/public-referrals/orgs")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orgList(@CurrentUser User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Organization o : orgs.findAll()) {
            if (o.status != OrgStatus.APPROVED) continue;
            long mgrs = users.findByOrgId(o.id).stream().filter(m -> m.role == Role.ORG && m.status == UserStatus.ACTIVE).count();
            if (mgrs == 0) continue;
            boolean near = u.area != null && !u.area.isBlank() && ((o.address != null && o.address.contains(u.area)) || (o.name != null && o.name.contains(u.area)));
            out.add(Views.map("id", o.id, "name", o.name, "orgType", o.orgType, "address", o.address, "near", near));
        }
        out.sort((a, b) -> Boolean.compare((Boolean) b.get("near"), (Boolean) a.get("near")));
        return out;
    }

    /** 사용자가 "담당자에게 전달하기"를 눌렀을 때. 동의가 없으면 받지 않는다. */
    @PostMapping("/public-referrals")
    public Map<String, Object> send(@CurrentUser User u, @RequestBody SendBody b) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자만 전달할 수 있어요");
        if (b == null || !Boolean.TRUE.equals(b.consent())) throw ApiException.bad("CONSENT_REQUIRED", "전달에 동의해야 담당자에게 보낼 수 있어요");
        if (b.question() == null || b.question().isBlank()) throw ApiException.bad("QUESTION_REQUIRED", "전달할 내용이 없어요. 먼저 필요한 도움을 물어봐 주세요");
        Organization o = b.orgId() == null ? null : orgs.findById(b.orgId()).orElse(null);
        if (o == null || o.status != OrgStatus.APPROVED) throw ApiException.bad("ORG_REQUIRED", "전달할 기관을 골라 주세요");
        List<User> mgrs = users.findByOrgId(o.id).stream().filter(m -> m.role == Role.ORG && m.status == UserStatus.ACTIVE).toList();
        if (mgrs.isEmpty()) throw ApiException.bad("NO_MANAGER", "이 기관에는 지금 받을 담당자가 없어요");
        boolean dup = refs.findByUserIdOrderByIdDesc(u.id).stream().anyMatch(r -> r.orgId.equals(o.id)
                && (PublicReferral.NEW.equals(r.status) || PublicReferral.CONTACTED.equals(r.status) || PublicReferral.HELPING.equals(r.status))
                && r.question.equals(cut(b.question(), 1000)));
        if (dup) throw ApiException.conflict("ALREADY_SENT", "같은 내용을 이미 이 기관에 전달했어요");
        PublicReferral r = new PublicReferral();
        r.userId = u.id; r.orgId = o.id;
        r.question = cut(b.question(), 1000);
        r.answer = cut(b.answer(), 2000);
        List<Map<String, Object>> list = b.services() == null ? List.of() : b.services().stream().limit(10).toList();
        String js = Views.json(list);
        r.servicesJson = js != null && js.length() > 6000 ? Views.json(list.stream().limit(4).toList()) : js;
        if (r.servicesJson != null && r.servicesJson.length() > 6000) r.servicesJson = "[]";
        r.note = cut(b.note(), 300);
        r.consentAt = LocalDateTime.now();
        refs.save(r);
        for (User m : mgrs) noti.send(m.id, "PUBLIC_REFERRAL", "공공 서비스 연계 요청이 왔어요", u.name + " 님이 공공 서비스 안내 내용을 전달했어요.", "/org/public-referrals/" + r.id);
        return view(r, false);
    }

    @GetMapping("/public-referrals/mine")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(@CurrentUser User u) {
        return refs.findByUserIdOrderByIdDesc(u.id).stream().map(r -> view(r, false)).toList();
    }

    // ---------------- 담당자 (/api/org/** 는 ORG 역할만) ----------------
    @GetMapping("/org/public-referrals")
    @Transactional(readOnly = true)
    public Map<String, Object> orgRefs(@CurrentUser User m) {
        List<PublicReferral> all = m.orgId == null ? List.of() : refs.findByOrgIdOrderByIdDesc(m.orgId);
        long open = all.stream().filter(r -> !PublicReferral.DONE.equals(r.status) && !PublicReferral.NOT_ELIGIBLE.equals(r.status)).count();
        return Views.map("items", all.stream().map(r -> view(r, true)).toList(),
                "newCount", all.stream().filter(r -> PublicReferral.NEW.equals(r.status)).count(), "openCount", open);
    }

    @PostMapping("/org/public-referrals/{id}/status")
    public Map<String, Object> setStatus(@CurrentUser User m, @PathVariable Long id, @RequestBody StatusBody b) {
        PublicReferral r = refs.findById(id).orElseThrow(() -> ApiException.notFound("연계 요청을 찾을 수 없어요"));
        if (m.orgId == null || !m.orgId.equals(r.orgId)) throw ApiException.forbidden("우리 기관에 온 요청만 처리할 수 있어요");
        String st = b == null ? null : b.status();
        if (st == null || !LABEL.containsKey(st) || PublicReferral.NEW.equals(st)) throw ApiException.bad("INVALID_STATUS", "처리 상태를 골라 주세요");
        r.status = st;
        if (b.memo() != null) r.memo = cut(b.memo(), 500);
        r.handlerId = m.id; r.updatedAt = LocalDateTime.now();
        Organization o = orgs.findById(r.orgId).orElse(null);
        noti.send(r.userId, "PUBLIC_REFERRAL", "공공 서비스 연계: " + LABEL.get(st),
                (o == null ? "기관" : o.name) + " 담당자가 전달하신 내용을 '" + LABEL.get(st) + "'(으)로 처리했어요.", "/public-referrals");
        return view(r, true);
    }

    private Map<String, Object> view(PublicReferral r, boolean forOrg) {
        Organization o = orgs.findById(r.orgId).orElse(null);
        Map<String, Object> m = Views.map("id", r.id, "orgId", r.orgId, "orgName", o == null ? null : o.name,
                "question", r.question, "answer", r.answer,
                "services", Views.parse(r.servicesJson, new TypeReference<List<Map<String, Object>>>() {}, List.of()),
                "note", r.note, "status", r.status, "statusLabel", LABEL.get(r.status), "memo", forOrg ? r.memo : null,
                "createdAt", r.createdAt == null ? null : r.createdAt.toString(), "updatedAt", r.updatedAt == null ? null : r.updatedAt.toString());
        if (forOrg) { // 전달에 동의한 사용자의 이름·연락처·동네·나이대만 담당자에게 준다
            User u = users.findById(r.userId).orElse(null);
            m.put("user", u == null ? null : Views.map("id", u.id, "name", u.name, "phone", u.phone, "area", u.area, "ageGroup", u.ageGroup));
        }
        return m;
    }
}
