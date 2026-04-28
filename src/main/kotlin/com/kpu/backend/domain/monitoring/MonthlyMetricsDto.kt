package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.annotation.JsonInclude

/** 단일 지표의 일별 집계 (avg/min/max/latest) */
data class MetricStats(
    val avg: Double,
    val min: Double,
    val max: Double,
    val latest: Double
)

/** 호스트 하루치 집계 */
data class HostDailyMetrics(
    val cpu: MetricStats,      // %
    val memory: MetricStats,   // MB
    val disk: MetricStats,     // %
    val network: MetricStats   // KB/s (rx+tx)
)

/** 컨테이너 하루치 집계 */
data class ContainerDailyMetrics(
    val containerId: String,
    val status: String,
    val cpu: MetricStats,      // %
    val memory: MetricStats,   // MB
    val network: MetricStats   // KB/s (rx)
)

/** 하루 전체 집계 */
data class DailyMetrics(
    val date: String,                     // "2026-04-01"
    val hasData: Boolean,
    val worstStatus: String,              // STABLE / WARNING / CRITICAL / NO_DATA
    val alertCount: Int,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val host: HostDailyMetrics?,
    val containers: List<ContainerDailyMetrics>
)

/** 월간 응답 최상위 */
data class MonthlyMetricsResponse(
    val year: Int,
    @JsonInclude(JsonInclude.Include.NON_NULL) val month: Int?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val startDate: String?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val endDate: String?,
    val days: List<DailyMetrics>
)
