# EP-10 클라우드 인프라 구축 & 무중단/카오스/부하 테스트 실행 계획

> `LARGE_SCALE_TRAFFIC_SPEC.md`(EP-9)가 "왜 이런 패턴이 필요한가"를 정리한 **개념 참고 문서**라면,
> 이 문서는 그중 일부를 실제로 **손으로 돌려보는 실행 계획**이다. 로컬이 아니라 AWS EC2(프리티어) +
> k3s(경량 Kubernetes) 위에서, 무중단 배포·카오스 테스트·대규모 부하 테스트를 직접 실행해보고
> 그 과정에서 실무에서 실제로 터지는 문제들을 재현/관찰하는 것이 목표.
>
> 비용 원칙: **클러스터는 상시로 켜두지 않는다.** 개발/설정은 EC2 프리티어(t3.micro) 1대로 충분하고,
> 부하 테스트처럼 CPU/네트워크를 많이 쓰는 순간만 스팟 인스턴스를 몇 시간 켰다 끈다.

## 0. 왜 EC2 + k3s인가 (관리형 EKS 대신)

| | EKS(관리형) | EC2 + k3s(직접 구성) |
|---|---|---|
| 클러스터 비용 | 컨트롤플레인만 시간당 과금 (24시간 켜두면 월 7~8만 원) | 0원 (EC2 요금에 포함) |
| 실제로 배우는 것 | kubectl로 리소스 다루는 법 | 그거 + 클러스터 자체가 어떻게 뜨는지(컨트롤플레인/kubelet/CNI) |
| 이력서 임팩트 | "EKS 운영 경험" (신입 기준 과함) | "쿠버네티스 클러스터 구성부터 무중단/부하 테스트까지" (과정 서술 가능) |

포트폴리오 단계에서는 k3s가 정직하고 저렴한 선택. 나중에 실무에서 EKS/GKE를 만나도 개념은 동일해서 전환 비용이 크지 않음.

---

## 1. 인프라 구성 (1단계)

```
[GitHub Actions] --build & push--> [ECR 또는 Docker Hub]
                                          │
                                          ▼
                              [EC2 (k3s single-node)]
                              ┌─────────────────────┐
                              │ Ingress (Traefik)    │
                              │  └─ account-service  │ (Deployment, replica 2~3)
                              │       └─ MySQL (RDS, 클러스터 밖으로 분리)
                              │  └─ kube-prometheus-stack│
                              │       (Prometheus+Grafana+Alertmanager)
                              └─────────────────────┘
```

- **MySQL은 클러스터 안에 안 둔다.** 파드는 재시작/삭제가 일상인데 DB가 그 안에 있으면 데이터가 위험함 — 기존처럼 RDS(관리형)를 그대로 쓰고, 클러스터에서는 접속만 함.
- k3s는 설치 스크립트 한 줄(`curl -sfL https://get.k3s.io | sh -`)로 EC2 하나에 컨트롤플레인+워커가 같이 뜸. 노드가 1대뿐이라 "여러 서버 간 장애 전파" 같은 진짜 분산 환경 특유의 문제까진 재현 못 하지만, 롤링 업데이트/헬스체크/오토스케일링 같은 핵심 개념은 노드 수와 무관하게 재현됨.
- Ingress는 NGINX보다 k3s 기본 내장인 Traefik이 설정이 적어 이 단계에선 더 적합.

### IS-32: EC2 + k3s 클러스터 구성

- [ ] EC2(t3.micro, 프리티어) 인스턴스 생성, 보안그룹에서 6443(k3s API), 80/443(Ingress)만 개방
- [ ] k3s 설치, `kubectl get nodes`로 확인
- [ ] Dockerfile 작성 (현재 `cd.yml`은 jar를 직접 실행 — 컨테이너 이미지로 전환)
- [ ] `Deployment` + `Service` + `Ingress` 매니페스트 작성, RDS 접속 정보는 `Secret`으로 관리 (하드코딩 금지, `CLAUDE.md` 4번 원칙과 동일한 이유)
- [ ] GitHub Actions에서 이미지 빌드 → 레지스트리 푸시 → `kubectl set image`까지 CD 파이프라인 갱신

---

