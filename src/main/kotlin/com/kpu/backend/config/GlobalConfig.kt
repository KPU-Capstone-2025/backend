package com.kpu.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.client.RestTemplate
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.ssm.SsmClient

@EnableAsync
@Configuration
@EnableWebSecurity
class GlobalConfig(private val jwtUtil: JwtUtil) {

    @Value("\${cloud.aws.region.static}") private lateinit var region: String

    @Bean fun restTemplate() = RestTemplate()

    @Bean fun passwordEncoder() = BCryptPasswordEncoder()

    @Bean fun ec2Client(): Ec2Client = Ec2Client.builder()
        .region(Region.of(region)).build()

    @Bean fun albClient(): ElasticLoadBalancingV2Client = ElasticLoadBalancingV2Client.builder()
        .region(Region.of(region)).build()

    @Bean fun ssmClient(): SsmClient = SsmClient.builder()
        .region(Region.of(region)).build()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }.cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/company/login",
                    "/api/company/register",
                    "/api/alerts/webhook",
                    "/api/alerts/webhook/**",
                    "/api/servers/**",
                    "/api/dashboard/*/hosts",
                    "/api/dashboard/*/anomaly",
                    "/api/dashboard/*/prediction",
                    "/api/dashboard/*/alerts/daily",
                    "/error"
                ).permitAll()
                auth.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .addFilterBefore(JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter::class.java)
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
