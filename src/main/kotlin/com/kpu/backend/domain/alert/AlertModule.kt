package com.kpu.backend.domain.alert

import com.kpu.backend.domain.company.CompanyRepository
import com.kpu.backend.infra.AiService
import com.kpu.backend.infra.NotificationService
import jakarta.persistence.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.SendCommandRequest
import java.time.LocalDateTime

//DTO
data class RuleUpdateRequest(
    val companyId: String? = null,
    val monitoringId: String? = null,
    val cpuThreshold: Int,
    val memoryThreshold: Int,
    val networkThreshold: Long,
    val durationSeconds: Int
)
data class AlertReceivedEvent(val monitoringId: String, val alertName: String, val severity: String, val description: String, val targetEmail: String, val companyName: String)

@Entity
@Table(name = "alert_logs")
class AlertLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    val monitoringId: String,
    val alertName: String,
    val severity: String,
    @Column(columnDefinition = "TEXT") val description: String,
    var aiAnalysis: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface AlertRepository : JpaRepository<AlertLog, Long> {
    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>
}

@Service
class AlertService(private val companyRepository: CompanyRepository, private val eventPublisher: ApplicationEventPublisher) {
    fun processWebhook(alertData: Map<String, Any>) {
        val alerts = alertData["alerts"] as? List<Map<String, Any>> ?: return
        alerts.forEach { alert ->
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            val monitoringId = labels["company_id"] ?: "unknown"
            val company = companyRepository.findByMonitoringId(monitoringId)
            eventPublisher.publishEvent(AlertReceivedEvent(monitoringId, labels["alertname"] ?: "Unknown", labels["severity"] ?: "info", annotations["description"] ?: "No description", company?.email ?: "admin@kpu.ac.kr", company?.name ?: "System"))
        }
    }
}

@Service
class AlertEventHandler(private val aiService: AiService, private val notificationService: NotificationService, private val alertRepository: AlertRepository) {
    @Async @EventListener
    fun handleAlertEvent(event: AlertReceivedEvent) {
        val aiAnalysis = aiService.getAnalysisFromGPT(event.alertName, event.description)
        alertRepository.save(AlertLog(monitoringId = event.monitoringId, alertName = event.alertName, severity = event.severity, description = event.description, aiAnalysis = aiAnalysis))
        notificationService.sendAlert(event.targetEmail, "[긴급] 장애 발생: ${event.alertName}", "분석: $aiAnalysis")
    }
}

@Service
class AlertRuleService(
    private val ssmClient: SsmClient,
    private val ec2Client: Ec2Client,
    private val companyRepository: CompanyRepository
) {
    private val log = LoggerFactory.getLogger(AlertRuleService::class.java)

    fun updateRules(request: RuleUpdateRequest) {
        val resolvedMonitoringId = resolveMonitoringId(request)
        val instanceId = findMonitoringInstanceId(resolvedMonitoringId)
            ?: throw IllegalStateException("모니터링 서버를 찾을 수 없습니다. monitoringId=$resolvedMonitoringId")

        val yaml = """
            |groups:
            |  - name: rules
            |    rules:
            |      - alert: HighCpuUsage
            |        expr: (system_cpu_usage * 100) > ${request.cpuThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: critical
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: CPU 과부하 감지
            |          description: 서버의 CPU 사용량이 ${request.cpuThreshold}%를 초과했습니다.
            |
            |      - alert: HighMemoryUsage
            |        expr: system_memory_usage > ${request.memoryThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: critical
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: 메모리 과부하 감지
            |          description: 서버의 메모리 사용량이 ${request.memoryThreshold}%를 초과했습니다.
            |
            |      - alert: HighNetworkTraffic
            |        expr: rate(system_network_rx_bytes[1m]) + rate(system_network_tx_bytes[1m]) > ${request.networkThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: warning
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: 네트워크 트래픽 급증 감지
            |          description: 서버의 네트워크 트래픽이 임계치(${request.networkThreshold} bytes/s)를 초과했습니다.
        """.trimMargin()

        val command = "cat << 'EOF' > /opt/monitoring/alert.rules.yml\n$yaml\nEOF\n(docker kill -s SIGHUP prometheus || docker restart prometheus)"

        val response = ssmClient.sendCommand(
            SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(mapOf("commands" to listOf(command)))
                .build()
        )

        log.info(
            "Rule update command sent. monitoringId={}, instanceId={}, commandId={}",
            resolvedMonitoringId,
            instanceId,
            response.command().commandId()
        )
    }

    private fun resolveMonitoringId(request: RuleUpdateRequest): String {
        if (!request.monitoringId.isNullOrBlank()) {
            return request.monitoringId
        }

        val companyIdOrMonitoringId = request.companyId?.trim()
            ?: throw IllegalArgumentException("companyId 또는 monitoringId 중 하나는 필수입니다.")

        if (companyIdOrMonitoringId.startsWith("mon-")) {
            return companyIdOrMonitoringId
        }

        val companyId = companyIdOrMonitoringId.toLongOrNull()
            ?: throw IllegalArgumentException("companyId 형식이 올바르지 않습니다: $companyIdOrMonitoringId")

        return companyRepository.findById(companyId)
            .orElseThrow { IllegalArgumentException("해당 companyId를 찾을 수 없습니다: $companyId") }
            .monitoringId
    }

    private fun findMonitoringInstanceId(monitoringId: String): String? {
        val filterMonitoringId = Filter.builder().name("tag:MonitoringId").values(monitoringId).build()
        val filterRole = Filter.builder().name("tag:Role").values("Monitoring").build()
        val filterRunning = Filter.builder().name("instance-state-name").values("running").build()
        
        val strictReq = DescribeInstancesRequest.builder()
            .filters(filterMonitoringId, filterRole, filterRunning)
            .build()

        val strictMatch = ec2Client.describeInstances(strictReq)
            .reservations()
            .flatMap { it.instances() }
            .firstOrNull()
            ?.instanceId()

        if (strictMatch != null) {
            return strictMatch
        }

        log.warn("No strict Role=Monitoring match for monitoringId={}. Falling back to legacy tag search.", monitoringId)

        val legacyReq = DescribeInstancesRequest.builder()
            .filters(filterMonitoringId, filterRunning)
            .build()
            
        return ec2Client.describeInstances(legacyReq)
            .reservations()
            .flatMap { it.instances() }
            .firstOrNull()
            ?.instanceId()
    }
}

@RestController
@RequestMapping("/api/rules")
class RuleController(private val alertRuleService: AlertRuleService) {
    @PostMapping("/update")
    fun update(@RequestBody request: RuleUpdateRequest): ResponseEntity<Unit> {
        alertRuleService.updateRules(request)
        return ResponseEntity.ok().build()
    }
}