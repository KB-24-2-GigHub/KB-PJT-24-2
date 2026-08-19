#!/bin/sh
# /opt/gighub/.env 의 API_TAG 를 방금 띄운 이미지 태그로 맞춘다.
#
# 왜 필요한지와 2026-08-19 사고 경위는 deploy/SETUP.md 7절에 있다. 한 줄로 줄이면,
# Compose 가 셸 환경변수를 .env 보다 우선하는 탓에 배포는 옳은 이미지를 띄우지만 그
# 값이 서버에 남지 않아 이후 맨손 `up -d app` 이 옛 태그로 되돌아간다.
#
# 배치 위치: /opt/gighub/set-api-tag.sh  (deploy-api.yml 이 배포마다 올린다)
# 사용:      sh /opt/gighub/set-api-tag.sh <tag>

set -eu

# 아래 case 패턴의 [A-Za-z0-9_] 는 범위 표현이고, POSIX 는 범위의 해석을 POSIX 로케일
# 밖에서는 미정의로 둔다(LC_COLLATE 의존). sshd 가 AcceptEnv 로 클라이언트의 LANG/LC_*
# 를 받으므로 판정 기준을 호출자가 정하게 되는 구조다. 이 검증이 곧 주입 방지 경계이니
# 고정한다. ${#tag} 계산도 함께 결정적이 된다.
#
# glibc(en_US.UTF-8, sh/dash)와 musl 에서 악센트 문자가 범위에 접히는지 실측해 봤고
# 둘 다 거부됐다. 지금 깨져 있다는 뜻이 아니라, 미정의 동작에 기대지 않겠다는 것이다.
LC_ALL=C
export LC_ALL

tag="${1:-}"

if [ -z "$tag" ]; then
  echo "usage: set-api-tag.sh <tag>" >&2
  exit 1
fi

# 이 값은 .env 에 그대로 적히고 다음 기동의 이미지 참조가 된다. Docker 태그 문법을
# 벗어난 값은 지금이 아니라 "다음에 누가 up -d 할 때" 깨지므로 여기서 막는다.
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
trap 'rm -f "$tmp"' EXIT
# POSIX sh 에서 EXIT 이 아닌 시그널 트랩은 핸들러가 끝나면 "중단된 명령 다음 줄부터"
# 실행을 재개한다. exit 를 넣지 않으면 임시본만 지운 채 남은 줄이 계속 돌아간다.
trap 'rm -f "$tmp"; exit 130' INT
trap 'rm -f "$tmp"; exit 143' TERM

# 원본의 권한과 소유를 물려받은 임시본을 먼저 만들고 내용만 덮어쓴다. cp -p 는 소유자
# 복제에 실패해도 POSIX 상 오류가 아니므로, 권한 보존이 실패 사유가 되지는 않는다.
cp -p "$ENV_FILE" "$tmp"

# 첫 API_TAG 줄만 바꾸고 나머지 키·값·순서는 그대로 둔다. 키가 없으면 끝에 붙이고,
# 중복 줄은 하나로 정리한다. Compose 는 마지막 값을 쓰므로 남겨 두면 고친 줄이 조용히
# 무시된다.
#
# 앵커가 선행 공백과 `export ` 접두까지 받는 이유가 그것이다. `^API_TAG=` 만 보면
# ` API_TAG=old` 나 `export API_TAG=old` 를 지나치는데, Compose 는 두 형태를 모두
# 읽으므로 그 줄이 뒤에 남아 우리가 쓴 값을 덮는다.
awk -v tag="$tag" '
  /^[ \t]*(export[ \t]+)?API_TAG=/ { if (!seen) { print "API_TAG=" tag; seen = 1 } next }
  { print }
  END { if (!seen) print "API_TAG=" tag }
' "$ENV_FILE" > "$tmp"

# 같은 디렉터리 안의 mv 는 rename(2) 이라 원자적이다. .env 는 옛 내용이거나 새 내용
# 이지 그 중간이 될 수 없다. `cat tmp > .env` 는 한 바이트를 쓰기 전에 .env 를 먼저
# 비우므로, 쓰기가 중간에 실패하면 접속값이 든 유일한 사본이 잘린 채로 남는다.
mv "$tmp" "$ENV_FILE"

# 사후조건. 이 스크립트의 존재 이유가 "서버에 남는 값이 참이어야 한다" 이므로, 썼다고
# 출력하기 전에 되읽는다. 테스트가 만들어 낼 수 없는 상태 — 짧은 쓰기, 꽉 찬 디스크,
# 읽기전용 오버레이가 삼킨 쓰기 — 가 초록으로 지나가지 않게 한다.
grep -qxF -- "API_TAG=$tag" "$ENV_FILE"
[ "$(grep -Ec '^[[:space:]]*(export[[:space:]]+)?API_TAG=' "$ENV_FILE")" -eq 1 ]

echo "API_TAG=$tag written to $ENV_FILE"
