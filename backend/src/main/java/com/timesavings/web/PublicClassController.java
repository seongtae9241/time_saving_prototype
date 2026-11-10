package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Elder;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.ai.NearbyServiceAi;
import com.timesavings.repo.ElderRepo;
import com.timesavings.service.PublicClassService;
import com.timesavings.service.Views;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 기관 클래스 추천(2026-11-10): 공공기관 강좌를 모아(AI 웹 검색·공공데이터 CSV·기관 소식) 사용자·담당자에게 추천한다.
 * 신청은 기관 사이트(외부 링크). 계약은 docs/design/contract.md 7.15.
 */
@RestController
@RequestMapping("/api")
public class PublicClassController {
    private final PublicClassService svc;
    private final ElderRepo elders;

    public PublicClassController(PublicClassService svc, ElderRepo elders) { this.svc = svc; this.elders = elders; }

    public record SearchBody(String region, Double lat, Double lng, Boolean force) {}
    public record HideBody(Boolean hidden) {}

    @GetMapping("/public-classes/status")
    public Map<String, Object> status(@CurrentUser User u) { return svc.status(); }

    /** 사용자 추천(하루 캐시). USER 전용 — 담당자는 대상별 추천을 쓴다. */
    @GetMapping("/public-classes/recommend")
    public Map<String, Object> recommend(@CurrentUser User u) {
        if (u.role != Role.USER) throw ApiException.forbidden("사용자 계정에서 볼 수 있어요");
        return svc.recommend(u);
    }

    @GetMapping("/public-classes/{id}")
    public Map<String, Object> detail(@CurrentUser User u, @PathVariable Long id) { return svc.detail(id, u.role == Role.ADMIN); }

    /** "내 위치로 다시 찾기": 프로필 동네(+동의한 경우 좌표, 약 1km로 줄임)로 AI 웹 검색. 지역별 하루 1회. */
    @PostMapping("/public-classes/search")
    public Map<String, Object> search(@CurrentUser User u, @RequestBody(required = false) SearchBody b) {
        if (u.role == Role.ADMIN) throw ApiException.forbidden("운영자는 관리 화면에서 다시 찾기를 써 주세요");
        Double lat = b == null ? null : b.lat(), lng = b == null ? null : b.lng();
        if ((lat != null || lng != null) && !NearbyServiceAi.validCoord(lat, lng)) throw ApiException.bad("INVALID_LOCATION", "위치 값이 맞지 않아요");
        String area = u.area;
        if (u.role == Role.ORG && b != null && b.region() != null && !b.region().isBlank()) area = b.region();
        return svc.search(area, lat, lng, false);
    }

    /** 담당자: 이 대상께 맞는 기관 클래스(대상의 동·나이대·필요 도움). */
    @GetMapping("/org/elders/{id}/public-classes")
    public Map<String, Object> forElder(@CurrentUser User u, @PathVariable Long id) {
        Elder e = elders.findById(id).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        if (!u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 볼 수 있어요");
        return svc.recommendForElder(e);
    }

    // ---------------- 운영자 ----------------
    @GetMapping("/admin/public-classes")
    public Map<String, Object> adminList(@RequestParam(required = false) String source) {
        Map<String, Object> st = svc.status();
        return Views.map("items", svc.adminList(source), "searchAvailable", st.get("searchAvailable"), "counts", st.get("counts"));
    }

    @PostMapping("/admin/public-classes/import")
    public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw ApiException.bad("FILE_REQUIRED", "CSV 파일을 골라 주세요");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.isEmpty() && !name.endsWith(".csv") && !name.endsWith(".txt")) throw ApiException.bad("CSV_ONLY", "CSV 파일만 올릴 수 있어요");
        return svc.importCsv(file.getBytes());
    }

    @PostMapping("/admin/public-classes/search")
    public Map<String, Object> adminSearch(@RequestBody SearchBody b) {
        if (b == null || b.region() == null || b.region().isBlank()) throw ApiException.bad("REGION_REQUIRED", "찾을 지역(시군구·동)을 적어 주세요");
        return svc.search(b.region().trim(), null, null, b.force() == null || b.force());
    }

    @PostMapping("/admin/public-classes/{id}/hide")
    public Map<String, Object> hide(@PathVariable Long id, @RequestBody(required = false) HideBody b) {
        return svc.setHidden(id, b == null || b.hidden() == null || b.hidden());
    }

    @DeleteMapping("/admin/public-classes/{id}")
    public Map<String, Object> delete(@PathVariable Long id) { svc.delete(id); return Views.map("ok", true); }

}
