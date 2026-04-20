package com.kpu.backend.domain.company

import com.kpu.backend.config.JwtUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${aws.alb.dns.name}") private val albDnsName: String,
    @Value("\${aws.ami.id}") private val amiId: String,
    @Value("\${aws.vpc.id}") private val vpcId: String,
    @Value("\${aws.subnet.private.id}") private val subnetId: String,
    @Value("\${aws.sg.monitoring.id}") private val sgId: String,
    @Value("\${aws.alb.listener.arn}") private val listenerArn: String,
    @Value("\${monitoring.alert-webhook-url:\${monitoring.backend-url}}") private val backendUrl: String,
    @Value("\${spring.profiles.active:default}") private val activeProfile: String
) {
    private val log = LoggerFactory.getLogger(CompanyService::class.java)

    @Transactional
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)

        if (activeProfile == "local") {
            log.info("[로컬 모드] AWS 인프라 자동 생성 로직을 건너뜁니다. DB에만 저장됩니다. monitoringId=$monitoringId")
            return saveCompany(req, monitoringId)
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
                        Tag.builder().key("Name").value("${req.name}-Server").build(),
                        Tag.builder().key("MonitoringId").value(monitoringId).build(),
                        Tag.builder().key("Role").value("Monitoring").build()
                    ).build()
                ).build()

            val instanceId = ec2Client.runInstances(runReq).instances().first().instanceId()
            ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(listOf(instanceId)) }

            val promTg = createTargetGroup(monitoringId + "-prom", 9090, "/-/healthy", instanceId)
            val lokiTg = createTargetGroup(monitoringId + "-loki", 3100, "/ready", instanceId)
            val otelTg = createTargetGroup(monitoringId + "-otel", 4318, "/", instanceId)

            val basePrio = (nextId * 10).toInt()
            createListenerRule(basePrio + 1, monitoringId, "/api/v1/*", promTg)
            createListenerRule(basePrio + 2, monitoringId, "/loki/*", lokiTg)
            createListenerRule(basePrio + 3, monitoringId, "*", otelTg)
        } catch (e: Exception) {
            throw RuntimeException("인프라 구성 실패: ${e.message}", e)
        }

        return saveCompany(req, monitoringId)
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String): Company {
        val nextId = companyRepository.findMaxId() + 1
        return companyRepository.save(
            Company(
                id = nextId, name = req.name, email = req.email,
                password = passwordEncoder.encode(req.password),
                phone = req.phone,
                monitoringId = monitoringId, collectorUrl = albDnsName
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

    private fun createListenerRule(priority: Int, monitoringId: String, path: String, tgArn: String) {
        val conditions = mutableListOf<RuleCondition>()
        conditions.add(
            RuleCondition.builder().field("http-header")
                .httpHeaderConfig { it.httpHeaderName("X-Server-Group").values(monitoringId) }
                .build()
        )
        if (path != "*") {
            conditions.add(
                RuleCondition.builder().field("path-pattern")
                    .pathPatternConfig { it.values(path) }
                    .build()
            )
        }

        albClient.createRule(
            CreateRuleRequest.builder()
                .listenerArn(listenerArn).priority(priority).conditions(conditions)
                .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build())
                .build()
        )
    }

    private fun buildInstallScript(monitoringId: String): String = """
        |#!/bin/bash
        |mkdir -p /opt/monitoring
        |cd /opt/monitoring
        |
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
        |cat << 'EOF' > alert.rules.yml
        |groups:
        |  - name: rules
        |    rules:
        |      - alert: HighCpuUsage
        |        expr: system_cpu_usage > 80
        |        for: 30s
        |        labels:
        |          severity: critical
        |          company_id: ${monitoringId}
        |        annotations:
        |          summary: "CPU 과부하 감지"
        |          description: "서버의 CPU 사용량이 80%를 초과했습니다."
        |      - alert: HighMemoryUsage
        |        expr: system_memory_usage > 85
        |        for: 30s
        |        labels:
        |          severity: critical
        |          company_id: ${monitoringId}
        |        annotations:
        |          summary: "메모리 과부하 감지"
        |          description: "서버의 메모리 사용량이 85%를 초과했습니다."
        |      - alert: HighDiskUsage
        |        expr: system_disk_usage > 90
        |        for: 30s
        |        labels:
        |          severity: critical
        |          company_id: ${monitoringId}
        |        annotations:
        |          summary: "디스크 용량 부족 감지"
        |          description: "서버의 디스크 사용량이 90%를 초과했습니다."
        |      - alert: HighNetworkTraffic
        |        expr: rate(system_network_rx_bytes[1m]) + rate(system_network_tx_bytes[1m]) > 10485760
        |        for: 30s
        |        labels:
        |          severity: warning
        |          company_id: ${monitoringId}
        |        annotations:
        |          summary: "네트워크 트래픽 급증 감지"
        |          description: "서버의 네트워크 트래픽이 임계치(10MB/s)를 초과했습니다."
        |EOF
        |
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
        |    default_labels_enabled:
        |      exporter: false
        |      job: true
        |      instance: true
        |      level: true
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
        |docker rm -f loki prometheus otel-collector alertmanager || true
        |docker run -d --name loki --network host grafana/loki:2.9.4
        |docker run -d --name alertmanager --network host -v /opt/monitoring/alertmanager.yml:/etc/alertmanager/alertmanager.yml prom/alertmanager:v0.27.0 --config.file=/etc/alertmanager/alertmanager.yml
        |docker run -d --name prometheus --network host -v /opt/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml -v /opt/monitoring/alert.rules.yml:/etc/prometheus/alert.rules.yml prom/prometheus:v2.50.0
        |docker run -d --name otel-collector --network host -v /opt/monitoring/otel-config.yaml:/etc/otel/config.yaml otel/opentelemetry-collector-contrib:0.98.0 --config=/etc/otel/config.yaml
    """.trimMargin()
}
