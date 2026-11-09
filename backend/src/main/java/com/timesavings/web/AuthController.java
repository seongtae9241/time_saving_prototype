package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;

/** 인증·회원(A-01~A-04, A-09, B-01, B-43, C-01), 파일 업로드. */
@RestController
@RequestMapping("/api")
@Transactional
public class AuthController {
    private final AuthService auth;
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final RequestRepo requests;
    private final BankService bank;
    private final Views views;
    private final FileService files;
    private final NotificationService noti;
    private final FirebasePhoneVerifier firebase;

    public AuthController(AuthService auth, UserRepo users, OrganizationRepo orgs, RequestRepo requests, BankService bank, Views views,
                          FileService files, NotificationService noti, FirebasePhoneVerifier firebase) {
        this.auth = auth; this.users = users; this.orgs = orgs; this.requests = requests; this.bank = bank; this.views = views;
        this.files = files; this.noti = noti; this.firebase = firebase;
    }

    public record PhoneBody(String phone, String code, String firebaseIdToken) {}
    public record SignupBody(String loginId, String password, String name, String phone, String verifyToken, String area,
                             String ageGroup, List<String> categories, List<String> timeSlots, String intro) {}
    public record LoginBody(String loginId, String password, Boolean keep) {}
    public record ResetBody(String loginId, String phone, String verifyToken, String newPassword) {}
    public record PwBody(String currentPassword, String newPassword) {}
    public record MeBody(String name, String phone, String area, String ageGroup, String intro, String experience, String personality,
                         List<String> categories, List<String> timeSlots, String photoUrl, String textSize, Boolean voiceAlert) {}
    public record FileBody(String fileUrl) {}
    public record WithdrawBody(String password) {}
    public record OrgApplyBody(String name, String orgType, String address, String phone, String managerName, String managerPhone,
                               String managerEmail, String memo) {}

    @PostMapping("/auth/phone/send")
    public Map<String, Object> send(@RequestBody PhoneBody b) {
        return Views.map("demoCode", auth.sendCode(b.phone()), "message", "테스트용 인증번호예요 (실제 문자는 보내지 않아요)");
    }

    /** 화면이 고를 인증 방식: firebase(실제 문자, 백엔드 키 있음), demo(123456 허용). */
    @GetMapping("/auth/phone/config")
    public Map<String, Object> phoneConfig() {
        return Views.map("firebase", firebase.enabled(), "demo", auth.demoCodeEnabled());
    }

    /** {phone, firebaseIdToken} → Firebase 로 확인 / {phone, code:'123456'} → 데모 코드(켜져 있을 때). */
    @PostMapping("/auth/phone/verify")
    public Map<String, Object> verify(@RequestBody PhoneBody b) {
        if (b.firebaseIdToken() != null && !b.firebaseIdToken().isBlank()) {
            String fbPhone = firebase.verifiedPhone(b.firebaseIdToken());
            return Views.map("verified", true, "verifyToken", auth.verifyFirebase(b.phone(), fbPhone), "method", "firebase");
        }
        return Views.map("verified", true, "verifyToken", auth.verify(b.phone(), b.code()), "method", "demo");
    }

    static void checkPassword(String pw) {
        if (pw == null || pw.length() < 4) throw ApiException.bad("WEAK_PASSWORD", "비밀번호는 4자 이상으로 정해 주세요");
    }

    @PostMapping("/auth/signup")
    public Map<String, Object> signup(@RequestBody SignupBody b) {
        if (b.loginId() == null || !b.loginId().matches("[A-Za-z0-9_]{3,20}")) throw ApiException.bad("INVALID_LOGIN_ID", "아이디는 영문·숫자 3~20자로 정해 주세요");
        checkPassword(b.password());
        if (b.name() == null || b.name().isBlank()) throw ApiException.bad("NAME_REQUIRED", "이름을 적어 주세요");
        auth.requireVerified(b.verifyToken(), b.phone());
        if (users.findByLoginId(b.loginId()).isPresent()) throw ApiException.conflict("DUPLICATE_LOGIN_ID", "이미 쓰는 아이디예요");
        User u = new User();
        u.loginId = b.loginId();
        u.passwordHash = auth.hash(b.password());
        u.name = b.name().trim();
        u.phone = AuthService.normPhone(b.phone());
        u.role = Role.USER;
        u.area = b.area();
        u.ageGroup = b.ageGroup();
        u.categories = clean(b.categories());
        u.timeSlots = b.timeSlots() == null ? new ArrayList<>() : new ArrayList<>(b.timeSlots());
        u.intro = b.intro();
        users.save(u);
        return Views.map("token", auth.issueToken(u, true), "user", me(u));
    }

