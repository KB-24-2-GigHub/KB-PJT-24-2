# EC2 배포 환경 구성

`api.gighub.store`을 처음부터 구성하는 절차다. 서버를 새로 만들 때 이 문서만 보고
재현할 수 있어야 한다.

## 전제

| 항목       | 값                                                       |
| ---------- | -------------------------------------------------------- |
| EC2        | `ec2-user@13.125.191.199` (public subnet)                |
| 접속 키    | `~/.ssh/my-keypair.pem`                                  |
| API 도메인 | `api.gighub.store`                                      |
| 프론트     | `gighub.store` (Vercel, 이 문서 범위 밖)                |
| DB         | RDS MySQL 8.4 (private subnet)                           |
| 이미지     | `ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api`                 |

```bash
ssh -i ~/.ssh/my-keypair.pem ec2-user@13.125.191.199
```

> **소유권 경계**
> nginx 설정은 certbot이 EC2 사본을 직접 수정한다. 저장소의
> `deploy/nginx/api.gighub.store.conf`는 최초 구성 기준이며, 운영 중 진실은 EC2에 있다.
> 재구성할 때만 이 파일에서 시작한다.
>
> `/opt/gighub/documents`는 유일한 상태 보존 디렉터리다. 계약 PDF 원본이 들어 있다.
> 컨테이너를 지워도 이 디렉터리는 남겨야 한다.

---

## 1. DNS 레코드

도메인 등록기관의 DNS 관리 화면에서 A 레코드를 만든다.

| 호스트                | 타입 | 값               | 용도                  |
| --------------------- | ---- | ---------------- | --------------------- |
| `api.gighub.store`    | A    | `13.125.191.199` | 이 문서의 대상        |
| `gighub.store`        | —    | Vercel 지시값    | Frontend (범위 밖)    |

> **도메인은 직접 등록한 것이어야 한다.** Let's Encrypt의 "Certificates per Registered
> Domain" 제한(주 50장)은 **등록 도메인 단위**로 적용된다. `kro.kr` 같은 무료 서브도메인
> 서비스는 Public Suffix List에 없으면 모든 이용자가 한 쿼터를 공유하므로, 남이 소진하면
> 발급이 거부된다. 실제로 이 프로젝트는 그 이유로 `gighub.kro.kr`에서 `gighub.store`로
> 옮겼다.
>
> Frontend와 API가 **같은 등록 도메인 아래**에 있어야 한다. 이것이 same-site를 성립시켜
> `SameSite=Lax` 세션 쿠키와 `Domain` 지정 CSRF 쿠키가 동작하는 근거다. 서로 다른 등록
> 도메인으로 나뉘면 CSRF 토큰 전달 방식을 새로 설계해야 한다.

```bash
nslookup api.gighub.store
```

기대: `Address: 13.125.191.199`

**전파될 때까지 5절(certbot)로 넘어가지 않는다.** certbot은 DNS가 맞아야 발급한다.

## 2. 보안그룹

| 그룹        | 유형         | 포트 | 소스        | 목적                       |
| ----------- | ------------ | ---- | ----------- | -------------------------- |
| `my-ec2-sg` | HTTP         | 80   | `0.0.0.0/0` | ACME 검증, HTTPS 리다이렉트 |
| `my-ec2-sg` | HTTPS        | 443  | `0.0.0.0/0` | 서비스                     |
| `my-ec2-sg` | SSH          | 22   | `(확인 필요)` | GitHub Actions 배포        |
| `my-rds-sg` | MYSQL/Aurora | 3306 | `my-ec2-sg` | EC2에서만 접근             |

22번 상시 개방 여부는 미확인이다. 3절의 키 인증 강제로 완화한다.

> `deploy-api.yml`과 `migrate-db.yml`은 여기에 더해 실행마다 러너 IP `/32` 규칙을
> 추가했다가 `if: always()`로 회수한다. 상시 규칙과 별개이므로, 워크플로가 중간에
> 죽으면 회수되지 않은 `/32` 규칙이 남을 수 있다. `gh-actions run <id>` 설명이 붙은
> 오래된 규칙이 보이면 지운다.

