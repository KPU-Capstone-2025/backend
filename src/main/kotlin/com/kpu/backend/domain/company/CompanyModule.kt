package com.kpu.backend.domain.company

import jakarta.persistence.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.PriorityInUseException
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum
import java.time.LocalDateTime
import java.util.*

/* --- DTO --- */
data class CompanyRegisterRequest(val name: String, val email: String, val password: String, val ip: String, val phone: String)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val id: Long, val name: String, val monitoringId: String)
data class AgentDestination(val monitoringId: String, val collectorUrl: String)

/* --- Entity & Repository --- */
@Entity
@Table(name = "companies")
class Company(
    @Id var id: Long = 0,
    val name: String,
    @Column(unique = true) val email: String,
    val password: String,
    val phone: String,
    val ip: String,
    val monitoringId: String,
    val collectorUrl: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByEmail(email: String): Company?
    
    // 🌟 챗봇/알림 모듈에서 사용하는 핵심 함수!
    fun findByMonitoringId(monitoringId: String): Company?
    
    @Query("SELECT COALESCE(MAX(c.id), 0) FROM Company c") fun findMaxId(): Long
}

/* --- Service --- */
@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val ec2Client: Ec2Client,
    private val albClient: ElasticLoadBalancingV2Client,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String,
    @Value("\${aws.ami.id}") private val amiId: String,
    @Value("\${aws.vpc.id}") private val vpcId: String,
    @Value("\${aws.subnet.private.id}") private val subnetId: String,
    @Value("\${aws.sg.monitoring.id}") private val sgId: String
) {
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        return saveCompany(req, monitoringId)
    }

    @Transactional
    fun saveCompany(req: CompanyRegisterRequest, monitoringId: String): Company {
        val nextId = companyRepository.findMaxId() + 1
        return companyRepository.save(Company(id = nextId, name = req.name, email = req.email, password = req.password, phone = req.phone, ip = req.ip, monitoringId = monitoringId, collectorUrl = albDnsName))
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow()
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:80")
    }
    
    fun login(req: LoginRequest): Company? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        return if (company.password == req.password) company else null
    }
}

//Controller
@RestController
@RequestMapping("/api/company")
@CrossOrigin("*")
class CompanyController(
    private val companyService: CompanyService
) {
    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val company = companyService.login(req) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(LoginResponse(company.id, company.name, company.monitoringId))
    }

    @GetMapping("/agent/{companyId}")
    fun getAgent(@PathVariable companyId: Long) = ResponseEntity.ok(companyService.getAgentInfo(companyId))

    @PostMapping("/register")
    fun register(@RequestBody req: CompanyRegisterRequest): ResponseEntity<Map<String, String>> {
        companyService.registerAndProvision(req)
        return ResponseEntity.ok(mapOf("status" to "success", "message" to "회원가입 완료"))
    }
}