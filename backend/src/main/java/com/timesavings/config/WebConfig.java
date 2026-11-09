package com.timesavings.config;

import com.timesavings.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor interceptor;
    private final String[] origins;

    public WebConfig(AuthInterceptor interceptor, @Value("${app.cors-origins:http://localhost:*,http://127.0.0.1:*}") String origins) {
        this.interceptor = interceptor;
        this.origins = origins.split(",");
    }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(interceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/api/**").allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*").allowCredentials(true);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) {
                return p.hasParameterAnnotation(CurrentUser.class) && User.class.isAssignableFrom(p.getParameterType());
            }
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest w, WebDataBinderFactory f) {
                Object u = w.getAttribute(AuthInterceptor.ATTR, RequestAttributes.SCOPE_REQUEST);
                if (u == null) throw ApiException.unauthorized("로그인이 필요해요");
                return u;
            }
        });
    }
}
