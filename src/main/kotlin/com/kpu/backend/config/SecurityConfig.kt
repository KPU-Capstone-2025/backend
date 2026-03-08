package com.kpu.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * 401 에러 해결을 위한 보안 설정 파일
 * 패키지 경로를 com.kpu.backend.config로 수정했습니다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // CSRF 비활성화 (API 서버 필수)
            .cors { it.configurationSource(corsConfigurationSource()) } // CORS 설정 적용
            .authorizeHttpRequests { auth ->
                // 모든 /api/로 시작하는 경로는 인증 없이 허용
                auth.requestMatchers("/api/**").permitAll()
                auth.anyRequest().authenticated()
            }
            .httpBasic { it.disable() } // 기본 로그인 창 비활성화
            .formLogin { it.disable() } // 폼 로그인 비활성화

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = listOf("*") // 모든 도메인 허용
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}