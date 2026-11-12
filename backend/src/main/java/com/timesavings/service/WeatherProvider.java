package com.timesavings.service;

import java.util.List;

/**
 * 기능 A-08 기상 정보 제공자. 실제 기상청 호출({@link KmaWeatherProvider})은 환경변수 KMA_API_KEY 가 있을 때만 켜진다.
 * 키가 없으면 live() 가 false 이고, 운영자가 화면에서 특보를 직접 넣거나 시연용 표본을 불러온다.
 */
public interface WeatherProvider {
    List<String> TYPES = List.of("폭염", "한파", "호우", "대설", "강풍", "태풍", "건조");
    List<String> LEVELS = List.of("주의보", "경보");

    /** 지금 발효 중인 특보 한 줄. region 은 기상청 구역 이름(예: 서울). */
    record Alert(String type, String level, String region) {}

    /** 실제 기상 API 를 부를 수 있는지(키가 있는지). */
    boolean live();

    /** 지금 발효 중인 특보. 키가 없으면 빈 목록. 실패하면 예외 대신 빈 목록. */
    List<Alert> current();
}
