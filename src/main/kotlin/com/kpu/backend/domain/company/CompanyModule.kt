package com.kpu.backend.domain.company

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*
import java.time.LocalDateTime
import java.util.*

/* --- DTO & Entity (변경 없음) --- */
data class CompanyRegisterRequest(val name: String, val email: String, val password: String, val ip: String, val phone: String)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val id: Long, val name: String, val monitoringId: String)
data class AgentDestination(
    val monitoringId: String,
    @JsonProperty("collector_url") val collectorUrl: String
)

@Entity
@Table(name = "companies")
class Company(
    @Id var id: Long = 0,
    val name: String,
    @Column(unique = true) val email: String,
    val password: String,
    val phone: String,
    val ip: String,
    val monitoringId: String,
    val collectorUrl: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByEmail(email: String): Company?
    fun findByMonitoringId(monitoringId: String): Company?
    @Query("SELECT COALESCE(MAX(c.id), 0) FROM Company c") fun findMaxId(): Long
}

@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val ec2Client: Ec2Client,
    private val albClient: ElasticLoadBalancingV2Client,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String,
    @Value("\${aws.ami.id}") private val amiId: String,
    @Value("\${aws.vpc.id}") private val vpcId: String,
    @Value("\${aws.subnet.private.id}") private val subnetId: String,
    @Value("\${aws.sg.monitoring.id}") private val sgId: String,
    @Value("\${aws.alb.listener.arn}") private val listenerArn: String,
    @Value("\${monitoring.alert-webhook-url:\${monitoring.backend-url}}") private val backendUrl: String
) {
    @Transactional
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        val nextId = companyRepository.findMaxId() + 1

        try {
            val installScript = """
                |#!/bin/bash
                |mkdir -p /opt/monitoring
                |cd /opt/monitoring
                |
                |# Prometheus 설정 (OTel 8889 포트에서 데이터를 긁어감)
                |cat << 'EOF' > prometheus.yml
                |global:
                |  scrape_interval: 5s
                |  evaluation_interval: 10s
                |rule_files:
                |  - /etc/prometheus/alert.rules.yml
                |alerting:
                |  alertmanagers:
                |    - static_configs:
                |        - targets: ['localhost:9093']
                |scrape_configs:
                |  - job_name: 'otel-collector'
                |    static_configs:
                |      - targets: ['localhost:8889']
                |    honor_labels: true
                |EOF
                |
                |# Alertmanager 설정 (기업별 webhook 경로로 백엔드 전달)
                |cat << 'EOF' > alertmanager.yml
                |route:
                |  receiver: backend-webhook
                |  group_wait: 10s
                |  group_interval: 30s
                |  repeat_interval: 2m
                |receivers:
                |  - name: backend-webhook
                |    webhook_configs:
                |      - url: "${backendUrl}/api/alerts/webhook/${monitoringId}"
                |        send_resolved: true
                |EOF
                |
                |# 기본 알람 룰 파일 생성 (초기값)
                |cat << 'EOF' > alert.rules.yml
                |groups:
                |  - name: rules
                |    rules:
                |      - alert: HighCpuUsage
                |        expr: (system_cpu_usage * 100) > 80
                |        for: 30s
                |        labels:
                |          severity: critical
                |          company_id: ${monitoringId}
                |        annotations:
                |          summary: "CPU 과부하 감지"
                |          description: "CPU 사용률이 80%를 초과했습니다."
                |EOF
                |
                |# OTel 설정 (4318에서 받고, 8889로 내보냄)
                |cat << 'EOF' > otel-config.yaml
                |receivers:
                |  otlp:
                |    protocols:
                |      http:
                |        endpoint: "0.0.0.0:4318"
                |exporters:
                |  prometheus:
                |    endpoint: "0.0.0.0:8889"
                |    resource_to_telemetry_conversion:
                |      enabled: true
                |  loki:
                |    endpoint: "http://localhost:3100/loki/api/v1/push"
                |service:
                |  pipelines:
                |    metrics:
                |      receivers: [otlp]
                |      exporters: [prometheus]
                |    logs:
                |      receivers: [otlp]
                |      exporters: [loki]
                |EOF
                |
                |# 컨테이너 실행 (호스트 네트워크 모드로 통신 병목 해결)
                |docker rm -f loki prometheus otel-collector alertmanager || true
                |docker run -d --name loki --network host grafana/loki:2.9.4
                |docker run -d --name alertmanager --network host -v /opt/monitoring/alertmanager.yml:/etc/alertmanager/alertmanager.yml prom/alertmanager:v0.27.0 --config.file=/etc/alertmanager/alertmanager.yml
                |docker run -d --name prometheus --network host -v /opt/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml -v /opt/monitoring/alert.rules.yml:/etc/prometheus/alert.rules.yml prom/prometheus:v2.50.0
                |docker run -d --name otel-collector --network host -v /opt/monitoring/otel-config.yaml:/etc/otel/config.yaml otel/opentelemetry-collector-contrib:0.98.0 --config=/etc/otel/config.yaml
            """.trimMargin()

            val encodedUserData = Base64.getEncoder().encodeToString(installScript.toByteArray())

            //EC2 생성
            val runReq = RunInstancesRequest.builder()
                .imageId(amiId).instanceType(InstanceType.T3_SMALL).minCount(1).maxCount(1)
                .subnetId(subnetId).securityGroupIds(sgId).userData(encodedUserData)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("Monitoring-EC2-Role").build())
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(
                    Tag.builder().key("Name").value("${req.name}-Server").build(),
                    Tag.builder().key("MonitoringId").value(monitoringId).build(),
                    Tag.builder().key("Role").value("Monitoring").build()
                ).build()).build()

            val instanceId = ec2Client.runInstances(runReq).instances().first().instanceId()
            ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(listOf(instanceId)) }

            //대상 그룹 생성 및 등록
            val promTg = createTg(monitoringId + "-prom", 9090, "/-/healthy", instanceId)
            val lokiTg = createTg(monitoringId + "-loki", 3100, "/ready", instanceId)
            val otelTg = createTg(monitoringId + "-otel", 4318, "/", instanceId)

            //규칙 생성
            val basePrio = (nextId * 10).toInt()
            createRule(basePrio + 1, monitoringId, "/api/v1/*", promTg)
            createRule(basePrio + 2, monitoringId, "/loki/*", lokiTg)
            createRule(basePrio + 3, monitoringId, "*", otelTg)

        } catch (e: Exception) {
            throw RuntimeException("인프라 구성 실패: \${e.message}")
        }

        return saveCompany(req, monitoringId)
    }

    private fun createTg(name: String, port: Int, path: String, instanceId: String): String {
        val tgReq = CreateTargetGroupRequest.builder()
            .name(name).port(port).protocol(ProtocolEnum.HTTP).vpcId(vpcId).targetType(TargetTypeEnum.INSTANCE)
            .healthCheckPath(path).matcher(Matcher.builder().httpCode("200-499").build()).build()
        val tgArn = albClient.createTargetGroup(tgReq).targetGroups().first().targetGroupArn()
        albClient.registerTargets(RegisterTargetsRequest.builder().targetGroupArn(tgArn).targets(TargetDescription.builder().id(instanceId).port(port).build()).build())
        return tgArn
    }

    private fun createRule(prio: Int, monId: String, path: String, tgArn: String) {
        val conds = mutableListOf<RuleCondition>()
        conds.add(RuleCondition.builder().field("http-header").httpHeaderConfig { it.httpHeaderName("X-Server-Group").values(monId) }.build())
        if (path != "*") conds.add(RuleCondition.builder().field("path-pattern").pathPatternConfig { it.values(path) }.build())

        val ruleReq = CreateRuleRequest.builder().listenerArn(listenerArn).priority(prio).conditions(conds)
            .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build()).build()
        albClient.createRule(ruleReq)
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String): Company {
        val nextId = companyRepository.findMaxId() + 1
        return companyRepository.save(Company(id = nextId, name = req.name, email = req.email, password = req.password, phone = req.phone, ip = req.ip, monitoringId = monitoringId, collectorUrl = albDnsName))
    }

    fun login(req: LoginRequest): Company? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        return if (company.password == req.password) company else null
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow()
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:80")
    }
}

@RestController
@RequestMapping("/api/company")
@CrossOrigin("*")
class CompanyController(private val companyService: CompanyService) {
    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val company = companyService.login(req) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(LoginResponse(company.id, company.name, company.monitoringId))
    }
    @PostMapping("/register")
    fun register(@RequestBody req: CompanyRegisterRequest): ResponseEntity<Map<String, String>> {
        companyService.registerAndProvision(req)
        return ResponseEntity.ok(mapOf("status" to "success"))
    }
    @GetMapping("/agent/{companyId}")
    fun getAgent(@PathVariable companyId: Long) = ResponseEntity.ok(companyService.getAgentInfo(companyId))
}