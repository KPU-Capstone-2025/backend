package com.kpu.backend.domain.company.repository

import com.kpu.backend.domain.company.entity.Company
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CompanyRepository : JpaRepository<Company, Long> {

    fun findByEmail(email: String): Company?

    fun findByMonitoringId(monitoringId: String): Company?

    @Query("SELECT COALESCE(MAX(c.id), 0) FROM Company c")
    fun findMaxId(): Long
}
