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

### 2.1 GitHub Actions 자격증명 — OIDC

`/32` 규칙을 넣고 빼는 것이 워크플로가 AWS에 하는 유일한 일이다. 여기에 만료되지
않는 IAM User Access Key를 쓰면 그 키가 저장소 Secret에 영구히 남는다. 대신 실행마다
만료되는 임시 세션을 받는다.

AWS 콘솔에서 한 번만 준비한다.

1. **IAM → Identity providers**에 GitHub OIDC Provider를 등록한다.
   Provider URL은 `https://token.actions.githubusercontent.com`,
   Audience는 `sts.amazonaws.com`이다.
2. **Role**을 만들고 신뢰 정책의 `sub`를 이 저장소로 제한한다.
   기본 통합 브랜치만 배포하므로 `repo:KB-24-2-GigHub/KB-PJT-24-2:ref:refs/heads/dev`
   형태로 좁힌다. 다른 브랜치에서 실행하면 AssumeRole 단계에서 거부된다.
3. **권한 정책**은 대상 보안그룹에 대한 `ec2:AuthorizeSecurityGroupIngress`와
   `ec2:RevokeSecurityGroupIngress` 둘만 준다. 그 이상은 필요 없다.
4. Role ARN을 저장소 Secret `AWS_ROLE_ARN`에 넣는다.

> **Role의 `MaxSessionDuration`이 워크플로 `timeout-minutes`보다 길어야 한다.**
> `Close SSH`는 job 마지막에 `if: always()`로 `/32` 규칙을 회수하는데, 세션이 job보다
> 먼저 만료되면 그 회수가 실패해 22번이 열린 채 남는다. 워크플로가
> `role-duration-seconds`를 지정하지 않고 Role 값을 따르는 이유가 이것이다.
> 현재 최장은 `deploy-api.yml`의 25분이므로 기본값 1시간으로 충분하다.

확인은 `Verify AWS OIDC` 워크플로를 수동 실행한다. AssumeRole과 위 두 권한을
`--dry-run`으로 확인만 하고 보안그룹은 바꾸지 않는다. 배포가 `Open SSH`에서 멈췄을 때
원인이 자격증명인지 아닌지를 여기서 먼저 가른다.

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
  .env                        Compose 가 자동으로 읽는 API_TAG 와 Flyway 접속값
  set-api-tag.sh              .env 의 API_TAG 갱신 — 배포가 매번 올린다
  config/database.properties  애플리케이션 설정 전체
  documents/                  계약 PDF 영속 볼륨 — 삭제 금지
  migrations/                 Flyway SQL
  drivers/                    MySQL Connector/J
```

로컬에서 템플릿과 Compose, 배포 스크립트를 복사한다:

```bash
scp -i ~/.ssh/my-keypair.pem \
  deploy/config/app.properties.example \
  ec2-user@13.125.191.199:/opt/gighub/config/database.properties

scp -i ~/.ssh/my-keypair.pem \
  deploy/compose.prod.yaml \
  ec2-user@13.125.191.199:/opt/gighub/compose.prod.yaml

scp -i ~/.ssh/my-keypair.pem \
  deploy/set-api-tag.sh \
  ec2-user@13.125.191.199:/opt/gighub/set-api-tag.sh
```

뒤의 두 파일은 **배포가 매번 다시 올린다**(`deploy-api.yml` 의 `Upload deploy artifacts`).
여기서 한 번 올려 두는 것은 첫 배포 전에도 9.2절 수동 교체와 아래 Compose 문법 확인이
동작하게 하기 위해서다.

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
# 첫 배포 전의 부트스트랩 값이다. 이후로는 deploy-api.yml 이 배포마다
# set-api-tag.sh 로 방금 띄운 커밋 SHA 를 써 넣는다. 손으로 맞추지 않는다.
API_TAG=dev
FLYWAY_URL=jdbc:mysql://<rds-endpoint>:3306/kb_pjt?useSSL=true&requireSSL=true&serverTimezone=Asia%2FSeoul
FLYWAY_USER=<rds-user>
FLYWAY_PASSWORD=<rds-password>
EOF
vi /opt/gighub/.env
chmod 600 /opt/gighub/.env
```

