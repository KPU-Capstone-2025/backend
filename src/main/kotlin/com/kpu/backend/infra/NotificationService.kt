package com.kpu.backend.infra

interface NotificationService {
    fun sendAlert(to: String, subject: String, content: String)
}
