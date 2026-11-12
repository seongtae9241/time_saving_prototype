package com.timesavings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.PublicClassAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 기관 클래스 추천(2026-11-10). 강좌 모으기(세 경로 → public_classes) · 추천(코드 점수 → LLM 이유 한 줄) · 운영자 관리.
 * <ul>
 *   <li>AI_SEARCH: Gemini 그라운딩이 준 후보 중 applyUrl 있는 것만, 서버가 LinkPreviewService(SSRF 규칙 그대로)로 열어 제목을 얻으면 verified, 아니면 숨김. 지역별 하루 1회.</li>
 *   <li>PUBLIC_DATA: 운영자가 올린 평생학습강좌 표준데이터 CSV(끝난 강좌 제외, verified).</li>
 *   <li>ORG: 동네 소식 기관 행사(org_events)를 옮겨 둔다(org_event_id, 마감되면 숨김).</li>
 * </ul>
 * 강좌 자체는 시간 통장 시간을 쓰지 않는다. 신청은 기관 사이트에서.
 */
@Service
public class PublicClassService {
    private static final Logger log = LoggerFactory.getLogger(PublicClassService.class);
    public static final String AI_NOTICE = "AI가 찾은 정보 · 신청 전 기관에 확인";
    public static final List<String> CATEGORIES = List.of("디지털", "정서·건강", "생활", "기타");
    public static final int TOP_N = 12;
    static final int MAX_CSV_ROWS = 5000;

    private final ObjectMapper om = new ObjectMapper();
    private final PublicClassRepo repo;
    private final OrgEventRepo events;
    private final OrganizationRepo orgs;
    private final RequestRepo requests;
    private final LinkPreviewService preview;
    private final PublicClassAi ai;

    /** 데이터가 바뀔 때마다 올려 추천 캐시를 무효로 만든다. */
    private final AtomicLong version = new AtomicLong();
    /** 검색 키 → 마지막 검색 날짜(결과 0건이어도 하루 1회) */
    private final Map<String, LocalDate> searched = new ConcurrentHashMap<>();
    /** 추천 캐시: "u:<id>" / "e:<id>" → 날짜·지문·결과 */
    private final Map<String, Cached> recCache = new ConcurrentHashMap<>();
    private record Cached(LocalDate date, String finger, Map<String, Object> result) {}

    public PublicClassService(PublicClassRepo repo, OrgEventRepo events, OrganizationRepo orgs, RequestRepo requests,
                              LinkPreviewService preview, PublicClassAi ai) {
        this.repo = repo; this.events = events; this.orgs = orgs; this.requests = requests; this.preview = preview; this.ai = ai;
    }

    // ===================================================== 분류·표시 =====================================================
    private static final String[][] CAT_WORDS = {
            {"디지털", "스마트폰|휴대폰|핸드폰|폰 |디지털|키오스크|컴퓨터|인터넷|카카오|유튜브|앱 |어플|태블릿|SNS|사진 정리|영상통화|AI|챗GPT|정보화|모바일|무인"},
            {"정서·건강", "건강|치매|기억|운동|요가|체조|노래|웃음|마음|심리|명상|걷기|댄스|춤|원예|미술|그림|힐링|우울|스트레칭|국선도|탁구|합창|악기|하모니카|우쿨렐레|낭독|시 쓰기|서예|웰다잉|상담"},
            {"생활", "요리|반찬|생활|금융|법률|안전|정리|살림|공예|재테크|사기|보이스피싱|세금|연금|교통|운전|목공|수선|바느질|텃밭|일자리|자격증|생활영어|한글"}};

    public static String classify(String text) {
        String t = " " + (text == null ? "" : text) + " ";
        for (String[] c : CAT_WORDS) if (java.util.regex.Pattern.compile(c[1], java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t).find()) return c[0];
        return "기타";
    }

    /** 봉사 카테고리 → 강좌 카테고리 */
    static String fromHelpCategory(String c) {
        if (c == null) return null;
        if (c.contains("디지털")) return "디지털";
        if (c.contains("말동무") || c.contains("정서") || c.contains("동행")) return "정서·건강";
        if (c.contains("생활") || c.contains("장보기") || c.contains("집안일")) return "생활";
        return null;
    }

    private static final String[] WD = {"월", "화", "수", "목", "금", "토", "일"};