> **`.env` 의 `API_TAG` 는 서버에 남는 유일한 태그 값이다.** `compose.prod.yaml` 의
> `app` 이미지는 `${API_TAG}` 로 보간되고, Compose 는 셸 환경변수를 `.env` 보다
> 우선한다. 그래서 배포 중에는 `export API_TAG=<sha>` 가 이기지만 그 값은 SSH 세션과
> 함께 사라진다. 배포가 `.env` 까지 갱신하지 않으면 뒤에 누가 `API_TAG` 없이
> `docker compose up -d app` 을 하는 순간 옛 태그가 조용히 다시 뜬다. 2026-08-19 에
> 실제로 그렇게 구버전 WAR 가 올라와 `POST /api/documents` 가 사라졌다 (#450, #452).

Compose 문법 확인:

```bash
cd /opt/gighub && docker compose -f compose.prod.yaml config >/dev/null && echo OK
```

기대: `OK`. 이미지가 아직 없어도 통과한다.

`config` 는 `API_TAG` 가 비어 있어도 경고만 내고 통과한다(실측 exit 0). 값이 실제로
들어갔는지는 이미지 참조로 확인한다:

```bash
docker compose -f compose.prod.yaml config --images | grep kb-pjt-24-2-api
```

기대: `ghcr.io/kb-24-2-gighub/kb-pjt-24-2-api:<태그>`. **콜론 뒤가 비어 있으면** `.env` 에
`API_TAG` 가 없는 것이다. `app` 이미지 태그에는 기본값을 두지 않았으므로 그 상태의
`up -d app` 은 `invalid reference format` 으로 멈춘다 — 구버전이 조용히 뜨지 않는다.

> **`compose.prod.yaml` 과 `set-api-tag.sh` 는 배포가 매번 덮어쓴다.** 예전에는 사람이
> scp 하도록 남겨 뒀는데, 그 수동 단계를 놓치면 서버 파일이 저장소와 조용히 어긋난다.
> 14.1절의 `seed` 서비스 추가가 그 형태였다. 지금은 `deploy-api.yml` 의
> `Upload deploy artifacts` 가 배포마다 두 파일을 올리므로 별도 조치가 필요 없다.
>
> 서버 파일이 저장소와 같은지 확인하려면:
>
> ```bash
> ssh ... "grep -n 'kb-pjt-24-2-api:' /opt/gighub/compose.prod.yaml"
> ```
>
> 기대: `${API_TAG}` — `${API_TAG:-dev}` 가 보이면 첫 배포 전이거나 배포가 실패한 것이다.

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
sh set-api-tag.sh <tag>
docker compose -f compose.prod.yaml ps
```

셸 환경변수가 `.env` 의 `API_TAG` 보다 우선하므로 **이 명령 자체는** 의도한 이미지로 뜬다.
그러나 그 값은 이 셸에서만 살아 있고 `.env` 는 그대로라, 여기서 멈추면 서버에 남는 값이
거짓이 된다. 다음에 누가 `API_TAG` 없이 `up -d app` 을 하면 옛 태그로 되돌아간다. 그래서
`set-api-tag.sh` 로 `.env` 를 함께 맞춘다. 배포 워크플로도 같은 스크립트를 쓴다.

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

# 되돌린 버전을 .env 에도 남긴다. 하지 않으면 서버의 .env 는 롤백 전 태그를 가리킨
# 채로 남고, 이후 누군가의 맨손 up -d 가 방금 물린 그 버전을 다시 올린다.
sh set-api-tag.sh <이전-커밋-SHA>

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
  | sed 's/.*\/V//;s/__.*//' | grep -E '^[0-9]+$' | sort | tail -1
```

`grep -E '^[0-9]+$'` 는 빼면 안 된다. Migration 디렉터리에는 `.gitkeep` 이 함께 들어
있는데 `sed` 가 이 줄을 바꾸지 않고 흘려보내고, 경로 문자열이 버전 숫자보다 뒤로 정렬돼
`tail -1` 이 버전 대신 `.gitkeep` 경로를 뱉는다. 어떤 태그를 넣어도 같은 값이 나오므로
비호환 이미지를 호환으로 오판한다.

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

## 13. 모니터링 알림 — CPU 부하를 Slack 으로

EC2 의 CPU 가 임계치를 넘으면 Slack 채널에 알린다.

```
EC2 ──지표 자동 송출──▶ CloudWatch Alarm ──▶ SNS ──▶ Lambda ──▶ Slack
손대지 않음             ← ← ←  이 구간을 AWS 콘솔에서 조립  → → →
```

> **EC2 에는 아무것도 설치하지 않는다.** SSH 접속이 필요한 절은 13.7절의 부하 시험
> 하나뿐이다. CloudWatch·SNS·Lambda 는 EC2 와 별개인 관리형 서비스이고, EC2 는 이미
> 지금도 CPU 지표를 CloudWatch 로 보내고 있다.

메모리와 디스크 사용률은 **이 절의 범위가 아니다.** 그 값들은 하이퍼바이저가 주는 기본
지표에 없어서 EC2 안에 CloudWatch Agent 를 설치해야 하고, 커스텀 지표로 과금된다.
CPU 만 보는 이 구성은 EC2 를 건드리지 않고 프리티어 안에서 끝난다.

리전은 전부 `ap-northeast-2`(서울)다.

### 13.1 선행 확인 — 인스턴스 타입

EC2 콘솔 → 인스턴스 → 대상 선택 → **세부 정보** 탭에서 인스턴스 유형을 본다.

| 유형              | Alarm B(크레딧) | 비고                                            |
| ----------------- | --------------- | ----------------------------------------------- |
| `t3.*`, `t4g.*`   | **만든다**      | 크레딧 소진이 실제 성능 저하로 이어진다         |
| `m*`, `c*`, `r*`  | **만들지 않는다** | `CPUCreditBalance` 지표 자체가 없다            |

> **버스터블이 아닌데 Alarm B 를 만들면 영원히 `INSUFFICIENT_DATA` 에 머문다.** 울리지도
> 않고 꺼지지도 않는 알람이 하나 남아서, 나중에 알람 목록을 볼 때마다 판단을 흐린다.

t 계열이면 **크레딧 사양**도 함께 본다(같은 화면의 "크레딧 사양" 또는 인스턴스 → 작업 →
인스턴스 설정 → 크레딧 사양 수정).

| 크레딧 사양 | 크레딧이 0 이 되면          | Alarm B 의 의미     |
| ----------- | --------------------------- | ------------------- |
| `standard`  | baseline 성능으로 묶인다    | **API 가 느려진다** |
| `unlimited` | 성능은 유지되고 초과 과금된다 | **요금이 샌다**     |

어느 쪽이든 알 가치가 있으므로 알람은 만들되, Slack 알림을 받았을 때 무엇을 뜻하는지
팀이 알고 있어야 한다. **현재 이 서버는 `unlimited` 이므로 Alarm B 는 성능 알람이 아니라
비용 조기경보다.** 울리면 "느려진다"가 아니라 "이대로 두면 과금이 시작된다"로 읽는다.

> "실제로 청구되고 있다"를 직접 보는 지표는 `CPUSurplusCreditsCharged`(> 0) 다. 잔량
> 알람은 그보다 앞서 울리는 예고편이므로 조기경보에는 잔량 쪽이 낫다. 확정 신호가 따로
> 필요해지면 같은 SNS 주제에 알람을 하나 더 붙인다.

### 13.2 임계치 — 관측이 먼저다

**숫자를 먼저 정하지 않는다.** 이 서버의 평상시 CPU 를 모르는 상태에서 임계치를 박으면
오탐이나 미탐 중 하나가 확정이다.

CloudWatch → **지표** → EC2 → 인스턴스별 지표 → 대상 인스턴스의 `CPUUtilization`

| 항목 | 값        |
| ---- | --------- |
| 기간 | 2주       |
| 통계 | 평균      |
| 주기 | 5분       |

그래프에서 두 가지를 읽는다.

1. **평상시 상한** — 트래픽이 없는 시간대의 최댓값
2. **배포 스파이크의 높이와 지속시간** — `deploy-api.yml` 실행 시각과 겹치는 봉우리

임계치는 평상시 상한보다 확실히 위이면서, 배포 스파이크가 **15분을 넘지 않는다**는 것을
그래프에서 확인한 값으로 정한다(13.6절의 평가 조건이 3회 연속 5분이다).

첫 값이 맞을 필요는 없다. **조정 주기가 있다는 사실이 문서에 남는 것**이 중요하다.
오탐이 나면 올리고, 실제로 느렸는데 안 울렸으면 내린다.

#### 현재 적용값 (2026-08-13 결정)

관측 근거는 EC2 가동 3일차, **실사용 트래픽 없이 배포만 반복한** 구간이다.

| 관측 항목        | 값        |
| ---------------- | --------- |
| 인스턴스 유형    | `t3.small` (vCPU 2, 베이스라인 20%) |
| 크레딧 사양      | `unlimited`                         |
| CPU 최고점       | **13.3%** (배포 스파이크)           |
| 실사용 시 CPU    | **미상** — 트래픽이 아직 없다       |

| 알람 | 임계치      | 근거                                                                 |
| ---- | ----------- | -------------------------------------------------------------------- |
| A    | **> 40%**   | 관측 최고점 13.3% 의 3배. 배포 스파이크가 닿지 않으므로 오탐이 없고, 베이스라인 20% 의 두 배라 "실부하"를 뜻한다 |
| B    | **< 400**   | 최대 576 에서 400 까지 떨어지는 것 자체가 이례적 사건이다            |

> **Alarm A 를 70% 로 잡지 않은 이유.** 이 서버의 최고점이 13.3% 이므로 70% 는 최고점의
> 5배다. 그 값에 도달할 때면 이미 손쓸 시점을 지난다. 70% 는 관측 이력이 전혀 없을 때의
> 출발점이지 이 서버의 값이 아니다.

> **Alarm B 를 "최대의 20%"(115) 로 잡지 않은 이유.** 115 는 크레딧을 이미 8할 써버린
> 뒤다. `unlimited` 에서 이 알람의 목적은 성능 저하 감지가 아니라 **과금 시작 전 예고**
> 이므로, 576 → 400 이라는 변화 자체를 신호로 쓴다.

> **베이스라인 20% 가 경제적 분기선이다.** CPU 가 20% 아래면 크레딧을 버는 속도가 쓰는
> 속도보다 빨라 잔량이 576 에 붙어 있고 비용이 발생하지 않는다. 현재 최고점 13.3% 는
> 그 아래이므로 **Alarm B 는 당분간 잠자는 알람**이다. 그래도 만들어 두는 이유는 실사용
> 트래픽이 붙는 순간이 정확히 위험이 시작되는 순간이기 때문이다.

**재검토 시점: 실사용 트래픽이 2주 쌓인 뒤.** 위 두 값은 트래픽 없는 구간의 관측으로
정한 잠정치다. 사용자가 붙으면 40% 를 정상적으로 넘길 수 있으므로, 그때 이 절의 관측을
다시 수행하고 표를 갱신한다.

### 13.3 Slack Incoming Webhook 발급

AWS 가 아니라 Slack 웹사이트에서 한다.

1. <https://api.slack.com/apps> → **Create New App** → From scratch
2. 이름 `GigHub Alerts`, 대상 워크스페이스 선택
3. 좌측 **Incoming Webhooks** → 활성화
4. **Add New Webhook to Workspace** → 알림 받을 채널 선택 → 허용

발급된 URL 형태:

```
https://hooks.slack.com/services/T01ABCD2EFG/B09HIJK3LMN/xY7z...
```

> **이 URL 은 사실상 비밀번호다.** 아는 사람은 누구나 그 채널에 글을 쓸 수 있다.
> 저장소, 이슈, PR, 스크린샷 어디에도 넣지 않는다. 13.5절에서 Lambda 환경 변수에
> 한 번 입력하는 것이 유일한 보관 위치다.

### 13.4 SNS 토픽 생성

SNS → **주제** → 주제 생성

| 항목 | 값                    |
| ---- | --------------------- |
| 유형 | **표준**              |
| 이름 | `gighub-ops-alerts`   |

나머지는 기본값이다. 구독은 지금 만들지 않는다 — 13.5절에서 Lambda 트리거를 붙이면
구독이 자동으로 생긴다.

> **CloudWatch Alarm 은 Lambda 를 직접 호출하지 못한다.** SNS 가 그래서 필요하다.
> 덤으로 나중에 이메일 구독이나 Amazon Q Developer(구 AWS Chatbot)를 **같은 토픽에**
> 추가로 붙일 수 있다.

### 13.5 Lambda 함수

Lambda → **함수 생성** → 새로 작성

| 항목      | 값                       |
| --------- | ------------------------ |
| 함수 이름 | `gighub-slack-notifier`  |
| 런타임    | `Node.js 24.x`           |
| 아키텍처  | `arm64`                  |
| 실행 역할 | 기본 Lambda 권한을 가진 새 역할 생성 |

> **VPC 는 연결하지 않는다.** 이 함수는 Slack(외부 HTTPS)만 호출하고 RDS 를 포함한 어떤
> 사설 자원에도 접근하지 않는다. VPC 에 붙이면 인터넷으로 나가기 위해 NAT Gateway 가
> 필요해지고 **월 $35 이상**이 고정으로 발생한다.

> **실행 역할에 권한을 더 주지 않는다.** 자동 생성되는 `AWSLambdaBasicExecutionRole`
> (CloudWatch Logs 쓰기)이면 충분하다. 함수가 AWS API 를 호출하지 않기 때문이다.

**코드 배포** — 저장소 `deploy/lambda/slack-alert/index.mjs` 를 올린다.
방법은 `deploy/lambda/slack-alert/README.md` 에 있다. 콘솔 편집기에 붙여넣었다면
**Deploy 버튼을 눌러야** 반영된다.

**환경 변수** — 구성 탭 → 환경 변수 → 편집 → 환경 변수 추가

| 키                  | 값                          |
| ------------------- | --------------------------- |
| `SLACK_WEBHOOK_URL` | 13.3절에서 발급받은 URL     |

**기본 설정** — 구성 탭 → 일반 구성 → 편집

| 항목    | 값     |
| ------- | ------ |
| 메모리  | 128 MB |
| Timeout | 10 초  |

**트리거 연결** — 함수 개요 → 트리거 추가 → SNS → 주제 `gighub-ops-alerts` → 추가

이때 SNS 가 Lambda 를 호출할 수 있는 리소스 기반 정책이 자동으로 생긴다. 직접 만들지 않는다.

**로그 보존 기간** — CloudWatch → 로그 그룹 → `/aws/lambda/gighub-slack-notifier`
→ 작업 → 보존 설정 편집 → **30일**

> 로그 그룹은 함수의 **첫 실행 때** 자동으로 생긴다. 아직 없으면 13.7절의 검증을 한 번
> 돌린 뒤에 이 설정을 한다.
>
> **기본 보존 기간은 "만료되지 않음"이다.** 이 함수의 로그량은 알림 1건당 수백 바이트라
> 프리티어(5GB) 근처도 못 가지만, 무기한 로그 그룹을 계정에 방치하는 습관이 나중에
> 비용과 정리 부담으로 돌아온다.

### 13.6 CloudWatch 알람

CloudWatch → **경보** → 경보 생성 → 지표 선택 → EC2 → 인스턴스별 지표

|                | **A: CPU 과부하**       | **B: 크레딧 고갈**          |
| -------------- | ----------------------- | --------------------------- |
| 경보 이름      | `gighub-ec2-cpu-high`   | `gighub-ec2-cpu-credit-low` |
| 지표           | `CPUUtilization`        | `CPUCreditBalance`          |
| 통계           | 평균                    | **최소**                    |
| 기간           | 5분                     | 5분                         |
| 조건           | **`>` 40** (13.2절)     | **`<` 400** (13.2절)        |
| 평가 기간      | 3                       | 2                           |
| 경보 발생 데이터 요소 | 3/3              | 2/2                         |
| 누락 데이터 처리 | **정상으로 처리**      | **정상으로 처리**           |
| 알림 대상      | SNS `gighub-ops-alerts` | SNS `gighub-ops-alerts`     |

알림 설정에서 **경보 상태**와 **정상 상태** 두 가지 모두에 같은 SNS 주제를 지정한다.
해제 알림이 없으면 Slack 에서 상황이 끝났는지 알 수 없다.

> **Alarm B 의 통계가 "최소"인 이유.** 크레딧은 최악값이 중요하다. 평균으로 보면 5분
> 안에 바닥을 쳤다가 회복한 구간을 놓친다.

> **누락 데이터를 "정상으로 처리"하는 이유.** 인스턴스가 죽으면 지표가 끊긴다. 그걸
> 부하 알람이 경보로 처리하면 "CPU 과부하"라는 잘못된 원인을 가리킨다. 인스턴스 다운
> 감지는 `StatusCheckFailed` 가 담당할 별개 문제이고, 이 절의 범위가 아니다.

> **알람은 상태가 바뀔 때만 발화한다.** 경보 상태가 6시간 지속돼도 Slack 메시지는 1건이다.
> 스팸 억제를 따로 구현할 필요가 없는 이유다.

### 13.7 검증 — 한 번도 실패한 적 없는 알림은 믿을 수 없다

네 단계를 순서대로 한다. 2번과 3번이 핵심이다.

**1) Lambda 단독** — 코드 탭 → 테스트 → 새 이벤트, 아래를 본문으로 넣고 실행한다.

