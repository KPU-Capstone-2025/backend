package com.kpu.backend.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*

@Service
class Ec2Service(
    // application.properties에서 키를 가져옴
    @Value("\${aws.accessKey}") private val accessKey: String,
    @Value("\${aws.secretKey}") private val secretKey: String,
    @Value("\${aws.region}") private val regionString: String
) {

    // AWS 클라이언트
    private val ec2Client: Ec2Client by lazy {
        Ec2Client.builder()
            .region(Region.of(regionString))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .build()
    }

    fun createMyEc2(userId: String): String {
        println("[$userId] 님의 진짜 EC2 생성을 시작합니다...")

        val targetAmiId = "ami-0c9c942bd7bf113a2"

        try {
            // 1. 보안 그룹 ID 찾기 (기본 VPC의 default 그룹 사용)
            // (나중에 포트 8080 열어주는 보안 그룹 생성 로직도 추가해야 함)
            
            // 2. EC2 생성 요청
            val request = RunInstancesRequest.builder()
                .imageId(targetAmiId)
                .instanceType(InstanceType.T3_MICRO) // 프리티어 무료
                .maxCount(1)
                .minCount(1)
                .tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(
                        Tag.builder().key("Name").value("User-Server-$userId").build(),
                        Tag.builder().key("Owner").value(userId).build(),
                        Tag.builder().key("CreatedBy").value("CapstoneBackend").build()
                    )
                    .build())
                .build()

            val response = ec2Client.runInstances(request)
            val instanceId = response.instances()[0].instanceId()

            println("성공! 인스턴스 ID: $instanceId")
            
            return """
                [EC2 생성 성공]
                사용자: $userId
                인스턴스 ID: $instanceId
                상태: 생성 중 (Pending) -> 잠시 후 Running 됩니다.
                AWS 콘솔에서 확인해보세요!
            """.trimIndent()

        } catch (e: Exception) {
            e.printStackTrace()
            return "실패했습니다 에러: ${e.message}"
        }
    }
}