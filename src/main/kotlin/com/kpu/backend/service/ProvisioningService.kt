package com.kpu.backend.service

import com.kpu.backend.dto.ProvisioningRequest
import com.kpu.backend.entity.Company
import com.kpu.backend.repository.CompanyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*
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
        println("[${request.companyName}] 인프라 및 독립 모니터링 환경 생성 시작...")

        val tgArn = createTargetGroup(request.companyId)
        val currentMax = companyRepository.findMaxPriority() ?: 100
        val nextPriority = if (currentMax < 100) 101 else currentMax + 1
        val ruleArn = createAlbRule(tgArn, request.companyId, nextPriority)

        // 1. EC2 실행 명령
        val instanceId = launchInstance(request.companyId)

        println("EC2($instanceId) 부팅 완료 대기 중...")
        ec2Client.waiter().waitUntilInstanceRunning {
            it.instanceIds(instanceId)
        }
        println("EC2 부팅 완료! 타겟 그룹에 등록합니다.")

        // 3. 타겟 그룹에 등록
        registerTarget(tgArn, instanceId)

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

        return companyRepository.save(newCompany)
    }

    private fun createTargetGroup(companyId: String): String {
        val response = albClient.createTargetGroup { 
            it.name("$companyId")
              .protocol(ProtocolEnum.HTTP)
              .port(8080)
              .vpcId(vpcId)
              .targetType(TargetTypeEnum.INSTANCE)
              .healthCheckPath("/-/healthy")
              .healthCheckPort("8080")
              .matcher { it.httpCode("200,302")
        }
        return response.targetGroups()[0].targetGroupArn()
    }

    private fun createAlbRule(tgArn: String, companyId: String, priority: Int): String {
        val response = albClient.createRule {
            it.listenerArn(listenerArn)
              .priority(priority)
              .conditions(RuleCondition.builder()
                  .field("http-header")
                  .httpHeaderConfig { h -> h.httpHeaderName("X-Server-Group").values(companyId) }
                  .build())
              .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build())
        }
        return response.rules()[0].ruleArn()
    }

    private fun launchInstance(companyId: String): String {
        val userDataScript = """
            #!/bin/bash
            
            # 모니터링 폴더 생성
            mkdir -p /home/ubuntu/monitoring/config
            cd /home/ubuntu/monitoring

            # 1. 프로메테우스 설정
            cat <<EOF > config/prometheus.yml
            global:
              scrape_interval: 15s
            scrape_configs:
              - job_name: 'prometheus'
                static_configs:
                  - targets: ['localhost:9090']
            alerting:
              alertmanagers:
                - static_configs:
                    - targets: ['alertmanager:9093']
            EOF

            # 2. 로키 설정
            cat <<EOF > config/loki-config.yaml
            auth_enabled: false
            server:
              http_listen_port: 3100
            ingester:
              lifecycler:
                ring:
                  kvstore:
                    store: inmemory
                  replication_factor: 1
              chunk_idle_period: 5m
              chunk_retain_period: 30s
            schema_config:
              configs:
                - from: 2020-10-24
                  store: boltdb-shipper
                  object_store: filesystem
                  schema: v11
                  index:
                    prefix: index_
                    period: 24h
            storage_config:
              boltdb_shipper:
                active_index_directory: /tmp/loki/boltdb-shipper-active
                cache_location: /tmp/loki/boltdb-shipper-cache
              filesystem:
                directory: /tmp/loki/chunks
            EOF

            # 3. 알러트매니저 설정
            cat <<EOF > config/alertmanager.yml
            route:
              receiver: 'default-receiver'
            receivers:
              - name: 'default-receiver'
            EOF

            # 4. OTel Collector 설정
            cat <<EOF > config/otel-config.yaml
            receivers:
              hostmetrics:
                scrapers:
                  cpu: {}
                  memory: {}
            processors:
              attributes:
                actions:
                  - key: company_id
                    value: $companyId  
                    action: insert
            exporters:
              prometheusremotewrite:
                endpoint: "http://prometheus:9090/api/v1/write"
                tls:
                  insecure: true
              otlphttp/loki:
                endpoint: "http://loki:3100/otlp"
                tls:
                  insecure: true
            service:
              pipelines:
                metrics:
                  receivers: [hostmetrics]
                  processors: [attributes]
                  exporters: [prometheusremotewrite]
            EOF

            # 5. 도커 컴포즈 파일 생성
            cat <<EOF > docker-compose.yml
            version: '3.8'
            services:
              prometheus:
                image: prom/prometheus:latest
                container_name: prometheus
                command:
                  - '--config.file=/etc/prometheus/prometheus.yml'
                  - '--web.enable-remote-write-receiver'
                volumes:
                  - ./config/prometheus.yml:/etc/prometheus/prometheus.yml
                ports:
                  - "8080:9090"
              loki:
                image: grafana/loki:latest
                container_name: loki
                command: -config.file=/etc/loki/local-config.yaml
                volumes:
                  - ./config/loki-config.yaml:/etc/loki/local-config.yaml
                ports:
                  - "3100:3100"
              alertmanager:
                image: prom/alertmanager:latest
                container_name: alertmanager
                volumes:
                  - ./config/alertmanager.yml:/etc/alertmanager/config.yml
                ports:
                  - "9093:9093"
              otel-collector:
                image: otel/opentelemetry-collector-contrib:latest
                container_name: otel-collector
                command: ["--config=/etc/otelcol/config.yaml"]
                volumes:
                  - ./config/otel-config.yaml:/etc/otelcol/config.yaml
                depends_on:
                  - prometheus
                  - loki
            EOF

            # 6. 도커 실행
            docker-compose up -d

        """.trimIndent()

        val encodedUserData = Base64.getEncoder().encodeToString(userDataScript.toByteArray())
        val response = ec2Client.runInstances { req ->
            req.imageId(amiId)
                .instanceType(InstanceType.T3_MICRO)
                .maxCount(1)
                .minCount(1)
                .subnetId(privateSubnetId)
                .securityGroupIds(monitoringSgId)
                .userData(encodedUserData)
                .tagSpecifications(
                    TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        // 명시적 패키지 경로를 써서 Unresolved 에러 해결!
                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder().key("Name").value("$companyId").build())
                        .build()
                )
        }
        
        return response.instances()[0].instanceId()
    }

    private fun registerTarget(tgArn: String, instanceId: String) {
        val request = RegisterTargetsRequest.builder()
            .targetGroupArn(tgArn)
            .targets(TargetDescription.builder().id(instanceId).build())
            .build()
        albClient.registerTargets(request)
    }
}