```json
{
  "Records": [
    {
      "Sns": {
        "Message": "{\"AlarmName\":\"gighub-ec2-cpu-high\",\"NewStateValue\":\"ALARM\",\"OldStateValue\":\"OK\",\"NewStateReason\":\"검증용 이벤트\",\"StateChangeTime\":\"2026-08-13T04:12:33.891+0000\",\"Trigger\":{\"MetricName\":\"CPUUtilization\",\"ComparisonOperator\":\">\",\"Threshold\":70}}"
      }
    }
  ]
}
```

기대: 실행 결과 성공, Slack 채널에 빨간 막대 알림. 시각이 **13:12:33 KST** 로 보여야 한다
(payload 의 04:12 는 UTC 다).

**2) 배선 전 구간 — 부하를 만들지 않고** 알람 상태를 강제한다.

```bash
aws cloudwatch set-alarm-state --alarm-name gighub-ec2-cpu-high \
  --state-value ALARM --state-reason "배선 검증"

aws cloudwatch set-alarm-state --alarm-name gighub-ec2-cpu-high \
  --state-value OK --state-reason "배선 검증 종료"
```

기대: Slack 에 빨간 알림과 초록 알림이 차례로 온다. Alarm → SNS → Lambda → Slack 전
구간이 이것으로 확인된다. Alarm B 에도 같은 명령을 이름만 바꿔 실행한다.

