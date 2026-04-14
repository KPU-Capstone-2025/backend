package com.kpu.backend.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
class EmailNotificationService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username}") private val fromEmail: String
) : NotificationService {
    private val log = LoggerFactory.getLogger(EmailNotificationService::class.java)

    override fun sendAlert(to: String, subject: String, content: String) {
        try {
            val message = SimpleMailMessage().apply {
                setTo(to)
                setSubject(subject)
                setText(content)
                setFrom(fromEmail)
            }
            mailSender.send(message)
            log.info("이메일 발송 완료 -> $to")
        } catch (e: Exception) {
            log.error("이메일 발송 실패: ${e.message}")
        }
    }
}
