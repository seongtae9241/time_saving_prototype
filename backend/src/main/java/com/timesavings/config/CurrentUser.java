package com.timesavings.config;

import java.lang.annotation.*;

/** 컨트롤러 인자에 붙이면 로그인한 사용자(User)를 넣어 준다. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {}
