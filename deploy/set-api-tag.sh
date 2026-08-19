#!/bin/sh
# /opt/gighub/.env 의 API_TAG 를 방금 띄운 이미지 태그로 맞춘다.
#
# compose.prod.yaml 의 app 이미지는 ${API_TAG} 로 보간되고, Compose 는 셸
# 환경변수를 .env 보다 우선한다. 배포는 `export API_TAG=<sha>` 로 올바른 이미지를
# 띄우지만 그 값은 SSH 세션과 함께 사라진다. .env 를 갱신하지 않으면 서버에 남는
# 값은 계속 옛 태그이고, 뒤에 누가 API_TAG 없이 `docker compose up -d app` 을
# 하면 그 옛 태그가 조용히 다시 뜬다. 2026-08-19 에 실제로 그렇게 구버전 WAR 가
# 올라와 POST /api/documents 가 사라졌다 (#450, 근본 원인 #452).
#
# 배치 위치: /opt/gighub/set-api-tag.sh  (deploy-api.yml 이 배포마다 올린다)
# 사용:      sh /opt/gighub/set-api-tag.sh <tag>

set -eu

tag="${1:-}"

if [ -z "$tag" ]; then
  echo "usage: set-api-tag.sh <tag>" >&2
  exit 1
fi

# 이 값은 .env 에 그대로 적히고 다음 기동의 이미지 참조가 된다. Docker 태그 문법을
# 벗어난 값은 지금이 아니라 "다음에 누가 up -d 할 때" 깨지므로 여기서 막는다.
# 치환문에 들어가는 값이기도 해서, 형식 검증이 곧 주입 방지다.
case "$tag" in
  [A-Za-z0-9_]*) ;;
  *)
    echo "tag must start with a letter, digit, or underscore, got: $tag" >&2
    exit 1
    ;;
esac
case "$tag" in
  *[!A-Za-z0-9._-]*)
    echo "tag may contain only [A-Za-z0-9._-], got: $tag" >&2
    exit 1
    ;;
esac
if [ "${#tag}" -gt 128 ]; then
  echo "tag must be at most 128 characters, got ${#tag}" >&2
  exit 1
fi

# 서버에서는 항상 이 경로다. 테스트에서만 다른 파일을 가리킨다.
ENV_FILE="${ENV_FILE:-/opt/gighub/.env}"

# 없으면 만들지 않고 멈춘다. .env 에는 FLYWAY_* 접속값이 함께 있어서, API_TAG 한
# 줄짜리 파일을 새로 만들면 Migration 과 seed 가 접속값을 잃은 채로 진행된다.
if [ ! -f "$ENV_FILE" ]; then
  echo "env file not found: $ENV_FILE" >&2
  exit 1
fi

# .env 에는 FLYWAY_PASSWORD 가 있다. 임시 파일이 한순간이라도 넓게 열리면 안 된다.
umask 077

tmp="$ENV_FILE.tmp.$$"
trap 'rm -f "$tmp"' EXIT INT TERM

# 첫 API_TAG 줄만 제자리에서 바꾸고 나머지 키·값·순서는 그대로 둔다. 키가 없으면
# 끝에 붙이고, 중복 줄은 하나로 정리한다(Compose 는 마지막 값을 쓰므로 남겨 두면
# 고친 줄이 조용히 무시된다).
awk -v tag="$tag" '
  /^API_TAG=/ { if (!seen) { print "API_TAG=" tag; seen = 1 } next }
  { print }
  END { if (!seen) print "API_TAG=" tag }
' "$ENV_FILE" > "$tmp"

# mv 가 아니라 원본에 부어 넣는다. inode 를 유지해야 chmod 600 과 소유자가
# 그대로 남는다. mv 로 바꾸면 umask 가 만든 임시 파일의 속성이 .env 가 된다.
cat "$tmp" > "$ENV_FILE"

echo "API_TAG=$tag written to $ENV_FILE"
