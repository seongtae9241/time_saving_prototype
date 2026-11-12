package com.timesavings.service;

import java.util.*;

/**
 * 기능 A-05 시간 은행 재정 추계 · 기능 A-06 정책 시뮬레이션의 계산 모형(순수 계산, DB·AI 없음).
 * 해마다 나이대가 올라가는 단순 코호트 모형: 한 해에 각 나이대의 1/10 이 다음 나이대로 옮겨 가고, 이탈률만큼 빠지고, 가입 수만큼 들어온다.
 * 공급 = 회원 수 × 나이대별 1인당 월 봉사 시간 × 12, 수요 = (회원 + 관리 대상) × 나이대별 1인당 월 도움 받은 시간 × 12.
 * 숫자는 모두 여기서 계산한다. LLM 은 숫자를 만들지 않는다.
 */
public final class FinanceModel {
    private FinanceModel() {}

    public static final List<String> BANDS = List.of("10대", "20대", "30대", "40대", "50대", "60대", "70대", "80대 이상");
    /** 자료가 적을 때 쓰는 기본 가정값(1인당 월 시간). 화면에 "가정"으로 표시한다. */
    public static final double[] DEFAULT_SUPPLY = {1.0, 2.0, 1.5, 1.5, 2.5, 3.0, 1.5, 0.3};
    public static final double[] DEFAULT_DEMAND = {0.1, 0.2, 0.3, 0.3, 0.5, 1.0, 2.5, 4.0};
    /** 기본 가정: 한 해 이탈률 15%, 한 달 가입 = 지금 회원의 2% */
    public static final double DEFAULT_CHURN_YEAR = 0.15;
    public static final double DEFAULT_JOIN_RATE_MONTH = 0.02;
    /** 나이대를 정하지 않은 가입 증가를 나이대에 나누는 비율(가정) */
    public static final double[] JOIN_SHARE = {0.05, 0.25, 0.15, 0.10, 0.15, 0.20, 0.08, 0.02};
    public static final double AGING_PER_YEAR = 0.1;
    public static final int MIN_SAMPLE = 3, MIN_CHURN_SAMPLE = 5, WINDOW_MONTHS = 6;
    public static final int[] OFFSETS = {0, 1, 3, 5, 10};
    public static final List<String> ADJ_TYPES = List.of("JOIN_ADD", "CHURN_MULT", "SUPPLY_ADD", "DEMAND_ADD");

    /** "20대"·"80대"·"80대 이상"·"65세" → 나이대 번호(0~7). 알 수 없으면 -1. */
    public static int band(String ageGroup) {
        if (ageGroup == null) return -1;
        String d = ageGroup.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return -1;
        int n;
        try { n = Integer.parseInt(d.length() > 3 ? d.substring(0, 3) : d); } catch (NumberFormatException e) { return -1; }
        if (n < 10) return -1;
        return Math.min(7, n / 10 - 1);
    }

    /** 모형에 넣는 지금 상태. */
    public static class Inputs {
        public List<String> areas = new ArrayList<>();
        public double[][] users = new double[0][8];   // [동네][나이대] 회원
        public double[][] elders = new double[0][8];  // [동네][나이대] 관리 대상(도움만 받음)
        public double[] supplyPer = DEFAULT_SUPPLY.clone(), demandPer = DEFAULT_DEMAND.clone(); // 1인당 월 시간
        public boolean[] supplyReal = new boolean[8], demandReal = new boolean[8];
        public int[] supplySample = new int[8], demandSample = new int[8];
        public double[][] joins = new double[0][8];   // [동네][나이대] 월 가입 수
        public boolean joinsReal;
        public double churnYear = DEFAULT_CHURN_YEAR;
        public boolean churnReal;
        public int churnSample;
        public int baseYear;
    }

    /**
     * 조정 값 하나(기능 A-06). type: JOIN_ADD(월 가입 +명) · CHURN_MULT(이탈률 ×배) · SUPPLY_ADD(1인당 월 봉사 시간 +시간) · DEMAND_ADD(1인당 월 도움 시간 +시간).
     * ageBand·area 가 null 이면 전체.
     */
    public record Adj(String type, String ageBand, String area, double value) {}