> 강제한 상태는 다음 지표 평가 때 실제 상태로 되돌아간다. `OK` 를 명시적으로 한 번 더
> 보내는 이유는 그 사이에 남은 경보 상태를 보고 다른 사람이 오해하지 않게 하기 위해서다.

**3) 실패 경로 — 일부러 깨뜨린다.** 이 단계를 건너뛰면 "알림이 안 갔는데 성공으로 보이는"
상태를 발견할 방법이 없다.

1. Lambda 환경 변수 `SLACK_WEBHOOK_URL` 의 마지막 몇 글자를 지워 잘못된 값으로 만든다
2. 2번의 `set-alarm-state --state-value ALARM` 을 다시 실행한다
3. CloudWatch Logs `/aws/lambda/gighub-slack-notifier` 를 본다

기대: Lambda 실행이 **실패**하고 로그에 `Slack 이 404 로 거절했다` 류의 오류가 남는다.
Slack 에는 아무것도 오지 않는다.

**조용히 성공하면 구성이 잘못된 것이다.** 확인 후 환경 변수를 원래 값으로 되돌리고
2번을 다시 실행해 정상 복구를 확인한다.

**4) 임계치와 평가 기간** — 실제 부하를 만든다. 앞의 세 단계와 달리 **운영 서버를 건드린다.**

