package com.kpu.backend.domain.alert

import org.springframework.data.jpa.repository.JpaRepository

interface AlertRepository : JpaRepository<AlertLog, Long> {
    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>
    fun findByMonitoringIdAndCreatedAtBetween(
        monitoringId: String,
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime
    ): List<AlertLog>
}
