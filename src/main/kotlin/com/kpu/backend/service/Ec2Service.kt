package com.kpu.backend.service

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*

@Service
class Ec2Service(private val ec2Client: Ec2Client) {

    fun createMonitoringTargetInstance(companyName: String): String {
        // 1. 보안 그룹 생성 및 포트 설정
        val securityGroupId = createSecurityGroup(companyName)

        // 2. EC2 실행 시 자동 실행될 스크립트 (UserData)
        // 깃허브가 Public으로 변경되었으므로 별도의 인증 없이 클론 가능합니다.
        val userDataScript = """
            #!/bin/bash
            # 기본 패키지 설치
            apt-get update -y
            apt-get install -y openjdk-17-jdk git curl

            # 스왑 메모리 2GB 설정 (t3.micro 메모리 부족 방지)
            fallocate -l 2G /swapfile
            chmod 600 /swapfile
            mkswap /swapfile
            swapon /swapfile
            echo '/swapfile none swap sw 0 0' | tee -a /etc/fstab

            # 데모 서버 다운로드 및 실행
            mkdir -p /home/ubuntu/demo
            cd /home/ubuntu/demo
            git clone https://github.com/KPU-Capstone-2025/backend_demo.git .
            
            chmod +x gradlew
            # 로그를 남기며 백그라운드에서 실행 (8081 포트)
            nohup ./gradlew bootRun > /home/ubuntu/server.log 2>&1 &
        """.trimIndent()

        val userDataEncoded = java.util.Base64.getEncoder().encodeToString(userDataScript.toByteArray())

        // 3. 인스턴스 생성 요청
        val runRequest = RunInstancesRequest.builder()
            .imageId("ami-0c9c942bd7bf113a2") // Ubuntu 22.04 LTS (서울 리전 확인 필요)
            .instanceType(InstanceType.T3_MICRO)
            .maxCount(1)
            .minCount(1)
            .securityGroupIds(securityGroupId)
            .userData(userDataEncoded)
            .tagSpecifications(
                TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value(companyName).build())
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

        // 인바운드 규칙 설정 (SSH, 데모웹, 모니터링 포트)
        val authorizeRequest = AuthorizeSecurityGroupIngressRequest.builder()
            .groupId(groupId)
            .ipPermissions(
                // [필수] 22번 포트 (SSH 접속용)
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(22).toPort(22)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build(),
                
                // [필수] 8081번 포트 (데모 웹사이트 접속용)
                IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(8081).toPort(8081)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build(),

                // [선택] 8080번 포트 (혹시 모를 메인 통신용)
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