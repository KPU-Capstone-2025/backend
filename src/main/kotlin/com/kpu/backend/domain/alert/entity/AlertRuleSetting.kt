package com.kpu.backend.domain.alert.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "alert_rule_settings",
    uniqueConstraints = [UniqueConstraint(columnNames = ["company_id", "host_name"])]
)
class AlertRuleSetting(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "host_name")
    val hostName: String? = null,

    var cpuThreshold: Int = 80,
    var memoryThreshold: Int = 85,
    var diskThreshold: Int = 90,
    var diskIoThreshold: Long = 104857600,   // bytes/s (default 100MB/s)
    var userCountThreshold: Int = 10,
    var networkInThreshold: Long = 10485760,  // bytes/s (default 10MB/s)
    var networkOutThreshold: Long = 10485760,
    var durationSeconds: Int = 10
)
