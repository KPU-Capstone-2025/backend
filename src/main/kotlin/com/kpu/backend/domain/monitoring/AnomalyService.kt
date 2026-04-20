package com.kpu.backend.domain.monitoring

import com.kpu.backend.domain.company.CompanyRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.sqrt

data class MetricAnomaly(
    val metric: String,
    val current: Double,
    val mean: Double,
    val stddev: Double,
    val isAnomaly: Boolean,
    val severity: String  // "normal" | "warning" | "critical"
)

data class AnomalyResult(
    val hostName: String?,
    val detectedAt: String,
    val anomalies: List<MetricAnomaly>
)

@Service
class AnomalyService(
    private val companyRepository: CompanyRepository,
    private val monitoringService: MonitoringService
) {
    fun detect(companyId: Long, hostName: String?): AnomalyResult {
        val company = companyRepository.findById(companyId).orElseThrow()
        val monId = company.monitoringId
        val now = Instant.now().epochSecond
        val oneHourAgo = now - 3600

        val metrics = listOf(
            "system_cpu_usage" to 80.0,
            "system_memory_usage" to 85.0,
            "system_disk_usage" to 90.0
        )

        val anomalies = metrics.map { (metricName, threshold) ->
            val query = if (hostName != null) "$metricName{host_name=\"$hostName\"}" else metricName
            val pts = monitoringService.queryRangePublic(query, monId, oneHourAgo, now, 60)
            val values = pts.map { it.second }
            val current = monitoringService.querySingleValuePublic(query, monId) ?: 0.0

            if (values.size < 5) {
                MetricAnomaly(metricName, current, 0.0, 0.0, false, "normal")
            } else {
                val mean = values.average()
                val stddev = sqrt(values.map { (it - mean) * (it - mean) }.average())
                val zScore = if (stddev > 0) (current - mean) / stddev else 0.0
                val isAnomaly = zScore > 2.0 && current > threshold * 0.5
                val severity = when {
                    current >= threshold -> "critical"
                    isAnomaly && zScore > 3.0 -> "critical"
                    isAnomaly -> "warning"
                    else -> "normal"
                }
                MetricAnomaly(metricName, current.round2(), mean.round2(), stddev.round2(), isAnomaly, severity)
            }
        }

        return AnomalyResult(
            hostName = hostName,
            detectedAt = Instant.now().atZone(ZoneOffset.UTC).toString(),
            anomalies = anomalies
        )
    }

    private fun Double.round2() = Math.round(this * 100.0) / 100.0
}
