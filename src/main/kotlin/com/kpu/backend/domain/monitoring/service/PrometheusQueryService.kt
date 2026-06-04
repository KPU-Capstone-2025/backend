package com.kpu.backend.domain.monitoring.service

interface PrometheusQueryService {
    fun queryRange(query: String, prometheusUrl: String, start: Long, end: Long, step: Int): List<Pair<Long, Double>>
    fun querySingleValue(query: String, prometheusUrl: String): Double?
}
