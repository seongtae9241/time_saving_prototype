package com.timesavings.service;

import java.util.Map;

/**
 * 위치 도우미(B-20 가까운 순, B-59). 공고에는 동 이름만 있으므로 동별 중심 좌표 표를 코드에 두고,
 * 화면이 보낸 현재 좌표와의 거리를 계산한다. 받은 좌표는 계산에만 쓰고 저장하지 않는다.
 * 지금 표는 시범 지역 5개 동의 가상 좌표다(행복동을 기준점으로 Views.DONG_XY 의 km 값을 위·경도로 옮김).
 * 실제 서비스 지역이 정해지면 이 표만 바꾸면 된다.
 */
public final class Geo {
    private Geo() {}

    private static final double BASE_LAT = 37.5665, BASE_LNG = 126.9780;
    private static final double KM_PER_LAT = 111.0, KM_PER_LNG = 88.8; // 위도 37.5도 부근

    private static double[] at(double eastKm, double northKm) {
        return new double[]{BASE_LAT + northKm / KM_PER_LAT, BASE_LNG + eastKm / KM_PER_LNG};
    }

    /** 동 이름 → {위도, 경도} */
    public static final Map<String, double[]> DONG = Map.of(
            "행복동", at(0, 0), "희망동", at(1.0, 0.4), "사랑동", at(-0.8, 1.1),
            "미래동", at(0.6, -1.3), "소망동", at(-1.5, -0.6));

    public static boolean valid(Double lat, Double lng) {
        return lat != null && lng != null && !lat.isNaN() && !lng.isNaN() && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    /** 두 좌표 사이 거리(km, 하버사인). */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 현재 좌표에서 그 동 중심까지 거리(km, 0.1 단위). 표에 없는 동이면 null. */
    public static Double kmToDong(double lat, double lng, String dong) {
        double[] c = dong == null ? null : DONG.get(dong.trim());
        if (c == null) return null;
        return Math.round(km(lat, lng, c[0], c[1]) * 10) / 10.0;
    }
}
