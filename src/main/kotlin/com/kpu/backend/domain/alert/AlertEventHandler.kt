package com.kpu.backend.domain.alert

import com.kpu.backend.infra.AiService
import com.kpu.backend.infra.NotificationService
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

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
        alertRepository.save(
            AlertLog(
                monitoringId = event.monitoringId,
                alertName = event.alertName,
                severity = event.severity,
                description = event.description,
                aiAnalysis = aiAnalysis
            )
        )
        notificationService.sendAlert(
            event.targetEmail,
            "[긴급] 장애 발생: ${event.alertName}",
            "분석: $aiAnalysis"
        )
    }
}
