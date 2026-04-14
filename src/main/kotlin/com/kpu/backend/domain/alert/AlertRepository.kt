package com.kpu.backend.domain.alert

import org.springframework.data.jpa.repository.JpaRepository

interface AlertRepository : JpaRepository<AlertLog, Long> {
    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>
}
