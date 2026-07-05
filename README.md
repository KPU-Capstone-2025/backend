# Monittoring — Backend

AI 기반 서버 모니터링 플랫폼의 Spring Boot 백엔드 서버

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.25-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/JDK-17-007396?logo=openjdk)](https://openjdk.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql)](https://www.mysql.com/)
[![AWS](https://img.shields.io/badge/AWS-ECS%20%7C%20RDS-FF9900?logo=amazonaws)](https://aws.amazon.com/)

---

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [시스템 아키텍처](#시스템-아키텍처)
- [기술 스택](#기술-스택)
- [주요 기능](#주요-기능)
- [API 명세](#api-명세)
- [도메인 구조](#도메인-구조)
- [시작하기](#시작하기)
- [환경변수 설정](#환경변수-설정)
- [배포](#배포)

---

## 프로젝트 개요

Monittoring은 서버 운영자가 여러 서버의 자원(CPU, 메모리, 디스크, 네트워크, 컨테이너)을 하나의 대시보드에서 실시간으로 모니터링하고, AI 기반 이상 감지·알림·챗봇 분석을 통해 장애를 사전에 예방할 수 있는 플랫폼입니다.

본 리포지토리는 플랫폼의 REST API 서버로, Prometheus·Loki 기반 메트릭/로그 수집 파이프라인과 OpenAI GPT를 연동하여 지능형 분석 기능을 제공합니다.

---

## 시스템 아키텍처

AWS Region `ap-northeast-2 (Seoul)`, VPC `10.0.0.0/16`, 2 AZ 구성.

```
Internet / Corp Agent
      │ HTTPS         │ :4318 (metrics+logs)
      ▼               ▼
┌─────────────────────────────────────────────────────────────────┐
│  AWS ALB  (캡스톤-elb)                                           │
│  :80/:443 → React(CloudFront) / Spring API                      │
│  :4318    → OTel Collector  (X-Server-Group 헤더로 기업 격리)    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
         ┌─────────────────┼───────────────────┐
         ▼                 ▼                   ▼
 ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐
 │ ECS Fargate  │  │  Jenkins     │  │  Per-Company EC2 (t3.small)│
 │ Spring Boot  │  │  CI/CD Server│  │  private-monitoring-c      │
 │ ← 본 리포지토리│  │ private-cicd │  │  Docker Compose:           │
 └──────┬───────┘  └──────┬───────┘  │  ├─ Prometheus  :9090     │
        │                 │          │  ├─ Grafana Loki :3100     │
        │           GitHub│Webhook   │  ├─ OTel Collector :4318   │
        │           → ECR │→ ECS     │  └─ AlertManager :9093     │
        ▼                 │          └──────────────┬─────────────┘
 ┌──────────────┐         ▼                         │
 │  RDS MySQL   │   ┌──────────┐    Spring Boot이    │
 │  Master+     │   │ Amazon   │    DB에서 EC2 IP 조회 │
 │  Multi-AZ    │   │   ECR    │    후 VPC 내부망으로  │
 └──────────────┘   └──────────┘    Prom/Loki 직접 쿼리
        │
        ├─ 사용자·서버 정보, 알림 규칙, 대화 이력
        └─ companyId → EC2 내부 IP 매핑 (Monitoring EC2 조회)
```

DNS (Gabia, monittoring.co.kr)

| 서브도메인 | 대상 | 역할 |
|----------|------|------|
| `api` | ALB CNAME | Spring Boot REST API |
| `data` | ALB :4318 | 에이전트 메트릭·로그 수신 |
| `agent` | S3 CNAME | 에이전트 설치 파일 |
| `jenkins` | ALB CNAME | Jenkins CI/CD UI |
| `www` | CloudFront | React 프론트엔드 |

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 | Kotlin 1.9.25 |
| 프레임워크 | Spring Boot 3.4.1 (Web, Security, Data JPA, Mail) |
| 런타임 | JDK 17 |
| 데이터베이스 | MySQL 8.0 (AWS RDS), H2 (테스트) |
| 메트릭 수집 | Prometheus HTTP API |
| 로그 수집 | Grafana Loki HTTP API |
| AI 분석 | OpenAI GPT-3.5-turbo (LangChain4j 0.30) |
| 인증 | JWT (jjwt 0.12.6) |
| 클라우드 | AWS ECS Fargate, ECR, RDS, ALB, EC2, SSM, S3, CloudFront (AWS SDK v2) |
| 이메일 | Gmail SMTP (Spring Mail) |
| API 문서 | Swagger / SpringDoc OpenAPI 2.3 |
| 빌드 | Gradle Kotlin DSL |

---

## 주요 기능

### 1. 실시간 서버 메트릭 수집
- Prometheus API를 직접 쿼리하여 CPU·메모리·디스크·네트워크 사용률을 실시간 제공
- 멀티호스트 지원: `host_name` 파라미터로 서버별 필터링
- 시계열 이력 조회: 최근 N분 범위 및 step 단위 설정 가능

### 2. 컨테이너 모니터링
- cAdvisor 기반 Docker 컨테이너별 CPU·메모리·네트워크 메트릭 수집
- 컨테이너 상태 목록 및 개별 메트릭 상세 조회

### 3. 사용자별 자원 사용 현황
- Prometheus의 `user_cpu_usage`, `user_memory_bytes` 메트릭으로 Linux 사용자별 실시간 자원 추적

### 4. AI 이상 탐지 및 자원 예측
- 이상 탐지: 현재 메트릭과 과거 평균을 비교하여 임계치 초과 항목 자동 식별
- 자원 예측: 선형 회귀 기반으로 디스크·메모리 고갈 예상 시점 계산

### 5. 로그 분석
- Loki LogQL로 컨테이너/호스트 로그를 severity·키워드·날짜 필터 조회
- AI 로그 분석: 단일 로그 라인을 GPT에 전달하여 원인 및 조치 방안 반환
- 일별 서버 상태 요약: 해당 날짜의 알람 이력 + ERROR 로그를 GPT가 종합 요약

### 6. 알림 시스템
- Prometheus Alertmanager 웹훅 수신 → AlertLog DB 저장
- 사용자 정의 임계치 규칙 설정 (CPU·메모리·디스크·네트워크 수신/송신·디스크 I/O·접속자 수)
- 이메일 알림: Gmail SMTP로 임계치 초과 시 즉시 발송

### 7. AI 챗봇
- LangChain4j + GPT-3.5-turbo 기반 서버 상태 문답 인터페이스
- 시스템 컨텍스트 주입: 최근 에러 로그 + SSH 공격 현황을 프롬프트에 포함
- 대화 이력 RDS 저장 및 조회

### 8. SSH 공격 분석
- Loki `{job="auth-log"}` 쿼리로 auth.log SSH 브루트포스 공격 실시간 집계
- 일별·시간대별 공격 횟수, 공격 IP 순위, 시도 계정명 분석
- bcrypt 기반 CPU 포화 시점 계산
- Loki 미연결 환경에서는 정적 fallback 데이터 반환

### 9. 월간 메트릭 리포트
- 월 단위 일별 CPU·메모리·디스크 평균/최대값 집계
- 날짜 범위 지정 조회 지원

---

## API 명세

Swagger UI: `http://{host}:8080/swagger-ui/index.html`

### Dashboard

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/api/dashboard/{companyId}/host` | 실시간 호스트 메트릭 |
| `GET` | `/api/dashboard/{companyId}/host/history` | 메트릭 시계열 이력 |
| `GET` | `/api/dashboard/{companyId}/hosts` | 발견된 호스트 목록 |
| `GET` | `/api/dashboard/{companyId}/users` | 사용자별 자원 사용량 |
| `GET` | `/api/dashboard/container/{companyId}` | 컨테이너 목록 및 상태 |
| `GET` | `/api/dashboard/{companyId}/container/{name}/metrics` | 컨테이너 메트릭 |
| `GET` | `/api/dashboard/{companyId}/logs` | 로그 조회 |
| `GET` | `/api/dashboard/{companyId}/anomaly` | AI 이상 탐지 결과 |
| `GET` | `/api/dashboard/{companyId}/prediction` | 자원 고갈 예측 |
| `GET` | `/api/dashboard/{companyId}/metrics/monthly` | 월간 일별 메트릭 |
| `GET` | `/api/dashboard/{companyId}/alerts/daily` | 일별 알람 + AI 요약 |
| `GET` | `/api/dashboard/{companyId}/alerts/daily/raw` | 일별 알람 원본 데이터 |
| `POST` | `/api/dashboard/logs/analyze` | 단일 로그 AI 분석 |

### Alert

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/api/rules/{companyId}` | 알림 규칙 조회 |
| `POST` | `/api/rules/update` | 알림 임계치 규칙 수정 |
| `POST` | `/api/alert/webhook` | Alertmanager 웹훅 수신 |

### Chat

| Method | Endpoint | 설명 |
|--------|----------|------|
| `POST` | `/api/chat/ask` | AI 챗봇 질의 |
| `GET` | `/api/chat/history/{monitoringId}` | 대화 이력 조회 |

### Security

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/api/security/ssh-stats/{companyId}` | SSH 공격 통계 (`?days=33`) |

### Company / Server

| Method | Endpoint | 설명 |
|--------|----------|------|
| `POST` | `/api/company/register` | 회사 등록 |
| `GET` | `/api/company/{id}` | 회사 정보 조회 |
| `POST` | `/api/server/register` | 서버 등록 |
| `GET` | `/api/server/{companyId}` | 서버 목록 조회 |

---

## 도메인 구조

```
src/main/kotlin/com/kpu/backend/
├── config/
│   ├── JwtFilter.kt
│   ├── JwtUtil.kt
│   ├── SecurityConfig.kt
│   ├── AwsConfig.kt
│   ├── ApiResponse.kt
│   └── exception/
├── domain/
│   ├── alert/
│   ├── chat/
│   ├── company/
│   ├── monitoring/
│   ├── security/
│   └── server/
└── infra/
    ├── AiService.kt
    ├── EmailNotificationService.kt
    └── NotificationService.kt
```

각 도메인은 controller → service (interface + impl) → repository → entity 구조를 따릅니다.

---

## 시작하기

사전 요구사항:
- JDK 17+
- MySQL 8.0 (또는 AWS RDS)
- 모니터링 대상 서버에 node_exporter, cAdvisor, Promtail 설치

```bash
./gradlew build
set -a && source .env && set +a
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

---

## 환경변수 설정

```env
# Database
DB_URL=jdbc:mysql://{RDS_ENDPOINT}:3306/{DB_NAME}
DB_USERNAME=
DB_PASSWORD=

# JWT
JWT_SECRET=

# OpenAI
OPENAI_API_KEY=

# Email (Gmail App Password)
MAIL_USERNAME=
MAIL_PASSWORD=

# AWS
AWS_VPC_ID=
AWS_SUBNET_PRIVATE_ID=
AWS_SG_MONITORING_ID=
AWS_ALB_DNS_NAME=
AWS_ALB_LISTENER_ARN=

# Monitoring
MONITORING_BACKEND_URL=http://{SERVER_IP}:8080
```

---

## 배포

### CI/CD 파이프라인

```
GitHub main 브랜치 Push
  → GitHub Webhook → Jenkins (ALB 경유, private-cicd-a 서브넷)
  → Gradle 빌드 → Docker 이미지 빌드
  → Amazon ECR Push (IAM Role 기반)
  → aws ecs update-service --force-new-deployment
  → ECS Fargate Rolling 배포 완료
```

### 수동 배포

```bash
./gradlew build

aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin {ECR_URI}
docker build -t capstone-backend .
docker tag capstone-backend:latest {ECR_URI}:latest
docker push {ECR_URI}:latest

aws ecs update-service --cluster capstone \
  --service backend-service --force-new-deployment
```

### 모니터링 스택 (기업별 자동 프로비저닝)

신규 기업 등록 시 Spring Boot가 AWS SDK로 t3.small EC2를 생성하고 Docker Compose로 아래 스택을 자동 배포합니다.

| 구성 요소 | 포트 | 역할 |
|----------|------|------|
| OTel Collector | 4318 | 에이전트 데이터 수신 → Prometheus/Loki 라우팅 |
| Prometheus | 9090 | 메트릭 시계열 저장·쿼리 |
| Grafana Loki | 3100 | 로그 저장·쿼리 |
| AlertManager | 9093 | 임계치 초과 알림 발송 |

---

## 팀

한국공학대학교 컴퓨터공학부 2025 캡스톤 디자인
GitHub Organization: [KPU-Capstone-2025](https://github.com/KPU-Capstone-2025)
