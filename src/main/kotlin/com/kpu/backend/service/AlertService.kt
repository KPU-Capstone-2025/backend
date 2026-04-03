package com.kpu.backend.service

import com.kpu.backend.entity.AlertLog
import com.kpu.backend.repository.AlertRepository
import com.kpu.backend.repository.CompanyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory // 이 녀석이 꼭 필요합니다.

@Service
class AlertService(
    private val alertRepository: AlertRepository,
    private val companyRepository: CompanyRepository,
    private val notificationService: NotificationService,
    private val aiService: AiService
) {
    private val log = LoggerFactory.getLogger(AlertService::class.java)

    @Transactional
    fun processWebhook(alertData: Map<String, Any>) {
        log.info("알림 수신 완료")
        
        // 1. 데이터 파싱
        val alerts = alertData["alerts"] as? List<Map<String, Any>> ?: return
        
        alerts.forEach { alert ->
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            
            val monitoringId = labels["company_id"] ?: "unknown"
            val alertName = labels["alertname"] ?: "UnknownAlert"
            val description = annotations["description"] ?: "No description"

            // 2. 해당 기업의 이메일 찾기
            val company = companyRepository.findByMonitoringId(monitoringId)
            val targetEmail = company?.email ?: "admin@kpu.ac.kr"
            
            log.info("[검색 결과] 모니터링ID: $monitoringId -> 수신 메일: $targetEmail")

            // 3. AI 분석 요청
            val aiAnalysis = aiService.getAnalysisFromGPT(alertName, description)

            // 4. DB 저장
            alertRepository.save(AlertLog(
                monitoringId = monitoringId,
                alertName = alertName,
                severity = labels["severity"] ?: "info",
                description = description,
                aiAnalysis = aiAnalysis
            ))

            // 5. 이메일 발송
            val mailSubject = "[경고] ${company?.name ?: "시스템"} 서버 장애 감지: $alertName"
            val mailContent = """
                장애 명칭: $alertName
                상세 내용: $description
                
                AI 가이드라인:
                $aiAnalysis
                
                조치 후 챗봇에게 추가 질문을 던져보세요.
            """.trimIndent()

            notificationService.sendAlert(targetEmail, mailSubject, mailContent)
        }
    }
}