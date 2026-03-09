package com.kpu.backend.repository

import com.kpu.backend.entity.Company
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByEmail(email: String): Company?
    
    @Query("SELECT COALESCE(MAX(c.id), 0) FROM Company c")
    fun findMaxId(): Long
    
    @Modifying
    @Query("UPDATE Company c SET c.id = c.id - 1 WHERE c.id > :deletedId")
    fun decrementIdsAfter(deletedId: Long)
    
    fun findAllByIdGreaterThan(id: Long): List<Company>
}