package com.kpu.backend.domain.company

import com.kpu.backend.config.JwtUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*
import java.util.*

@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val ec2Client: Ec2Client,
    private val albClient: ElasticLoadBalancingV2Client,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val jwtUtil: JwtUtil,
    @Value("\${aws.alb.dns.name:}") private val albDnsName: String,
    @Value("\${aws.ami.id:}") private val amiId: String,
    @Value("\${aws.vpc.id:}") private val vpcId: String,
    @Value("\${aws.subnet.private.id:}") private val subnetId: String,
    @Value("\${aws.sg.monitoring.id:}") private val sgId: String,
    @Value("\${aws.alb.listener.arn:}") private val listenerArn: String,
    @Value("\${monitoring.alert-webhook-url:\${monitoring.backend-url}}") private val backendUrl: String,
    @Value("\${spring.profiles.active:default}") private val activeProfile: String
) {
    private val log = LoggerFactory.getLogger(CompanyService::class.java)

    @Transactional
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)

        if (activeProfile == "local") {
            log.info("[로컬 모드] AWS 인프라 자동 생성 로직을 건너뜁니다. monitoringId=$monitoringId")
            return saveCompany(req, monitoringId, monitoringIp = "localhost")
        }

        val nextId = companyRepository.findMaxId() + 1

        try {
            val installScript = buildInstallScript(monitoringId)
            val encodedUserData = Base64.getEncoder().encodeToString(installScript.toByteArray())

            val runReq = RunInstancesRequest.builder()
                .imageId(amiId).instanceType(InstanceType.T3_SMALL).minCount(1).maxCount(1)
                .subnetId(subnetId).securityGroupIds(sgId).userData(encodedUserData)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("Monitoring-EC2-Role").build())
                .tagSpecifications(
                    TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(
                        Tag.builder().key("Name").value("${req.name}-Monitoring").build(),
                        Tag.builder().key("MonitoringId").value(monitoringId).build(),
                        Tag.builder().key("Role").value("Monitoring").build()
                    ).build()
                ).build()

            val instanceId = ec2Client.runInstances(runReq).instances().first().instanceId()
            ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(listOf(instanceId)) }

            val privateIp = ec2Client.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build()
            ).reservations().first().instances().first().privateIpAddress()

            val otelTg = createTargetGroup(monitoringId, 4318, "/", instanceId)
            createListenerRule((nextId * 10).toInt(), monitoringId, otelTg)

            return saveCompany(req, monitoringId, monitoringIp = privateIp)
        } catch (e: Exception) {
            throw RuntimeException("인프라 구성 실패: ${e.message}", e)
        }
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String, monitoringIp: String? = null): Company {
        val nextId = companyRepository.findMaxId() + 1
        return companyRepository.save(
            Company(
                id = nextId,
                name = req.name,
                email = req.email,
                password = passwordEncoder.encode(req.password),
                phone = req.phone,
                monitoringId = monitoringId,
                collectorUrl = albDnsName,
                ip = monitoringIp,
                serverIp = req.ip ?: ""
            )
        )
    }

    fun login(req: LoginRequest): LoginResponse? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        if (!passwordEncoder.matches(req.password, company.password)) return null
        val token = jwtUtil.generateToken(company.id, company.monitoringId)
        return LoginResponse(company.id, company.name, company.monitoringId, token)
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow()
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:80")
    }

    private fun createTargetGroup(name: String, port: Int, path: String, instanceId: String): String {
        val tgArn = albClient.createTargetGroup(
            CreateTargetGroupRequest.builder()
                .name(name).port(port).protocol(ProtocolEnum.HTTP).vpcId(vpcId)
                .targetType(TargetTypeEnum.INSTANCE)
                .healthCheckPath(path).matcher(Matcher.builder().httpCode("200-499").build())
                .build()
        ).targetGroups().first().targetGroupArn()

        albClient.registerTargets(
            RegisterTargetsRequest.builder()
                .targetGroupArn(tgArn)
                .targets(TargetDescription.builder().id(instanceId).port(port).build())
                .build()
        )
        return tgArn
    }

    private fun createListenerRule(priority: Int, monitoringId: String, tgArn: String) {
        albClient.createRule(
            CreateRuleRequest.builder()
                .listenerArn(listenerArn).priority(priority)
                .conditions(
                    RuleCondition.builder().field("http-header")
                        .httpHeaderConfig { it.httpHeaderName("X-Server-Group").values(monitoringId) }.build()
                )
                .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build())
                .build()
        )
    }

    private fun buildInstallScript(monitoringId: String): String {
        val prometheus   = readResource("monitoring/prometheus.yml")
        val alertmanager = readResource("monitoring/alertmanager.yml")
            .replace("{{BACKEND_URL}}", backendUrl)
            .replace("{{MONITORING_ID}}", monitoringId)
        val alertRules   = readResource("monitoring/alert.rules.yml")
            .replace("{{MONITORING_ID}}", monitoringId)
        val otelConfig   = readResource("monitoring/otel-config.yaml")
        val compose      = readResource("monitoring/docker-compose.yml")

        return buildString {
            appendLine("#!/bin/bash")
            appendLine("mkdir -p /opt/monitoring")
            appendLine("cd /opt/monitoring")
            appendLine()
            appendFileBlock("prometheus.yml", prometheus)
            appendFileBlock("alertmanager.yml", alertmanager)
            appendFileBlock("alert.rules.yml", alertRules)
            appendFileBlock("otel-config.yaml", otelConfig)
            appendFileBlock("docker-compose.yml", compose)
            appendLine("docker compose up -d")
        }
    }

    private fun readResource(path: String): String =
        ClassPathResource(path).inputStream.bufferedReader().readText()

    private fun StringBuilder.appendFileBlock(filename: String, content: String) {
        appendLine("cat << 'SCRIPT_EOF' > $filename")
        append(content)
        if (!content.endsWith("\n")) appendLine()
        appendLine("SCRIPT_EOF")
        appendLine()
    }
}