    static List<String> clean(List<String> cats) {
        if (cats == null) return new ArrayList<>();
        return new ArrayList<>(cats.stream().filter(Rules::isCategory).distinct().toList());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginBody b) {
        User u = auth.login(b.loginId(), b.password());
        return Views.map("token", auth.issueToken(u, Boolean.TRUE.equals(b.keep())), "user", me(u));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) auth.logout(h.substring(7).trim());
        return Views.map("ok", true);
    }

    @PostMapping("/auth/password/reset")
    public Map<String, Object> reset(@RequestBody ResetBody b) {
        auth.requireVerified(b.verifyToken(), b.phone());
        User u = users.findByLoginId(b.loginId() == null ? "" : b.loginId()).orElseThrow(() -> ApiException.notFound("가입 정보를 찾을 수 없어요"));
        if (!AuthService.normPhone(b.phone()).equals(u.phone)) throw ApiException.bad("PHONE_MISMATCH", "가입한 휴대폰 번호와 달라요");
        checkPassword(b.newPassword());
        u.passwordHash = auth.hash(b.newPassword());
        auth.revokeAll(u.id);
        return Views.map("ok", true);
    }

    // ---------------- 내 정보 ----------------
    public Map<String, Object> me(User u) {
        BankService.Summary s = bank.summary(u.id);
        double[] r = views.rating(u.id);
        Map<String, Object> m = Views.map("id", u.id, "loginId", u.loginId, "name", u.name, "phone", u.phone, "role", u.role,
                "status", u.status, "area", u.area, "ageGroup", u.ageGroup, "intro", u.intro, "experience", u.experience,
                "personality", u.personality, "categories", u.categories, "timeSlots", u.timeSlots, "photoUrl", u.photoUrl,
                "orgId", u.orgId, "orgName", u.orgId == null ? null : orgs.findById(u.orgId).map(o -> o.name).orElse(null),
                "idStatus", u.idStatus, "crimeStatus", u.crimeStatus, "eduDone", u.eduDone, "voiceAlert", u.voiceAlert,
                "textSize", u.textSize, "penaltyCount", u.penaltyCount, "warningCount", u.warningCount, "createdAt", u.createdAt,
                "balance", s.balance(), "hold", s.hold(), "available", s.available(), "usable", s.usable(),
                "earnedSelf", s.earnedSelf(), "canUseSelf", s.canUseSelf(),
                "regularBadges", views.regularBadges(u.id, u.id), "ratingAvg", r[0], "ratingCount", (int) r[1],
                "verify", Views.map("idStatus", u.idStatus, "crimeStatus", u.crimeStatus, "eduDone", u.eduDone));
        return m;
    }

    @GetMapping("/me")
    public Map<String, Object> getMe(@CurrentUser User u) { return me(users.findById(u.id).orElseThrow()); }

    @PutMapping("/me")
    public Map<String, Object> putMe(@CurrentUser User cu, @RequestBody MeBody b) {
        User u = users.findById(cu.id).orElseThrow();
        if (b.name() != null && !b.name().isBlank()) u.name = b.name().trim();
        if (b.phone() != null) u.phone = AuthService.normPhone(b.phone());
        if (b.area() != null) u.area = b.area();
        if (b.ageGroup() != null) u.ageGroup = b.ageGroup();
        if (b.intro() != null) u.intro = b.intro();
        if (b.experience() != null) u.experience = b.experience();
        if (b.personality() != null) u.personality = b.personality();
        if (b.categories() != null) u.categories = clean(b.categories());
        if (b.timeSlots() != null) u.timeSlots = new ArrayList<>(b.timeSlots());
        if (b.photoUrl() != null) u.photoUrl = b.photoUrl();
        if (b.textSize() != null) u.textSize = b.textSize();
        if (b.voiceAlert() != null) u.voiceAlert = b.voiceAlert();
        return me(u);
    }

    @PutMapping("/me/password")
    public Map<String, Object> pw(@CurrentUser User cu, @RequestBody PwBody b) {
        User u = users.findById(cu.id).orElseThrow();
        if (!auth.matches(b.currentPassword(), u.passwordHash)) throw ApiException.bad("WRONG_PASSWORD", "지금 비밀번호가 맞지 않아요");
        checkPassword(b.newPassword());
        u.passwordHash = auth.hash(b.newPassword());
        return Views.map("ok", true);
    }

    private List<Map<String, Object>> activeOf(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Request r : requests.findAll()) {
            if (r.status.terminal()) continue;
            if (u.id.equals(r.volunteerId) || u.id.equals(r.recipientId) || u.id.equals(r.senderId))
                out.add(Views.map("id", r.id, "status", r.status, "date", r.date.toString(), "category", r.category, "content", r.content));
        }
        return out;
    }

    @GetMapping("/me/withdraw-check")
    public Map<String, Object> withdrawCheck(@CurrentUser User u) {
        BankService.Summary s = bank.summary(u.id);
        List<Map<String, Object>> active = activeOf(u);
        String msg = !active.isEmpty() ? "진행 중인 요청·활동 " + active.size() + "건을 먼저 끝내거나 취소해야 탈퇴할 수 있어요."
                : s.balance() > 0 ? "남은 시간 " + s.balance() + "시간은 탈퇴하면 사라지고 되돌릴 수 없어요. 가족에게 먼저 선물할 수 있어요."
                : "탈퇴하면 활동 기록은 통계용으로만 남고 다시 로그인할 수 없어요.";
        return Views.map("activeRequests", active, "balance", s.balance(), "hold", s.hold(), "canWithdraw", active.isEmpty(), "message", msg);
    }

    @PostMapping("/me/withdraw")
    public Map<String, Object> withdraw(@CurrentUser User cu, @RequestBody WithdrawBody b) {
        User u = users.findById(cu.id).orElseThrow();
        if (!auth.matches(b.password(), u.passwordHash)) throw ApiException.bad("WRONG_PASSWORD", "비밀번호가 맞지 않아요");
        if (!activeOf(u).isEmpty()) throw ApiException.conflict("ACTIVE_REQUESTS", "진행 중인 요청·활동을 먼저 정리해 주세요");
        u.status = UserStatus.WITHDRAWN;
        u.phone = null;
        auth.revokeAll(u.id);
        return Views.map("ok", true);
    }

    @PostMapping("/me/verify/id")
    public Map<String, Object> verifyId(@CurrentUser User cu, @RequestBody FileBody b) {
        if (b.fileUrl() == null || b.fileUrl().isBlank()) throw ApiException.bad("FILE_REQUIRED", "신분증 사진을 올려 주세요");
        User u = users.findById(cu.id).orElseThrow();
        u.idFileUrl = b.fileUrl();
        u.idStatus = VerifyStatus.PENDING;
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "VERIFY", "신원 인증 대기", u.name + " · 신분증", "/admin/verifications"));
        return Views.map("idStatus", u.idStatus);
    }

    @PostMapping("/me/verify/crime")
    public Map<String, Object> verifyCrime(@CurrentUser User cu, @RequestBody FileBody b) {
        if (b.fileUrl() == null || b.fileUrl().isBlank()) throw ApiException.bad("FILE_REQUIRED", "범죄경력 회보서를 올려 주세요");
        User u = users.findById(cu.id).orElseThrow();
        u.crimeFileUrl = b.fileUrl();
        u.crimeStatus = VerifyStatus.PENDING;
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "VERIFY", "범죄경력 회보서 확인 대기", u.name, "/admin/verifications"));
        return Views.map("crimeStatus", u.crimeStatus);
    }

    @PostMapping("/orgs/apply")
    public Map<String, Object> orgApply(@RequestBody OrgApplyBody b) {
        if (b.name() == null || b.name().isBlank() || b.managerName() == null || b.managerName().isBlank())
            throw ApiException.bad("INVALID_INPUT", "기관 이름과 담당자 이름을 적어 주세요");
        Organization o = new Organization();
        o.name = b.name().trim(); o.orgType = b.orgType(); o.address = b.address(); o.phone = b.phone();
        o.managerName = b.managerName(); o.managerPhone = b.managerPhone(); o.managerEmail = b.managerEmail(); o.memo = b.memo();
        orgs.save(o);
        users.findByRole(Role.ADMIN).forEach(a -> noti.send(a.id, "ORG_APPLY", "기관 서비스 신청이 들어왔어요", o.name, "/admin/orgs"));
        return Views.map("id", o.id, "status", o.status);
    }

    @GetMapping("/users/{id}/profile")
    public Map<String, Object> profile(@CurrentUser User viewer, @PathVariable Long id) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        double[] r = views.rating(u.id);
        long helped = requests.findAll().stream().filter(x -> u.id.equals(x.volunteerId) && x.status == ReqStatus.DONE).count();
        return Views.map("id", u.id, "name", u.name, "ageGroup", u.ageGroup, "area", u.area, "intro", u.intro, "experience", u.experience,
                "personality", u.personality, "categories", u.categories, "timeSlots", u.timeSlots, "photoUrl", u.photoUrl,
                "ratingAvg", r[0], "ratingCount", (int) r[1], "tags", views.topTags(u.id, 5),
                "crime", u.crimeStatus == VerifyStatus.APPROVED, "idVerified", u.idStatus == VerifyStatus.APPROVED, "eduDone", u.eduDone,
                "regularBadges", views.regularBadges(u.id, viewer.id), "helpCount", helped);
    }

    // ---------------- 파일 ----------------
    @PostMapping("/files")
    public Map<String, Object> upload(@CurrentUser User u, @RequestParam("file") MultipartFile file) {
        return Views.map("url", "/api/files/" + files.save(file));
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        Path p = files.path(id);
        MediaType t = MediaTypeFactory.getMediaType(p.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(t).body(new FileSystemResource(p));
    }
}
