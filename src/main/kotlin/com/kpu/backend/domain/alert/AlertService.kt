package com.kpu.backend.domain.alert

import com.kpu.backend.domain.company.CompanyRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class AlertService(
    private val companyRepository: CompanyRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    fun processWebhook(alertData: Map<String, Any>) {
        val alerts = alertData["alerts"] as? List<Map<String, Any>> ?: return
        alerts.forEach { alert ->
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            val monitoringId = labels["company_id"] ?: "unknown"
            val hostName = labels["host_name"]
            val company = companyRepository.findByMonitoringId(monitoringId)
            val baseDesc = annotations["description"] ?: "No description"
            val fullDesc = if (!hostName.isNullOrBlank()) "[$hostName] $baseDesc" else baseDesc
            eventPublisher.publishEvent(
                AlertReceivedEvent(
                    monitoringId = monitoringId,
                    alertName = labels["alertname"] ?: "Unknown",
                    severity = labels["severity"] ?: "info",
                    description = fullDesc,
                    targetEmail = company?.email ?: "admin@kpu.ac.kr",
                    companyName = company?.name ?: "System",
                    hostName = hostName
                )
            )
        }
    }
}
