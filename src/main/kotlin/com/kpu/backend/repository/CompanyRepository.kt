package com.kpu.backend.repository

import com.kpu.backend.entity.Company
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CompanyRepository : JpaRepository<Company, Long> {
    // 마지막 우선순위 값을 찾아서 +1 할 때 사용 (규칙 충돌 방지)
    @Query("SELECT COALESCE(MAX(c.priority), 0) FROM Company c")
    fun findMaxPriority(): Int
}