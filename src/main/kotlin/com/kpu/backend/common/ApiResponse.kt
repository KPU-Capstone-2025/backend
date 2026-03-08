package com.kpu.backend.common

import com.fasterxml.jackson.annotation.JsonInclude


data class ApiResponse<T>(
    val isSuccess: Boolean,
    val code: String,
    val message: String,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val result: T? = null,
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val containers: List<T>? = null, 

    @JsonInclude(JsonInclude.Include.NON_NULL)
    val results: T? = null         
)