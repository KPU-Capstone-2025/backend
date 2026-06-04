package com.kpu.backend.domain.alert.dto

import com.kpu.backend.domain.alert.entity.AlertRuleSetting

data class RuleUpdateRequest(
    val companyId: String? = null,
    val monitoringId: String? = null,
    val hostName: String? = null,
    val cpuThreshold: Int = 80,
    val memoryThreshold: Int = 85,
    val diskThreshold: Int = 90,
    val diskIoThreshold: Long = 104857600,
    val userCountThreshold: Int = 10,
    val networkInThreshold: Long = 10485760,
    val networkOutThreshold: Long = 10485760,
    val durationSeconds: Int = 10
)

data class AlertRuleSettingResponse(
    val id: Long,
    val companyId: Long,
    val hostName: String?,
    val cpuThreshold: Int,
    val memoryThreshold: Int,
    val diskThreshold: Int,
    val diskIoThreshold: Long,
    val userCountThreshold: Int,
    val networkInThreshold: Long,
    val networkOutThreshold: Long,
    val durationSeconds: Int
) {
    companion object {
        fun from(entity: AlertRuleSetting) = AlertRuleSettingResponse(
            id = entity.id,
            companyId = entity.companyId,
            hostName = entity.hostName,
            cpuThreshold = entity.cpuThreshold,
            memoryThreshold = entity.memoryThreshold,
            diskThreshold = entity.diskThreshold,
            diskIoThreshold = entity.diskIoThreshold,
            userCountThreshold = entity.userCountThreshold,
            networkInThreshold = entity.networkInThreshold,
            networkOutThreshold = entity.networkOutThreshold,
            durationSeconds = entity.durationSeconds
        )
    }
}