## 2. Prometheus/Grafana 스택 (2단계)

금융권을 포함해 실무에서 쓰는 조합은 거의 예외 없이 **kube-prometheus-stack** (Helm 차트 하나로 Prometheus + Grafana + Alertmanager + node-exporter + kube-state-metrics가 한 번에 설치됨). 이걸 직접 하나하나 설정하는 회사는 거의 없음 — 이 차트를 설치하고, 우리 서비스만 추가로 연결하는 게 실무 패턴.

```
helm install monitoring prometheus-community/kube-prometheus-stack -n monitoring
```

- `ServiceMonitor` 리소스로 "이 Service의 `/actuator/prometheus`를 긁어라"고 Prometheus에 등록 (IS-20에서 만든 엔드포인트를 여기 연결하는 게 이번 단계의 핵심)
- Grafana에는 **Golden Signals**(Google SRE에서 말하는 4대 관측 지표) 대시보드를 기본으로 둠:
  - **Latency**(응답 지연) — p50/p95/p99를 다 봐야 함, 평균만 보면 소수의 느린 요청이 묻힘
  - **Traffic**(트래픽량) — TPS
  - **Errors**(에러율) — HTTP 5xx 비율
  - **Saturation**(포화도) — CPU/메모리/커넥션풀 사용률, "터지기 직전"을 미리 감지하는 지표
- **Alertmanager 규칙 예시**:
  ```yaml
  - alert: HighErrorRate
    expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.05
    for: 5m
    annotations:
      summary: "에러율 5% 초과가 5분 지속됨"
  ```
- 금융권 특유의 알람은 "정상 응답인데 값이 이상한" 비즈니스 메트릭 — 예: 1분간 이체 실패(잔고부족 제외) 건수 급증, 동일 계좌로의 반복 이체 시도 급증(이상 거래 패턴). 이건 인프라 메트릭이 아니라 우리 서비스 코드에서 Micrometer 커스텀 카운터로 직접 만들어야 함.

### IS-33: kube-prometheus-stack 설치 및 대시보드 구성

- [ ] Helm으로 kube-prometheus-stack 설치
- [ ] `ServiceMonitor`로 account-service의 `/actuator/prometheus` 연동
- [ ] Golden Signals 대시보드 구성 (Grafana에서 직접 패널 구성 또는 커뮤니티 대시보드 import)
- [ ] Alertmanager 규칙 2~3개 작성 (에러율, p99 지연시간, 파드 재시작 횟수)

---

## 3. 무중단 배포 테스트 (3단계)

### "무중단"이 기술적으로 뭘 의미하는가

롤링 업데이트 중에도 **바깥에서 API를 계속 두드리는 클라이언트 입장에서 실패(에러/타임아웃)가 0건**이어야 진짜 무중단. 파드가 하나씩 죽고 새로 뜨는 과정 자체는 당연히 일어나지만, 그 과정이 트래픽에 티가 안 나야 함.

이걸 위해 최소한 필요한 설정:

| 설정 | 없으면 생기는 문제 |
|---|---|
| `readinessProbe` | 새 파드가 뜨자마자(아직 DB 커넥션풀 초기화도 안 끝났는데) 트래픽을 받아서 초반 요청들이 실패 |
| `terminationGracePeriodSeconds` + graceful shutdown (`server.shutdown=graceful`) | SIGTERM 받자마자 프로세스가 즉사 → 처리 중이던 요청이 응답을 못 받고 끊김 |
| `preStop` hook (몇 초 sleep) | Pod가 "종료 시작"과 "Service 엔드포인트에서 빠지는 것" 사이에 시차가 있어서, 이미 죽어가는 파드로 새 요청이 몇 초간 계속 들어옴 |
| `maxUnavailable: 0` (RollingUpdate 전략) | 한 번에 여러 파드가 같이 내려가서 순간적으로 처리 가능 용량 자체가 부족해짐 |

### 테스트 방법

