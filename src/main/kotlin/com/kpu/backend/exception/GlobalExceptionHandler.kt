package com.kpu.monitor.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// 에러 응답용 DTO (간단하게 내부에 정의하거나 dto 패키지로 빼셔도 됩니다)
data class ErrorResponse(val success: Boolean = false, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        e.printStackTrace() // 콘솔에 에러 로그 출력
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(message = e.message ?: "알 수 없는 서버 오류가 발생했습니다."))
    }
}