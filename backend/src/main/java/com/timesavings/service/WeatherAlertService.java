package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.ForecastAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 기능 A-08 특보 알림 리포트 · 기능 A-09 특보 전달.
 * 특보는 기상청 API(키가 있을 때)·운영자 직접 입력·시연용 표본으로 들어온다. 위험 지역의 관리 대상 수·담당 기관·예정 활동은 코드가 세고,
 * 리포트 문장만 AI(키 없으면 규칙 문장). 공지 게시와 담당자 알림은 운영자가 "전달" 버튼을 눌러야 나간다.
 * 관리 대상은 별칭도 쓰지 않고 수만 센다. 의료·건강 판단은 하지 않고 안부 확인·일정 조정 같은 일반 행동 요령만 쓴다.
 */
@Service
@Transactional
public class WeatherAlertService {
    private final WeatherAlertRepo alerts;
    private final ElderRepo elders;
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final RequestRepo requests;
    private final NoticeRepo notices;
    private final NotificationService noti;
    private final WeatherProvider provider;
    private final ForecastAi ai;

    public WeatherAlertService(WeatherAlertRepo alerts, ElderRepo elders, UserRepo users, OrganizationRepo orgs, RequestRepo requests,
                               NoticeRepo notices, NotificationService noti, WeatherProvider provider, ForecastAi ai) {
        this.alerts = alerts; this.elders = elders; this.users = users; this.orgs = orgs; this.requests = requests;
        this.notices = notices; this.noti = noti; this.provider = provider; this.ai = ai;
    }

    static final Map<String, List<String>> ACTIONS = Map.of(
            "폭염", List.of("낮 12~17시 바깥 동행·장보기 일정은 오전이나 저녁으로 옮기기", "혼자 사시는 대상에게 안부 전화하기", "방문하는 봉사자에게 물과 쉬는 시간 챙기기 안내"),
            "한파", List.of("빙판길 바깥 동행 일정은 날을 바꾸거나 짧게 줄이기", "혼자 사시는 대상에게 안부 전화하기(난방·수도 얼었는지 묻기)", "방문하는 봉사자에게 미끄럼 주의 안내"));
    static final List<String> ACTIONS_ETC = List.of("바깥 동행·장보기 일정은 특보가 풀린 뒤로 미루기", "혼자 사시는 대상에게 안부 전화하기", "방문하는 봉사자에게 이동 안전 안내");

    /** 서비스에 있는 동네(회원·관리 대상의 동). */
    public List<String> knownAreas() {
        TreeSet<String> s = new TreeSet<>();
        elders.findAll().forEach(e -> { if (e.dong != null && !e.dong.isBlank()) s.add(e.dong); });
        users.findAll().forEach(u -> { if (u.role == Role.USER && u.area != null && !u.area.isBlank()) s.add(u.area); });
        return new ArrayList<>(s);
    }

    /** 위험 지역과 대상 집계(코드). */
    public Map<String, Object> facts(WeatherAlert a) {
        List<Elder> all = elders.findAll().stream().filter(e -> e.status == ElderStatus.ACTIVE).toList();
        LocalDate from = a.startDate == null ? LocalDate.now() : a.startDate, to = a.endDate == null ? from.plusDays(2) : a.endDate;
        List<Request> reqs = requests.findAll().stream().filter(r -> r.elderId != null && (r.status.pending() || r.status.active())
                && r.date != null && !r.date.isBefore(from) && !r.date.isAfter(to)).toList();
        List<Map<String, Object>> areaRows = new ArrayList<>();
        Map<Long, Integer> mgrCount = new LinkedHashMap<>();
        int total = 0, total80 = 0, totalAct = 0;
        for (String area : a.areas) {
            List<Elder> in = all.stream().filter(e -> area.equals(e.dong)).toList();
            Set<Long> ids = new HashSet<>();
            in.forEach(e -> ids.add(e.id));
            long old = in.stream().filter(e -> FinanceModel.band(e.ageGroup) >= 7).count();
            long act = reqs.stream().filter(r -> ids.contains(r.elderId)).count();
            Map<Long, Integer> byMgr = new LinkedHashMap<>();
            for (Elder e : in) if (e.managerId != null) { byMgr.merge(e.managerId, 1, Integer::sum); mgrCount.merge(e.managerId, 1, Integer::sum); }
            List<Map<String, Object>> mgrs = new ArrayList<>();
            byMgr.forEach((id, n) -> users.findById(id).ifPresent(m -> mgrs.add(Views.map("managerId", m.id, "name", m.name,
                    "orgName", m.orgId == null ? null : orgs.findById(m.orgId).map(o -> o.name).orElse(null), "elders", n))));
            areaRows.add(Views.map("area", area, "elders", in.size(), "elders80", old, "activities", act, "managers", mgrs));
            total += in.size(); total80 += old; totalAct += act;
        }
        areaRows.sort((x, y) -> Integer.compare((Integer) y.get("elders"), (Integer) x.get("elders")));
        return Views.map("type", a.type, "level", a.level, "areas", areaRows, "from", from.toString(), "to", to.toString(),
                "totalElders", total, "totalElders80", total80, "totalActivities", totalAct, "managerCount", mgrCount.size(), "managerIds", new ArrayList<>(mgrCount.keySet()));
    }