## 3. SSH 접속과 잠금

기본 환경 확인:

```bash
cat /etc/os-release | head -2
docker --version
docker compose version
sudo systemctl is-active docker
```

기대: Docker 25.x, Compose v5.x, docker `active`.
비활성이면 `sudo systemctl enable --now docker`.

OS 수준 키 인증을 강제한다:

```bash
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo sshd -t && sudo systemctl reload sshd
```

`sshd -t`가 통과한 뒤에만 reload한다.
**현재 세션을 유지한 채** 새 터미널에서 재접속이 되는지 먼저 확인한다.

## 4. nginx 설치와 리버스 프록시

```bash
sudo dnf install -y nginx
sudo systemctl enable --now nginx
curl -sS -D - -o /dev/null http://api.gighub.store/ | head -3
```

기대: `HTTP/1.1 200 OK` (nginx 기본 페이지)

로컬에서 설정을 복사한다:

```bash
scp -i ~/.ssh/my-keypair.pem \
  deploy/nginx/api.gighub.store.conf \
  ec2-user@13.125.191.199:/tmp/api.conf
```

EC2에서:

```bash
sudo mv /tmp/api.conf /etc/nginx/conf.d/api.gighub.store.conf
sudo nginx -t && sudo systemctl reload nginx
```

기대: `test is successful`

**설정 적용은 `curl`이 아니라 `nginx -T`로 확인한다.**

```bash
sudo nginx -T 2>/dev/null | grep -c "proxy_pass http://127.0.0.1:8080"
```

기대: `1`

> `nginx -t`(소문자)는 문법만 본다. 파일이 include되지 않아도 `syntax is ok`가 나오므로
> 적용 여부의 근거가 되지 않는다. 실제 로드된 설정을 보려면 `nginx -T`(대문자)를 쓴다.

> **`systemctl reload` 직후의 `curl`을 믿지 않는다.** reload는 신호만 보내고 즉시
> 반환하며, nginx는 기존 워커를 유지한 채 새 워커를 비동기로 띄운다. 그 사이에 도착한
> 요청은 **옛 설정으로 응답**한다. 실제로 이 절차를 처음 수행할 때 reload 1초 뒤의
> `curl`이 기본 페이지(200)를 반환해 설정이 안 먹은 것처럼 보인 적이 있다.

응답까지 확인하려면 조건이 만족될 때까지 재시도한다.

```bash
for i in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://api.gighub.store/)
  [ "$code" = "502" ] && { echo "OK: 502 (프록시 동작, 백엔드 없음)"; break; }
  echo "attempt $i: $code"; sleep 1
done
```

기대: `OK: 502` — 백엔드가 아직 없으므로 **502가 정상이다.** 프록시가 동작한다는 증거다.

## 5. certbot 인증서 발급

**먼저 외부 도달성을 확인한다.** Let's Encrypt는 실패도 rate limit(시간당 5회)에
포함하므로, 80번이 인터넷에서 닫혀 있는 채로 시도하면 재시도 여지를 소모한다.

EC2 안에서 자기 공개 IP를 치는 것은 외부 도달성의 증거가 아니다. **EC2 밖의 장비**에서
실행한다.

```bash
curl -sS -m 15 -o /dev/null -w "HTTP %{http_code}\n" http://api.gighub.store/
```

기대: `HTTP 502` — 응답 코드가 무엇이든 **연결되면 통과**다. 타임아웃이나
`Could not connect`이면 보안그룹의 80번 인바운드를 확인한다.

```bash
sudo dnf install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.gighub.store --agree-tos -m <이메일> --redirect
```

`location /`가 모든 경로를 8080으로 넘기지만 ACME 검증은 영향받지 않는다. nginx 플러그인이
정확 일치(`location = /.well-known/acme-challenge/<token>`) 블록을 임시로 끼워넣고,
정확 일치가 접두사 매칭보다 우선하기 때문이다.

