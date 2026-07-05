package com.kpu.backend.domain.security.dto

data class DailyAttackCount(
    val date: String,
    val count: Long
)

data class HourlyAttackCount(
    val hour: Int,
    val count: Long
)

data class IpAttackCount(
    val ip: String,
    val count: Long,
    val topUsername: String? = null
)

data class UsernameAttackCount(
    val username: String,
    val count: Long
)

data class CapacityAnalysis(
    val coresAvailable: Int = 16,
    val bcryptMsPerAttempt: Int = 100,
    val maxStartupsDefault: Int = 100,
    val poolFillRatePerSec: Int = 33,
    val cpuAtPoolFullPct: Double = 62.5,
    val cpuSaturationPerSec: Int = 160,
    val currentRatePerSec: Double,
    val currentCpuLoadPct: Double,
    val riskLevel: String
)

data class SshStatsResponse(
    val totalAttempts: Long,
    val periodStart: String,
    val periodEnd: String,
    val todayCount: Long,
    val lastHourCount: Long,
    val avgPerSec: Double,
    val dailyCounts: List<DailyAttackCount>,
    val hourlyCounts: List<HourlyAttackCount>,
    val topIps: List<IpAttackCount>,
    val topUsernames: List<UsernameAttackCount>,
    val capacityAnalysis: CapacityAnalysis,
    val dataSource: String
)
