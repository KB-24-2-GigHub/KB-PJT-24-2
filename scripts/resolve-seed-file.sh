#!/bin/sh
# 워크플로 입력으로 받은 seed 파일명을 검증하고 그 이름을 표준출력으로 돌려준다.
#
# 워크플로가 ${{ inputs.file }} 을 run 스크립트에 직접 박으면, GitHub 이 셸 파싱
# 전에 문자열을 치환하므로 작은따옴표나 줄바꿈이 든 값이 셸 구문으로 실행된다.
# 스크립트 안의 어떤 검사도 그보다 먼저 돌 수 없다. 그래서 입력은 반드시 env 로
# 넘겨받고, 검증은 여기서 한다.
#
# 검증은 두 단계다.
#   1) SEED_SOURCE 에 실제로 존재하는 .sql 파일 이름과 정확히 일치할 것
#   2) 그 이름에 셸 메타문자가 없을 것
#
# 1번만으로도 임의 문자열은 걸러지지만, 저장소에 이상한 이름의 파일이 들어오는
# 경우까지 막으려면 2번이 필요하다. 원격 명령에 넘어가는 값이기 때문이다.

set -eu

: "${SEED_SOURCE:?SEED_SOURCE is required}"
: "${SEED_FILE_INPUT:?SEED_FILE_INPUT is required}"

if [ ! -d "$SEED_SOURCE" ]; then
  echo "seed source directory not found: $SEED_SOURCE" >&2
  exit 1
fi

list_available() {
  for candidate in "$SEED_SOURCE"/*.sql; do
    [ -f "$candidate" ] || continue
    echo "  ${candidate##*/}" >&2
  done
}

matched=""
for candidate in "$SEED_SOURCE"/*.sql; do
  [ -f "$candidate" ] || continue
  name="${candidate##*/}"
  if [ "$name" = "$SEED_FILE_INPUT" ]; then
    matched="$name"
    break
  fi
done

if [ -z "$matched" ]; then
  echo "not an allowed seed file: $SEED_FILE_INPUT" >&2
  echo "available:" >&2
  list_available
  exit 1
fi

case "$matched" in
  *[!A-Za-z0-9._-]*)
    echo "seed file name has characters outside [A-Za-z0-9._-]: $matched" >&2
    exit 1
    ;;
esac

printf '%s\n' "$matched"
