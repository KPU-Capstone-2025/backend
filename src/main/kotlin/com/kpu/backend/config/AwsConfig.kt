package com.kpu.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client

@Configuration
class AwsConfig {

    @Value("\${cloud.aws.credentials.access-key:}")
    private lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key:}")
    private lateinit var secretKey: String

    @Value("\${cloud.aws.region.static:ap-northeast-2}")
    private lateinit var region: String

    private fun getProvider(): StaticCredentialsProvider {
        // 빈 값일 경우를 대비한 처리 (실제 호출 시 에러가 나겠지만, 컨텍스트 로딩은 성공함)
        val finalAccessKey = if (accessKey.isEmpty()) "placeholder" else accessKey
        val finalSecretKey = if (secretKey.isEmpty()) "placeholder" else secretKey
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(finalAccessKey, finalSecretKey))
    }

    @Bean
    fun ec2Client(): Ec2Client {
        return Ec2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(getProvider())
            .build()
    }

    @Bean
    fun albClient(): ElasticLoadBalancingV2Client {
        return ElasticLoadBalancingV2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(getProvider())
            .build()
    }
}
