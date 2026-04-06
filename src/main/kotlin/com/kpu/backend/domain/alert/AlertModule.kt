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
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.SendCommandRequest
import java.time.LocalDateTime

//Dto,Event,Entity
data class RuleUpdateRequest(val companyId: String, val cpuThreshold: Int, val memoryThreshold: Int, val networkThreshold: Long, val durationSeconds: Int)
data class AlertReceivedEvent(val monitoringId: String, val alertName: String, val severity: String, val description: String, val targetEmail: String, val companyName: String)

@Entity
@Table(name = "alert_logs")
class AlertLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(nullable = false) val monitoringId: String,
    @Column(nullable = false) val alertName: String,
    val severity: String,
    @Column(columnDefinition = "TEXT") val description: String,
    @Column(columnDefinition = "TEXT") var aiAnalysis: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface AlertRepository : JpaRepository<AlertLog, Long> {
    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>
}

//Service
@Service
class AlertService(
    private val companyRepository: CompanyRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(AlertService::class.java)

    fun processWebhook(alertData: Map<String, Any>) {
        val alerts = alertData["alerts"] as? List<Map<String, Any>> ?: return
        alerts.forEach { alert ->
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            val monitoringId = labels["company_id"] ?: "unknown"
            
            val company = companyRepository.findByMonitoringId(monitoringId)
            
            eventPublisher.publishEvent(
                AlertReceivedEvent(
                    monitoringId = monitoringId, 
                    alertName = labels["alertname"] ?: "UnknownAlert",
                    severity = labels["severity"] ?: "info", 
                    description = annotations["description"] ?: "No description",
                    targetEmail = company?.email ?: "admin@kpu.ac.kr", 
                    companyName = company?.name ?: "System"
                )
            )
        }
    }
}

@Service
class AlertEventHandler(
    private val aiService: AiService,
    private val notificationService: NotificationService,
    private val alertRepository: AlertRepository
) {
    @Async
    @EventListener
    fun handleAlertEvent(event: AlertReceivedEvent) {
        val aiAnalysis = aiService.getAnalysisFromGPT(event.alertName, event.description)
        saveAlertLog(event, aiAnalysis)
        
        val mailContent = """
            🚨 [${event.companyName}] 서버 장애 감지 🚨
            
            - 장애 명칭: ${event.alertName}
            - 상세 내용: ${event.description}
            
            AI 원인 분석 및 가이드:
            $aiAnalysis
            
            ※ 조치 후 대시보드에서 상태를 확인하세요.
        """.trimIndent()
        
        notificationService.sendAlert(event.targetEmail, "[긴급] 서버 장애 발생: ${event.alertName}", mailContent)
    }

    @Transactional
    fun saveAlertLog(event: AlertReceivedEvent, aiAnalysis: String) {
        alertRepository.save(AlertLog(monitoringId = event.monitoringId, alertName = event.alertName, severity = event.severity, description = event.description, aiAnalysis = aiAnalysis))
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
        val company = companyRepository.findByMonitoringId(request.companyId) ?: throw IllegalArgumentException("존재하지 않는 기업입니다.")
        val instanceId = findInstanceIdByName(company.name) ?: throw IllegalStateException("해당 기업의 모니터링 서버를 찾을 수 없습니다.")
        val yamlContent = """
groups:
  - name: ${request.companyId}-rules
    rules:
      - alert: HighCpuUsage
        expr: system_cpu_usage > ${request.cpuThreshold}
        for: ${request.durationSeconds}s
        labels:
          severity: critical
          company_id: ${request.companyId}
        annotations:
          description: "CPU 사용량이 ${request.cpuThreshold}%를 초과했습니다."
      - alert: HighMemoryUsage
        expr: system_memory_usage > ${request.memoryThreshold}
        for: ${request.durationSeconds}s
        labels:
          severity: warning
          company_id: ${request.companyId}
        annotations:
          description: "메모리 사용량이 ${request.memoryThreshold}%를 초과했습니다."
""".trimIndent()

        val bashCommand = "cat << 'EOF' > /opt/monitoring/alert.rules.yml\n$yamlContent\nEOF\ndocker kill -s SIGHUP prometheus"

        try {
            val ssmRequest = SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(mapOf("commands" to listOf(bashCommand)))
                .build()
            
            ssmClient.sendCommand(ssmRequest)
            log.info("[${company.name}] 임계치 업데이트 명령 전송 성공")
        } catch (e: Exception) {
            log.error("SSM 명령 전송 실패", e)
            throw RuntimeException("AWS 서버 통신 실패: ${e.message}")
        }
    }

    private fun findInstanceIdByName(companyName: String): String? {
        val req = software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest.builder()
            .filters(
                Filter.builder().name("tag:Name").values(companyName).build(),
                Filter.builder().name("instance-state-name").values("running").build()
            ).build()
        return ec2Client.describeInstances(req).reservations().firstOrNull()?.instances()?.firstOrNull()?.instanceId()
    }
}

//Controllers
@RestController
@RequestMapping("/api/rules")
class RuleController(private val alertRuleService: AlertRuleService) {
    @PostMapping("/update")
    fun updateAlertRules(@RequestBody request: RuleUpdateRequest): ResponseEntity<Map<String, String>> {
        alertRuleService.updateRules(request)
        return ResponseEntity.ok(mapOf("message" to "알림 규칙 업데이트 요청 완료"))
    }
}