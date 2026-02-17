package com.kpu.monitor.service

import com.kpu.monitor.dto.ProvisioningRequest
import com.kpu.monitor.entity.Company
import com.kpu.monitor.repository.CompanyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*
import java.util.Base64

@Service
class ProvisioningService(
    private val ec2Client: Ec2Client,
    private val albClient: ElasticLoadBalancingV2Client,
    private val companyRepository: CompanyRepository
) {
    @Value("\${aws.vpc.id}") private lateinit var vpcId: String
    @Value("\${aws.alb.listener.arn}") private lateinit var listenerArn: String
    @Value("\${aws.subnet.private.id}") private lateinit var privateSubnetId: String
    @Value("\${aws.sg.monitoring.id}") private lateinit var monitoringSgId: String
    @Value("\${aws.ami.id}") private lateinit var amiId: String

    @Transactional
    fun provision(request: ProvisioningRequest): Company {
        println("[${request.companyName}] 프로비저닝 시작...")

        // 1. Target Group 생성
        val tgArn = createTargetGroup(request.companyId)
        println(" Target Group 생성 완료")

        // 2. ALB 규칙 추가 (Priority 자동 계산)
        val nextPriority = companyRepository.findMaxPriority() + 1
        val ruleArn = createAlbRule(tgArn, request.companyId, nextPriority)
        println(" ALB 규칙 추가 완료 (우선순위: $nextPriority)")

        // 3. EC2 생성 (데모 앱 자동 실행 포함)
        val instanceId = launchInstance(request.companyId)
        println("EC2 생성 완료 ($instanceId)")

        // 4. Target Group에 EC2 등록
        registerTarget(tgArn, instanceId)
        println(" 타겟 등록 완료")

        // 5. 결과 저장
        return companyRepository.save(Company(
            companyId = request.companyId,
            instanceId = instanceId,
            targetGroupArn = tgArn,
            albRuleArn = ruleArn,
            priority = nextPriority
        ))
    }

    private fun createTargetGroup(companyId: String): String {
        val request = CreateTargetGroupRequest.builder()
            .name("tg-$companyId")
            .protocol(ProtocolEnum.HTTP)
            .port(8081) // 데모 앱 포트
            .vpcId(vpcId)
            .healthCheckPath("/") // 데모 앱 메인 페이지로 헬스체크
            .targetType(TargetTypeEnum.INSTANCE)
            .build()
        
        return albClient.createTargetGroup(request).targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, companyId: String, priority: Int): String {
        val request = CreateRuleRequest.builder()
            .listenerArn(listenerArn)
            .priority(priority)
            .conditions(
                RuleCondition.builder()
                    .field("http-header")
                    .httpHeaderConfig { it.httpHeaderName("X-Company-Id").values(companyId) }
                    .build()
            )
            .actions(
                Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build()
            )
            .build()

        return albClient.createRule(request).rules()[0].ruleArn()
    }

    private fun launchInstance(companyId: String): String {
        // 깃허브에서 데모 앱을 받아와서 실행하는 스크립트
        val userDataScript = """
            #!/bin/bash
            apt-get update -y
            apt-get install -y openjdk-17-jdk git curl

            # 스왑 메모리 설정 (t3.micro 램 부족 방지)
            fallocate -l 2G /swapfile
            chmod 600 /swapfile
            mkswap /swapfile
            swapon /swapfile
            
            # 데모 앱 다운로드 및 실행
            mkdir -p /home/ubuntu/demo
            cd /home/ubuntu/demo
            git clone https://github.com/KPU-Capstone-2025/backend_demo.git .
            chmod +x gradlew
            
            # 백그라운드 실행
            nohup ./gradlew bootRun > /home/ubuntu/server.log 2>&1 &
        """.trimIndent()

        val encodedUserData = Base64.getEncoder().encodeToString(userDataScript.toByteArray())

        val runRequest = RunInstancesRequest.builder()
            .imageId(amiId)
            .instanceType(InstanceType.T3_MICRO)
            .maxCount(1).minCount(1)
            .subnetId(privateSubnetId)
            .securityGroupIds(monitoringSgId)
            .userData(encodedUserData)
            .tagSpecifications(
                TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value("Mon-$companyId").build())
                    .build()
            )
            .build()

        return ec2Client.runInstances(runRequest).instances()[0].instanceId()
    }

    private fun registerTarget(tgArn: String, instanceId: String) {
        // EC2가 생성되고 나서 바로 등록하면 Running상태가 아니라서 실패할 수 있어서
        // 실제로는 상태 체크 로직이 필요하지만, 여기선 바로 등록 시도
        // AWS가 알아서 Pending 상태인 인스턴스도 받음
        val request = RegisterTargetsRequest.builder()
            .targetGroupArn(tgArn)
            .targets(TargetDescription.builder().id(instanceId).build())
            .build()
        
        albClient.registerTargets(request)
    }
}