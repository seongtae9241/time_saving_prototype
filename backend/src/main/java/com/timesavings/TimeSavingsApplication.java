package com.timesavings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TimeSavingsApplication {
    public static final String ZONE = "Asia/Seoul";

    /** 서버가 UTC 로 떠도 시각(체크인·알림·마감 계산)이 한국 시간으로 기록되게 JVM 기본 시간대를 정한다. */
    public static void useKoreaTime() { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZONE)); }

    public static void main(String[] args) {
        useKoreaTime();
        SpringApplication.run(TimeSavingsApplication.class, args);
    }
}
