package com.kpu.backend.config

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.client.RestTemplate
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client

@EnableAsync // 비동기 처리 활성화
@Configuration
@EnableWebSecurity
class GlobalConfig {

    @Value("\${cloud.aws.credentials.access-key}")
    private lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key}")
    private lateinit var secretKey: String

    @Value("\${cloud.aws.region.static}")
    private lateinit var region: String

    @Bean
    fun restTemplate() = RestTemplate()

    private fun getCredentialsProvider() = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey)
    )

    @Bean
    fun ec2Client(): Ec2Client = Ec2Client.builder()
        .region(Region.of(region))
        .credentialsProvider(getCredentialsProvider())
        .build()

    @Bean
    fun albClient(): ElasticLoadBalancingV2Client = ElasticLoadBalancingV2Client.builder()
        .region(Region.of(region))
        .credentialsProvider(getCredentialsProvider())
        .build()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/**").permitAll()
                auth.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}

// 공통 응답 DTO
data class ApiResponse<T>(
    val isSuccess: Boolean,
    val code: String,
    val message: String,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val result: T? = null,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val containers: List<T>? = null,

    @JsonInclude(JsonInclude.Include.NON_NULL)
    val results: T? = null
)