package com.kpu.backend.config.exception

class BusinessException(
    val errorCode: ErrorCode,
    message: String = errorCode.message
) : RuntimeException(message)
