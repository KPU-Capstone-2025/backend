package com.kpu.backend.domain.alert.repository

import com.kpu.backend.domain.alert.entity.AlertRuleSetting
import org.springframework.data.jpa.repository.JpaRepository

interface AlertRuleSettingRepository : JpaRepository<AlertRuleSetting, Long> {

    fun findByCompanyIdAndHostName(companyId: Long, hostName: String?): AlertRuleSetting?
}
