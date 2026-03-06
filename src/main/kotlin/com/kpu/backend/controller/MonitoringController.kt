package com.kpu.backend.controller

import com.kpu.backend.service.MonitoringService
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/monitoring")
@CrossOrigin(origins = ["*"])
class MonitoringController(
    private val monitoringService: MonitoringService
) {
    @GetMapping("/dashboard/summary")
    fun getDashboardSummary(@RequestParam companyId: String): ResponseEntity<Any> =
        ResponseEntity.ok(monitoringService.getSummary(companyId))

    @GetMapping("/logs")
    fun getLogs(@RequestParam companyId: String, @RequestParam(defaultValue = "100") limit: Int): ResponseEntity<Any> =
        ResponseEntity.ok(monitoringService.fetchLogs(companyId, limit))

    @GetMapping("/companies")
    fun getCompanies(): ResponseEntity<Any> = ResponseEntity.ok(monitoringService.getCompanies())
}
