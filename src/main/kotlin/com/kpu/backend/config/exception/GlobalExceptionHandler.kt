package com.kpu.backend.config.exception

import com.kpu.backend.config.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("BusinessException [{}] {}", e.errorCode.code, e.message)
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ApiResponse(isSuccess = false, code = e.errorCode.code, message = e.message ?: e.errorCode.message))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("IllegalArgumentException: {}", e.message)
        return ResponseEntity
            .status(400)
            .body(ApiResponse(isSuccess = false, code = "COMMON-400", message = e.message ?: "요청 파라미터가 올바르지 않습니다."))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ApiResponse<Nothing>> {
        log.error("IllegalStateException: {}", e.message)
        return ResponseEntity
            .status(500)
            .body(ApiResponse(isSuccess = false, code = "COMMON-500", message = e.message ?: "서버 내부 오류가 발생했습니다."))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        log.warn("ValidationException: {}", detail)
        return ResponseEntity
            .status(400)
            .body(ApiResponse(isSuccess = false, code = "COMMON-400", message = detail))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", e)
        return ResponseEntity
            .status(500)
            .body(ApiResponse(isSuccess = false, code = "COMMON-500", message = "서버 내부 오류가 발생했습니다."))
    }
}