1. `k6`나 간단한 `while` 반복문으로 배포 내내(예: 2~3분) 계속 API를 호출하면서 성공/실패를 카운트
2. 그 와중에 `kubectl rollout restart deployment/account-service` 실행
3. 롤아웃이 끝난 뒤 실패 건수를 확인 — 목표는 **0건**
4. 위 표의 설정을 하나씩 일부러 빼고 재현해보면, 실패가 몇 건씩 찍히는 게 눈으로 보임 (이게 "느낌으로 이해하기"에 제일 좋은 방법 — 설정 있을 때/없을 때를 직접 비교)

### 실무에서 실제로 자주 터지는 문제 (사용자가 물어본 "무중단에서 보통 발생하는 문제")

| # | 문제 | 원인 | 증상 |
|---|---|---|---|
| 1 | 처리 중이던 요청이 끊김 | Graceful shutdown 미설정, SIGTERM에 즉시 종료 | 배포 중 순간적으로 500/커넥션 리셋 발생 |
| 2 | 새 파드가 준비 안 된 채로 트래픽 받음 | readinessProbe 없음 or 너무 관대한 조건 | 배포 직후 몇 초간 에러율 스파이크 |
| 3 | 죽은 파드로 요청이 계속 감 | Service 엔드포인트 갱신 지연(kube-proxy), preStop 없음 | 종료 직전 파드로 요청 몇 건이 새어 들어감 |
| 4 | 롤링 업데이트 중 커넥션풀 고갈 | 여러 파드가 동시에 재시작하며 DB 커넥션을 한꺼번에 재생성 | DB `max_connections` 근처에서 순간적으로 커넥션 대기/거부 |
| 5 | 신/구 버전 동시 존재로 인한 스키마 불일치 | 롤링 업데이트 중간엔 구버전+신버전 파드가 같이 떠 있음 | 신규 컬럼을 참조하는 신버전 파드가 아직 마이그레이션 안 된 DB를 침 (또는 그 반대) — 이게 실무에서 제일 무서운 유형 |
| 6 | 세션/WebSocket 끊김 | Stateless 원칙 위반, sticky session 의존 | 롤링 업데이트 중 로그인 풀림, 실시간 연결 끊김 |

5번(스키마 불일치)이 특히 중요한데, 우리 프로젝트는 이체/잔고를 다루므로 **DB 마이그레이션은 항상 "구버전 코드도 신버전 코드도 둘 다 돌아가는 중간 상태"를 가정하고 짜야 함** — 컬럼 추가는 nullable로 먼저 배포하고 채우는 배치를 나중에 돌리는 식(expand-contract 패턴). 컬럼 삭제/타입 변경은 절대 한 번의 배포로 하지 않음.

### IS-34: 무중단 배포 설정 및 검증

- [ ] `readinessProbe`/`livenessProbe`, graceful shutdown, `preStop`, `maxUnavailable: 0` 적용
- [ ] k6로 배포 중 연속 요청을 날리며 `kubectl rollout restart` 실행, 실패 건수 0 확인
- [ ] 각 설정을 하나씩 제거하고 재현 → 실패가 발생하는 것을 직접 관찰하고 기록 (트러블슈팅 스토리로 README/문서화 가치 있음)

---

## 4. 카오스 엔지니어링 — 일부러 공격해서 문제 일으키기 (4단계)

무중단 배포 테스트가 "정상적인 배포 흐름"을 점검한다면, 카오스 테스트는 **예고 없이 뭔가 망가졌을 때도 버티는가**를 점검. Netflix의 Chaos Monkey가 원조 — "프로덕션에서 무작위로 서버를 죽여보면서, 그래도 서비스가 안 죽는지 평소에 검증해두자"는 발상.

### 시나리오 목록

