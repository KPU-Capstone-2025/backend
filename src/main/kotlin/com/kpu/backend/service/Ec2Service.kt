package com.kpu.backend.service

import org.springframework.stereotype.Service
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.ec2.model.*

@Service
class Ec2Service {

    private val accessKey = "MOCK_ACCESS_KEY" // 실제 키로 교체 필요
    private val secretKey = "MOCK_SECRET_KEY" // 실제 키로 교체 필요
    private val region = Region.AP_NORTHEAST_2

    private val ec2Client: Ec2Client by lazy {
        Ec2Client.builder()
            .region(region)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .build()
    }

    fun createMyEc2(userId: String): String {
        println("[$userId] 님의 EC2 생성을 시작합니다...")
        val targetAmiId = "ami-0c9c942bd7bf113a2"

        try {
            val request = RunInstancesRequest.builder()
                .imageId(targetAmiId)
                .instanceType(InstanceType.T2_MICRO)
                .maxCount(1)
                .minCount(1)
                .tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value("User-Server-$userId").build())
                    .build())
                .build()

            val response = ec2Client.runInstances(request)
            return "성공! ID: ${response.instances()[0].instanceId()}"

        } catch (e: Exception) {
            return "[MOCK MODE] $userId 님의 가상 EC2가 생성되었습니다. (ID: i-mock12345)"
        }
    }
}