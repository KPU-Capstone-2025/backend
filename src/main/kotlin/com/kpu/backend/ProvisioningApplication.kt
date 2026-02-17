package com.kpu.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.boot.autoconfigure.domain.EntityScan

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.kpu.backend.repository"])
@EntityScan(basePackages = ["com.kpu.backend.domain"])
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}