    private static double[][] copy(double[][] a) {
        double[][] o = new double[a.length][];
        for (int i = 0; i < a.length; i++) o[i] = a[i].clone();
        return o;
    }

    private static double r1(double v) { return Math.round(v * 10) / 10.0; }

    public static String status(Double ratio) {
        if (ratio == null) return "안정";
        return ratio < 80 ? "위험" : ratio < 100 ? "주의" : "안정";
    }

    private static Map<String, Object> row(int offset, int year, double[] u, double[] e, double[] sup, double[] dem) {
        double supply = 0, demand = 0, members = 0, eld = 0, senior = 0;
        for (int b = 0; b < 8; b++) {
            supply += u[b] * sup[b] * 12;
            demand += (u[b] + e[b]) * dem[b] * 12;
            members += u[b];
            eld += e[b];
            if (b >= 6) senior += u[b] + e[b];
        }
        Double ratio = demand <= 0 ? null : (double) Math.round(supply * 100 / demand);
        return Views.map("offset", offset, "year", year, "supply", r1(supply), "demand", r1(demand), "gap", r1(supply - demand),
                "ratio", ratio, "status", status(ratio), "members", r1(members), "elders", r1(eld),
                "seniorShare", members + eld <= 0 ? 0 : Math.round(senior * 100 / (members + eld)));
    }

    /** 조정 값을 적용한 뒤 10년을 돌려 동네별·전체 결과를 낸다. adjs 가 비면 지금 추세 그대로. */
    public static Map<String, Object> project(Inputs in, List<Adj> adjs) {
        int A = in.areas.size();
        double[][] u = copy(in.users), e = copy(in.elders), j = copy(in.joins);
        double[][] sup = new double[A][], dem = new double[A][], churn = new double[A][8];
        for (int a = 0; a < A; a++) { sup[a] = in.supplyPer.clone(); dem[a] = in.demandPer.clone(); Arrays.fill(churn[a], in.churnYear); }
        double total = 0;
        double[] areaMembers = new double[A];
        for (int a = 0; a < A; a++) { for (double v : u[a]) areaMembers[a] += v; total += areaMembers[a]; }
        for (Adj x : adjs == null ? List.<Adj>of() : adjs) {
            int ab = x.ageBand() == null ? -1 : BANDS.indexOf(x.ageBand());
            int aa = x.area() == null ? -1 : in.areas.indexOf(x.area());
            if (x.area() != null && aa < 0) continue;
            for (int a = 0; a < A; a++) {
                if (aa >= 0 && aa != a) continue;
                double areaShare = aa >= 0 ? 1 : total <= 0 ? 1.0 / Math.max(1, A) : areaMembers[a] / total;
                for (int b = 0; b < 8; b++) {
                    if (ab >= 0 && ab != b) continue;
                    switch (x.type()) {
                        case "JOIN_ADD" -> j[a][b] = Math.max(0, j[a][b] + x.value() * areaShare * (ab >= 0 ? 1 : JOIN_SHARE[b]));
                        case "CHURN_MULT" -> churn[a][b] = Math.min(0.9, Math.max(0, churn[a][b] * x.value()));
                        case "SUPPLY_ADD" -> sup[a][b] = Math.max(0, sup[a][b] + x.value());
                        case "DEMAND_ADD" -> dem[a][b] = Math.max(0, dem[a][b] + x.value());
                        default -> { }
                    }
                }
            }
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int a = 0; a < A; a++) rows.add(new ArrayList<>());
        List<Map<String, Object>> totalRows = new ArrayList<>();
        int last = OFFSETS[OFFSETS.length - 1];
        for (int y = 0; y <= last; y++) {
            final int yy = y;
            if (Arrays.stream(OFFSETS).anyMatch(o -> o == yy)) {
                double ts = 0, td = 0, tm = 0, te = 0, tsen = 0;
                for (int a = 0; a < A; a++) {
                    Map<String, Object> r = row(y, in.baseYear + y, u[a], e[a], sup[a], dem[a]);
                    rows.get(a).add(r);
                    ts += (Double) r.get("supply"); td += (Double) r.get("demand"); tm += (Double) r.get("members"); te += (Double) r.get("elders");
                    for (int b = 6; b < 8; b++) tsen += u[a][b] + e[a][b];
                }
                Double ratio = td <= 0 ? null : (double) Math.round(ts * 100 / td);
                totalRows.add(Views.map("offset", y, "year", in.baseYear + y, "supply", r1(ts), "demand", r1(td), "gap", r1(ts - td), "ratio", ratio,
                        "status", status(ratio), "members", r1(tm), "elders", r1(te), "seniorShare", tm + te <= 0 ? 0 : Math.round(tsen * 100 / (tm + te))));
            }
            if (y == last) break;
            for (int a = 0; a < A; a++) {
                double[] nu = new double[8], ne = new double[8];
                for (int b = 0; b < 8; b++) {
                    double stayU = u[a][b] * (1 - churn[a][b]);
                    double move = b < 7 ? AGING_PER_YEAR : 0;
                    nu[b] += stayU * (1 - move);
                    ne[b] += e[a][b] * (1 - move);
                    if (b < 7) { nu[b + 1] += stayU * move; ne[b + 1] += e[a][b] * move; }
                    nu[b] += j[a][b] * 12;
                }
                u[a] = nu; e[a] = ne;
            }
        }
        List<Map<String, Object>> areas = new ArrayList<>();
        for (int a = 0; a < A; a++) {
            List<Map<String, Object>> rs = rows.get(a);
            String risk = "안정";
            for (Map<String, Object> r : rs) {
                if ((Integer) r.get("offset") == 0) continue;
                String s = (String) r.get("status");
                if (s.equals("위험")) risk = "위험"; else if (s.equals("주의") && !risk.equals("위험")) risk = "주의";
            }
            double joinsMonth = Arrays.stream(j[a]).sum(), young = j[a][1] + j[a][2];
            double churnAvg = Arrays.stream(churn[a]).average().orElse(in.churnYear);
            areas.add(Views.map("area", in.areas.get(a), "risk", risk, "rows", rs, "joinsPerMonth", r1(joinsMonth), "youngJoinsPerMonth", r1(young),
                    "churnYear", Math.round(churnAvg * 100), "reasons", reasons(rs, joinsMonth, young, churnAvg)));
        }
        areas.sort(Comparator.comparingInt((Map<String, Object> m) -> switch ((String) m.get("risk")) { case "위험" -> 0; case "주의" -> 1; default -> 2; })
                .thenComparing(m -> { List<?> rs = (List<?>) m.get("rows"); Object ra = ((Map<?, ?>) rs.get(rs.size() - 1)).get("ratio"); return ra == null ? 9999.0 : (Double) ra; }));
        return Views.map("baseYear", in.baseYear, "offsets", OFFSETS, "areas", areas, "total", totalRows);
    }

