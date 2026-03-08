package com.kpu.backend.service

import com.kpu.backend.dto.*
import com.kpu.backend.entity.Company
import com.kpu.backend.repository.CompanyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum
import java.util.*

@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val ec2Client: Ec2Client,
    private val albClient: ElasticLoadBalancingV2Client,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String,
    @Value("\${aws.alb.listener.arn}") private val listenerArn: String,
    @Value("\${aws.ami.id}") private val amiId: String,
    @Value("\${aws.vpc.id}") private val vpcId: String,
    @Value("\${aws.subnet.private.id}") private val subnetId: String,
    @Value("\${aws.sg.monitoring.id}") private val sgId: String
) {

    @Transactional
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        // 1. 고유 식별자 생성
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        val safeIp = req.ip.replace(".", "-")

        // 2. 타겟 그룹 생성 (prefix "tg-" 제거)
        val tgArn = createTargetGroup(safeIp, 4318, "/")

        // 3. ALB 규칙 생성
        val priority = (companyRepository.count() * 5).toInt() + 100
        createAlbRule(tgArn, monitoringId, priority)

        // 4. 인스턴스 생성 (prefix "Mon-" 제거)
        val instanceId = launchInstance(monitoringId)

        // 🌟 [핵심 해결] 인스턴스가 Running 상태가 될 때까지 최대 1분간 대기
        waitForInstanceRunning(instanceId)

        // 5. 타겟 그룹에 인스턴스 등록 (이제 에러가 나지 않습니다)
        registerTarget(tgArn, instanceId, 4318)

        val company = Company(
            name = req.name,
            email = req.email,
            password = req.password,
            phone = req.phone,
            ip = req.ip,
            monitoringId = monitoringId,
            collectorUrl = albDnsName
        )
        return companyRepository.save(company)
    }

    fun login(req: LoginRequest): Long? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        return if (company.password == req.password) company.id else null
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow { Exception("Company Not Found") }
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:4318")
    }

    // --- AWS 내부 로직 ---

    private fun createTargetGroup(name: String, port: Int, path: String): String {
        val response = albClient.createTargetGroup { 
            it.name(name)
              .protocol(ProtocolEnum.HTTP)
              .port(port)
              .vpcId(vpcId)
              .targetType(TargetTypeEnum.INSTANCE)
              .healthCheckPath(path)
              .matcher { m -> m.httpCode("200-499") } // 헬스체크 조건 완화
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, monitoringId: String, priority: Int) {
        albClient.createRule { req ->
            req.listenerArn(listenerArn).priority(priority)
                .conditions(
                    { c -> c.field("http-header").httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(monitoringId) } },
                    { c -> c.field("path-pattern").pathPatternConfig { p -> p.values("/v1/*") } }
                )
                .actions({ a -> a.type("forward").targetGroupArn(tgArn) })
        }
    }

    private fun launchInstance(monitoringId: String): String {
        val tagSpec = TagSpecification.builder()
            .resourceType(ResourceType.INSTANCE)
            .tags(Tag.builder().key("Name").value(monitoringId).build()).build()

        val response = ec2Client.runInstances { req ->
            req.imageId(amiId)
                .instanceType(InstanceType.T3_SMALL) // 혹은 c7i 등 보유한 사양
                .maxCount(1).minCount(1)
                .subnetId(subnetId)
                .securityGroupIds(sgId)
                .tagSpecifications(tagSpec)
        }
        return response.instances()[0].instanceId()
    }

    private fun waitForInstanceRunning(instanceId: String) {
        // AWS SDK의 Waiter를 사용하여 Running 상태가 될 때까지 블로킹 대기합니다.
        ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
    }

    private fun registerTarget(tgArn: String, instanceId: String, port: Int) {
        albClient.registerTargets { 
            it.targetGroupArn(tgArn)
              .targets({ t -> t.id(instanceId).port(port) }) 
        }
    }
}