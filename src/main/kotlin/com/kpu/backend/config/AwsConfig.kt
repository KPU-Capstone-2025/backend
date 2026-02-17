package com.kpu.monitor.config

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

    @Value("\${cloud.aws.credentials.access-key}")
    private lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key}")
    private lateinit var secretKey: String

    @Value("\${cloud.aws.region.static}")
    private lateinit var region: String

    private fun getProvider(): StaticCredentialsProvider {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
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