```bash
ssh -i ~/.ssh/my-keypair.pem ec2-user@13.125.191.199

nproc                                    # 코어 수 확인
for i in $(seq 1 $(nproc)); do yes > /dev/null & done

# 15분 이상 유지한 뒤 반드시 종료한다
jobs
kill %1 %2 ...      # 또는  pkill yes
```

기대: 부하 시작 후 **15~20분 사이**에 Slack 알림. 즉시 오면 평가 기간 설정이 잘못된
것이고, 30분이 지나도 안 오면 임계치가 너무 높은 것이다.

> **시연·발표 시간대를 피한다.** 그리고 `pkill yes` 로 종료를 반드시 확인한다. 남겨두면
> 계속 CPU 를 먹는다.
>
> t 계열이면 이 시험이 CPU 크레딧을 소모한다. Alarm B 가 함께 울릴 수 있는데, 그것도
> 검증이 된 것이다. 다만 크레딧이 회복되려면 부하를 멈춘 뒤 수 시간이 걸린다.

### 13.8 이 절의 결과물이 어디에 있나

| 것                        | 위치                                        | Git |
| ------------------------- | ------------------------------------------- | --- |
| 함수 소스(원본)           | `deploy/lambda/slack-alert/index.mjs`       | O   |
| 함수 테스트               | `deploy/lambda/slack-alert/index.test.mjs`  | O   |
| 실제 실행되는 함수 코드   | AWS Lambda `gighub-slack-notifier`          | X   |
| Webhook URL               | Lambda 환경 변수                            | **X** |
| SNS 주제, 알람 2개        | AWS 계정                                    | X   |
| 로그                      | `/aws/lambda/gighub-slack-notifier`         | X   |

**저장소와 AWS 는 자동으로 동기화되지 않는다.** `index.mjs` 를 고치면 13.5절의 코드 배포를
다시 하고 13.7절 2번으로 확인한다. 하지 않으면 저장소는 새 코드, 운영은 옛 코드가 되고
이 어긋남은 어떤 지표에도 나타나지 않는다. 9.1절의 WAR 함정과 같은 종류의 실패다.

## 14. seed 데이터 반복 적용

스키마를 바꾼 뒤 데이터를 다시 맞출 때 쓴다. 8장의 Flyway 적용과 짝이지만 **버튼은 따로**다.
Migration 은 한 번만 되돌릴 수 없이 적용되고 seed 는 반복 실행이 목적이라 성질이 반대다.
한 버튼에 묶으면 seed 를 다시 넣으려다 DDL 까지 나간다.

**seed 는 성질이 다른 두 계열이다. 누르기 전에 어느 쪽인지부터 확인한다.**

| 계열 | 파일 | `confirm` | 하는 일 |
| ---- | ---- | --------- | ------- |
| 고정 Fixture | `test-*.sql` | `seed` | 자기 fixture 범위만 바꾸고 나머지 데이터는 **보존**한다 |
| 시연용 데모 | `demo-*.sql` | `reset-all-data` | 앱을 정지하고 **모든 애플리케이션 데이터와 문서 저장소를 지운 뒤** 실행 시각 기준으로 다시 만들고 재기동한다 |

