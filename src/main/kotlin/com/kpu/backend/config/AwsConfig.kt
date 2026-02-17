package com.kpu.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client

@Configuration
class AwsConfig {

    // application.properties에 적어둔 키값들을 가져옵니다.
    @Value("\${cloud.aws.credentials.access-key}")
    private lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key}")
    private lateinit var secretKey: String

    @Value("\${cloud.aws.region.static}")
    private lateinit var region: String

    @Bean
    fun ec2Client(): Ec2Client {
        // AWS 계정 정보를 설정합니다.
        val credentials = AwsBasicCredentials.create(accessKey, secretKey)
        
        // 설정된 정보로 EC2 접속 클라이언트를 생성하여 스프링 빈(Bean)으로 등록합니다.
        return Ec2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .build()
    }
}