기대: `Congratulations! You have successfully enabled HTTPS`

```bash
curl -sS -D - -o /dev/null https://api.gighub.store/ | head -3
sudo systemctl status certbot-renew.timer
```

기대: HTTPS 응답(502여도 좋다 — TLS 동작이 확인 대상), 타이머 `active`.

certbot이 `/etc/nginx/conf.d/api.gighub.store.conf`에 443 블록과 `ssl_certificate`를
직접 써넣는다. 이후 이 파일의 진실은 EC2에 있다.

## 6. RDS 생성과 접속 확인

AWS 콘솔에서 MySQL 8.4 인스턴스를 private subnet에 만든다. 보안그룹은 `my-rds-sg`.

EC2에서:

```bash
sudo dnf install -y mariadb105
mysql -h <rds-endpoint> -u <rds-user> -p -e "SELECT VERSION();"
```

기대: `8.4.x`

접속이 안 되면 `my-rds-sg` 인바운드 소스가 `my-ec2-sg`인지 확인한다.

### 6.1 시간대를 Asia/Seoul로 맞춘다 — 데이터 투입 전에

**RDS 기본 시간대는 UTC지만 이 프로젝트는 DB에 Asia/Seoul wall-clock 값이 들어있다고
전제한다**(`docs/agent/ARCHITECTURE_OVERVIEW.md`). 로컬 `compose.yaml`도
`--default-time-zone=+09:00`으로 띄운다.

Migration이 `DEFAULT CURRENT_TIMESTAMP(6)`를 52곳에서 쓰는데, 이 값은 **서버 시간대로
평가**되므로 JDBC `serverTimezone` 파라미터로 교정할 수 없다. UTC인 채로 두면 DB 기본값으로
채워지는 모든 시각이 9시간 어긋난다. 로컬에서는 재현되지 않는다.

1. RDS 콘솔 → **파라미터 그룹** → 파라미터 그룹 생성
   - 엔진 유형 `MySQL Community`, 파라미터 그룹 패밀리 `mysql8.4`
2. 생성한 그룹 편집 → `time_zone` 검색 → 값 `Asia/Seoul` → 저장
3. RDS → 해당 인스턴스 → **수정** → DB 파라미터 그룹을 새 그룹으로 변경 → 즉시 적용
4. 인스턴스 **재부팅** — RDS → 인스턴스 선택 → 작업 → 재부팅

> **"즉시 적용"은 재부팅을 대체하지 않는다.** `time_zone` 자체는 동적 파라미터지만,
> **파라미터 그룹을 다른 그룹으로 교체하는 것**은 정적 변경으로 취급된다. 그래서 그룹을
> 갈아끼우면 안의 파라미터가 동적이어도 `pending-reboot` 상태로 대기한다. "즉시 적용"은
> *변경 요청을 지금 처리하라*는 뜻이지 *재부팅 없이 반영하라*는 뜻이 아니다.
>
> 반영 여부는 RDS 콘솔 → 인스턴스 → **구성** 탭의 파라미터 그룹 옆 괄호로 확인한다.
> `(pending-reboot)`면 아직이고, `(in-sync)`여야 반영된 것이다.

확인:

```bash
mysql -h <rds-endpoint> -u <rds-user> -p -e "SELECT @@global.time_zone, @@session.time_zone, NOW();"
```

기대: `Asia/Seoul`과 한국 현재 시각. `SYSTEM`이나 `UTC`면 아직 반영되지 않은 것이다.

**데이터가 들어간 뒤에 바꾸면 기존 행과 새 행의 시간대가 섞인다.** Migration 전에 끝낸다.

### 6.2 데이터베이스 생성

RDS 인스턴스 생성 시 "초기 데이터베이스 이름"을 비워두면 데이터베이스가 만들어지지 않는다.
Flyway는 `Unknown database 'kb_pjt'`(Error 1049)로 실패한다.

문자셋과 콜레이션은 로컬 `compose.yaml`과 일치시킨다.

