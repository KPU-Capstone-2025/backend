package com.kpu.backend.controller

import com.kpu.backend.service.AlertService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/alerts")
class AlertController(
    private val alertService: AlertService 
) {

    @PostMapping("/webhook")
    fun receiveAlert(@RequestBody payload: Map<String, Any>) {
        alertService.processWebhook(payload)
    }
}