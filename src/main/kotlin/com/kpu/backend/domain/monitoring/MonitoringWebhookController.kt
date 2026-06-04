package com.kpu.backend.domain.monitoring

import com.kpu.backend.domain.alert.entity.AlertLog
import com.kpu.backend.domain.alert.repository.AlertRepository
import com.kpu.backend.domain.company.CompanyRepository
import com.kpu.backend.infra.AiService
import com.kpu.backend.infra.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/alerts")
class MonitoringWebhookController(
    private val aiService: AiService,
    private val notificationService: NotificationService,
    private val companyRepository: CompanyRepository,
    private val alertRepository: AlertRepository
) {
    private val log = LoggerFactory.getLogger(MonitoringWebhookController::class.java)

    @PostMapping(value = ["/webhook", "/webhook/{pathId}"])
    fun receiveAlert(
        @RequestBody payload: Map<String, Any>,
        @PathVariable(required = false) pathId: String?
    ): ResponseEntity<String> {
        val alerts = payload["alerts"] as? List<Map<String, Any>> ?: emptyList()

        for (alert in alerts) {
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            val status = alert["status"]?.toString() ?: payload["status"]?.toString() ?: "firing"

            val monitoringId = pathId ?: labels["company_id"] ?: "unknown"
            val company = companyRepository.findByMonitoringId(monitoringId) ?: continue

            if (status == "firing") {
                val alertName = labels["alertname"] ?: "UnknownAlert"
                val description = annotations["description"] ?: annotations["summary"] ?: "상세 내용 없음"
                val aiAnalysis = aiService.getAnalysisFromGPT(alertName, description)

                alertRepository.save(
                    AlertLog(
                        monitoringId = monitoringId,
                        alertName = alertName,
                        severity = labels["severity"] ?: "critical",
                        description = description,
                        aiAnalysis = aiAnalysis
                    )
                )

                val emailContent = """
                    [${company.name}] 서버 장애 감지
                    - 장애명: $alertName
                    - 상세내용: $description

                    AI 장애 분석 및 가이드:
                    $aiAnalysis

                    ※ 즉시 시스템을 점검하시기 바랍니다.
                """.trimIndent()

                notificationService.sendAlert(company.email, "[긴급] 서버 장애 발생: $alertName", emailContent)
                log.info("장애 감지: ${company.name} 알림 발송 완료")
            }
        }
        return ResponseEntity.ok("Success")
    }
}
