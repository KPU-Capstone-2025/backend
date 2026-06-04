package com.kpu.backend.domain.monitoring.service

import com.kpu.backend.domain.company.repository.CompanyRepository
import com.kpu.backend.domain.monitoring.dto.MetricPrediction
import com.kpu.backend.domain.monitoring.dto.PredictionResult
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PredictionService(
    private val companyRepository: CompanyRepository,
    private val monitoringService: MonitoringServicePort
) {
    fun predict(companyId: Long, hostName: String?): PredictionResult {
        val company = companyRepository.findById(companyId).orElseThrow()
        val prometheusUrl = "http://${company.ip ?: "localhost"}:9090"
        val now = Instant.now().epochSecond
        val sixHoursAgo = now - 21600

        val targets = listOf(
            Triple("system_cpu_usage",    85.0, 900),
            Triple("system_memory_usage", 85.0, 900),
            Triple("system_disk_usage",   90.0, 900)
        )

        val predictions = targets.map { (metricName, threshold, step) ->
            val query   = if (hostName != null) "$metricName{host_name=\"$hostName\"}" else metricName
            val pts     = monitoringService.queryRangePublic(query, prometheusUrl, sixHoursAgo, now, step)
            val current = monitoringService.querySingleValuePublic(query, prometheusUrl) ?: 0.0

            if (pts.size < 3) {
                MetricPrediction(metricName, threshold, current.round2(), "stable", null, null)
            } else {
                val (slope, intercept) = linearRegression(pts)
                val trend = when {
                    slope > 0.01  -> "rising"
                    slope < -0.01 -> "falling"
                    else          -> "stable"
                }
                val hoursUntilBreach = if (slope > 0) {
                    val breachEpoch = (threshold - intercept) / slope
                    val hoursLeft   = (breachEpoch - now) / 3600.0
                    if (hoursLeft in 0.0..168.0) Pair(hoursLeft.round2(), breachEpoch) else null
                } else null

                MetricPrediction(
                    metric            = metricName,
                    threshold         = threshold,
                    currentValue      = current.round2(),
                    trend             = trend,
                    hoursUntilBreach  = hoursUntilBreach?.first,
                    predictedAt       = hoursUntilBreach?.second?.round2()
                )
            }
        }

        return PredictionResult(hostName = hostName, predictions = predictions)
    }

    private fun linearRegression(pts: List<Pair<Long, Double>>): Pair<Double, Double> {
        val n     = pts.size.toDouble()
        val sumX  = pts.sumOf { it.first.toDouble() }
        val sumY  = pts.sumOf { it.second }
        val sumXY = pts.sumOf { it.first.toDouble() * it.second }
        val sumX2 = pts.sumOf { it.first.toDouble() * it.first.toDouble() }
        val slope     = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n
        return Pair(slope, intercept)
    }

    private fun Double.round2() = Math.round(this * 100.0) / 100.0
}