`test-*.sql` 은 멱등이라 실패해도 그냥 다시 돌리면 된다. `demo-*.sql` 은 그렇지 않다.
운영 데이터를 지우고 앱을 잠시 내리므로, **살려 둘 데이터가 있으면 눌러선 안 된다.**
데모 계정은 공개된 고정 비밀번호를 쓰는 합성 계정이므로 통제된 시연 시간에만 적용하고
끝나면 즉시 정리한다. 시나리오별로 무엇이 만들어지는지는
[`backend/src/test/resources/db/seed/README.md`](../backend/src/test/resources/db/seed/README.md)
에 있다. 이 절은 **운영에서 어떻게 실행하는가**만 다룬다.

### 14.1 최초 1회 준비

`compose.prod.yaml` 에 `seed` 서비스가 필요하다. **`Deploy API` 가 배포마다 이 파일을 올리므로
보통은 배포 한 번이면 끝난다.** 예전에는 사람이 올려야 했고, 그 수동 단계를 놓쳐 서버 파일이
저장소와 어긋나는 일이 반복돼 배포에 넣었다(7절).

배포를 기다릴 수 없으면 직접 올린다:

```bash
scp -i ~/.ssh/my-keypair.pem \
  deploy/compose.prod.yaml \
  ec2-user@13.125.191.199:/opt/gighub/compose.prod.yaml
```

확인:

```bash
ssh ... "docker compose -f /opt/gighub/compose.prod.yaml --profile tools config --services"
```

기대: 목록에 **`seed`** 가 있어야 한다. 없으면 워크플로가 첫 단계에서 이 안내와 함께 멈춘다.

`.env` 에 값을 더할 필요는 없다. `apply-seed.sh` 가 기존 `FLYWAY_URL` 에서 host·port·database 를
파싱하고 `FLYWAY_USER` / `FLYWAY_PASSWORD` 를 그대로 쓴다. mysql 전용 변수를 따로 두면 RDS
엔드포인트가 바뀔 때 한쪽만 고쳐져 조용히 어긋나기 때문이다. 파싱은
`scripts/apply-seed.test.js` 가 고정한다.

### 14.2 실행

Actions → **Seed DB** → Run workflow.

| 입력                 | 값                                                                    |
| -------------------- | --------------------------------------------------------------------- |
| `Use workflow from`  | **`dev`** — 다른 브랜치는 자격증명 단계에서 거부된다                   |
| `confirm`            | `test-*.sql` 이면 `seed`, `demo-*.sql` 이면 `reset-all-data`           |
| `file`               | 적용할 파일명 (예: `test-contract-escrow.sql`, `demo-functional.sql`)  |

터미널에서 누를 때도 같다.

```bash
gh workflow run seed-db.yml --ref dev \
  -f file=demo-functional.sql \
  -f confirm=reset-all-data

gh run list --workflow=seed-db.yml --limit 1
```

**브랜치가 `dev` 여야 하는 이유는 AWS 쪽 제약이다.** 배포용 Role 의 신뢰 정책이
`refs/heads/dev` 로 좁혀져 있어, 다른 ref 로 실행하면 보안그룹을 열기도 전에
`Configure AWS credentials` 에서 AssumeRole 이 거부된다. 2.1 절을 참고할 것.

선택 가능한 파일은 `backend/src/test/resources/db/seed/` 의 `.sql` 7개다. 워크플로는 그
디렉터리의 `.sql` 과 `.inc` 를 전부 서버로 올린 뒤 `file` 로 고른 하나만 실행한다.
`.inc` 는 다른 SQL 이 포함해 쓰는 조각이므로 `file` 로 고를 수 없다.

`file` 은 **그 디렉터리에 실제로 있는 `.sql` 파일 이름과 정확히 일치해야 한다.** 경로 표기,
`../`, 다른 확장자는 모두 거부된다. 워크플로가 임의 문자열을 받아 셸로 넘기지 않게 하려는
것이므로, 목록에 없는 이름이면 보안그룹을 열기 전에 멈추고 사용 가능한 이름을 찍어 준다.

`confirm` 은 두 군데서 검사한다. 값이 `seed` 도 `reset-all-data` 도 아니면 **job 자체가
돌지 않고 `skipped` 로 끝난다.** 실패가 아니라 건너뛴 것으로 표시되므로 오타를 눈치채기
어렵다. 값은 맞지만 파일 계열과 어긋나면 `Resolve seed file` 이 기대값을 알려 주고 멈춘다.

### 14.3 seed 를 새로 만들 때 지킬 것

**`test-*.sql` 계열**은 아래 두 성질을 지킨다. 이게 깨지면 반복 적용이 안전하지 않다.

- **멱등**: 모든 `INSERT` 에 `ON DUPLICATE KEY UPDATE` 를 붙인다. 몇 번을 돌려도 결과가 같아야 한다.
- **범위 한정**: `DELETE` 는 반드시 자기 fixture 의 owner/workplace 로 좁힌다. 화면에서 손으로
  만들어 둔 다른 데이터를 지우면 안 된다.
