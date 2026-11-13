package com.timesavings.web;

import com.timesavings.config.CurrentUser;
import com.timesavings.domain.User;
import com.timesavings.service.WeatherAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 담당자 화면의 특보 알림: 날씨 특보와 운영자가 보낸 안내를 본다. /api/org/** 는 ORG 역할만(인터셉터). */
@RestController
@RequestMapping("/api/org/weather")
public class OrgWeatherController {
    private final WeatherAlertService weather;

    public OrgWeatherController(WeatherAlertService weather) { this.weather = weather; }

    @GetMapping
    public Map<String, Object> list(@CurrentUser User u) { return weather.forManager(u); }
}
