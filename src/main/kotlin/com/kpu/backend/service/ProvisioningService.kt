package com.kpu.backend.service

import com.kpu.backend.entity.Company
import com.kpu.backend.repository.CompanyRepository
import com.kpu.backend.dto.ProvisioningRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum
import java.util.Base64

@Service
class ProvisioningService(
    private val ec2Client: Ec2Client,
    private val elbClient: ElasticLoadBalancingV2Client,
    private val companyRepository: CompanyRepository,
    @Value("\${aws.ami.id}") private val amiId: String,
    @Value("\${aws.vpc.id}") private val vpcId: String,
    @Value("\${aws.subnet.private.id}") private val privateSubnetId: String,
    @Value("\${aws.sg.monitoring.id}") private val monitoringSgId: String,
    @Value("\${aws.alb.listener.arn}") private val listenerArn: String
) {

    @Transactional
    fun provision(request: ProvisioningRequest): Company {
        val tgArn = createTargetGroup(request.companyId)
        val currentMax = companyRepository.findMaxPriority() ?: 100
        val nextPriority = currentMax + 1
        val ruleArn = createAlbRule(tgArn, request.companyId, nextPriority)
        val instanceId = launchInstance(request.companyId)

        ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
        registerTarget(tgArn, instanceId)

        val newCompany = Company(
            name = request.name, phone = request.phone, email = request.email,
            password = request.password, companyName = request.companyName,
            companyId = request.companyId, instanceId = instanceId,
            targetGroupArn = tgArn, albRuleArn = ruleArn, priority = nextPriority
        )
        return companyRepository.save(newCompany)
    }

    private fun launchInstance(companyId: String): String {
        val userDataScript = """
            #!/bin/bash
            mkdir -p /home/ubuntu/monitoring/config
            cd /home/ubuntu/monitoring

            # 1. OTel Collector 설정 (메트릭 전용)
            cat <<EOF > config/otel-config.yaml
            receivers:
              hostmetrics:
                collection_interval: 10s
                scrapers:
                  cpu: {}
                  memory: {}
            processors:
              batch:
              resource:
                attributes:
                  - action: insert
                    key: company_id
                    value: ${companyId}
            exporters:
              prometheus:
                endpoint: "0.0.0.0:8889"
                resource_to_telemetry_conversion:
                  enabled: true
            service:
              pipelines:
                metrics:
                  receivers: [hostmetrics]
                  processors: [resource, batch]
                  exporters: [prometheus]
            EOF

            # 2. Prometheus 설정
            cat <<'EOF' > config/prometheus.yml
            global:
              scrape_interval: 10s
            scrape_configs:
              - job_name: "otel-collector"
                static_configs:
                  - targets: ["otel-collector:8889"]
            EOF

            # 3. 도커 컴포즈 실행 (Nginx 제거, Prometheus 8080 직결)
            cat <<'EOF' > docker-compose.yml
            version: "3.9"
            services:
              prometheus:
                image: prom/prometheus:v2.50.0
                container_name: prometheus
                ports: ["8080:9090"] # 외부 8080을 프로메테우스 9090으로 바로 연결
                volumes:
                  - ./config/prometheus.yml:/etc/prometheus/prometheus.yml
                restart: always

              otel-collector:
                image: otel/opentelemetry-collector-contrib:0.98.0
                container_name: otel-collector
                volumes:
                  - ./config/otel-config.yaml:/etc/otelcol-contrib/config.yaml
                command: ["--config=/etc/otelcol-contrib/config.yaml"]
                restart: always

              cpu-burner:
                image: busybox
                command: sh -c "while :; do :; done"
                restart: always
            EOF

            docker-compose up -d
        """.trimIndent()

        val encodedUserData = Base64.getEncoder().encodeToString(userDataScript.toByteArray())
        val response = ec2Client.runInstances { req ->
            req.imageId(amiId).instanceType(InstanceType.T3_SMALL).maxCount(1).minCount(1)
                .subnetId(privateSubnetId).securityGroupIds(monitoringSgId).userData(encodedUserData)
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value("$companyId").build()).build())
        }
        return response.instances()[0].instanceId()
    }

    private fun createTargetGroup(companyId: String): String {
        val response = elbClient.createTargetGroup { req ->
            req.name("$companyId").protocol(ProtocolEnum.HTTP).port(8080).vpcId(vpcId)
                .targetType(TargetTypeEnum.INSTANCE)
                .healthCheckPath("/-/healthy") // 프로메테우스 헬스체크 경로
                .healthCheckPort("8080")
                .healthCheckIntervalSeconds(15)
                .healthyThresholdCount(2)
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(targetGroupArn: String, companyId: String, priority: Int): String {
        val response = elbClient.createRule { req ->
            req.listenerArn(listenerArn).priority(priority)
                .conditions({ c -> c.field("http-header").httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(companyId) } })
                .actions({ a -> a.type("forward").targetGroupArn(targetGroupArn) })
        }
        return response.rules()[0].ruleArn()
    }

    private fun registerTarget(targetGroupArn: String, instanceId: String) {
        elbClient.registerTargets { req -> req.targetGroupArn(targetGroupArn).targets({ t -> t.id(instanceId).port(8080) }) }
    }
}