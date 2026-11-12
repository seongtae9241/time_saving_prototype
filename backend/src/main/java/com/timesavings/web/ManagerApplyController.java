package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.OrgStatus;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.Enums.UserStatus;
import com.timesavings.domain.ManagerApplication;
import com.timesavings.domain.Organization;
import com.timesavings.domain.User;
import com.timesavings.repo.ManagerApplicationRepo;
import com.timesavings.repo.OrganizationRepo;
import com.timesavings.repo.UserRepo;
import com.timesavings.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 기능 C-04 담당자 계정 신청(기관 서비스 신청 C-03 과 별도의 길).
 * 이미 승인된 기관을 골라 그 소속 담당자로 신청하고, 휴대폰 본인인증이 되면 자동 승인되어 담당자 계정이 바로 생긴다.
 * 자동 승인이 위험하므로 재직 증빙 파일을 받아 두고, 운영자와 그 기관의 기존 담당자에게 알림을 보낸다.
 * 운영자는 내역을 보고(GET /api/admin/manager-applications, 기능 A-11) 잘못된 계정을 취소할 수 있다.
 * 로그인 전에 쓰는 주소라 /api/auth/** 아래에 둔다(공개 경로).
 */
@RestController
@RequestMapping("/api")
@Transactional
public class ManagerApplyController {
    private final ManagerApplicationRepo apps;
    private final OrganizationRepo orgs;
    private final UserRepo users;
    private final AuthService auth;
    private final FileService files;
    private final FileAccessService fileAccess;
    private final NotificationService noti;
    private final RateLimiter limiter;
    private final AuthController authController;

    public ManagerApplyController(ManagerApplicationRepo apps, OrganizationRepo orgs, UserRepo users, AuthService auth, FileService files,
                                  FileAccessService fileAccess, NotificationService noti, RateLimiter limiter, AuthController authController) {
        this.apps = apps; this.orgs = orgs; this.users = users; this.auth = auth; this.files = files;
        this.fileAccess = fileAccess; this.noti = noti; this.limiter = limiter; this.authController = authController;
    }

    public record RevokeBody(String reason) {}

    /** 신청 화면에서 고를 기관: 운영자가 승인한 기관만. */
    @GetMapping("/auth/manager-apply/orgs")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvedOrgs() {
        return orgs.findAll().stream().filter(o -> o.status == OrgStatus.APPROVED)
                .sorted(Comparator.comparing((Organization o) -> o.name))
                .map(o -> Views.map("id", o.id, "name", o.name, "orgType", o.orgType, "address", o.address)).toList();
    }

