package com.kpu.backend.domain.alert

import org.springframework.data.jpa.repository.JpaRepository

interface AlertRuleSettingRepository : JpaRepository<AlertRuleSetting, Long> {
    fun findByCompanyIdAndHostName(companyId: Long, hostName: String?): AlertRuleSetting?
}
