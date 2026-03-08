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

    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        val safeIp = req.ip.replace(".", "-")
        
        val companyCount = companyRepository.count().toInt()
        val basePriority = 100 + (companyCount * 10) 

        val tgNameOtel = "$safeIp-o-${monitoringId.takeLast(4)}".take(32)
        val tgArnOtel = createTargetGroup(tgNameOtel, 4318, "/v1/metrics") 
        createAlbRule(tgArnOtel, monitoringId, basePriority + 1, "/v1/*")

        val tgNameProm = "$safeIp-p-${monitoringId.takeLast(4)}".take(32)
        val tgArnProm = createTargetGroup(tgNameProm, 9090, "/-/healthy")
        createAlbRule(tgArnProm, monitoringId, basePriority + 2, "/api/*")

        val instanceId = launchInstance(monitoringId)
        waitForInstanceRunning(instanceId)

        registerTarget(tgArnOtel, instanceId, 4318)
        registerTarget(tgArnProm, instanceId, 9090)

        return saveCompany(req, monitoringId, instanceId)
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String, instanceId: String): Company {
        val company = Company(
            name = req.name, email = req.email, password = req.password,
            phone = req.phone, ip = req.ip, monitoringId = monitoringId,
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

    private fun createTargetGroup(name: String, port: Int, path: String): String {
        val response = albClient.createTargetGroup { 
            it.name(name).protocol(ProtocolEnum.HTTP).port(port).vpcId(vpcId)
              .targetType(TargetTypeEnum.INSTANCE).healthCheckPath(path)
              .matcher { m -> m.httpCode("200-499") }
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, monitoringId: String, priority: Int, pathPattern: String) {
        albClient.createRule { req ->
            req.listenerArn(listenerArn).priority(priority)
                .conditions(
                    { c -> c.field("http-header").httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(monitoringId) } },
                    { c -> c.field("path-pattern").pathPatternConfig { p -> p.values(pathPattern) } }
                )
                .actions({ a -> a.type("forward").targetGroupArn(tgArn) })
        }
    }

    private fun getUserDataScript(): String {
        val script = """
            #!/bin/bash
            mkdir -p /opt/monitoring
            cd /opt/monitoring
            
            # 🌟 OTel 설정 복구: 에이전트가 보내는 복잡한 컨테이너 라벨들을 전부 수용하도록 설정!
            cat << 'EOF' > otel-config.yaml
            receivers:
              otlp:
                protocols:
                  grpc:
                    endpoint: "0.0.0.0:4317"
                  http:
                    endpoint: "0.0.0.0:4318"
                    cors:
                      allowed_origins: ["*"]
            processors:
              batch: {}
            exporters:
              prometheus:
                endpoint: "0.0.0.0:8889"
                # 🌟 핵심: 컨테이너 이름표(Resource Attributes)를 프로메테우스 라벨로 변환하여 살려냄!
                resource_to_telemetry_conversion:
                  enabled: true
            service:
              pipelines:
                metrics:
                  receivers: [otlp]
                  processors: [batch]
                  exporters: [prometheus]
            EOF
            
            cat << 'EOF' > prometheus.yml
            global:
              scrape_interval: 5s
            scrape_configs:
              - job_name: 'otel-collector'
                static_configs:
                  - targets: ['otel-collector:8889']
                # 🌟 추가: 수집된 라벨 이름 충돌을 방지 (명확하게 저장)
                honor_labels: true
            EOF
            
            cat << 'EOF' > docker-compose.yml
            version: '3.8'
            services:
              prometheus:
                image: prom/prometheus:latest
                restart: always
                volumes:
                  - ./prometheus.yml:/etc/prometheus/prometheus.yml
                ports:
                  - "9090:9090"
              otel-collector:
                image: otel/opentelemetry-collector-contrib:latest
                restart: always
                volumes:
                  - ./otel-config.yaml:/etc/otel/config.yaml
                command: ["--config=/etc/otel/config.yaml"]
                ports:
                  - "4317:4317"
                  - "4318:4318"
                  - "8889:8889"
            EOF
            
            chmod 644 /opt/monitoring/*
            docker-compose up -d
        """.trimIndent()
        return Base64.getEncoder().encodeToString(script.toByteArray())
    }

    private fun launchInstance(monitoringId: String): String {
        val tagSpec = TagSpecification.builder()
            .resourceType(ResourceType.INSTANCE)
            .tags(Tag.builder().key("Name").value(monitoringId).build()).build()

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