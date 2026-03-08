package com.kpu.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.kpu.backend.repository"]) // 리포지토리 위치 지정
@EntityScan(basePackages = ["com.kpu.backend.entity", "com.kpu.backend.domain"]) // 엔티티 위치 지정 (entity와 domain 둘 다 스캔)
class ProvisioningApplication

fun main(args: Array<String>) {
    runApplication<ProvisioningApplication>(*args)
}