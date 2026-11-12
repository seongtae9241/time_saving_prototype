package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 기능 A-08·A-09 기상 특보 한 건. 운영자가 직접 넣거나(MANUAL) 시연용 표본(SAMPLE)이거나 기상청 API(KMA)에서 온 것.
 * 리포트 문장은 만들 때 한 번 저장하고, 대상 수 같은 집계는 볼 때마다 다시 센다. 전달(공지·담당자 알림)은 운영자가 버튼을 눌러야 나간다.
 */
@Entity
@Table(name = "weather_alerts")
public class WeatherAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    /** 폭염·한파·호우·대설·강풍·태풍·건조 */
    @Column(nullable = false, length = 10) public String type;
    /** 주의보·경보 */
    @Column(nullable = false, length = 10) public String level;
    @Convert(converter = StringListConverter.class) @Column(length = 300) public List<String> areas = new ArrayList<>();
    public LocalDate startDate;
    public LocalDate endDate;
    @Column(length = 10) public String source = "MANUAL";
    @Column(length = 300) public String memo;
    /** {summary, actions[], source, provider} */
    @Column(name = "report_json", length = 3000) public String reportJson;
    public Long noticeId;
    public LocalDateTime sentAt;
    public Integer sentManagers;
    public LocalDateTime createdAt = LocalDateTime.now();
}
