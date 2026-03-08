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

@Configuration
class AppConfig {

    @Value("\${cloud.aws.credentials.access-key}")
    lateinit var accessKey: String

    @Value("\${cloud.aws.credentials.secret-key}")
    lateinit var secretKey: String

    @Bean
    fun restTemplate(): RestTemplate = RestTemplate()

    private fun getCredentialsProvider(): StaticCredentialsProvider {
        val credentials = AwsBasicCredentials.create(accessKey, secretKey)
        return StaticCredentialsProvider.create(credentials)
    }

    @Bean
    fun ec2Client(): Ec2Client {
        return Ec2Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(getCredentialsProvider()) 
            .build()
    }

    @Bean
    fun albClient(): ElasticLoadBalancingV2Client {
        return ElasticLoadBalancingV2Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(getCredentialsProvider())
            .build()
    }
}