package com.kpu.monitor.entity

import jakarta.persistence.*

@Entity
@Table(name = "companies")
class Company(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val companyId: String,

    @Column(nullable = false)
    val instanceId: String,

    @Column(nullable = false)
    val targetGroupArn: String,

    @Column(nullable = false)
    val albRuleArn: String,

    @Column(nullable = false)
    val priority: Int
)