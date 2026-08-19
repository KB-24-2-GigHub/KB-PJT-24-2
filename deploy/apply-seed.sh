#!/bin/sh
# /seed 에 마운트된 SQL 한 개를 RDS 에 적용한다. seed 컨테이너의 entrypoint 다.
#
# 접속값은 .env 의 FLYWAY_* 를 그대로 쓴다. mysql 전용 변수를 따로 두면 RDS
# 엔드포인트나 비밀번호가 바뀔 때 한쪽만 고쳐져 조용히 어긋난다. Flyway 와
# 같은 값을 본다는 것이 이 스크립트의 전제다.
#
# 배치 위치: /opt/gighub/apply-seed.sh

set -eu

: "${FLYWAY_URL:?FLYWAY_URL is required}"
: "${FLYWAY_USER:?FLYWAY_USER is required}"
: "${FLYWAY_PASSWORD:?FLYWAY_PASSWORD is required}"
: "${SEED_FILE:?SEED_FILE is required}"

# SEED_FILE 은 워크플로 입력에서 그대로 내려온다. 경로를 벗어나 /seed 밖의
# 파일을 읽지 못하게 막는다.
case "$SEED_FILE" in
  */*|*..*)
    echo "SEED_FILE must be a bare file name, got: $SEED_FILE" >&2
    exit 1
    ;;
esac
case "$SEED_FILE" in
  *.sql) ;;
  *)
    echo "SEED_FILE must end with .sql, got: $SEED_FILE" >&2
    exit 1
    ;;
esac

# demo 시드는 전체 애플리케이션 데이터를 지웁니다. Compose 서비스나 Workflow를
# 우회해 entrypoint를 직접 호출해도 명시적 전체 초기화 확인값 없이는 실행하지 않습니다.
case "$SEED_FILE" in
  demo-*.sql)
    if [ "${DEMO_RESET_CONFIRM:-}" != "reset-all-data" ]; then
      echo "demo seed requires DEMO_RESET_CONFIRM=reset-all-data" >&2
      exit 1
    fi
    ;;
esac

case "$FLYWAY_URL" in
  jdbc:mysql://*) ;;
  *)
    echo "FLYWAY_URL must start with jdbc:mysql://, got: $FLYWAY_URL" >&2
    exit 1
    ;;
esac

# jdbc:mysql://host[:port]/database[?params] 를 조각으로 나눈다.
rest="${FLYWAY_URL#jdbc:mysql://}"
authority="${rest%%/*}"
path="${rest#*/}"
database="${path%%\?*}"

case "$authority" in
  *:*)
    host="${authority%%:*}"
    port="${authority##*:}"
    ;;
  *)
    host="$authority"
    port=3306
    ;;
esac

if [ -z "$host" ] || [ -z "$database" ] || [ "$rest" = "$path" ]; then
  echo "could not parse host/database from FLYWAY_URL: $FLYWAY_URL" >&2
  exit 1
fi

# 컨테이너에서는 항상 /seed 다. 테스트에서만 다른 디렉터리를 가리킨다.
SEED_DIR="${SEED_DIR:-/seed}"

if [ ! -f "$SEED_DIR/$SEED_FILE" ]; then
  echo "seed file not found: $SEED_DIR/$SEED_FILE" >&2
  exit 1
fi

# 자격 증명을 argv 에 두면 컨테이너 안에서 ps 로 보인다.
MYSQL_PWD="$FLYWAY_PASSWORD"
export MYSQL_PWD

echo "applying $SEED_DIR/$SEED_FILE to $host:$port/$database"

exec mysql \
  --host="$host" \
  --port="$port" \
  --user="$FLYWAY_USER" \
  --default-character-set=utf8mb4 \
  --ssl-mode=REQUIRED \
  "$database" < "$SEED_DIR/$SEED_FILE"
