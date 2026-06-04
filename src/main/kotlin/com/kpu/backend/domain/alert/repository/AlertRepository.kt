package com.kpu.backend.domain.alert.repository

import com.kpu.backend.domain.alert.entity.AlertLog
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface AlertRepository : JpaRepository<AlertLog, Long> {

    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>

    fun findByMonitoringIdAndCreatedAtBetween(
        monitoringId: String,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<AlertLog>

    fun findByMonitoringIdAndHostNameAndCreatedAtBetween(
        monitoringId: String,
        hostName: String,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<AlertLog>
}
