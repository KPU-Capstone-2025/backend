package com.kpu.backend.service

import com.kpu.backend.dto.ProvisioningRequest
import com.kpu.backend.entity.Company
import com.kpu.backend.repository.CompanyRepository

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// ▼▼▼ [핵심] 별(*) 대신 명시적으로 하나씩 임포트해서 충돌을 막습니다 ▼▼▼
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.InstanceType
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification

import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateRuleRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleCondition
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum

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
        println("🚀 [${request.companyName}] 프로비저닝 시작...")

        // 1. Target Group 생성
        val tgArn = createTargetGroup(request.companyId)
        println("✅ Target Group 생성 완료")

        // 2. ALB 규칙 추가 (Priority 자동 계산)
        val nextPriority = companyRepository.findMaxPriority() + 1
        val ruleArn = createAlbRule(tgArn, request.companyId, nextPriority)
        println("✅ ALB 규칙 추가 완료 (우선순위: $nextPriority)")

        // 3. EC2 생성 (데모 앱 자동 실행 포함)
        val instanceId = launchInstance(request.companyId)
        println("✅ EC2 생성 완료 ($instanceId)")

        // 4. Target Group에 EC2 등록
        registerTarget(tgArn, instanceId)
        println("✅ 타겟 등록 완료")

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

        val response = ec2Client.runInstances(runRequest)
        val instanceId = response.instances()[0].instanceId()

        // [핵심 추가] 인스턴스가 'Running' 상태가 될 때까지 기다립니다.
        println("⏳ EC2($instanceId)가 켜질 때까지 대기 중...")
        try {
            ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
            println("✅ EC2($instanceId) 실행 완료 (Running)")
        } catch (e: Exception) {
            println("⚠️ 대기 중 오류 발생 (무시하고 진행): ${e.message}")
        }

        return instanceId
    }

    private fun registerTarget(tgArn: String, instanceId: String) {
        val request = RegisterTargetsRequest.builder()
            .targetGroupArn(tgArn)
            .targets(TargetDescription.builder().id(instanceId).build())
            .build()
        
        albClient.registerTargets(request)
    }
}