```bash
mysql -h <rds-endpoint> -u <rds-user> -p \
  -e "CREATE DATABASE IF NOT EXISTS kb_pjt CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

mysql -h <rds-endpoint> -u <rds-user> -p \
  -e "SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
      FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='kb_pjt';"
```

기대: `kb_pjt | utf8mb4 | utf8mb4_0900_ai_ci`

## 7. /opt/gighub 디렉터리와 설정 파일

```bash
sudo mkdir -p /opt/gighub/{config,documents,migrations,drivers}
sudo chown -R ec2-user:ec2-user /opt/gighub
chmod 700 /opt/gighub /opt/gighub/config
```

구조:

```
/opt/gighub/
  compose.prod.yaml           운영 Compose 정의
  .env                        Compose 가 자동으로 읽는 Flyway 접속값
  config/database.properties  애플리케이션 설정 전체
  documents/                  계약 PDF 영속 볼륨 — 삭제 금지
  migrations/                 Flyway SQL
  drivers/                    MySQL Connector/J
```

로컬에서 템플릿과 Compose를 복사한다:

```bash
scp -i ~/.ssh/my-keypair.pem \
  deploy/config/app.properties.example \
  ec2-user@13.125.191.199:/opt/gighub/config/database.properties

scp -i ~/.ssh/my-keypair.pem \
  deploy/compose.prod.yaml \
  ec2-user@13.125.191.199:/opt/gighub/compose.prod.yaml
```

EC2에서 값을 채우고 권한을 잠근다:

```bash
vi /opt/gighub/config/database.properties     # <...> 를 실제 값으로
chmod 600 /opt/gighub/config/database.properties
grep -vE '^\s*#' /opt/gighub/config/database.properties | grep -c '<'
```

기대: **`0`** — 치환하지 않은 자리표시자가 없어야 한다.

> 주석을 먼저 걸러내는 이유는 템플릿의 안내 주석 자체에 꺾쇠가 들어 있기 때문이다.
> `grep -c '<'` 만 쓰면 그 주석 줄까지 세어 항상 0 이 아닌 값이 나온다.

시크릿 생성:

```bash
openssl rand -base64 32     # invite.hmac.secret
openssl rand -base64 32     # qr.hmac.key.k1
```

> `invite.web-origin`을 로컬 값(`http://localhost:5173`)으로 두면 **발급된 초대 링크가
> 로컬을 가리켜 WORKER가 접속할 수 없다.** 배포 후 초대 흐름이 조용히 깨지는 대표
> 원인이므로 `https://gighub.store`인지 반드시 확인한다.

`.env`를 만든다:

```bash
cat > /opt/gighub/.env <<'EOF'
API_TAG=dev
FLYWAY_URL=jdbc:mysql://<rds-endpoint>:3306/kb_pjt?useSSL=true&requireSSL=true&serverTimezone=Asia%2FSeoul
FLYWAY_USER=<rds-user>
FLYWAY_PASSWORD=<rds-password>
EOF
vi /opt/gighub/.env
chmod 600 /opt/gighub/.env
```

Compose 문법 확인:

```bash
cd /opt/gighub && docker compose -f compose.prod.yaml config >/dev/null && echo OK
```

기대: `OK`. 이미지가 아직 없어도 통과한다.

## 8. 최초 Flyway 적용

로컬에서 드라이버와 SQL을 전송한다:

```bash
sh backend/gradlew -p backend prepareFlywayDriver

scp -i ~/.ssh/my-keypair.pem \
  backend/build/flyway-drivers/mysql-connector-j-9.7.0.jar \
  ec2-user@13.125.191.199:/opt/gighub/drivers/

scp -i ~/.ssh/my-keypair.pem \
  backend/src/main/resources/db/migration/*.sql \
  ec2-user@13.125.191.199:/opt/gighub/migrations/
```

EC2에서 **적용 예정 목록을 먼저 본다**:

```bash
cd /opt/gighub
docker compose -f compose.prod.yaml --profile tools run --rm flyway info
```

