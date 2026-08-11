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
| `my-ec2-sg` | SSH          | 22   | `0.0.0.0/0` | GitHub Actions 배포        |
| `my-rds-sg` | MYSQL/Aurora | 3306 | `my-ec2-sg` | EC2에서만 접근             |

22번 전체 개방은 GitHub 러너 IP 대역이 광범위해서다. 3절의 키 인증 강제로 완화한다.

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

22번을 전체 개방했으므로 키 인증만 남긴다:

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
curl -I http://api.gighub.store
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
curl -I https://api.gighub.store
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

기대: 12개 Migration이 `Pending`. 눈으로 확인한 뒤 적용한다:

```bash
docker compose -f compose.prod.yaml --profile tools run --rm flyway migrate
docker compose -f compose.prod.yaml --profile tools run --rm flyway info
```

기대: 전부 `Success`, Head `202608061428`.

> Flyway는 되돌리지 않는다. `FLYWAY_CLEAN_DISABLED=true`가 `clean`을 막아두었지만,
> `migrate`로 적용된 DDL은 백업 복구나 수동 `ALTER`로만 되돌릴 수 있다.

## 9. 수동 배포

> Task 4에서 실제로 성공한 명령으로 채운다.

## 10. 롤백

> Task 5에서 실제로 검증한 절차로 채운다.

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
| 약 90일 후 인증서 만료        | `systemctl status certbot-renew.timer`                 |
| `docker compose pull` 403     | EC2에서 `docker login ghcr.io` 여부                    |
