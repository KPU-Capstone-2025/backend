// src/main/kotlin/com/kpu/backend/service/NotificationService.kt
package com.kpu.backend.service

interface NotificationService {
    fun sendAlert(to: String, subject: String, content: String)
}

@org.springframework.stereotype.Service
class EmailNotificationService(
    private val mailSender: org.springframework.mail.javamail.JavaMailSender
) : NotificationService {

    override fun sendAlert(to: String, subject: String, content: String) {
        val message = org.springframework.mail.SimpleMailMessage()
        message.setTo(to)
        message.subject = subject
        message.text = content
        message.from = "jonghee12386@gmail.com"
        
        try {
            mailSender.send(message)
            println("이메일 발송 성공: $to")
        } catch (e: Exception) {
            println("이메일 발송 실패: ${e.message}")
        }
    }
}