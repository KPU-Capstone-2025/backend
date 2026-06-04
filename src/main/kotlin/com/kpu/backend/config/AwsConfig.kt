package com.kpu.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.ssm.SsmClient

@Configuration
class AwsConfig {

    @Value("\${cloud.aws.region.static}") private lateinit var region: String

    @Bean fun ec2Client(): Ec2Client =
        Ec2Client.builder().region(Region.of(region)).build()

    @Bean fun albClient(): ElasticLoadBalancingV2Client =
        ElasticLoadBalancingV2Client.builder().region(Region.of(region)).build()

    @Bean fun ssmClient(): SsmClient =
        SsmClient.builder().region(Region.of(region)).build()
}