| 공격 | 실행 방법(예시) | 확인할 것 |
|---|---|---|
| 파드 강제 종료 | `kubectl delete pod <name> --grace-period=0 --force` | Service가 즉시 다른 파드로 트래픽을 돌리는지, 요청 실패가 있는지 |
| 노드 드레인 | `kubectl drain <node>` | 파드가 다른 곳으로 정상 재스케줄되는지(단일 노드 환경에선 제한적으로만 재현 가능) |
| CPU/메모리 리소스 제한 | Deployment에 낮은 `resources.limits` 설정 후 부하 유발 | OOMKilled로 죽었을 때 자동 재시작되는지, 그 사이 요청은 어떻게 되는지 |
| 네트워크 지연/유실 주입 | Chaos Mesh의 `NetworkChaos` (지연 200ms, 패킷 유실 10% 등) | Timeout/Retry/Circuit Breaker(`LARGE_SCALE_TRAFFIC_SPEC.md` 1번)가 실제로 작동하는지 |
| DB 커넥션 강제 차단 | RDS 보안그룹 순간적으로 막기 or `iptables`로 3306 차단 | 애플리케이션이 500을 계속 뿜는 대신 Circuit Breaker로 빠르게 fail-fast 하는지 |
| 의존 서비스 응답 지연 | (Phase 2/3에서 외부 API 붙으면) 응답을 일부러 느리게 | Bulkhead로 다른 기능까지 같이 멈추지 않는지 |

### 도구

- **Chaos Mesh**: 쿠버네티스 네이티브 카오스 엔지니어링 도구, YAML로 "이 파드를 죽여라/네트워크를 지연시켜라"를 선언적으로 정의. 이 프로젝트 규모(단일 노드)에서도 설치·사용 가능.
- **AWS FIS(Fault Injection Simulator)**: AWS 관리형 카오스 도구, EC2/RDS 레벨 장애도 주입 가능하지만 관리형이라 비용이 붙음 — 지금 단계에선 Chaos Mesh로 충분.

카오스 테스트는 "터지는 걸 확인하는 것"이 아니라 **"우리가 만든 안전장치(Circuit Breaker, Retry, HPA, readinessProbe)가 실제로 의도한 대로 동작하는지 증명하는 것"**이 목적. 안전장치 없이 카오스 테스트를 돌리면 그냥 장애 재현일 뿐이라, IS-25/26(Rate Limiting, Circuit Breaker)이 먼저 구현돼 있어야 의미가 있음.

### IS-35: Chaos Mesh 도입 및 시나리오 3종 실행

- [ ] Chaos Mesh 설치
- [ ] 파드 강제 종료, 네트워크 지연, DB 연결 차단 3개 시나리오 실행
- [ ] 각 시나리오에서 에러율/복구 시간을 Grafana로 관찰하고 기록 (Before: 안전장치 없을 때 vs After: 있을 때 비교하면 제일 설득력 있는 결과물이 됨)

---

## 5. 대규모 트래픽 부하 테스트 (5단계)

### 부하 테스트의 4가지 종류 (목적이 다름)

| 종류 | 목적 | 방법 |
|---|---|---|
| **Load Test** | "목표 트래픽을 문제없이 견디는가" 확인 | 목표 TPS로 일정 시간 유지 |
| **Stress Test** | "어디까지 견디다가 터지는가"(한계점, breaking point) 찾기 | 트래픽을 계속 증가시키며 에러율/지연시간이 꺾이는 지점 관찰 |
| **Soak(Endurance) Test** | 오래 켜뒀을 때만 드러나는 문제(메모리 누수, 커넥션 누수) 발견 | 중간 강도 트래픽을 몇 시간 이상 유지 |
| **Spike Test** | 순간 폭증(이벤트, 뉴스 등)에도 버티는가 | 짧은 시간에 트래픽을 몇 배로 확 올렸다가 내림 |

포트폴리오 단계에서 제일 가치있는 건 절대적인 숫자(초당 몇만 건)가 아니라 **Stress Test로 "우리 구조의 한계점을 직접 찾고, 병목이 뭔지 특정하고, 대응책을 근거를 들어 적용한 뒤 재측정으로 개선을 증명하는" 과정**. 면접관도 "초당 10만 건 처리했어요"보다 "500 TPS에서 DB 커넥션풀이 병목이라 HikariCP 설정을 조정했더니 900 TPS까지 늘었어요" 쪽을 훨씬 신뢰함.

### 목표 트래픽 수치를 어떻게 정하나

임의로 큰 숫자를 잡기보다, 시나리오를 근거로 역산하는 게 설득력 있음:

```
가정: 동시 접속 사용자 500명, 각자 평균 10초에 1번 요청(조회 위주)
→ 500 / 10 = 초당 50건이 "평상시" 트래픽

여기에 피크 배율(은행 앱은 급여일/장 시작 시간대에 평소 대비 5~10배 몰림을 가정)을 곱함
→ 50 * 8 ≈ 400 TPS를 "우리가 견뎌야 하는 목표"로 설정
```

