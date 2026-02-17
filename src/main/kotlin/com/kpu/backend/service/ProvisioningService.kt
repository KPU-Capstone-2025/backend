package com.kpu.backend.service

import com.kpu.backend.dto.ProvisioningRequest
import com.kpu.backend.entity.Company
import com.kpu.backend.repository.CompanyRepository

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

    private val centralGatewayPrivateIp = "10.0.100.194"

    @Transactional
    fun provision(request: ProvisioningRequest): Company {
        println("🚀 [${request.companyName}] 인프라 생성 및 회원가입 시작...")

        // 1. Target Group 생성
        val tgArn = createTargetGroup(request.companyId)
        
        // 2. ALB 우선순위 결정 (100번부터 시작하여 충돌 방지)
        val currentMax = companyRepository.findMaxPriority()
        val nextPriority = if (currentMax < 100) 101 else currentMax + 1
        
        // 3. ALB 규칙 생성
        val ruleArn = createAlbRule(tgArn, request.companyId, nextPriority)

        // 4. 회사 전용 EC2 인스턴스 실행 (모니터링 에이전트 자동 설치)
        val instanceId = launchInstance(request.companyId)

        // 5. Target Group에 인스턴스 등록
        registerTarget(tgArn, instanceId)

        // 6. DB에 모든 가입 정보와 인프라 정보를 합쳐서 저장
        val newCompany = Company(
            name = request.name,
            phone = request.phone,
            email = request.email,
            password = request.password,
            companyName = request.companyName,
            companyId = request.companyId,
            instanceId = instanceId,
            targetGroupArn = tgArn,
            albRuleArn = ruleArn,
            priority = nextPriority
        )

        val saved = companyRepository.save(newCompany)
        println("[${request.companyName}] 가입 완료 (DB ID: ${saved.id})")
        return saved
    }

    private fun createTargetGroup(companyId: String): String {
        val response = albClient.createTargetGroup { 
            it.name("tg-$companyId")
              .protocol(ProtocolEnum.HTTP)
              .port(8081)
              .vpcId(vpcId)
              .targetType(TargetTypeEnum.INSTANCE)
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, companyId: String, priority: Int): String {
        val response = albClient.createRule {
            it.listenerArn(listenerArn)
              .priority(priority)
              .conditions(RuleCondition.builder()
                  .field("http-header")
                  .httpHeaderConfig { h -> h.httpHeaderName("X-Company-Id").values(companyId) }
                  .build())
              .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build())
        }
        return response.rules()[0].ruleArn()
    }

    private fun launchInstance(companyId: String): String {
        // 중앙 서버($centralGatewayPrivateIp)로 데이터를 쏘도록 UserData 수정
        val userDataScript = """
            #!/bin/bash
            apt-get update -y
            apt-get install -y openjdk-17-jdk git curl
            curl -L -o otelcol.deb https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.90.0/otelcol-contrib_0.90.0_linux_amd64.deb
            dpkg -i otelcol.deb

            cat <<EOF > /etc/otelcol-contrib/config.yaml
            receivers:
              otlp:
                protocols:
                  grpc:
                  http:
              hostmetrics:
                collection_interval: 10s
                scrapers:
                  cpu:
                  memory:
                  disk:
                  network:
            processors:
              batch:
              resource:
                attributes:
                  - key: company_id
                    value: $companyId
                    action: insert
            exporters:
              otlp:
                endpoint: "$centralGatewayPrivateIp:4317"
                tls:
                  insecure: true
            service:
              pipelines:
                metrics:
                  receivers: [hostmetrics, otlp]
                  processors: [resource, batch]
                  exporters: [otlp]
            EOF

            systemctl restart otelcol-contrib
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
        println("EC2($instanceId)가 켜질 때까지 대기 중...")
        try {
            ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
            println("EC2($instanceId) 실행 완료 (Running)")
        } catch (e: Exception) {
            println("대기 중 오류 발생 (무시하고 진행): ${e.message}")
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