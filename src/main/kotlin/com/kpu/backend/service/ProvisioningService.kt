package com.kpu.backend.service

import com.kpu.backend.domain.User
import com.kpu.backend.dto.LoginRequest
import com.kpu.backend.dto.SignUpRequest
import com.kpu.backend.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val ec2Service: Ec2Service,
    private val passwordEncoder: PasswordEncoder
) {
    @Transactional
    fun signUp(request: SignUpRequest): String {
        // 1. 중복 체크
        if (userRepository.existsByEmail(request.email)) {
            throw IllegalArgumentException("이미 가입된 이메일입니다.")
        }

        // 2. 비밀번호 암호화
        val encodedPassword = passwordEncoder.encode(request.password)

        // 3. EC2 생성 (Ec2Service 호출)
        println(" [${request.companyName}] EC2 생성 요청...")
        val instanceId = ec2Service.createSecureEc2(request.companyName, request.companyIp)

        // 4. DB 저장
        val user = User(
            name = request.name,
            phone = request.phone,
            email = request.email,
            password = encodedPassword,
            companyName = request.companyName,
            companyIp = request.companyIp,
            ec2InstanceId = instanceId
        )
        userRepository.save(user)

        return "가입 완료! 서버 ID: $instanceId"
    }

    fun login(request: LoginRequest): String {
        val user = userRepository.findByEmail(request.email)
            ?: throw IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.")
            
        if (!passwordEncoder.matches(request.password, user.password)) {
            throw IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        return "로그인 성공! ${user.name}님 (서버: ${user.ec2InstanceId})"
    }
}