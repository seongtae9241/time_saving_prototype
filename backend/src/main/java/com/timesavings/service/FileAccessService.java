package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.Role;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 올린 파일을 누가 열 수 있는지(2026-11-12). 주소(무작위 이름)를 아는 것만으로는 열 수 없다.
 * <ul>
 *   <li>신분증·범죄경력 회보서: 낸 사람 본인과 운영자</li>
 *   <li>기관 신청의 재직 증빙: 운영자</li>
 *   <li>활동 일지 사진: 그 요청의 봉사자·받는 사람·보낸 사람, 독거노인 건의 담당자, 운영자</li>
 *   <li>프로필 사진: 로그인한 사용자 누구나</li>
 *   <li>아직 어디에도 붙지 않은 파일(방금 올려 미리 보는 중): 올린 사람과 운영자</li>
 * </ul>
 * 민감한 서류로 쓰인 파일은 다른 곳(프로필 사진 등)에 같은 주소를 적어도 열리지 않게, 위에서부터 처음 맞는 규칙 하나만 본다.
 */
@Service
@Transactional(readOnly = true)
public class FileAccessService {
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final RequestRepo requests;
    private final UploadFileRepo uploads;
    private final Views views;

    public FileAccessService(UserRepo users, OrganizationRepo orgs, RequestRepo requests, UploadFileRepo uploads, Views views) {
        this.users = users; this.orgs = orgs; this.requests = requests; this.uploads = uploads; this.views = views;
    }

    @Transactional
    public void record(String fileId, User uploader) {
        UploadFile f = new UploadFile();
        f.fileId = fileId;
        f.uploaderId = uploader == null ? null : uploader.id;
        uploads.save(f);
    }

    /** 내가 올린 파일인가(프로필 사진·인증 서류·일지 사진으로 붙일 때 남의 파일 주소를 못 쓰게). */
    public boolean uploadedBy(String url, User me) {
        if (url == null || !url.startsWith("/api/files/")) return false;
        return uploads.findById(url.substring("/api/files/".length())).map(f -> me.id.equals(f.uploaderId)).orElse(false);
    }

    public void requireReadable(String fileId, User me) {
        if (me == null) throw ApiException.unauthorized("로그인이 필요해요");
        if (!canRead(fileId, me)) throw ApiException.forbidden("이 파일을 볼 수 있는 권한이 없어요");
    }

    public boolean canRead(String fileId, User me) {
        if (me.role == Role.ADMIN) return true;
        String url = "/api/files/" + fileId;
        // 1) 신분증·범죄경력 회보서: 본인만
        for (User u : users.findAll())
            if (url.equals(u.idFileUrl) || url.equals(u.crimeFileUrl)) return u.id.equals(me.id);
        // 2) 재직 증빙: 운영자만(위에서 이미 통과)
        for (Organization o : orgs.findAll())
            if (url.equals(o.proofFileUrl)) return false;
        // 3) 활동 일지 사진: 그 요청의 당사자·담당자
        boolean logPhoto = false;
        for (Request r : requests.findAll()) {
            if (!url.equals(r.logPhotoUrl)) continue;
            logPhoto = true;
            if (me.id.equals(r.volunteerId) || me.id.equals(r.recipientId) || (r.elderId == null && me.id.equals(r.senderId))
                    || (r.elderId != null && views.isManagerOf(me, r.elderId))) return true;
        }
        if (logPhoto) return false;
        // 4) 프로필 사진: 로그인한 사용자
        for (User u : users.findAll())
            if (url.equals(u.photoUrl)) return true;
        // 5) 아직 붙지 않은 파일: 올린 사람
        return uploads.findById(fileId).map(f -> me.id.equals(f.uploaderId)).orElse(false);
    }
}
