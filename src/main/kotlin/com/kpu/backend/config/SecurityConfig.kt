package com.kpu.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {
    
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // 테스트 편의를 위해 CSRF 비활성화
            .authorizeHttpRequests { auth ->
                // 프로비저닝 및 모든 API 요청 허용
                auth.requestMatchers("/api/**").permitAll()
                auth.anyRequest().permitAll()
            }
        return http.build()
    }
}