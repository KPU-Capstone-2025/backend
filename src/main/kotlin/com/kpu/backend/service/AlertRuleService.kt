// src/main/kotlin/com/kpu/backend/service/AlertRuleService.kt
package com.kpu.backend.service

import com.kpu.backend.dto.RuleUpdateRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.io.File

@Service
class AlertRuleService(
    private val restTemplate: RestTemplate
) {
    @Value("\${prometheus.rule-file-path}")
    private lateinit var ruleFilePath: String

    private val prometheusReloadUrl = "http://localhost:9090/-/reload"

    fun updateRules(request: RuleUpdateRequest) {
val yamlContent = """
groups:
  - name: ${request.companyId}-rules
    rules:
      - alert: HighCpuUsage
        expr: (system_cpu_usage or vector(99)) > ${request.cpuThreshold}
        for: ${request.durationSeconds}s
        labels:
          severity: critical
          company_id: ${request.companyId}
        annotations:
          description: "CPU 사용량이 ${request.cpuThreshold}%를 초과했습니다."

      - alert: HighMemoryUsage
        expr: (system_memory_usage or vector(88)) > ${request.memoryThreshold}
        for: ${request.durationSeconds}s
        labels:
          severity: warning
          company_id: ${request.companyId}
        annotations:
          description: "메모리 사용량이 ${request.memoryThreshold}%를 초과했습니다."

      - alert: HighNetworkTraffic
       expr: ((rate(system_network_rx_bytes[1m]) + rate(system_network_tx_bytes[1m])) or vector(10485760)) > ${request.networkThreshold}
        for: ${request.durationSeconds}s
        labels:
          severity: warning
          company_id: ${request.companyId}
        annotations:
          description: "네트워크 트래픽이 ${request.networkThreshold} 바이트를 초과했습니다."
""".trimIndent()

        val file = File(ruleFilePath)
        file.writeText(yamlContent)

        try {
            restTemplate.postForEntity(prometheusReloadUrl, null, String::class.java)
            println("프로메테우스 리로드 성공")
        } catch (e: Exception) {
            println("프로메테우스 리로드 실패: ${e.message}")
        }
    }
}