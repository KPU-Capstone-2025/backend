package com.kpu.backend.common

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 모든 API 응답에 공통적으로 사용되는 규격입니다.
 */
data class ApiResponse<T>(
    val isSuccess: Boolean,
    val code: String,
    val message: String,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val result: T? = null,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val containers: List<T>? = null, // 목록 조회용 필드
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val results: T? = null          // 상세 조회용 필드
)