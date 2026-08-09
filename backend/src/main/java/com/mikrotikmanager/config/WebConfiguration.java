package com.mikrotikmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfiguration implements WebMvcConfigurer {
    private final AppProperties appProperties;

    WebConfiguration(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(appProperties.frontendOrigin(), "http://127.0.0.1:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
