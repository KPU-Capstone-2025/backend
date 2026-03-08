package com.kpu.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client

/**
 * [해결] com.kpu.backend 패키지로 통합하여 스프링이 설정값을 읽을 수 있게 함
 */
@Configuration
class AppConfig {

    @Value("\${cloud.aws.credentials.access-key}")
    lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key}")
    lateinit var secretKey: String

    @Bean
    fun restTemplate(): RestTemplate = RestTemplate()

    // 공통 인증 정보 제공자
    private fun getCredentialsProvider(): StaticCredentialsProvider {
        val credentials = AwsBasicCredentials.create(accessKey, secretKey)
        return StaticCredentialsProvider.create(credentials)
    }

    @Bean
    fun ec2Client(): Ec2Client {
        return Ec2Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(getCredentialsProvider()) // 이 부분이 명시되어야 에러가 안 납니다.
            .build()
    }

    @Bean
    fun albClient(): ElasticLoadBalancingV2Client {
        return ElasticLoadBalancingV2Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(getCredentialsProvider()) // 이 부분이 명시되어야 에러가 안 납니다.
            .build()
    }
}