실행 순서 추천:
1. **Load Test**: 낮은 값(50 TPS)부터 시작해 목표(400 TPS)까지 문제없이 견디는지 확인
2. **Stress Test**: 400에서 멈추지 말고 계속 올려서(600, 800, 1000...) 에러율이 튀거나 p99 지연시간이 급격히 나빠지는 지점을 찾음 — 그 지점이 "우리 시스템의 실제 한계"
3. 한계점에서 병목 확인(Grafana Saturation 패널로 CPU/커넥션풀/DB 중 뭐가 먼저 포화되는지 특정)
4. `LARGE_SCALE_TRAFFIC_SPEC.md`의 해당 패턴(Read Replica, 캐싱, Connection Pool 튜닝 등) 적용
5. 같은 Stress Test 재실행 → 한계점이 얼마나 올라갔는지로 개선을 정량 증명

### 도구: k6

```javascript
import http from 'k6/http';
export const options = {
  stages: [
    { duration: '1m', target: 50 },   // 워밍업
    { duration: '3m', target: 400 },  // 목표 트래픽 유지 (Load Test)
    { duration: '2m', target: 800 },  // 한계점 탐색 (Stress Test)
  ],
};
export default function () {
  http.get('https://api.example.com/api/accounts/xxx');
}
```
JMeter/nGrinder도 실무에서 쓰이지만, k6는 코드로 시나리오를 관리할 수 있어 최근 실무에서 더 흔하게 채택되는 추세.

### 그 트래픽을 버티기 위한 인프라 구조

이 부분은 `LARGE_SCALE_TRAFFIC_SPEC.md`에 이미 상세히 정리돼 있음 — 이번 실행 계획에서는 그중 **Stress Test로 실제로 병목이 확인된 부분만 순서대로 적용**하는 게 원칙 (병목이 아닌 곳을 먼저 최적화하는 건 낭비).

| 병목 위치(Saturation 지표로 확인) | 적용할 패턴 |
|---|---|
| DB 커넥션풀 대기 | Connection Pool 튜닝, PgBouncer/ProxySQL |
| 인기 계좌/조회 API에 몰림 | Redis 캐싱(Cache-Aside) |
| CPU 포화, 인스턴스 하나로 부족 | HPA로 파드 수평 확장 |
| 특정 계좌에 쓰기 경합 | Kafka 파티셔닝(IS-24)으로 직렬화 |
| 다운스트림 장애로 전체 지연 | Circuit Breaker/Bulkhead(IS-26) |

### IS-36: k6 부하 테스트 실행 및 병목 개선 사이클

- [ ] k6로 Load Test(400 TPS 목표) 실행, Grafana로 관찰
- [ ] Stress Test로 한계점(breaking point) 특정
- [ ] Saturation 지표로 병목 원인 특정
- [ ] 위 표 기준으로 대응 패턴 1개 적용
- [ ] 동일 Stress Test 재실행, 개선 수치를 기록(Before/After 비교표로 문서화)

---

## 6. 상시 헬스체크 (Synthetic Monitoring)

### Prometheus만으로는 왜 부족한가

Prometheus는 **pull 방식** — 우리 서버가 살아있어야 `/actuator/prometheus`를 긁어갈 수 있음. 서버가 완전히 죽어버리면 Prometheus 입장에선 "스크래핑 실패"만 보일 뿐, 그 자체가 즉각적인 장애 알람으로 잘 연결되지 않는 경우가 많음. Synthetic monitoring은 **바깥에서(제3자 관점으로) 진짜 사용자처럼 주기적으로 요청을 날려서, 응답이 오는지/얼마나 걸리는지를 감시**하는 것 — 관측 방향이 반대(inside-out이 아니라 outside-in).

### 실무에서 쓰는 방식

- **SaaS**: Pingdom, UptimeRobot, Datadog Synthetics, AWS CloudWatch Synthetics — 여러 지리적 리전에서 체크 가능, 회사 규모가 커지면 이쪽으로 감
- **자체 구현**: 소규모 서비스/개인 프로젝트에선 GitHub Actions의 `schedule` 트리거로 충분 — 비용 0원, 별도 인프라 불필요, 그 자체가 "GitHub 서버 → 우리 서버"라는 외부 관점 체크 조건도 만족함