    /** 위험 이유(코드가 뽑는 근거 문장). LLM 은 이 근거를 읽기 쉽게 요약만 한다. */
    static List<String> reasons(List<Map<String, Object>> rs, double joinsMonth, double youngJoins, double churn) {
        List<String> out = new ArrayList<>();
        Map<String, Object> now = rs.get(0), end = rs.get(rs.size() - 1);
        long s0 = ((Number) now.get("seniorShare")).longValue(), s1 = ((Number) end.get("seniorShare")).longValue();
        Object r0 = now.get("ratio"), r1v = end.get("ratio");
        if (r1v != null) out.add("수요 대비 공급이 " + (r0 == null ? "" : "지금 " + Math.round((Double) r0) + "%에서 ") + end.get("year") + "년 " + Math.round((Double) r1v) + "%가 돼요");
        if (s1 > s0) out.add("70대 이상 비율이 " + s0 + "%에서 " + s1 + "%로 늘어 도움 수요가 커져요");
        if (youngJoins < 1) out.add("20~30대 가입이 월 " + r1(youngJoins) + "명이라 봉사할 사람이 잘 늘지 않아요");
        if (churn >= 0.2) out.add("한 해 이탈률이 " + Math.round(churn * 100) + "%로 높아 회원이 줄어요");
        double e = ((Number) now.get("elders")).doubleValue();
        if (e > 0 && e >= ((Number) now.get("members")).doubleValue() * 0.5) out.add("도움만 받는 관리 대상이 " + Math.round(e) + "명으로 회원 수에 비해 많아요");
        if (out.isEmpty()) out.add("지금 추세로는 공급이 수요를 따라가요");
        return out;
    }
}