- **사업장을 만들면 고정 QR 도 함께 만든다**: 앱으로 등록하면 사업장 생성 트랜잭션이 QR 을
  같이 발급하지만, `workplaces` 를 직접 `INSERT` 하는 seed 는 그 경로를 타지 않는다. 빠뜨리면
  깨끗한 Database 에서 OWNER QR 화면이 막히고 WORKER 스캔도 시작할 수 없다(#381). `token_nonce`
  는 fixture 마다 고정값을 쓰되 서로 겹치지 않게 식별자 대역으로 나눈다 — 두 유일 제약이 같은
  행으로 모여야 반복 적용해도 사업장당 ACTIVE 가 한 건으로 유지된다.

**`demo-*.sql` 계열은 이 두 규칙을 의도적으로 지키지 않는다.** 시연을 매번 같은 출발점에서
시작하려면 이전 상태가 남아 있으면 안 되기 때문이다. 대신 다른 성질을 지킨다.

- **전체 초기화**: Flyway Schema 는 보존하고 애플리케이션 데이터를 모두 지운 뒤 다시 만든다.
  공통 조각은 `demo-reset.inc` 에 있다.
- **상대 시간**: 날짜와 시각을 박아 두지 않고 실행 시점의 `Asia/Seoul` 기준으로 만든다.
  그래서 같은 시나리오를 다음 날 다시 돌려도 일정이 어긋나지 않는다.
- **확인값 분리**: `confirm=reset-all-data` 없이는 실행되지 않는다. `test-*.sql` 의 `seed` 와
  값을 다르게 둔 것은 버튼을 잘못 눌러 운영 데이터가 통째로 날아가는 것을 막기 위해서다.

두 계열을 섞지 않는다. 새 파일을 만들 때 이름 접두사(`test-` / `demo-`)가 곧 계열 선언이고,
워크플로가 그 접두사로 요구 확인값을 정한다.

이 규칙들은 현재 사람이 지키는 것이고 코드로 강제되지 않는다. seed 가 늘거나 규칙을 어긴
파일이 실제로 들어오면 정적 검사 도입을 다시 판단한다.

### 14.4 실패했을 때

| 증상                                          | 원인                                                          |
| --------------------------------------------- | ------------------------------------------------------------- |
| `not an allowed seed file`                    | `file` 이 저장소의 `.sql` 이름과 다름. 같은 로그에 목록이 찍힌다 |
| `seed 서비스가 없다`                          | 14.1 을 하지 않음                                              |
| `could not parse host/database`               | `.env` 의 `FLYWAY_URL` 형식이 `jdbc:mysql://host/db` 가 아님   |
| `SEED_FILE must be a bare file name`          | 컨테이너 쪽 심층 방어가 걸린 경우. 정상 경로에서는 보이지 않는다 |
| job 이 `skipped` 로 끝남                      | `confirm` 이 `seed` 도 `reset-all-data` 도 아님. 오타를 의심한다 |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `dev` 가 아닌 브랜치로 실행함. 2.1 절 참고 |
| `실행 확인값은 ... 이어야 한다`               | `confirm` 과 파일 계열이 어긋남. 14.2 의 표를 볼 것            |

`test-*.sql` 은 멱등이므로 **실패해도 그냥 다시 돌리면 된다.** 중간에 끊겼을 때 별도 복구
절차가 없다.

`demo-*.sql` 은 앱을 정지하고 파일을 지우므로 그렇게 단순하지 않다. 다만 SQL 이 실패하면
Transaction 이 Rollback 되고 문서 삭제는 SQL 성공 뒤에만 일어나며, 재기동 Step 은
`if: always()` 라 중간에 죽어도 **재기동을 시도한다.** 그래서 **부분 실패의 결과는 "데이터가
반쯤 지워진 상태"가 아니라 "시연 준비가 덜 끝난 상태"다.** 원인을 고치고 다시 돌리면 된다.

**다만 `always()` 는 그 Step 이 실행된다는 보장이지 성공한다는 보장이 아니다.** SSH 나
`docker compose start` 자체가 실패하면 앱이 내려간 채로 남는다. 이어지는
`Wait for application after full demo reset` 은 앞 Step 이 실패하면 건너뛰므로 기동을 대신
확인해 주지도 않는다. 그래서 **demo 시드가 빨간 X 로 끝나면 run 로그에서
`Restart application after full demo reset` 의 결과부터 본다.** 그 Step 이 실패했거나
`https://api.gighub.store/api/health` 가 응답하지 않으면 서버에서 직접 올린다.

```bash
ssh -i ~/.ssh/my-keypair.pem ec2-user@13.125.191.199 \
  "docker compose -f /opt/gighub/compose.prod.yaml start app"
```

아래 두 가지는 2026-08-19 에 실제로 겪은 것이다. 워크플로는 고쳤지만 증상을 남겨 둔다.

| 증상                                                        | 확인할 것                                                                 |
| ----------------------------------------------------------- | ------------------------------------------------------------------------- |
| `rm: cannot remove '/opt/gighub/documents/...': Permission denied` | 문서는 컨테이너가 자기 UID 로 쓴다. 삭제에 `sudo` 가 붙어 있는지 (#448) |
| 시드 뒤 배포했던 API 가 사라짐 (`HttpRequestMethodNotSupportedException`) | 재기동이 `up -d` 가 아니라 `start` 인지. `up -d` 는 이미지 태그를 `.env` 의 `API_TAG` 로 다시 해석하므로, 그 값이 실제로 떠 있던 버전과 어긋나 있으면 다른 버전이 올라온다 (#450, 근본 원인 #452) |

두 번째는 시드가 **운영 애플리케이션 버전을 되돌려 놓는** 증상이라 시연 준비뿐 아니라 실제
사용자에게도 영향이 간다. 시드 실행 뒤 앱이 이상하면 10절의 `images app` 으로 태그와
IMAGE ID 부터 확인한다.

## 15. SSE 실시간 알림 — 배포에서만 실패하는 3건

`GET /api/notifications/stream` 은 끝나지 않는 응답(Server-Sent Events)이다. **로컬은 nginx 를
거치지 않으므로, 아래 3건이 빠져도 로컬에서는 멀쩡하고 배포에서만 실패한다.** 셋 다 애플리케이션
코드에 이미 반영돼 있다(#386). 이 절은 그것이 무엇을 막고 있는지와, 배포 후 어떻게 확인하는지를
남긴다.

| 대응                                              | 없으면 생기는 일                                   | 코드 위치 |
| ------------------------------------------------- | -------------------------------------------------- | --------- |
| 응답에 `X-Accel-Buffering: no`                    | nginx 가 응답을 버퍼링해 이벤트가 도달하지 않는다  | `NotificationStreamController` |
| 25초 주기 하트비트(comment 프레임)                | `proxy_read_timeout 60s` 로 60초에 끊긴다          | `NotificationEmitterRegistry` |
| `new SseEmitter(timeout)` 로 타임아웃 명시        | Tomcat 9 기본 async 타임아웃 30초로 30초마다 끊긴다 | `NotificationEmitterRegistry` |

**nginx 설정 대신 헤더로 푸는 이유**: 서버의 `/etc/nginx/conf.d/api.gighub.store.conf` 는 certbot
이 443 블록을 써넣어 저장소 사본과 이미 다르다. 저장소 파일을 서버로 복사하면 HTTPS 설정이
사라진다. nginx 는 업스트림 응답에 `X-Accel-Buffering: no` 가 있으면 **그 응답에 한해** 버퍼링을
끄므로, 애플리케이션이 헤더로 제어하면 서버를 재구축해도 자동으로 맞는다.

`cors.allowed-origins` 에 프론트 Origin 이 있어야 한다. 브라우저 `EventSource` 는 기본적으로
쿠키를 보내지 않아 `withCredentials: true` 로 열며, 그러면 CORS 검사 대상이 된다. GET 이므로
CSRF 는 무관하다.

### 15.1 배포 후 검증 — 끊기는 시각이 원인을 알려준다

```bash
# JSESSIONID 는 브라우저 로그인 후 개발자도구에서 복사한다.
curl -N -H "Accept: text/event-stream" \
  --cookie "JSESSIONID=<로그인해서 얻은 값>" \
  https://api.gighub.store/api/notifications/stream
```

`-N` 이 curl 쪽 버퍼링을 끈다. 이것을 빼면 서버가 정상이어도 아무것도 안 보인다.

| 관측                          | 판정                                    |
| ----------------------------- | --------------------------------------- |
| `:connected` 가 즉시, 이후 25초마다 `:keep-alive` | 정상                    |
| 아무것도 안 나오거나 뭉쳐서 나옴 | `X-Accel-Buffering` 누락             |
| 약 60초에 끊김                | 하트비트 누락                           |
| 약 30초에 끊김                | `SseEmitter` 타임아웃 미명시            |
| 즉시 401                      | 쿠키를 안 보냈거나 세션 만료            |

실제 알림 신호까지 보려면 위 `curl` 을 띄워 둔 채로 다른 창에서 그 사용자에게 알림이 생기는
동작(초대 수락·정산 완료 등)을 하고, `event: notification` 한 줄이 오는지 본다.

### 15.2 그래도 nginx 를 고쳐야 한다면

위 3건으로 해결되지 않을 때만 손댄다. **저장소 파일을 서버로 복사하지 말 것.** 서버 파일을 직접
편집한다.

```bash
sudo cp /etc/nginx/conf.d/api.gighub.store.conf{,.bak-$(date +%F)}
sudo vi /etc/nginx/conf.d/api.gighub.store.conf   # 443 server 블록 안에 location 추가
sudo nginx -t && sudo systemctl reload nginx      # -t 통과 전에 reload 금지
```

```nginx
location /api/notifications/stream {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
}
```

접두 location 은 가장 긴 일치가 이기므로 파일 안에서의 순서는 상관없다. 서버에서 고쳤다면
`deploy/nginx/api.gighub.store.conf` 에도 같은 내용을 반영하는 PR 을 올려야 재구축 시 사라지지
않는다.

### 15.3 스트림이 죽어도 알림은 죽지 않는다

SSE 는 전달 수단이다. 연결이 실패한 동안에도 알림 모달을 열면 목록이 조회되고 읽음 처리도
동작한다. 그러므로 **이 절의 실패는 배포를 되돌릴 사유가 아니다.** 영향은 "새 알림을 화면
새로고침으로만 확인하게 된다"까지다.

연결은 인스턴스 메모리에 유지되므로 앱 컨테이너를 재시작하면 모든 구독이 끊긴다. 브라우저
`EventSource` 가 자동으로 다시 붙으므로 별도 조치는 필요 없다.

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
| Slack 알림이 안 옴            | CloudWatch Logs `/aws/lambda/gighub-slack-notifier`. **로그에 실패가 있으면** Webhook URL, **호출 자체가 없으면** SNS 트리거와 알람의 알림 대상 |
| Lambda 로그 그룹이 없음       | 함수가 한 번도 실행된 적이 없다. 13.7절 2번으로 강제 발화시킨다 |
| 알람이 `INSUFFICIENT_DATA` 고정 | Alarm B 를 버스터블이 아닌 인스턴스에 만들었다. 13.1절 |
| 배포할 때마다 알림이 울림     | 임계치가 낮거나 평가 기간이 짧다. 13.2절로 재관측, 3/3 유지 |
| 알림 시각이 9시간 어긋남      | 옛 코드가 배포돼 있다. 13.5절 코드 배포 후 Deploy 버튼 |
| 알림 배지가 새로고침해야 갱신됨 | SSE 스트림이 끊겼다. 15.1절 `curl -N` 으로 **끊기는 초 단위**를 먼저 본다 |
| SSE 가 로컬은 되는데 배포만 안 됨 | 로컬은 nginx 를 거치지 않는다. 15절 3건이 모두 배포된 이미지인지 확인 |
