package com.kpu.backend.config.exception

enum class ErrorCode(val status: Int, val code: String, val message: String) {

    // 400 Bad Request
    INVALID_INPUT(400, "COMMON-400", "요청 파라미터가 올바르지 않습니다."),
    MISSING_REQUIRED_FIELD(400, "COMMON-401", "필수 파라미터가 누락되었습니다."),

    // 404 Not Found
    COMPANY_NOT_FOUND(404, "COMPANY-001", "해당 회사를 찾을 수 없습니다."),
    MONITORING_SERVER_NOT_FOUND(404, "ALERT-001", "모니터링 서버를 찾을 수 없습니다."),
    ALERT_RULE_NOT_FOUND(404, "ALERT-002", "알림 규칙 설정을 찾을 수 없습니다."),

    // 500 Internal Server Error
    RULE_DEPLOY_FAILED(500, "ALERT-500", "알림 규칙 배포 중 오류가 발생했습니다."),
    INTERNAL_SERVER_ERROR(500, "COMMON-500", "서버 내부 오류가 발생했습니다."),
}
