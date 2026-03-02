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
        val basePriority = (companyRepository.findMaxPriority() ?: 100) + 2

        val tgPromArn = createTargetGroup("-p", request.companyId, 9090, "/-/healthy")
        val rulePromArn = createAlbRule(tgPromArn, request.companyId, basePriority, "/api/v1/*")

        val tgLokiArn = createTargetGroup("-l", request.companyId, 3100, "/")
        val ruleLokiArn = createAlbRule(tgLokiArn, request.companyId, basePriority + 1, "/loki/api/v1/*")

        val instanceId = launchInstance(request.companyId)

        ec2Client.waiter().waitUntilInstanceRunning { it.instanceIds(instanceId) }
        registerTarget(tgPromArn, instanceId, 9090)
        registerTarget(tgLokiArn, instanceId, 3100)

        val newCompany = Company(
            name = request.name, phone = request.phone, email = request.email,
            password = request.password, companyName = request.companyName,
            companyId = request.companyId, instanceId = instanceId,
            targetGroupArn = tgPromArn, albRuleArn = rulePromArn, priority = basePriority
        )
        return companyRepository.save(newCompany)
    }

    private fun launchInstance(companyId: String): String {
        val userDataScript = """
            #!/bin/bash
            
            fallocate -l 2G /swapfile || true
            chmod 600 /swapfile || true
            mkswap /swapfile || true
            swapon /swapfile || true
            echo '/swapfile none swap sw 0 0' | tee -a /etc/fstab

            mkdir -p /root/monitoring
            cd /root/monitoring

            cat <<'EOF' > generate_logs.sh
            #!/bin/sh
            while true; do
              D=${'$'}(date +%H:%M:%S)
              NANO_TIME=${'$'}(date +%s)000000000
              
              cat <<INNER_EOF > /tmp/log.json
            {
              "streams": [
                {
                  "stream": {
                    "company_id": "${companyId}",
                    "service_name": "demo-app",
                    "job": "log-generator"
                  },
                  "values": [
                    [ "${'$'}{NANO_TIME}", "[INFO] 데모 서버 실시간 로그 정상 기록 중입니다... ${'$'}D" ]
                  ]
                }
              ]
            }
            INNER_EOF
              
              # OTel(4318) 대신 Loki(3100)의 Push API로 다이렉트 전송
              wget -qO- --header="Content-Type: application/json" --post-file=/tmp/log.json http://loki:3100/loki/api/v1/push
              sleep 5
            done
            EOF
            chmod +x generate_logs.sh

            cat <<'EOF' > alert.rules.yml
            groups:
              - name: container-monitoring
                rules:
                  - alert: ContainerHighCPU
                    expr: rate(container_cpu_usage_seconds_total[2m]) > 0.8
                    for: 2m
                    labels:
                      severity: critical
                  - alert: ContainerHighMemory
                    expr: container_memory_usage_bytes / container_memory_limit_bytes > 0.8
                    for: 2m
                    labels:
                      severity: critical
                  - alert: ContainerDown
                    expr: up == 0
                    for: 1m
                    labels:
                      severity: critical
                  - alert: ContainerHighNetworkTx
                    expr: rate(container_network_transmit_bytes_total[2m]) > 1048576
                    for: 2m
                    labels:
                      severity: warning
            EOF

            cat <<'EOF' > alertmanager.yml
            global:
              resolve_timeout: 5m
            route:
              receiver: default
            receivers:
              - name: default
                webhook_configs:
                  - url: "http://localhost:5001"
            EOF

            cat <<'EOF' > prometheus.yml
            global:
              scrape_interval: 10s
            rule_files:
              - "alert.rules.yml"
            alerting:
              alertmanagers:
                - static_configs:
                    - targets: ["alertmanager:9093"]
            scrape_configs:
              - job_name: "otel-collector"
                static_configs:
                  - targets: ["otel-collector:8889"]
            EOF

            cat <<EOF > otel-config.yaml
            receivers:
              otlp:
                protocols:
                  http:
                    endpoint: 0.0.0.0:4318
              hostmetrics:
                collection_interval: 10s
                scrapers:
                  cpu: {}
                  memory: {}
            processors:
              memory_limiter:
                check_interval: 1s
                limit_mib: 512
                spike_limit_mib: 128
              batch:
                send_batch_size: 500
                timeout: 5s
              resource:
                attributes:
                  - action: insert
                    key: host_name
                    from_attribute: host.name
                  - action: insert
                    key: company_id
                    value: ${companyId}
                  - action: insert
                    key: container_name
                    from_attribute: container.name
                  - action: insert
                    key: container_id
                    from_attribute: container.id
                  - action: insert
                    key: service_name
                    from_attribute: service.name
            exporters:
              debug:
                verbosity: detailed
              prometheus:
                endpoint: "0.0.0.0:8889"
                resource_to_telemetry_conversion:
                  enabled: true
              loki:
                endpoint: http://loki:3100/loki/api/v1/push
            service:
              pipelines:
                logs:
                  receivers: [otlp]
                  processors: [memory_limiter, resource, batch]
                  exporters: [debug, loki]
                metrics:
                  receivers: [otlp, hostmetrics]
                  processors: [memory_limiter, resource, batch]
                  exporters: [prometheus, debug]
            EOF

            cat <<'EOF' > docker-compose.yml
            version: "3.9"

            services:
              loki:
                image: grafana/loki:2.9.4
                container_name: loki
                ports:
                  - "3100:3100"
                volumes:
                  - loki-data:/loki

              prometheus:
                image: prom/prometheus:v2.50.0
                container_name: prometheus
                ports:
                  - "9090:9090"
                volumes:
                  - ./prometheus.yml:/etc/prometheus/prometheus.yml
                  - ./alert.rules.yml:/etc/prometheus/alert.rules.yml

              alertmanager:
                image: prom/alertmanager:v0.27.0
                container_name: alertmanager
                ports:
                  - "9093:9093"
                volumes:
                  - ./alertmanager.yml:/etc/alertmanager/alertmanager.yml

              otel-collector:
                image: otel/opentelemetry-collector-contrib:0.98.0
                container_name: otel-collector
                ports:
                  - "4318:4318"
                  - "8889:8889"
                volumes:
                  - ./otel-config.yaml:/etc/otelcol-contrib/config.yaml
                command:
                  - "--config=/etc/otelcol-contrib/config.yaml"
                depends_on:
                  - loki
                  - prometheus

              app-core:
                image: kimhongseok/target-server
                container_name: app-core
                ports:
                  - "8080:8080"
                restart: unless-stopped
                mem_limit: 512m

              web-gateway:
                image: nginx:latest
                container_name: web-gateway
                ports:
                  - "80:80"
                restart: unless-stopped

              log-generator:
                image: alpine:latest
                container_name: log-generator
                volumes:
                  - ./generate_logs.sh:/generate_logs.sh
                command: ["sh", "/generate_logs.sh"]
                restart: unless-stopped

            volumes:
              loki-data:
            EOF

            docker rm -f ${'$'}(docker ps -aq) 2>/dev/null || true
            
            if command -v docker-compose &> /dev/null; then
                docker-compose up -d
            else
                docker compose up -d
            fi
        """.trimIndent()

        val encodedUserData = Base64.getEncoder().encodeToString(userDataScript.toByteArray())
        val response = ec2Client.runInstances { req ->
            req.imageId(amiId).instanceType(InstanceType.T3_SMALL).maxCount(1).minCount(1)
                .subnetId(privateSubnetId).securityGroupIds(monitoringSgId).userData(encodedUserData)
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                    .tags(Tag.builder().key("Name").value(companyId).build()).build())
        }
        return response.instances()[0].instanceId()
    }

    private fun createTargetGroup(suffix: String, companyId: String, port: Int, healthCheckPath: String): String {
        val safeId = companyId.take(28)
        val tgName = "$safeId$suffix"
        
        val response = elbClient.createTargetGroup { req ->
            req.name(tgName).protocol(ProtocolEnum.HTTP).port(port).vpcId(vpcId)
                .targetType(TargetTypeEnum.INSTANCE)
                .healthCheckPath(healthCheckPath)
                .healthCheckPort(port.toString())
                .healthCheckIntervalSeconds(15)
                .healthyThresholdCount(2)
                .matcher { m -> m.httpCode("200-499") } 
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(targetGroupArn: String, companyId: String, priority: Int, pathPattern: String): String {
        val response = elbClient.createRule { req ->
            req.listenerArn(listenerArn).priority(priority)
                .conditions(
                    { c -> c.field("http-header").httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(companyId) } },
                    { c -> c.field("path-pattern").pathPatternConfig { p -> p.values(pathPattern) } }
                )
                .actions({ a -> a.type("forward").targetGroupArn(targetGroupArn) })
        }
        return response.rules()[0].ruleArn()
    }

    private fun registerTarget(targetGroupArn: String, instanceId: String, port: Int) {
        elbClient.registerTargets { req -> req.targetGroupArn(targetGroupArn).targets({ t -> t.id(instanceId).port(port) }) }
    }
}