    /**
     * 담당자 계정 신청(multipart): orgId, name, phone, verifyToken(휴대폰 본인인증), loginId, password, proof(재직 증빙 파일).
     * 본인인증이 확인되면 바로 담당자 계정을 만들고 {status:"APPROVED", token, user} 를 준다.
     */
    @PostMapping(value = "/auth/manager-apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> apply(@RequestParam(required = false) Long orgId, @RequestParam(required = false) String name,
                                     @RequestParam(required = false) String phone, @RequestParam(required = false) String verifyToken,
                                     @RequestParam(required = false) String loginId, @RequestParam(required = false) String password,
                                     @RequestParam(value = "proof", required = false) MultipartFile proof, HttpServletRequest req) {
        Organization o = orgId == null ? null : orgs.findById(orgId).orElse(null);
        if (o == null || o.status != OrgStatus.APPROVED) throw ApiException.bad("ORG_NOT_APPROVED", "승인된 기관을 골라 주세요. 기관이 목록에 없으면 기관 서비스 신청을 먼저 해 주세요");
        if (name == null || name.isBlank()) throw ApiException.bad("NAME_REQUIRED", "이름을 적어 주세요");
        if (loginId == null || !loginId.matches("[A-Za-z0-9_]{3,20}")) throw ApiException.bad("INVALID_LOGIN_ID", "아이디는 영문·숫자 3~20자로 정해 주세요");
        AuthController.checkPassword(password);
        auth.requireVerified(verifyToken, phone); // 본인 확인(휴대폰 본인인증)
        if (proof == null || proof.isEmpty()) throw ApiException.bad("PROOF_REQUIRED", "재직 증빙(재직증명서·사원증) 파일을 올려 주세요");
        if (users.findByLoginId(loginId).isPresent()) throw ApiException.conflict("DUPLICATE_LOGIN_ID", "이미 쓰는 아이디예요");
        String ph = AuthService.normPhone(phone);
        List<User> managers = users.findByOrgId(o.id).stream().filter(m -> m.role == Role.ORG && m.status == UserStatus.ACTIVE).toList();
        if (managers.stream().anyMatch(m -> ph.equals(m.phone)))
            throw ApiException.conflict("ALREADY_MANAGER", "이 번호로 이미 이 기관의 담당자 계정이 있어요. 그 계정으로 로그인해 주세요");
        limiter.check("manager-apply", RateLimiter.clientIp(req)); // 같은 IP 10분에 5번까지(파일을 저장하기 직전에 센다)
        String fid = files.save(proof);
        fileAccess.record(fid, null); // 로그인 없이 올린 파일: 운영자만 연다

        User m = new User();
        m.loginId = loginId; m.passwordHash = auth.hash(password); m.name = name.trim(); m.phone = ph;
        m.role = Role.ORG; m.orgId = o.id; m.area = null;
        m.termsAgreedAt = LocalDateTime.now(); m.privacyAgreedAt = m.termsAgreedAt;
        users.save(m);

        ManagerApplication a = new ManagerApplication();
        a.orgId = o.id; a.userId = m.id; a.name = m.name; a.phone = ph; a.loginId = m.loginId;
        a.proofFileUrl = "/api/files/" + fid; a.existingManagers = managers.size();
        apps.save(a);

        String body = o.name + " · " + m.name + " (" + m.loginId + ")";
        users.findByRole(Role.ADMIN).forEach(ad -> noti.send(ad.id, "MANAGER_APPLY", "담당자 계정이 자동 승인됐어요",
                body + (managers.isEmpty() ? "" : " · 이 기관에 이미 담당자 " + managers.size() + "명") + " · 재직 증빙을 확인해 주세요", "/admin/manager-applications"));
        managers.forEach(x -> noti.send(x.id, "MANAGER_APPLY", "우리 기관에 새 담당자 계정이 생겼어요",
                m.name + " 님이 " + o.name + " 담당자로 등록됐어요. 모르는 분이면 운영자에게 1:1 문의로 알려 주세요", "/qna"));
        return Views.map("id", a.id, "status", a.status, "orgId", o.id, "orgName", o.name, "loginId", m.loginId,
                "token", auth.issueToken(m, true), "user", authController.me(m),
                "message", "본인 확인이 끝나 담당자 계정이 바로 만들어졌어요");
    }

    // ---------------- 운영자: 승인 내역 (기능 A-11 의 서버 쪽) ----------------
    private Map<String, Object> view(ManagerApplication a) {
        Organization o = orgs.findById(a.orgId).orElse(null);
        User u = a.userId == null ? null : users.findById(a.userId).orElse(null);
        long now = users.findByOrgId(a.orgId).stream().filter(m -> m.role == Role.ORG && m.status == UserStatus.ACTIVE && !m.id.equals(a.userId)).count();
        return Views.map("id", a.id, "orgId", a.orgId, "orgName", o == null ? null : o.name, "orgType", o == null ? null : o.orgType,
                "userId", a.userId, "name", a.name, "loginId", a.loginId, "phone", a.phone, "proofFileUrl", a.proofFileUrl,
                "status", a.status, "statusLabel", ManagerApplication.REVOKED.equals(a.status) ? "취소됨" : "자동 승인",
                "userStatus", u == null ? null : u.status, "existingManagers", a.existingManagers, "otherManagersNow", now,
                "revokeReason", a.revokeReason, "revokedAt", a.revokedAt == null ? null : a.revokedAt.toString(),
                "createdAt", a.createdAt == null ? null : a.createdAt.toString());
    }

    /** /api/admin/** 는 운영자만(AuthInterceptor). 최근 것부터. */
    @GetMapping("/admin/manager-applications")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminList() { return apps.findAllByOrderByIdDesc().stream().map(this::view).toList(); }

    /** 자동 승인된 계정이 잘못됐을 때: 신청을 취소로 바꾸고 그 담당자 계정을 정지시키며 로그인을 끊는다. */
    @PostMapping("/admin/manager-applications/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id, @RequestBody(required = false) RevokeBody b) {
        ManagerApplication a = apps.findById(id).orElseThrow(() -> ApiException.notFound("담당자 계정 신청을 찾을 수 없어요"));
        if (ManagerApplication.REVOKED.equals(a.status)) throw ApiException.conflict("INVALID_STATE", "이미 취소한 신청이에요");
        a.status = ManagerApplication.REVOKED;
        a.revokedAt = LocalDateTime.now();
        String reason = b == null || b.reason() == null ? "" : b.reason().trim();
        a.revokeReason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        User u = a.userId == null ? null : users.findById(a.userId).orElse(null);
        if (u != null && u.status == UserStatus.ACTIVE) { u.status = UserStatus.SUSPENDED; auth.revokeAll(u.id); }
        return view(a);
    }
}