    /** "오늘" · "내일" · "이번 주 목요일" · "다음 주 화요일" · "11월 20일" · "진행 중" */
    public static String whenLabel(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null) return end != null && !end.isBefore(today) ? "~" + end.getMonthValue() + "월 " + end.getDayOfMonth() + "일" : "날짜는 기관에 확인";
        if (start.isBefore(today)) return end != null && !end.isBefore(today) ? "진행 중 (~" + end.getMonthValue() + "/" + end.getDayOfMonth() + ")" : "지난 강좌";
        long d = ChronoUnit.DAYS.between(today, start);
        String w = WD[start.getDayOfWeek().getValue() - 1] + "요일";
        if (d == 0) return "오늘";
        if (d == 1) return "내일 (" + w + ")";
        int wk = start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), tw = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        boolean sameYear = start.get(IsoFields.WEEK_BASED_YEAR) == today.get(IsoFields.WEEK_BASED_YEAR);
        if (sameYear && wk == tw) return "이번 주 " + w;
        if (d <= 13 && (sameYear ? wk == tw + 1 : start.with(DayOfWeek.MONDAY).minusWeeks(1).equals(today.with(DayOfWeek.MONDAY)))) return "다음 주 " + w;
        return start.getMonthValue() + "월 " + start.getDayOfMonth() + "일 (" + WD[start.getDayOfWeek().getValue() - 1] + ")";
    }

    /** 지금 사용자에게 보여도 되는지: 숨김 아님 · 확인됨 · 끝나지 않음 · 유효 기간 안 */
    public static boolean visible(PublicClass c, LocalDate today) {
        if (c.hidden || !c.verified) return false;
        if (c.endDate != null && c.endDate.isBefore(today)) return false;
        if (c.endDate == null && c.startDate != null && c.startDate.isBefore(today.minusDays(30))) return false;
        return c.expiresAt == null || c.expiresAt.isAfter(LocalDateTime.now());
    }

    public Map<String, Object> view(PublicClass c, LocalDate today) {
        return Views.map("id", c.id, "source", c.source, "title", c.title, "orgName", c.orgName, "region", c.region, "place", c.place,
                "startDate", c.startDate == null ? null : c.startDate.toString(), "endDate", c.endDate == null ? null : c.endDate.toString(),
                "when", whenLabel(c.startDate, c.endDate, today), "schedule", c.scheduleText, "target", c.target, "fee", c.feeText,
                "capacity", c.capacityText, "applyUrl", c.applyUrl, "applyHow", c.applyHow, "imageUrl", c.imageUrl, "category", c.category,
                "verified", c.verified, "aiFound", "AI_SEARCH".equals(c.source), "notice", "AI_SEARCH".equals(c.source) ? AI_NOTICE : null,
                "orgEventId", c.orgEventId, "sample", c.orgName != null && c.orgName.contains("(예시)"));
    }

    /**
     * B-64 동네 기관 소식 목록에 함께 넣을 항목: AI가 찾은 소식(AI_SEARCH)과 공공데이터 강좌(PUBLIC_DATA) 가운데
     * 지금 보여도 되고(visible — AI 항목은 서버가 출처 링크를 열어 확인한 것만) 기관 페이지 링크가 있는 것.
     * 담당자가 올린 소식(ORG)은 EventService 목록이 주므로 여기서는 뺀다. area 를 주면 지역·장소·기관 이름에 그 동이 들어간 것만.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> newsItems(String area, String viewerArea) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicClass c : repo.findAll()) {
            if ("ORG".equals(c.source) || !visible(c, today) || c.applyUrl == null || c.applyUrl.isBlank()) continue;
            String text = String.join(" ", Objects.toString(c.region, ""), Objects.toString(c.place, ""), Objects.toString(c.orgName, ""));
            if (area != null && !text.contains(area)) continue;
            boolean ai = "AI_SEARCH".equals(c.source);
            out.add(Views.map("key", "PC-" + c.id, "kind", ai ? "AI" : "PUBLIC", "id", c.id, "title", c.title, "orgName", c.orgName,
                    "date", c.startDate == null ? null : c.startDate.toString(), "startHour", null,
                    "when", whenLabel(c.startDate, c.endDate, today) + (c.scheduleText == null ? "" : " · " + c.scheduleText),
                    "place", c.place, "area", c.region, "near", viewerArea != null && !viewerArea.isBlank() && text.contains(viewerArea),
                    "url", c.applyUrl, "image", c.imageUrl, "aiFound", ai, "notice", ai ? AI_NOTICE : null));
        }
        return out;
    }

    // ===================================================== ③ 기관 행사 옮겨 두기 =====================================================
    /** 동네 소식 기관 행사를 public_classes(source ORG)에 맞춘다. 바뀐 게 있으면 version 을 올린다. */
    public synchronized void syncOrgEvents() {
        boolean changed = false;
        Map<Long, String> orgName = new HashMap<>();
        Map<Long, String> orgAddr = new HashMap<>();
        for (Organization o : orgs.findAll()) { orgName.put(o.id, o.name); orgAddr.put(o.id, o.address); }
        Set<Long> seen = new HashSet<>();
        for (OrgEvent e : events.findAll()) {
            seen.add(e.id);
            PublicClass c = repo.findFirstByOrgEventId(e.id).orElse(null);
            boolean fresh = c == null;
            if (fresh) { c = new PublicClass(); c.source = "ORG"; c.orgEventId = e.id; }
            String name = e.orgId == null ? null : orgName.get(e.orgId);
            String region = e.orgId == null ? null : PublicClassCsv.region(orgAddr.get(e.orgId));
            if (region == null && name != null) region = PublicClassCsv.region("x " + name.split("\\s+")[0]);
            boolean hide = !"OPEN".equals(e.status);
            String sched = e.startHour == null ? null : hourLabel(e.startHour) + " 시작";
            String cap = null; // B-64: 앱 안 신청·정원은 없앴다(신청은 기관 페이지 링크로만)
            String how = "기관 안내 링크에서 신청";
            String cat = classify(e.title + " " + (e.content == null ? "" : e.content));
            if (fresh || !Objects.equals(c.title, e.title) || !Objects.equals(c.startDate, e.eventDate) || c.hidden != hide
                    || !Objects.equals(c.place, e.place) || !Objects.equals(c.applyUrl, e.url) || !Objects.equals(c.orgName, name)) {
                c.title = clip(e.title, 200); c.orgName = clip(name, 100); c.region = clip(region, 60); c.place = clip(e.place, 200);
                c.startDate = e.eventDate; c.endDate = e.eventDate; c.scheduleText = sched; c.capacityText = cap; c.applyUrl = e.url;
                c.applyHow = how; c.imageUrl = e.ogImage; c.category = cat; c.description = clip(e.content, 1000);
                c.verified = true; c.verifiedAt = LocalDateTime.now(); c.hidden = hide; c.fetchedAt = LocalDateTime.now();
                repo.save(c);
                changed = true;
            }
        }
        for (PublicClass c : repo.findBySource("ORG"))
            if (c.orgEventId != null && !seen.contains(c.orgEventId) && !c.hidden) { c.hidden = true; repo.save(c); changed = true; }
        if (changed) version.incrementAndGet();
    }

    static String hourLabel(double h) {
        int H = (int) Math.floor(h);
        int m = (int) Math.round((h - H) * 60);
        String ap = H < 12 ? "오전" : "오후";
        int hh = H % 12 == 0 ? 12 : H % 12;
        return ap + " " + hh + "시" + (m > 0 ? " " + m + "분" : "");
    }

    // ===================================================== ① AI 웹 검색 =====================================================
    public boolean searchAvailable() { return ai.searchAvailable(); }

    static String searchKey(String region, Double lat, Double lng) {
        String r = region == null ? "" : region.trim().replaceAll("\\s+", " ");
        if (lat != null && lng != null) r = "geo:" + Math.round(lat * 100) / 100.0 + "," + Math.round(lng * 100) / 100.0 + (r.isEmpty() ? "" : " " + r);
        return clip(r, 80);
    }

    /** 검색 결과 저장(검증 포함). search 에서 분리해 테스트에서 후보를 바로 넣을 수 있게 했다. */
    public Map<String, Object> saveSearchResult(String key, String region, PublicClassAi.SearchResult res) {
        LocalDate today = LocalDate.now();
        int dropped = 0, verified = 0, hidden = 0;
        List<PublicClass> fresh = new ArrayList<>();
        Set<String> seenUrl = new HashSet<>();
        for (PublicClassAi.Candidate cand : res.items()) {
            if (cand.title() == null || cand.title().isBlank() || cand.applyUrl() == null || cand.applyUrl().isBlank()) { dropped++; continue; }
            PublicClass c = new PublicClass();
            c.source = "AI_SEARCH"; c.searchKey = key; c.region = clip(region, 60);
            c.title = clip(cand.title(), 200); c.orgName = clip(blank(cand.orgName()), 100); c.place = clip(blank(cand.place()), 200);
            c.startDate = PublicClassCsv.date(cand.startDate()); c.endDate = PublicClassCsv.date(cand.endDate());
            if (c.endDate == null && c.startDate != null && cand.startDate().matches(".*\\d.*[~∼-].*\\d{4}.*")) c.endDate = PublicClassCsv.date(cand.startDate().replaceFirst("^.*?[~∼]", ""));
            c.scheduleText = clip(blank(cand.schedule()), 200); c.target = clip(blank(cand.target()), 200); c.feeText = clip(PublicClassCsv.fee(blank(cand.fee())), 60);
            c.description = clip(blank(cand.summary()), 1000);
            c.category = classify(c.title + " " + (c.description == null ? "" : c.description) + " " + (c.target == null ? "" : c.target));
            c.fetchedAt = LocalDateTime.now();
            c.expiresAt = c.endDate != null ? c.endDate.plusDays(1).atStartOfDay() : c.fetchedAt.plusDays(14);
            try { c.rawJson = om.writeValueAsString(Map.of("item", cand.raw() == null ? "" : cand.raw(), "sources", res.sources())); } catch (Exception ignored) { /* 원본 없이 */ }
            // 서버가 주소를 실제로 열어 본다(SSRF 규칙: LinkPreviewService). 2xx(200)이고 제목이 있으면 확인됨.
            try {
                LinkPreviewService.Preview p = preview.fetch(cand.applyUrl().trim());
                if (p.title() != null && !p.title().isBlank()) {
                    c.verified = true; c.verifiedAt = LocalDateTime.now(); c.pageTitle = clip(p.title(), 200);
                    c.applyUrl = clip(p.url(), 500); c.imageUrl = p.image();
                } else { c.hidden = true; c.applyUrl = clip(cand.applyUrl().trim(), 500); }
            } catch (Exception e) {
                c.hidden = true; c.applyUrl = clip(cand.applyUrl().trim(), 500);
            }
            if (c.endDate != null && c.endDate.isBefore(today)) c.hidden = true;
            if (c.applyUrl != null && !seenUrl.add(c.applyUrl + "|" + c.title)) { dropped++; continue; }
            if (c.verified && !c.hidden) verified++; else hidden++;
            fresh.add(c);
        }
        repo.deleteAll(repo.findBySourceAndSearchKey("AI_SEARCH", key));
        repo.saveAll(fresh);
        version.incrementAndGet();
        log.info("기관 클래스 검색 저장: 후보 {} · 버림 {} · 확인 {} · 숨김 {}", res.items().size(), dropped, verified, hidden);
        return Views.map("searched", true, "cached", false, "searchAvailable", true, "region", key, "found", res.items().size(),
                "dropped", dropped, "verified", verified, "hidden", hidden);
    }

    /**
     * 지역 주변 공공기관 강좌를 웹 검색으로 찾는다(하루 1회, force=운영자 다시 찾기).
     * applyUrl 없는 항목은 버리고, 주소를 실제로 열어 제목을 얻은 것만 verified. 이 검색 키의 예전 AI 항목은 새 결과로 바꾼다.
     */
    public Map<String, Object> search(String region, Double lat, Double lng, boolean force) {
        String key = searchKey(region, lat, lng);
        if (key == null || key.isBlank()) throw ApiException.bad("REGION_REQUIRED", "찾을 지역(시군구·동)을 정해 주세요");
        return search0(key, region, lat, lng, force);
    }

    private Map<String, Object> search0(String key, String region, Double lat, Double lng, boolean force) {
        if (!ai.searchAvailable())
            return Views.map("searched", false, "searchAvailable", false, "region", key, "message", "AI 웹 검색은 Gemini 키가 있을 때만 쓸 수 있어요");
        LocalDate today = LocalDate.now();
        List<PublicClass> old = repo.findBySourceAndSearchKey("AI_SEARCH", key);
        boolean fresh = today.equals(searched.get(key)) || old.stream().anyMatch(c -> c.fetchedAt != null && c.fetchedAt.toLocalDate().equals(today));
        if (fresh && !force) {
            long vis = old.stream().filter(c -> visible(c, today)).count();
            return Views.map("searched", false, "cached", true, "searchAvailable", true, "region", key, "found", old.size(), "verified", vis,
                    "message", "오늘 이미 찾아봤어요. 내일 다시 찾을 수 있어요");
        }
        searched.put(key, today);
        String coord = lat != null && lng != null ? "위도 " + Math.round(lat * 100) / 100.0 + ", 경도 " + Math.round(lng * 100) / 100.0 + " 부근" : null;
        Optional<PublicClassAi.SearchResult> res = ai.search(region, coord);
        if (res.isEmpty())
            return Views.map("searched", false, "searchAvailable", true, "region", key, "message", "지금은 AI 검색을 할 수 없어요. 잠시 뒤 다시 해 주세요");
        return saveSearchResult(key, region, res.get());
    }

    // ===================================================== ② 공공데이터 CSV =====================================================
    public Map<String, Object> importCsv(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw ApiException.bad("FILE_REQUIRED", "CSV 파일을 골라 주세요");
        PublicClassCsv.Parsed p = PublicClassCsv.parse(bytes, MAX_CSV_ROWS);
        if (!p.mapped().containsKey("title")) throw ApiException.bad("CSV_NO_TITLE", "강좌명 열을 찾지 못했어요. 평생학습강좌 표준데이터 파일인지 확인해 주세요");
        LocalDate today = LocalDate.now();
        Map<String, PublicClass> existing = new HashMap<>();
        for (PublicClass c : repo.findBySource("PUBLIC_DATA")) existing.put(dedupKey(c.title, c.orgName, c.startDate), c);
        int imported = 0, updated = 0, ended = 0, invalid = 0;
        List<PublicClass> save = new ArrayList<>();
        for (int i = 0; i < p.rows().size(); i++) {
            Map<String, String> r = p.rows().get(i);
            String title = blank(r.get("title"));
            if (title == null) { invalid++; continue; }
            LocalDate s = PublicClassCsv.date(r.get("startDate")), e = PublicClassCsv.date(r.get("endDate"));
            if ((e != null && e.isBefore(today)) || (e == null && s != null && s.isBefore(today.minusDays(30)))) { ended++; continue; }
            String org = blank(r.get("orgName"));
            String k = dedupKey(title, org, s);
            PublicClass c = existing.get(k);
            if (c == null) { c = new PublicClass(); c.source = "PUBLIC_DATA"; imported++; existing.put(k, c); } else updated++;
            c.title = clip(title, 200); c.orgName = clip(org, 100);
            String addr = blank(r.get("address"));
            c.region = clip(PublicClassCsv.region(addr != null ? addr : r.get("place")), 60);
            String place = blank(r.get("place"));
            c.place = clip(place != null && addr != null && !place.equals(addr) ? place + " (" + addr + ")" : place != null ? place : addr, 200);
            c.startDate = s; c.endDate = e;
            c.scheduleText = clip(schedule(r), 200);
            c.target = clip(blank(r.get("target")), 200);
            c.feeText = clip(PublicClassCsv.fee(r.get("fee")), 60);
            c.capacityText = clip(PublicClassCsv.capacity(r.get("capacity")), 60);
            String url = blank(r.get("applyUrl"));
            if (url != null && !url.matches("(?i)^https?://.*")) url = url.matches("(?i)^www\\..*") ? "https://" + url : null;
            if (url != null) { try { LinkPreviewService.checkStatic(url); } catch (ApiException x) { url = null; } }
            c.applyUrl = clip(url, 500);
            String how = blank(r.get("applyHow")), phone = blank(r.get("phone"));
            c.applyHow = clip(how == null ? (phone == null ? null : "전화 " + phone) : phone == null ? how : how + " · 전화 " + phone, 200);
            String method = blank(r.get("method")), content = blank(r.get("content")), inst = blank(r.get("instructor"));
            c.description = clip(String.join(" · ", Arrays.asList(content, method == null ? null : "교육 방법: " + method, inst == null ? null : "강사: " + inst)
                    .stream().filter(Objects::nonNull).toList()), 1000);
            if (c.description != null && c.description.isBlank()) c.description = null;
            c.category = classify(title + " " + (content == null ? "" : content) + " " + (c.target == null ? "" : c.target));
            c.lat = num(r.get("lat")); c.lng = num(r.get("lng"));
            c.verified = true; c.verifiedAt = LocalDateTime.now(); c.fetchedAt = LocalDateTime.now();
            c.expiresAt = e != null ? e.plusDays(1).atStartOfDay() : null;
            try { c.rawJson = om.writeValueAsString(p.raws().get(i)); } catch (Exception ignored) { /* 원본 없이 */ }
            save.add(c);
        }
        repo.saveAll(save);
        version.incrementAndGet();
        return Views.map("encoding", p.encoding(), "total", p.rows().size(), "imported", imported, "updated", updated,
                "skippedEnded", ended, "skippedInvalid", invalid, "mapped", p.mapped(), "unmapped", p.unmapped());
    }

    private static String schedule(Map<String, String> r) {
        String days = blank(r.get("days")), st = PublicClassCsv.hm(r.get("startTime")), et = PublicClassCsv.hm(r.get("endTime")), t = blank(r.get("time"));
        String time = st != null ? st + (et != null ? "~" + et : "") : t;
        String out = ((days == null ? "" : days.replace(",", "·") + " ") + (time == null ? "" : time)).trim();
        return out.isEmpty() ? null : out;
    }

    private static String dedupKey(String t, String o, LocalDate s) { return (t == null ? "" : t.trim()) + "|" + (o == null ? "" : o.trim()) + "|" + s; }

    // ===================================================== 추천 =====================================================
    public record Profile(String area, String ageGroup, Set<String> interests) {}
    record Scored(PublicClass c, double score, boolean sameDong, boolean sameSgg) {}

    static String dongOf(String area) {
        if (area == null) return null;
        for (String t : area.trim().split("\\s+")) if (t.matches(".*[가-힣](동|읍|면)$")) return t;
        String[] tk = area.trim().split("\\s+");
        return tk.length == 1 && !tk[0].isBlank() ? tk[0] : null;
    }

    static String sggOf(String area) {
        if (area == null) return null;
        for (String t : area.trim().split("\\s+")) if (t.length() >= 2 && t.matches(".*[가-힣](구|군|시)$") && !t.endsWith("특별시") && !t.endsWith("광역시")) return t;
        return null;
    }

    static boolean older(String age) {
        if (age == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2})").matcher(age);
        return m.find() && Integer.parseInt(m.group(1)) >= 50;
    }

    /** 코드 점수: 같은 동 40 · 같은 시군구 20 · 디지털 25 · 정서·건강 20 · 생활 8 · 관심 분야 15 · 나이대 대상 10(아동·청소년 대상 −40) · 날짜 가까울수록 최대 20 · 확인됨 15 · 공공데이터 5 */
    static Scored score(PublicClass c, Profile p, LocalDate today) {
        double s = 0;
        String text = String.join(" ", Objects.toString(c.region, ""), Objects.toString(c.place, ""), Objects.toString(c.orgName, ""));
        String dong = dongOf(p.area()), sgg = sggOf(p.area());
        boolean sameDong = dong != null && text.contains(dong);
        boolean sameSgg = sgg != null && text.contains(sgg);
        if (sameDong) s += 40;
        if (sameSgg) s += 20;
        s += switch (c.category == null ? "기타" : c.category) { case "디지털" -> 25; case "정서·건강" -> 20; case "생활" -> 8; default -> 0; };
        if (p.interests() != null && p.interests().contains(c.category)) s += 15;
        String tg = Objects.toString(c.target, "");
        if (older(p.ageGroup())) {
            if (tg.matches(".*(어르신|노인|시니어|중장년|신중년|5060|7080|50세|55세|60세|65세|50대|60대|70대|장년).*")) s += 10;
            if (tg.matches(".*(아동|어린이|유아|초등|청소년|중학생|고등학생|학부모).*") && !tg.contains("누구나")) s -= 40;
        }
        if (c.startDate != null) {
            long d = ChronoUnit.DAYS.between(today, c.startDate);
            if (d < 0) s += 8;              // 진행 중
            else if (d <= 7) s += 20;
            else if (d <= 14) s += 12;
            else if (d <= 30) s += 6;
        } else s += 2;
        if (c.verified) s += 15;
        if ("PUBLIC_DATA".equals(c.source)) s += 5;
        return new Scored(c, s, sameDong, sameSgg);
    }

    /** 점수 순(같으면 날짜 가까운 순) 상위 n */
    public static List<PublicClass> rank(List<PublicClass> all, Profile p, LocalDate today, int n) {
        return all.stream().filter(c -> visible(c, today)).map(c -> score(c, p, today))
                .sorted(Comparator.comparingDouble((Scored x) -> -x.score())
                        .thenComparing(x -> x.c().startDate == null ? LocalDate.MAX : x.c().startDate.isBefore(today) ? today : x.c().startDate)
                        .thenComparing(x -> x.c().id == null ? 0L : x.c().id))
                .limit(n).map(Scored::c).toList();
    }

    /** 규칙 추천 이유: "같은 동네 · 휴대폰 사용법 · 이번 주 목요일" */
    static String ruleReason(Scored s, LocalDate today) {
        PublicClass c = s.c();
        List<String> parts = new ArrayList<>();
        if (s.sameDong()) parts.add("같은 동네");
        else if (s.sameSgg()) parts.add("가까운 동네");
        String t = Objects.toString(c.title, "");
        parts.add(switch (Objects.toString(c.category, "기타")) {
            case "디지털" -> t.matches(".*(스마트폰|휴대폰|핸드폰|폰).*") ? "휴대폰 사용법" : t.contains("키오스크") ? "키오스크 연습" : "디지털 배우기";
            case "정서·건강" -> t.matches(".*(치매|기억).*") ? "기억력 챙기기" : t.matches(".*(노래|합창|악기|하모니카).*") ? "노래로 즐겁게" : "마음·건강 챙기기";
            case "생활" -> t.matches(".*(사기|보이스피싱|금융).*") ? "사기 예방" : "생활에 도움";
            default -> "새로운 배움";
        });
        String w = whenLabel(c.startDate, c.endDate, today);
        if (!w.startsWith("날짜")) parts.add(w);
        if ("무료".equals(c.feeText)) parts.add("무료");
        return String.join(" · ", parts);
    }

    public Profile profileOf(User u) {
        Set<String> interests = new LinkedHashSet<>();
        if (u.categories != null) for (String c : u.categories) { String m = fromHelpCategory(c); if (m != null) interests.add(m); }
        LocalDateTime since = LocalDateTime.now().minusDays(180);
        for (Request r : requests.findAll()) {
            if (r.createdAt == null || r.createdAt.isBefore(since)) continue;
            if (!(u.id.equals(r.recipientId) || u.id.equals(r.volunteerId) || u.id.equals(r.senderId))) continue;
            if (r.status == Enums.ReqStatus.CANCELLED || r.status == Enums.ReqStatus.REJECTED || r.status == Enums.ReqStatus.EXPIRED) continue;
            String m = fromHelpCategory(r.category);
            if (m != null) interests.add(m);
        }
        return new Profile(u.area, u.ageGroup, interests);
    }

    public static Profile profileOf(Elder e) {
        Set<String> interests = new LinkedHashSet<>();
        if (e.needs != null) for (String c : e.needs) { String m = fromHelpCategory(c); if (m != null) interests.add(m); }
        return new Profile(e.dong, e.ageGroup, interests);
    }

    private String finger() {
        StringBuilder sb = new StringBuilder();
        for (OrgEvent e : events.findAll()) sb.append(e.id).append(e.status).append(';');
        return version.get() + ":" + sb.toString().hashCode();
    }

    public Map<String, Object> recommendFor(String cacheKey, Profile p) {
        LocalDate today = LocalDate.now();
        String fp = finger();
        Cached hit = recCache.get(cacheKey);
        if (hit != null && hit.date().equals(today) && hit.finger().equals(fp)) {
            Map<String, Object> r = new LinkedHashMap<>(hit.result());
            r.put("cached", true);
            return r;
        }
        syncOrgEvents();
        List<PublicClass> top = rank(repo.findAll(), p, today, TOP_N);
        List<Map<String, Object>> forAi = new ArrayList<>();
        for (PublicClass c : top)
            forAi.add(Views.map("id", c.id, "title", c.title, "orgName", c.orgName, "category", c.category, "when", whenLabel(c.startDate, c.endDate, today),
                    "place", c.place, "target", c.target, "fee", c.feeText));
        String profile = "동네 " + Objects.toString(p.area(), "모름") + " · 나이대 " + Objects.toString(p.ageGroup(), "모름")
                + " · 관심 " + (p.interests().isEmpty() ? "없음" : String.join(", ", p.interests()));
        Map<Long, String> reasons = top.isEmpty() ? Map.of() : ai.reasons(profile, forAi);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PublicClass c : top) {
            Scored s = score(c, p, today);
            Map<String, Object> v = view(c, today);
            String rr = reasons.get(c.id);
            v.put("reason", rr != null ? rr : ruleReason(s, today));
            v.put("reasonSource", rr != null ? "ai" : "rule");
            v.put("score", s.score());
            items.add(v);
        }
        Map<String, Object> out = Views.map("items", items, "source", reasons.isEmpty() ? "rule" : "ai", "cached", false, "date", today.toString(),
                "basis", Views.map("area", p.area(), "ageGroup", p.ageGroup(), "interests", new ArrayList<>(p.interests())),
                "searchAvailable", ai.searchAvailable(), "categories", CATEGORIES);
        // 결과가 바뀌는 쓰기(sync)가 있었을 수 있으니 지문을 다시 잰다
        recCache.put(cacheKey, new Cached(today, finger(), out));
        return out;
    }

    public Map<String, Object> recommend(User u) { return recommendFor("u:" + u.id, profileOf(u)); }

    public Map<String, Object> recommendForElder(Elder e) { return recommendFor("e:" + e.id, profileOf(e)); }

    // ===================================================== 상세 =====================================================
    public PublicClass get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("강좌를 찾을 수 없어요")); }

    public Map<String, Object> detail(Long id, boolean admin) {
        PublicClass c = get(id);
        LocalDate today = LocalDate.now();
        if (!admin && !visible(c, today)) throw ApiException.notFound("강좌를 찾을 수 없어요");
        Map<String, Object> v = view(c, today);
        v.put("description", c.description);
        v.put("pageTitle", c.pageTitle);
        v.put("easySummary", easySummary(c, today));
        v.put("companionText", companionText(c));
        v.put("timeNote", "강좌는 시간 통장 시간을 쓰지 않아요. 같이 갈 봉사자를 부르면 그 동행만 시간으로 정산돼요.");
        if (admin) { v.put("hidden", c.hidden); v.put("searchKey", c.searchKey); v.put("fetchedAt", c.fetchedAt == null ? null : c.fetchedAt.toString()); v.put("rawJson", c.rawJson); }
        return v;
    }

    /** 소리로 듣기용 쉬운 말 요약(코드가 만든다, AI 아님) */
    public static String easySummary(PublicClass c, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.orgName != null ? c.orgName + "에서 " : "").append("'").append(c.title).append("' 강좌가 있어요. ");
        String w = whenLabel(c.startDate, c.endDate, today);
        if (!w.startsWith("날짜")) sb.append("날짜는 ").append(w).append(c.scheduleText != null ? ", " + c.scheduleText : "").append("이에요. ");
        else if (c.scheduleText != null) sb.append("시간은 ").append(c.scheduleText).append("이에요. ");
        if (c.place != null) sb.append("장소는 ").append(c.place).append("이에요. ");
        if (c.target != null) sb.append("대상은 ").append(c.target).append("이에요. ");
        if (c.feeText != null) sb.append("수강료는 ").append(c.feeText).append("이에요. ");
        sb.append(c.applyUrl != null ? "신청은 기관 홈페이지에서 해요." : c.applyHow != null ? "신청은 " + c.applyHow + "으로 해요." : "신청 방법은 기관에 물어보세요.");
        if ("AI_SEARCH".equals(c.source)) sb.append(" AI가 찾은 정보라서 신청 전에 기관에 꼭 확인해 주세요.");
        return sb.toString();
    }

    /** 동행 봉사 요청 작성 화면에 미리 넣을 문장(등록은 사용자가 한다) */
    public static String companionText(PublicClass c) {
        StringBuilder sb = new StringBuilder();
        if (c.startDate != null) sb.append(c.startDate.getMonthValue()).append("월 ").append(c.startDate.getDayOfMonth()).append("일 ");
        String hm = c.scheduleText == null ? null : firstTime(c.scheduleText);
        if (hm != null) sb.append(hm).append(" ");
        String place = c.place != null ? c.place.replaceAll("\\s*\\(.*\\)$", "") : c.orgName;
        if (place != null) sb.append(place).append("에서 하는 ");
        sb.append("'").append(c.title).append("' 강좌에 같이 가 주세요 (동행)");
        return sb.toString();
    }

    static String firstTime(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?:\\s*[~∼-]\\s*(\\d{1,2}):(\\d{2}))?").matcher(s);
        if (m.find()) {
            String from = hourLabel(Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0);
            if (m.group(3) == null) return from;
            return from + "부터 " + hourLabel(Integer.parseInt(m.group(3)) + Integer.parseInt(m.group(4)) / 60.0) + "까지";
        }
        java.util.regex.Matcher k = java.util.regex.Pattern.compile("(오전|오후)\\s*\\d{1,2}\\s*시(\\s*\\d{1,2}\\s*분|\\s*반)?").matcher(s);
        return k.find() ? k.group() : null;
    }

    // ===================================================== 운영자 =====================================================
    public List<Map<String, Object>> adminList(String source) {
        syncOrgEvents();
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        repo.findAll().stream()
                .filter(c -> source == null || source.isBlank() || source.equalsIgnoreCase(c.source))
                .sorted(Comparator.comparing((PublicClass c) -> c.fetchedAt == null ? LocalDateTime.MIN : c.fetchedAt).reversed().thenComparing(c -> -c.id))
                .forEach(c -> {
                    Map<String, Object> v = view(c, today);
                    v.put("hidden", c.hidden); v.put("visible", visible(c, today)); v.put("searchKey", c.searchKey);
                    v.put("pageTitle", c.pageTitle); v.put("fetchedAt", c.fetchedAt == null ? null : c.fetchedAt.toString());
                    out.add(v);
                });
        return out;
    }

    public Map<String, Object> setHidden(Long id, boolean hidden) {
        PublicClass c = get(id);
        if ("AI_SEARCH".equals(c.source) && !hidden && !c.verified) throw ApiException.conflict("NOT_VERIFIED", "주소를 확인하지 못한 항목은 보이게 할 수 없어요");
        c.hidden = hidden;
        repo.save(c);
        version.incrementAndGet();
        return view(c, LocalDate.now());
    }

    public void delete(Long id) {
        PublicClass c = get(id);
        if ("ORG".equals(c.source)) throw ApiException.bad("ORG_EVENT", "기관이 올린 소식은 숨기기만 할 수 있어요");
        repo.delete(c);
        version.incrementAndGet();
    }

    public Map<String, Object> status() {
        syncOrgEvents();
        LocalDate today = LocalDate.now();
        List<PublicClass> all = repo.findAll();
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("AI_SEARCH", "PUBLIC_DATA", "ORG")) counts.put(s, all.stream().filter(c -> s.equals(c.source)).count());
        counts.put("visible", all.stream().filter(c -> visible(c, today)).count());
        counts.put("hidden", all.stream().filter(c -> c.hidden).count());
        return Views.map("searchAvailable", ai.searchAvailable(), "counts", counts);
    }

    // ===================================================== 도우미 =====================================================
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String clip(String s, int n) { return s == null || s.isBlank() ? null : s.length() > n ? s.substring(0, n) : s; }
    private static Double num(String s) { try { return s == null || s.isBlank() ? null : Double.parseDouble(s.trim()); } catch (Exception e) { return null; } }
}
