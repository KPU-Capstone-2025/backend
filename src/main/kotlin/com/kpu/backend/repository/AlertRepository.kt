package com.kpu.backend.repository

import com.kpu.backend.entity.AlertLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AlertRepository : JpaRepository<AlertLog, Long> {
    fun findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<AlertLog>
}