이 절은 **빈 데이터베이스에서 시작하는 신규 환경** 기준이다. 따라서 저장소의 Migration이
전부 `Pending`으로 나와야 한다.

기대: **19개 Migration이 전부 `Pending`.** 눈으로 확인한 뒤 적용한다:

```bash
docker compose -f compose.prod.yaml --profile tools run --rm flyway migrate
docker compose -f compose.prod.yaml --profile tools run --rm flyway info
```

기대: 전부 `Success`, Head `202608121403`.

> **위 숫자는 Migration이 추가되면 낡는다.** 기대값을 외우지 말고 다음 두 가지를 확인한다.
>
> - `info`의 `Pending` 목록이 `backend/src/main/resources/db/migration/`의 `.sql` 파일
>   목록과 일치하는가
> - 적용 후 Head가 그중 가장 최신 버전과 같은가
>
> 파일 개수는 이렇게 센다.
>
> ```bash
> ls backend/src/main/resources/db/migration/*.sql | wc -l
> ```

> **기존 운영 RDS는 이 절의 대상이 아니다.** 2026-08-11에 12건(Head `202608061428`)까지
> 적용한 뒤, 2026-08-12에 나머지 7건을 적용해 `202608121403`에 도달했다(#336).
> 이미 운영 중인 데이터베이스에는 `migrate`가 남은 것만 적용하므로 `Pending` 수가 다르다.

> Flyway는 되돌리지 않는다. `FLYWAY_CLEAN_DISABLED=true`가 `clean`을 막아두었지만,
> `migrate`로 적용된 DDL은 백업 복구나 수동 `ALTER`로만 되돌릴 수 있다.

## 9. 수동 배포

### 9.1 이미지 빌드와 push (로컬)

> **WAR 을 반드시 먼저 다시 빌드한다.** `gradle check` 는 `war` 를 의존하지 않으므로,
> 코드를 고친 뒤 `npm run check` 만 돌리면 `build/libs/gig-hub.war` 는 이전 빌드 그대로
> 남는다. 그 상태로 `docker build` 하면 **옛 코드가 배포되고, HTTP 상태 코드만으로는
> 절대 드러나지 않는다.** 실제로 이 함정에 한 번 걸렸다.

```bash
sh backend/gradlew -p backend war

# 이미지에 새 코드가 들어갔는지 확인한다. 이미지에 unzip 은 없고 jar 는 있다.
docker build -t ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api:<tag> backend
docker run --rm --entrypoint sh ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api:<tag> -c \
  "cd /tmp && jar xf /usr/local/tomcat/webapps/ROOT.war META-INF/context.xml \
   && grep -c RemoteIpValve META-INF/context.xml"

docker push ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api:<tag>
```

기대: 마지막 확인이 `1`

### 9.2 EC2 에서 교체

```bash
# 패키지가 private 이면 최초 1회 필요하다.
echo $CR_PAT | docker login ghcr.io -u <github-사용자명> --password-stdin

cd /opt/gighub
API_TAG=<tag> docker compose -f compose.prod.yaml pull app
API_TAG=<tag> docker compose -f compose.prod.yaml up -d app
docker compose -f compose.prod.yaml ps
```

셸 환경변수가 `.env` 의 `API_TAG` 보다 우선하므로 `.env` 를 고칠 필요는 없다.

### 9.3 배포 검증 — 상태 코드만으로는 부족하다

`/api/health` 는 DB 를 조회하지 않는 순수 liveness 다(`HealthController` Javadoc).
HikariCP 는 지연 초기화이므로 **DB 가 완전히 망가져도 앱은 기동하고 health 는 200 이다.**
아래 세 가지를 모두 확인한다.

```bash
# 1) DB 전 구간 — users 테이블을 실제로 SELECT 한다
curl -s -w "\nHTTP %{http_code}\n" \
  "https://api.gighub.store/api/auth/login-id-availability?loginId=smoke-check"

# 2) 쿠키 속성 — Domain 과 Secure 가 둘 다 있어야 한다
curl -sS -D - -o /dev/null https://api.gighub.store/api/auth/csrf | grep -i set-cookie

# 3) CORS — 프론트 Origin 이 허용되는지
curl -sS -D - -o /dev/null -X OPTIONS \
  -H "Origin: https://gighub.store" \
  -H "Access-Control-Request-Method: POST" \
  https://api.gighub.store/api/auth/login | grep -i access-control-allow-origin
```

기대:

```
{"data":{"available":true}}                                        HTTP 200
Set-Cookie: XSRF-TOKEN=...; Domain=gighub.store; Path=/; Secure; SameSite=Lax
Access-Control-Allow-Origin: https://gighub.store
```

세 확인이 각각 다른 실패를 잡는다.

| 확인 | 실패 시 원인                                                   |
| ---- | -------------------------------------------------------------- |
| 1    | RDS 연결, Flyway 미적용, `database.properties` 값               |
| 2 Domain | `security.cookie.domain` 누락, **또는 옛 WAR 이 배포됨**    |
| 2 Secure | nginx `X-Forwarded-Proto` 누락, `RemoteIpValve` 없는 옛 WAR |
| 3    | `cors.allowed-origins` 값, 또는 옛 WAR                          |

**EC2 내부(`127.0.0.1:8080`)에서 확인하면 `Secure` 는 없는 것이 정상이다.** nginx 를
거치지 않아 `X-Forwarded-Proto` 가 없기 때문이다. `Domain` 은 내부에서도 있어야 한다.
두 속성이 서로 다른 장치에서 나온다는 사실을 이 차이로 구분할 수 있다.

## 10. 롤백

`deploy-api.yml`은 커밋 SHA 와 브랜치 이름 두 태그로 push 한다. SHA 태그가 있으므로
이전 배포로 즉시 되돌릴 수 있다. 이미지는 GHCR 에 남아 있어 pull 없이 전환된다.

```bash
cd /opt/gighub

# 지금 무엇이 떠 있는가
docker compose -f compose.prod.yaml images app

# 이전 커밋으로 되돌린다
API_TAG=<이전-커밋-SHA> docker compose -f compose.prod.yaml up -d app

# 확인은 health 가 아니라 DB 경유 엔드포인트로 한다
sleep 20
curl -s "http://127.0.0.1:8080/api/auth/login-id-availability?loginId=rollback-check"
docker compose -f compose.prod.yaml images app
```

`images app` 의 **TAG 와 IMAGE ID 가 둘 다 바뀌어야** 실제로 전환된 것이다. TAG 만 보면
같은 이미지에 태그만 다시 붙은 경우를 구분하지 못한다.

배포에 쓸 수 있는 SHA 는 GitHub Actions 실행 요약의 "배포 완료" 절이나
`git log` 에서 확인한다.

> **DB 스키마는 롤백되지 않는다.** `migrate-db.yml` 로 적용한 Migration 은 애플리케이션을
> 되돌려도 그대로 남는다. 컬럼 삭제 같은 파괴적 변경을 적용한 뒤 애플리케이션만 되돌리면
> 이전 코드가 없는 컬럼을 찾다가 실패한다. 배포와 Migration 을 분리한 이유가 이것이다.

### 10.1 되돌리기 전에 — 그 이미지가 현재 스키마와 맞는가

**롤백 대상을 "직전 배포" 로 고르지 말고 "현재 스키마와 호환되는 가장 최근 이미지" 로
고른다.** 서버에 남아 있다고 해서 지금 되돌릴 수 있는 이미지가 아니다.

Migration 을 적용한 시점보다 **이전에 빌드된 이미지는 새 스키마를 모른다.** 그런 이미지로
되돌리면 새로 추가된 `NOT NULL` 컬럼이나 CHECK 제약이 구 코드의 쓰기를 거부한다.
롤백 절차를 시험하는 행위 자체가 장애를 만든다.

판단 순서는 다음과 같다.

EC2 에서 현재 스키마 Head 와 보유 이미지를 본다.

```bash
cd /opt/gighub
docker compose -f compose.prod.yaml --profile tools run --rm flyway info | grep "Schema version"
docker images ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api --format "{{.Tag}}  {{.CreatedAt}}"
```

**이미지 태그는 커밋 SHA 다.** 그 커밋의 코드가 아는 최신 Migration 을 저장소에서 뽑아
Head 와 비교한다. 같으면 호환, 낮으면 비호환이다.

```bash
git ls-tree -r <태그SHA> --name-only backend/src/main/resources/db/migration/ \
  | sed 's/.*\/V//;s/__.*//' | sort | tail -1
```

여러 태그를 한 번에 판정하려면 저장소에서 다음을 돌린다. `HEAD_VER` 에 위에서 확인한
Schema version 을 넣는다.

```bash
HEAD_VER=<현재 Schema version>
for sha in <태그1> <태그2> <태그3>; do
  V=$(git ls-tree -r "$sha" --name-only backend/src/main/resources/db/migration/ \
      | sed 's/.*\/V//;s/__.*//' | grep -E '^[0-9]+$' | sort | tail -1)
  printf "%s  최신Migration=%s  %s\n" "${sha:0:7}" "$V" \
    "$([ "$V" = "$HEAD_VER" ] && echo 호환 || echo 비호환)"
done
```

`git log` 로 Migration 디렉터리의 커밋 이력을 보는 방법도 있으나, 위 방식이 **버전 값을
직접 비교**하므로 판정이 모호하지 않다.

호환되는 이미지가 하나뿐이면 **롤백할 곳이 없다.** 그 경우 되돌리기가 아니라 고쳐서
새로 배포하는 것이 유일한 복구 경로다. 이 상태를 미리 알고 있어야 장애 중에 당황하지 않는다.

> 실측 사례: 2026-08-12 에 Migration 7건을 적용한 직후에는 호환 이미지가 하나뿐이었다.
> 다음 배포로 두 번째가 생긴 뒤에야 롤백 왕복을 검증할 수 있었다(#336, #337).

## 11. 브랜치와 배포 스위치

배포 대상 브랜치는 워크플로 파일이 아니라 GitHub Variable `DEPLOY_BRANCH` 하나가
결정한다. 브랜치 전략이 바뀌어도 워크플로 파일은 수정하지 않는다.

| `DEPLOY_BRANCH` | 자동 배포                    | 수동 버튼 |
| --------------- | ---------------------------- | --------- |
| `dev`           | `dev` push 마다              | 사용 가능 |
| **(삭제 상태)** | **꺼짐** — 어떤 push도 무시  | 사용 가능 |
| `main`          | 릴리스만                     | 사용 가능 |

```bash
# 켜기
gh variable set DEPLOY_BRANCH --body "dev"

# 끄기 — 시연·발표 전에 서버를 동결한다
gh variable delete DEPLOY_BRANCH

# 지금 상태
gh variable list
```

**끄는 방법이 "빈 값"이 아니라 "삭제"인 이유**는 GitHub API가 값이 빈 변수를 422로
거부하기 때문이다. 정의되지 않은 변수는 빈 문자열로 평가되고 `github.ref_name`은 절대
빈 문자열이 아니므로, `deploy-api.yml`의 `if` 조건이 거짓이 되어 Job이 skip된다.

변수와 무관하게 수동 실행은 언제나 동작한다.

```bash
gh workflow run deploy-api.yml --ref dev
gh run watch
```

필터 없는 `on: push`의 비용은 모든 브랜치 push에서 워크플로가 시작한 뒤 즉시 skip되는
것이다. Actions 목록에 회색 항목이 남지만 러너 시간은 소비하지 않는다.

## 12. Vercel 연결

프론트엔드는 Vercel의 네이티브 Git 연동이 배포한다. GitHub Actions는 관여하지 않는다.
중복 배포가 되기 때문이다.

**설정은 저장소 밖에만 존재한다.** 이 절이 유일한 기록이다.

| 위치                                    | 값                                    |
| ---------------------------------------- | ------------------------------------- |
| Settings → Git → Production Branch      | `dev`                                 |
| Settings → Environment Variables → Production | `VITE_API_BASE_URL` = `https://api.gighub.store/api` |

변경 후 **Deployments에서 Redeploy를 1회 실행한다.** 설정 변경만으로는 기존 배포에
반영되지 않는다.

`frontend/src/services/http.js`가 `import.meta.env.VITE_API_BASE_URL || '/api'`를 쓴다.
변수가 없으면 상대경로 `/api`로 떨어지는데 Vercel에는 그 경로를 받을 백엔드가 없다.
로컬에서는 Vite dev 프록시가 `/api`를 `DEV_PROXY_TARGET`으로 넘기므로 값이 달라도 된다.

### 12.1 Preview 배포에서 API 호출이 차단되는 것은 의도된 동작이다

Production Branch가 `dev`이므로 나머지 브랜치는 Preview 배포가 된다. Preview URL은
배포마다 달라 백엔드 `cors.allowed-origins`에 등록할 수 없고, 따라서 브라우저가
프리플라이트에서 막는다.

이는 결함이 아니라 안전한 기본값이다. 임의의 Preview 배포가 운영 API와 운영 DB에 닿지
못하게 한다. **Preview는 화면 확인용으로 쓰고, 연동 검증은 운영이나 로컬에서 한다.**

허용하려면 `/opt/gighub/config/database.properties`의 `cors.allowed-origins`에 해당
Origin을 추가해야 하는데, Preview 도메인은 고정되지 않으므로 사실상 와일드카드가
필요하다. 인증 쿠키를 실어 보내는 API에 와일드카드를 여는 것은 권장하지 않는다.

### 12.2 연동 검증

Vercel과 EC2가 처음 만나는 지점이므로, 설정 직후 브라우저에서 확인한다.

1. `https://gighub.store` 로그인 성공
2. DevTools → Network — 로그인 요청에 CORS 오류 없음
3. 저장 동작(POST)에 `X-XSRF-TOKEN` 헤더가 붙는다
4. Application → Cookies — `XSRF-TOKEN`에 `Domain=gighub.store`와 `Secure`
5. 새로고침 후 세션 유지

실패 시 확인 지점은 "문제 해결" 표를 따른다.

## 문제 해결

| 증상                          | 확인 순서                                              |
| ----------------------------- | ------------------------------------------------------ |
| `curl` 502                    | `docker compose -f compose.prod.yaml logs app`         |
| 컨테이너가 `unhealthy`        | `start_period` 60초 경과 여부, 그다음 DB 연결 로그     |
| DB 연결 실패                  | `my-rds-sg` 인바운드, `database.jdbc-url` 엔드포인트   |
| 쿠키에 `Secure` 없음          | nginx `proxy_set_header X-Forwarded-Proto $scheme;`    |
| 쿠키에 `Domain` 없음          | `database.properties`의 `security.cookie.domain`       |
| 브라우저 CORS 오류            | `database.properties`의 `cors.allowed-origins`         |
| 초대 링크가 localhost         | `database.properties`의 `invite.web-origin`            |
| 재배포 후 계약 PDF 사라짐     | `/opt/gighub/documents` 볼륨 마운트 여부               |
| Flyway `ServiceConfigurationError` | `/flyway/drivers`를 **디렉터리째** 마운트하면 이미지 내장 플러그인 드라이버가 가려진다. jar **파일 단위**로 마운트해야 한다 |
| 앱은 정상인데 `curl -I`가 401 | **`curl -I`는 HEAD다.** `SecurityConfig`의 공개 경로는 `AntPathRequestMatcher(pattern, GET)`로 GET만 허용하므로 HEAD는 인증 대상이 된다. 상태 확인은 GET으로 한다 |
| Flyway `Unknown database` (1049) | RDS에 `kb_pjt`가 없다. 6.2절 |
| 약 90일 후 인증서 만료        | `systemctl status certbot-renew.timer`                 |
| `docker compose pull` 403     | EC2에서 `docker login ghcr.io` 여부                    |
