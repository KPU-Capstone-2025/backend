package com.kpu.backend.domain.monitoring.service

import com.kpu.backend.domain.alert.entity.AlertLog
import com.kpu.backend.domain.monitoring.dto.*

interface MonitoringService {
    fun getContainerList(companyId: Long, hostName: String? = null): List<ContainerStatus>
    fun getHostMetrics(companyId: Long, hostName: String? = null): ResourceMetrics
    fun getHostMetricsHistory(companyId: Long, rangeMinutes: Int, step: Int, hostName: String?): List<Map<String, Any>>
    fun getDiscoveredHosts(companyId: Long): List<String>
    fun getContainerMetrics(companyId: Long, containerName: String): ResourceMetrics
    fun getLogs(companyId: Long, containerName: String?, severity: String?, keyword: String?, limit: Int, hostName: String? = null): List<LogEntry>
    fun getMonthlyMetrics(companyId: Long, year: Int, month: Int?, startDate: String?, endDate: String?, hostName: String? = null): MonthlyMetricsResponse
    fun getAlertsByDate(companyId: Long, date: String, hostName: String? = null): List<AlertLog>
    fun getLogsByDateRange(companyId: Long, date: String): List<LogEntry>
    fun getUserUsage(companyId: Long, hostName: String? = null): List<UserUsageStat>
}
