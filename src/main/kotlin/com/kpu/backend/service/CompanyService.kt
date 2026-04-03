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
import software.amazon.awssdk.services.elasticloadbalancingv2.model.PriorityInUseException
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

    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        val safeName = req.name.replace(Regex("[^a-zA-Z0-9-]"), "-").lowercase()

        val tgNameOtel = "$safeName-otel".take(32)
        val tgArnOtel = createTargetGroup(tgNameOtel, 4318, "/v1/metrics")
        createAlbRule(tgArnOtel, monitoringId, "/v1/*")

        val tgNameProm = "$safeName-prom".take(32)
        val tgArnProm = createTargetGroup(tgNameProm, 9090, "/-/healthy")
        createAlbRule(tgArnProm, monitoringId, "/api/*")

        val tgNameLoki = "$safeName-loki".take(32)
        val tgArnLoki = createTargetGroup(tgNameLoki, 3100, "/ready")
        createAlbRule(tgArnLoki, monitoringId, "/loki/*")

        val instanceId = launchInstance(req.name)
        waitForInstanceRunning(instanceId)

        registerTarget(tgArnOtel, instanceId, 4318)
        registerTarget(tgArnProm, instanceId, 9090)
        registerTarget(tgArnLoki, instanceId, 3100)

        return saveCompany(req, monitoringId)
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String): Company {
        val nextId = companyRepository.findMaxId() + 1
        val company = Company(
            name = req.name, email = req.email, password = req.password,
            phone = req.phone, ip = req.ip, monitoringId = monitoringId,
            collectorUrl = albDnsName
        )
        company.id = nextId
        return companyRepository.save(company)
    }

    @Transactional
    fun deleteCompany(companyId: Long) {
        companyRepository.deleteById(companyId)
        val companies = companyRepository.findAllByIdGreaterThan(companyId)
        companies.forEach { company ->
            company.id = company.id - 1
            companyRepository.save(company)
        }
    }

    fun login(req: LoginRequest): Pair<Long, String>? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        return if (company.password == req.password) Pair(company.id, company.name) else null
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow { Exception("Company Not Found") }
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:4318")
    }

    private fun createTargetGroup(name: String, port: Int, path: String): String {
        val response = albClient.createTargetGroup { 
            it.name(name).protocol(ProtocolEnum.HTTP).port(port).vpcId(vpcId)
              .targetType(TargetTypeEnum.INSTANCE).healthCheckPath(path)
              .matcher { m -> m.httpCode("200-499") }
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, monitoringId: String, pathPattern: String) {
        var searchStart = 100
        repeat(20) {
            val priority = findNextAvailablePriority(searchStart)
            try {
                albClient.createRule { req ->
                    req.listenerArn(listenerArn).priority(priority)
                        .conditions(
                            { c -> c.field("http-header").httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(monitoringId) } },
                            { c -> c.field("path-pattern").pathPatternConfig { p -> p.values(pathPattern) } }
                        )
                        .actions({ a -> a.type("forward").targetGroupArn(tgArn) })
                }
                return
            } catch (_: PriorityInUseException) {
                searchStart = priority + 1
            }
        }
        throw IllegalStateException("Failed to allocate unique ALB rule priority after multiple retries")
    }

    private fun findNextAvailablePriority(startFrom: Int): Int {
        val usedPriorities = albClient.describeRules { it.listenerArn(listenerArn) }
            .rules().mapNotNull { rule -> rule.priority().toIntOrNull() }.toSet()

        var candidate = startFrom
        while (candidate in usedPriorities) {
            candidate++
        }
        return candidate
    }

    private fun getUserDataScript(): String {
    val script = """
        #!/bin/bash
        mkdir -p /opt/monitoring
        cd /opt/monitoring

        # 1. 알림 규칙 설정
        cat << 'EOF' > alert.rules.yml
        groups:
          - name: resource_alerts
            rules:
              - alert: HighCpuUsage
                expr: system_cpu_usage > 80
                for: 1m
                labels:
                  severity: critical
                annotations:
                  summary: "CPU 과부하 감지"
                  description: "CPU 사용량이 80%를 초과했습니다."
        EOF

        # 2. Alertmanager 설정
        cat << 'EOF' > alertmanager.yml
        route:
          receiver: 'central-backend'
          group_wait: 10s
          repeat_interval: 1h

        receivers:
          - name: 'central-backend'
            webhook_configs:
              - url: 'http://api.monitor.com/api/alerts/webhook' # 중앙 서버 도메인/IP
        EOF

        # 3. OTel Config
        cat << 'EOF' > otel-config.yaml
        # ... (기존 내용 유지) ...
        EOF
        
        # 4. Prometheus 설정
        cat << 'EOF' > prometheus.yml
        global:
          scrape_interval: 5s

        alerting:
          alertmanagers:
            - static_configs:
                - targets: ["alertmanager:9093"]

        rule_files:
          - "alert.rules.yml"

        scrape_configs:
          - job_name: "otel-collector"
            static_configs:
              - targets: ["otel-collector:8889"]
            honor_labels: true
        EOF
        
        # 5. Docker Compose
        cat << 'EOF' > docker-compose.yml
        version: "3.9"
        services:
          loki:
            image: grafana/loki:2.9.4
            container_name: loki
            ports: ["3100:3100"]
            volumes: ["loki-data:/loki"]

          alertmanager:
            image: prom/alertmanager:v0.25.0
            container_name: alertmanager
            ports: ["9093:9093"]
            volumes: ["./alertmanager.yml:/etc/alertmanager/alertmanager.yml"]
            command: ["--config.file=/etc/alertmanager/alertmanager.yml"]

          prometheus:
            image: prom/prometheus:v2.50.0
            container_name: prometheus
            ports: ["9090:9090"]
            volumes:
              - ./prometheus.yml:/etc/prometheus/prometheus.yml
              - ./alert.rules.yml:/etc/prometheus/alert.rules.yml
            depends_on: ["alertmanager"]

          otel-collector:
            image: otel/opentelemetry-collector-contrib:0.98.0
            container_name: otel-collector
            ports: ["4318:4318", "8889:8889"]
            volumes: ["./otel-config.yaml:/etc/otel/config.yaml"]
            command: ["--config=/etc/otel/config.yaml"]
            depends_on: ["loki", "prometheus"]

        volumes:
          loki-data:
        EOF
        
        chmod 644 /opt/monitoring/*
        docker-compose up -d
    """.trimIndent()
    return Base64.getEncoder().encodeToString(script.toByteArray())
}

    private fun launchInstance(companyName: String): String {
        val tagSpec = TagSpecification.builder()
            .resourceType(ResourceType.INSTANCE)
        .tags(Tag.builder().key("Name").value(companyName).build()).build()

        val response = ec2Client.runInstances { req ->
            req.imageId(amiId).instanceType(InstanceType.T3_SMALL).maxCount(1).minCount(1)
                .subnetId(subnetId).securityGroupIds(sgId).tagSpecifications(tagSpec).userData(getUserDataScript())
        }
        return response.instances()[0].instanceId()
    }

    private fun waitForInstanceRunning(instanceId: String) {
        ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
    }

    private fun registerTarget(tgArn: String, instanceId: String, port: Int) {
        albClient.registerTargets { it.targetGroupArn(tgArn).targets({ t -> t.id(instanceId).port(port) }) }
    }
}