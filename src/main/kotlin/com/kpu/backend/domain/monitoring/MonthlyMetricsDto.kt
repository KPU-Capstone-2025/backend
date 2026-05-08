package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.annotation.JsonInclude

data class MetricStats(
    val avg: Double,
    val min: Double,
    val max: Double,
    val latest: Double
)

data class HostDailyMetrics(
    val cpu: MetricStats,
    val memory: MetricStats,
    val disk: MetricStats,
    val network: MetricStats
)

data class ContainerDailyMetrics(
    val containerId: String,
    val status: String,
    val cpu: MetricStats,
    val memory: MetricStats,
    val network: MetricStats
)

data class DailyMetrics(
    val date: String,
    val hasData: Boolean,
    val worstStatus: String,
    val alertCount: Int,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val host: HostDailyMetrics?,
    val containers: List<ContainerDailyMetrics>
)

data class MonthlyMetricsResponse(
    val year: Int,
    @JsonInclude(JsonInclude.Include.NON_NULL) val month: Int?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val startDate: String?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val endDate: String?,
    val days: List<DailyMetrics>
)