### 구현 방식 (그대로 따라 하면 됨)

1. `.github/workflows/synthetic-check.yml` 신규 workflow 작성
2. `schedule: - cron: '*/5 * * * *'` (5분 간격 — GitHub Actions schedule의 사실상 최소 단위)
3. `/actuator/health`를 curl로 호출 (DB 커넥션까지 검증되는 엔드포인트라 "앱은 떴는데 DB가 끊긴" 상황도 잡아냄)
4. HTTP 200이 아니면 실패로 간주, Slack/Discord webhook으로 알림 발송 후 워크플로 자체도 실패 처리(GitHub Actions 탭에서도 빨간 표시로 남아 이력 추적 가능)

```yaml
name: Synthetic Health Check

on:
  schedule:
    - cron: '*/5 * * * *'
  workflow_dispatch: {}   # 수동으로도 즉시 실행해볼 수 있게

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - name: Check /actuator/health
        run: |
          STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://${{ secrets.PROD_HOST }}/actuator/health)
          if [ "$STATUS" != "200" ]; then
            curl -X POST -H 'Content-type: application/json' \
              --data "{\"text\":\"🚨 헬스체크 실패: HTTP $STATUS\"}" \
              "${{ secrets.SLACK_WEBHOOK_URL }}"
            exit 1
          fi
```

- `PROD_HOST`, `SLACK_WEBHOOK_URL`은 GitHub Secrets에 등록 (하드코딩 금지 — `CLAUDE.md` 4번 원칙과 동일한 이유)
- Slack Incoming Webhook은 워크스페이스에서 앱 하나 추가하면 URL 하나 발급되는 게 전부라 설정 부담 적음

### 이 방식의 한계 (실무 감각 어필 포인트이기도 함)

- GitHub Actions의 `schedule`은 "정각 보장"이 아니라 GitHub 인프라가 바쁘면 몇 분 밀릴 수 있음 — 실제 프로덕션에서 초 단위 정밀도가 필요하면 SaaS나 자체 크론 서버를 씀
- 단일 실패로 바로 알람을 쏘면 일시적 네트워크 흔들림에도 오탐(false positive)이 남 — 개선하려면 "2회 연속 실패 시에만 알림" 같은 조건을 넣으면 됨 (지금은 단순하게 시작하고, 오탐이 실제로 거슬리면 그때 추가 — YAGNI)

### IS-37: Synthetic Monitoring(상시 헬스체크) 도입

- [ ] `.github/workflows/synthetic-check.yml` 작성 (5분 간격 cron + 수동 트리거)
- [ ] `/actuator/health` 상태코드 확인, 실패 시 Slack webhook 알림
- [ ] Secrets에 `PROD_HOST`, `SLACK_WEBHOOK_URL` 등록
- [ ] 일부러 서버를 잠깐 내려서 알림이 실제로 오는지 검증 (배포 테스트와 같은 원리 — 설정만 해두고 안 터뜨려보면 작동 여부를 모름)

---

## 7. 진행 순서 요약

```
IS-32 (k3s 클러스터) → IS-33 (Prometheus/Grafana)
        → IS-34 (무중단 배포 설정 및 검증)
        → IS-35 (카오스 테스트, IS-25/26 선행 필요)
        → IS-36 (부하 테스트 및 병목 개선 사이클)
        → IS-37 (상시 헬스체크, 위 단계와 독립적으로 아무 때나 붙여도 됨)
```

각 단계는 이전 단계가 있어야 다음 단계의 "관찰"이 가능함(모니터링 없이 부하 테스트를 하면 뭐가 병목인지 눈으로 못 봄) — 순서를 건너뛰지 않는 게 중요. IS-37만 예외적으로 순서 무관 — Prometheus 스택과 별개로 언제든 추가 가능.

## 8. 비용 관리 체크리스트

