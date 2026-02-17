package com.kpu.backend.service

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*

@Service
class Ec2Service(private val ec2Client: Ec2Client) {

    // 매개변수 이름을 userId 대신 companyName으로 변경하여 통일성을 높였습니다.
    fun createSecureEc2(companyName: String, ip: String): String {
        // 1. 보안 그룹 생성 (수정된 매개변수 전달)
        val securityGroupId = createSecurityGroup(companyName)

        // 2. EC2 실행 시 자동 실행될 스크립트 
        val userDataScript = """
            #!/bin/bash
            apt-get update -y
            apt-get install -y openjdk-17-jdk git curl

            fallocate -l 2G /swapfile
            chmod 600 /swapfile
            mkswap /swapfile
            swapon /swapfile
            echo '/swapfile none swap sw 0 0' | tee -a /etc/fstab

            mkdir -p /home/ubuntu/demo
            cd /home/ubuntu/demo
            git clone https://github.com/KPU-Capstone-2025/backend_demo.git .
            
            chmod +x gradlew
            nohup ./gradlew bootRun > /home/ubuntu/server.log 2>&1 &
        """.trimIndent()

        val userDataEncoded = java.util.Base64.getEncoder().encodeToString(userDataScript.toByteArray())

        // 3. 인스턴스 생성 요청
        val runRequest = RunInstancesRequest.builder()
            .imageId("ami-0c9c942bd7bf113a2") // 서울 리전 Ubuntu 22.04 확인 필요
            .instanceType(InstanceType.T3_MICRO)
            .maxCount(1)
            .minCount(1)
            .securityGroupIds(securityGroupId)
            .userData(userDataEncoded)
            .tagSpecifications(
                TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value(companyName).build()) // 이제 에러 안 남!
                    .build()
            )
            .build()

        val response = ec2Client.runInstances(runRequest)
        return response.instances()[0].instanceId()
    }

    private fun createSecurityGroup(companyName: String): String {
        val groupName = "SG-$companyName-${System.currentTimeMillis()}"
        
        val createRequest = CreateSecurityGroupRequest.builder()
            .groupName(groupName)
            .description("Security group for $companyName monitoring demo")
            .build()

        val createResponse = ec2Client.createSecurityGroup(createRequest)
        val groupId = createResponse.groupId()

        val authorizeRequest = AuthorizeSecurityGroupIngressRequest.builder()
            .groupId(groupId)
            .ipPermissions(
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(22).toPort(22)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build(),
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(8081).toPort(8081)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build(),
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(8080).toPort(8080)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build()
            )
            .build()

        ec2Client.authorizeSecurityGroupIngress(authorizeRequest)
        return groupId
    }
}