    private Map<String, Object> ruleText(WeatherAlert a, Map<String, Object> f) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) f.get("areas");
        String summary = String.join("·", a.areas) + "에 " + a.type + " " + a.level + "가 내려졌어요(" + f.get("from") + " ~ " + f.get("to") + "). "
                + "이 지역의 관리 대상은 " + f.get("totalElders") + "명(80대 이상 " + f.get("totalElders80") + "명), 담당자는 " + f.get("managerCount") + "명이고, "
                + "기간 안에 잡힌 방문·동행은 " + f.get("totalActivities") + "건이에요."
                + (rows.isEmpty() || (Integer) rows.get(0).get("elders") == 0 ? "" : " 대상이 가장 많은 곳은 " + rows.get(0).get("area") + "(" + rows.get(0).get("elders") + "명)이에요.");
        return Views.map("summary", summary, "actions", ACTIONS.getOrDefault(a.type, ACTIONS_ETC));
    }

    private void writeReport(WeatherAlert a) {
        Map<String, Object> f = facts(a);
        Map<String, Object> forAi = new LinkedHashMap<>(f);
        forAi.remove("managerIds");
        a.reportJson = Views.json(ai.weather(forAi, ruleText(a, f)));
    }

    public Map<String, Object> view(WeatherAlert a) {
        Map<String, Object> f = facts(a);
        f.remove("managerIds");
        return Views.map("id", a.id, "type", a.type, "level", a.level, "areas", a.areas, "startDate", a.startDate, "endDate", a.endDate,
                "source", a.source, "memo", a.memo, "facts", f,
                "report", Views.parse(a.reportJson, new TypeReference<Map<String, Object>>() {}, Map.of()),
                "noticeId", a.noticeId, "sentAt", a.sentAt, "sentManagers", a.sentManagers, "createdAt", a.createdAt);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        return Views.map("live", provider.live(), "types", WeatherProvider.TYPES, "levels", WeatherProvider.LEVELS, "knownAreas", knownAreas(),
                "alerts", alerts.findAll().stream().sorted(Comparator.comparing((WeatherAlert a) -> -a.id)).map(this::view).toList());
    }

    public WeatherAlert create(String type, String level, List<String> areas, LocalDate start, LocalDate end, String memo, String source) {
        if (type == null || !WeatherProvider.TYPES.contains(type)) throw ApiException.bad("INVALID_TYPE", "특보 종류를 골라 주세요");
        if (level == null || !WeatherProvider.LEVELS.contains(level)) throw ApiException.bad("INVALID_LEVEL", "주의보·경보 중에서 골라 주세요");
        List<String> known = knownAreas();
        List<String> as = areas == null ? List.of() : areas.stream().filter(known::contains).distinct().toList();
        if (as.isEmpty()) throw ApiException.bad("AREA_REQUIRED", "특보가 내려진 동네를 하나 이상 골라 주세요");
        WeatherAlert a = new WeatherAlert();
        a.type = type; a.level = level; a.areas = new ArrayList<>(as);
        a.startDate = start == null ? LocalDate.now() : start;
        a.endDate = end == null || end.isBefore(a.startDate) ? a.startDate.plusDays(2) : end;
        a.memo = memo == null || memo.isBlank() ? null : memo.trim();
        a.source = source;
        alerts.save(a);
        writeReport(a);
        return a;
    }

    /** 시연용 표본 특보: 계절에 맞는 종류로, 관리 대상이 많은 동네 두 곳. */
    public WeatherAlert sample() {
        int m = LocalDate.now().getMonthValue();
        String type = m >= 11 || m <= 2 ? "한파" : m >= 6 && m <= 9 ? "폭염" : "강풍";
        Map<String, Long> cnt = new TreeMap<>();
        elders.findAll().stream().filter(e -> e.status == ElderStatus.ACTIVE && e.dong != null).forEach(e -> cnt.merge(e.dong, 1L, Long::sum));
        List<String> areas = cnt.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(2).map(Map.Entry::getKey).toList();
        if (areas.isEmpty()) areas = knownAreas().stream().limit(2).toList();
        return create(type, type.equals("강풍") ? "주의보" : "경보", areas, LocalDate.now(), LocalDate.now().plusDays(2), "시연용 표본 특보(실제 기상 정보가 아니에요)", "SAMPLE");
    }

    /** 기상청에서 지금 특보를 받아 온다(키가 있을 때만). 같은 종류·수준의 오늘 특보가 이미 있으면 다시 만들지 않는다. */
    public Map<String, Object> fetch() {
        if (!provider.live()) return Views.map("live", false, "created", 0, "message", "기상 API 키가 없어요. 특보를 직접 넣거나 표본을 불러와 주세요");
        int created = 0;
        List<String> areas = knownAreas();
        for (WeatherProvider.Alert x : provider.current()) {
            boolean dup = alerts.findAll().stream().anyMatch(a -> "KMA".equals(a.source) && a.type.equals(x.type()) && a.level.equals(x.level()) && LocalDate.now().equals(a.startDate));
            if (dup || areas.isEmpty()) continue;
            create(x.type(), x.level(), areas, LocalDate.now(), null, "기상청 특보(" + x.region() + ")", "KMA");
            created++;
        }
        return Views.map("live", true, "created", created, "message", created == 0 ? "지금 새로 내려진 특보가 없어요" : "특보 " + created + "건을 받아 왔어요");
    }

    public Map<String, Object> refresh(Long id) {
        WeatherAlert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("특보를 찾을 수 없어요"));
        writeReport(a);
        return view(a);
    }

    public void delete(Long id) {
        WeatherAlert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("특보를 찾을 수 없어요"));
        if (a.sentAt != null) throw ApiException.conflict("ALREADY_SENT", "이미 전달한 특보는 지울 수 없어요");
        alerts.delete(a);
    }

    /** A-09: 공지사항으로 게시하고 해당 지역 기관 담당자에게 알림. 한 번만. */
    public Map<String, Object> send(Long id) {
        WeatherAlert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("특보를 찾을 수 없어요"));
        if (a.sentAt != null) throw ApiException.conflict("ALREADY_SENT", "이미 전달한 특보예요");
        Map<String, Object> f = facts(a);
        Map<String, Object> rep = Views.parse(a.reportJson, new TypeReference<Map<String, Object>>() {}, Map.of());
        @SuppressWarnings("unchecked") List<String> actions = rep.get("actions") instanceof List<?> l ? (List<String>) l : ACTIONS.getOrDefault(a.type, ACTIONS_ETC);
        String where = String.join("·", a.areas);
        Notice n = new Notice();
        n.title = "[특보] " + where + " " + a.type + " " + a.level;
        if (n.title.length() > 100) n.title = n.title.substring(0, 100);
        StringBuilder c = new StringBuilder();
        c.append(where).append("에 ").append(a.type).append(" ").append(a.level).append("가 내려졌어요.\n기간: ").append(f.get("from")).append(" ~ ").append(f.get("to")).append("\n\n이렇게 해 주세요\n");
        for (String s : actions) c.append("· ").append(s).append("\n");
        c.append("\n약속한 활동을 바꿔야 하면 상대와 메시지로 먼저 이야기해 주세요. 위급한 상황은 119에 알려 주세요.");
        n.content = c.toString();
        notices.save(n);
        @SuppressWarnings("unchecked") List<Long> mgrIds = (List<Long>) f.get("managerIds");
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) f.get("areas");
        int sent = 0;
        for (Long mid : mgrIds) {
            int mine = 0;
            for (Map<String, Object> r : rows) for (Map<?, ?> m : (List<Map<?, ?>>) (List<?>) r.get("managers")) if (mid.equals(m.get("managerId"))) mine += (Integer) m.get("elders");
            if (noti.send(mid, "WEATHER", where + " " + a.type + " " + a.level, "담당 대상 " + mine + "명이 있는 지역이에요. 안부 확인과 일정 조정을 살펴 주세요.", "/notice/" + n.id) != null) sent++;
        }
        a.noticeId = n.id; a.sentAt = LocalDateTime.now(); a.sentManagers = sent;
        return view(a);
    }
}
