#!/bin/sh
#
# .env 파일을 만든다 (INSTALL.md 1단계 — 기본값 대신 직접 정한 자격증명을 쓰고 싶을 때).
#
# macOS·Linux 전용이다. Windows는 같은 폴더의 init-env.ps1 을 쓴다.
#
# 프로그램끼리만 주고받는 비밀값 6개는 무작위로 채운다 — 사람이 볼 일이 없고,
# 눈으로 옮겨 적다 틀리는 것이 이 설치에서 가장 흔한 실패였다.
# 사람이 로그인할 때 실제로 입력하는 비밀번호 2개만 직접 받는다.
#
# 비대화식으로 쓰려면 --admin-password / --user-password 를 넘긴다.

set -eu

adminPassword=''
userPassword=''
while [ $# -gt 0 ]; do
    case "$1" in
        --admin-password)
            [ $# -ge 2 ] || { echo "[실패] --admin-password 에 값이 없습니다." >&2; exit 1; }
            adminPassword=$2; shift 2 ;;
        --user-password)
            [ $# -ge 2 ] || { echo "[실패] --user-password 에 값이 없습니다." >&2; exit 1; }
            userPassword=$2; shift 2 ;;
        *)
            echo "[실패] 알 수 없는 인자: $1" >&2; exit 1 ;;
    esac
done

scriptDir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
infraDir=$(dirname -- "$scriptDir")
example="$infraDir/.env.example"
target="$infraDir/.env"

if [ ! -f "$example" ]; then
    echo "[실패] .env.example 이 없습니다: $example" >&2
    exit 1
fi

# 이미 설치를 한 뒤라면 .env의 비밀값이 Keycloak·데이터베이스에 이미 반영돼 있다.
# 새로 만들면 그 값들과 어긋나 로그인이 막히므로 덮어쓰지 않는다.
if [ -e "$target" ]; then
    echo "[중단] .env 가 이미 있습니다."
    echo "       새로 만들면 기존 설치의 로그인 정보와 어긋납니다."
    echo "       정말 다시 만들려면 .env 를 지우고 이 명령을 다시 실행하세요."
    exit 1
fi

new_secret() {
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40
}

# 프롬프트는 표준오류로 내보낸다 — 표준출력은 호출한 쪽이 값으로 받아간다.
read_required_password() {
    _preset=$2
    if [ -n "$_preset" ]; then
        printf '%s' "$_preset"
        return
    fi
    while true; do
        printf '%s: ' "$1" >&2
        read -r _value || _value=''
        if [ ${#_value} -ge 4 ]; then
            printf '%s' "$_value"
            return
        fi
        echo "      4자 이상으로 입력하세요." >&2
    done
}

if [ -z "$adminPassword" ] || [ -z "$userPassword" ]; then
    echo ""
    echo "로그인할 때 쓸 비밀번호 2개를 정하세요."
    echo "입력한 값이 화면에 그대로 보입니다 - 메모해 두세요."
    echo ""
fi
adminPw=$(read_required_password "  1) 관리자 화면 비밀번호" "$adminPassword")
userPw=$(read_required_password "  2) 채팅 로그인 비밀번호" "$userPassword")

keys='POSTGRES_PASSWORD LITELLM_MASTER_KEY LITELLM_SALT_KEY NEXTAUTH_SECRET KEYCLOAK_CLIENT_SECRET KEYCLOAK_BFF_CLIENT_SECRET KEYCLOAK_ADMIN_PASSWORD APP_USER_PASSWORD'

value_POSTGRES_PASSWORD=$(new_secret)
value_LITELLM_MASTER_KEY="sk-$(new_secret)"
value_LITELLM_SALT_KEY=$(new_secret)
value_NEXTAUTH_SECRET=$(new_secret)
value_KEYCLOAK_CLIENT_SECRET=$(new_secret)
value_KEYCLOAK_BFF_CLIENT_SECRET=$(new_secret)
value_KEYCLOAK_ADMIN_PASSWORD=$adminPw
value_APP_USER_PASSWORD=$userPw

appUser='appuser'
filled=''
tmp="$target.tmp.$$"
: > "$tmp"

# 마지막 줄에 개행이 없어도 읽히도록 `|| [ -n "$line" ]` 을 붙인다.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        APP_USER=*) appUser=${line#APP_USER=} ;;
    esac
    matched=''
    for key in $keys; do
        case "$line" in
            "$key"=*) matched=$key; break ;;
        esac
    done
    if [ -n "$matched" ]; then
        eval "value=\$value_$matched"
        printf '%s=%s\n' "$matched" "$value" >> "$tmp"
        filled="$filled $matched"
    else
        printf '%s\n' "$line" >> "$tmp"
    fi
done < "$example"

missing=''
for key in $keys; do
    case " $filled " in
        *" $key "*) ;;
        *) missing="$missing $key" ;;
    esac
done
if [ -n "$missing" ]; then
    rm -f "$tmp"
    echo "[실패] .env.example 에서 못 찾은 항목:$missing" >&2
    echo "       .env.example 이 수정된 것 같습니다. .env.example 의 주석을 보고 직접 채우세요." >&2
    exit 1
fi

mv "$tmp" "$target"

echo ""
echo "[완료] .env 를 만들었습니다. 무작위 비밀값 6개는 자동으로 채웠습니다."
echo ""
echo "  8단계에서 채팅에 로그인할 값 - 메모해 두세요"
echo "    아이디   : $appUser"
echo "    비밀번호 : $userPw"
echo ""
echo "  관리자 화면 로그인 (필요할 때만)"
echo "    아이디   : admin"
echo "    비밀번호 : $adminPw"
echo ""
