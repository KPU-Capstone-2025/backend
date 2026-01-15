package com.kpu.backend.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import java.util.Base64

@Service
class Ec2Service(
    @Value("\${aws.accessKey}") private val accessKey: String,
    @Value("\${aws.secretKey}") private val secretKey: String,
    @Value("\${aws.region}") private val regionString: String
) {
    private val ec2Client: Ec2Client by lazy {
        Ec2Client.builder()
            .region(Region.of(regionString))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .build()
    }

    fun createSecureEc2(companyName: String, companyIp: String): String {
        try {
            // 1. 보안 그룹 생성
            val securityGroupId = createSecurityGroup(companyName, companyIp)
            println("✅ 보안 그룹 생성 완료: $securityGroupId")

            // 2. 우분투(Ubuntu)용 설치 스크립트
            val userDataScript = """
                #!/bin/bash
                apt-get update -y
                apt-get install -y docker.io
                systemctl start docker
                systemctl enable docker
                usermod -aG docker ubuntu
                
                # 그라파나 실행 (포트 3000)
                docker run -d -p 3000:3000 --name grafana grafana/grafana
                
                # Node Exporter 실행 (포트 9100)
                docker run -d -p 9100:9100 --name node-exporter prom/node-exporter
            """.trimIndent()
            
            val userDataBase64 = Base64.getEncoder().encodeToString(userDataScript.toByteArray())

            // 3. EC2 생성 요청
            val runRequest = RunInstancesRequest.builder()
                .imageId("ami-0c9c942bd7bf113a2")  // Ubuntu 22.04 LTS AMI
                .instanceType(InstanceType.T3_MICRO)
                .maxCount(1)
                .minCount(1)
                .securityGroupIds(securityGroupId)
                .userData(userDataBase64)
                .tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(
                        Tag.builder().key("Name").value(companyName).build(),
                        Tag.builder().key("Type").value("Customer-Server").build()
                    )
                    .build())
                .build()

            val response = ec2Client.runInstances(runRequest)
            val instanceId = response.instances()[0].instanceId()
            println("✅ EC2 인스턴스 생성 완료: $instanceId")
            return instanceId

        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("EC2 생성 실패: ${e.message}")
        }
    }

    private fun createSecurityGroup(companyName: String, allowedIp: String): String {
        val groupName = "SG-$companyName-${System.currentTimeMillis()}"
        
        val createRequest = CreateSecurityGroupRequest.builder()
            .groupName(groupName)
            .description("Security Group for $companyName")
            .build()
        val groupId = ec2Client.createSecurityGroup(createRequest).groupId()

        val cidrIp = if (allowedIp.contains("/")) allowedIp else "$allowedIp/32"

        val ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
            .groupId(groupId)
            .ipPermissions(
                // 그라파나 (포트 3000)
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(3000).toPort(3000)
                    .ipRanges(IpRange.builder().cidrIp(cidrIp).build())
                    .build(),
                // Node Exporter (포트 9100)
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(9100).toPort(9100)
                    .ipRanges(IpRange.builder().cidrIp(cidrIp).build())
                    .build()
            )
            .build()
        
        ec2Client.authorizeSecurityGroupIngress(ingressRequest)
        return groupId
    }
}