- [ ] EC2는 t3.micro(프리티어) 1대만 상시 유지, 부하 테스트용 추가 인스턴스는 테스트 직전 생성/직후 종료
- [ ] Stress/Spike Test처럼 트래픽을 많이 만드는 시간대는 미리 시간을 정해두고 그 시간에만 실행 (몇 시간 단위)
- [ ] RDS도 프리티어(db.t3.micro) 사용, 부하 테스트 후 스토리지 급증 여부 확인(로그 테이블 등)
- [ ] AWS Budget 알람을 걸어서 예상치 못한 과금(특히 트래픽/스토리지) 조기 감지
- [ ] Synthetic check는 5분 간격이라도 GitHub Actions 무료 티어(퍼블릭 레포는 무제한, 프라이빗은 월 2,000분) 안에서 충분히 커버됨 — 별도 비용 없음

## 9. 이력서 작성용 요약

이력서/포트폴리오에 "뭘 테스트했고 어떻게 대처했는지"를 쓸 때 참고용 — 숫자보다 **과정(문제 발견 → 원인 특정 → 대처 → 재검증)** 을 드러내는 게 핵심.

| 테스트 항목 | 어떻게 검증했나 | 문제 발생 시 대처 |
|---|---|---|
| 무중단 배포 | 배포 중 k6로 연속 요청을 날리며 `kubectl rollout restart` 실행, 실패율 측정 | readinessProbe/graceful shutdown 없을 때 실패 재현 → 적용 후 실패 0건으로 개선된 것을 Before/After로 제시 |
| 장애 복원력(카오스) | Chaos Mesh로 파드 강제종료/네트워크 지연/DB 연결 차단을 직접 주입 | Circuit Breaker/HPA 같은 안전장치가 실제로 트리거되는지 Grafana로 확인, 없을 때와 있을 때 비교 |
| 대규모 트래픽 | k6 Stress Test로 한계 TPS(breaking point)를 직접 찾음 | Saturation 지표로 병목(DB 커넥션풀/캐시/파티셔닝 등) 특정 후 그 부분만 개선, 재측정으로 개선폭을 수치로 제시 |
| 상시 가용성 | GitHub Actions cron으로 5분마다 헬스체크, 실패 시 Slack 알림 | 장애를 사용자 신고보다 먼저 자동 감지하도록 구성, 실제로 서버를 내려서 알림 동작까지 검증 |

이력서 한 줄 예시: "Kubernetes 환경에서 무중단 배포·장애 주입(Chaos Engineering)·부하 테스트를 직접 수행하고, Prometheus/Grafana 관측성과 상시 헬스체크로 장애를 사전에 감지하는 구조를 구축"

## 면접 예상 질문

- 무중단 배포를 어떻게 검증했나요? → 배포 중 연속 요청을 날리는 부하 도구(k6)를 같이 돌리면서 `kubectl rollout restart`를 실행해 실패 건수가 0인지 확인. readinessProbe/graceful shutdown을 일부러 빼고 재현해서 실패가 발생하는 것도 직접 확인함.
- 카오스 테스트에서 뭘 확인했나요? → Circuit Breaker나 HPA 같은 안전장치가 "설계만 되어 있는 것"이 아니라 실제 장애 상황에서 의도대로 작동하는지를 Chaos Mesh로 파드/네트워크/DB 연결에 장애를 주입해 검증.
- 부하 테스트 목표 수치는 어떻게 정했나요? → 동시 사용자 수와 요청 빈도를 가정해 평상시 TPS를 역산하고, 피크 배율을 곱해 목표치를 설정. 거기서 멈추지 않고 Stress Test로 실제 한계점까지 찾아 병목을 특정.
- 병목을 어떻게 찾았나요? → Prometheus의 Saturation(포화도) 지표로 CPU/커넥션풀/DB 중 어디가 먼저 한계에 닿는지 확인 후, 근거 없이 아무거나 최적화하지 않고 확인된 병목에만 대응 패턴을 적용.
- Prometheus로 감시하는데 왜 별도 헬스체크가 또 필요한가요? → Prometheus는 서버가 살아있어야 스크래핑이 되는 pull 방식이라 서버 전체가 죽는 상황엔 취약함. 외부에서 주기적으로 실제 요청을 보내 응답 여부를 확인하는 synthetic monitoring으로 그 사각지대를 보완.
