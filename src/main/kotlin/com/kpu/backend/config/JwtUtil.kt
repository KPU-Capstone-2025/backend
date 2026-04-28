package com.kpu.backend.config

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*

@Component
class JwtUtil(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.expiration-ms:86400000}") private val expirationMs: Long  // 기본 24시간
) {
    private val key by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    fun generateToken(companyId: Long, monitoringId: String): String =
        Jwts.builder()
            .subject(companyId.toString())
            .claim("monitoringId", monitoringId)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun getCompanyId(token: String): Long =
        parseClaims(token).subject.toLong()

    fun getMonitoringId(token: String): String =
        parseClaims(token).get("monitoringId", String::class.java)

    fun validate(token: String): Boolean = try {
        parseClaims(token)
        true
    } catch (e: JwtException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun parseClaims(token: String) =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
