package com.kpu.backend.domain.alert.service

import com.kpu.backend.domain.alert.dto.AlertRuleSettingResponse
import com.kpu.backend.domain.alert.dto.RuleUpdateRequest

interface AlertRuleServicePort {

    fun getRuleSetting(companyId: Long, hostName: String?): AlertRuleSettingResponse

    fun updateRules(request: RuleUpdateRequest)
}
