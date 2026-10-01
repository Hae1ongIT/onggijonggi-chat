<#
    .env 파일을 만든다 (INSTALL.md 1단계 — 기본값 대신 직접 정한 자격증명을 쓰고 싶을 때).

    Windows 전용이다. macOS·Linux는 같은 폴더의 init-env.sh 를 쓴다.

    ※ 이 파일은 반드시 UTF-8 BOM으로 저장한다. Windows PowerShell 5.1은 BOM이 없으면
      시스템 코드페이지(한국어 Windows는 949)로 읽어 한글이 깨지고 구문 오류가 난다.

    프로그램끼리만 주고받는 비밀값 6개는 무작위로 채운다 — 사람이 볼 일이 없고,
    눈으로 옮겨 적다 틀리는 것이 이 설치에서 가장 흔한 실패였다.
    사람이 로그인할 때 실제로 입력하는 비밀번호 2개만 직접 받는다.

    비대화식으로 쓰려면 -AdminPassword / -UserPassword 를 넘긴다.
#>
param(
    [string]$AdminPassword,
    [string]$UserPassword
)

$ErrorActionPreference = 'Stop'

$infraDir = Split-Path -Parent $PSScriptRoot
$example = Join-Path $infraDir '.env.example'
$target = Join-Path $infraDir '.env'

if (-not (Test-Path $example)) {
    Write-Host "[실패] .env.example 이 없습니다: $example" -ForegroundColor Red
    exit 1
}

# 이미 설치를 한 뒤라면 .env의 비밀값이 Keycloak·데이터베이스에 이미 반영돼 있다.
# 새로 만들면 그 값들과 어긋나 로그인이 막히므로 덮어쓰지 않는다.
if (Test-Path $target) {
    Write-Host "[중단] .env 가 이미 있습니다." -ForegroundColor Yellow
    Write-Host "       새로 만들면 기존 설치의 로그인 정보와 어긋납니다."
    Write-Host "       정말 다시 만들려면 .env 를 지우고 이 명령을 다시 실행하세요."
    exit 1
}

function New-Secret {
    -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ })
}

function Read-RequiredPassword($label, $preset) {
    if ($preset) { return $preset }
    while ($true) {
        $v = Read-Host $label
        if ($v.Length -ge 4) { return $v }
        Write-Host "      4자 이상으로 입력하세요." -ForegroundColor Yellow
    }
}

if (-not $AdminPassword -or -not $UserPassword) {
    Write-Host ""
    Write-Host "로그인할 때 쓸 비밀번호 2개를 정하세요."
    Write-Host "입력한 값이 화면에 그대로 보입니다 - 메모해 두세요."
    Write-Host ""
}
$adminPw = Read-RequiredPassword "  1) 관리자 화면 비밀번호" $AdminPassword
$userPw = Read-RequiredPassword "  2) 채팅 로그인 비밀번호" $UserPassword

$values = [ordered]@{
    'POSTGRES_PASSWORD'       = New-Secret
    'LITELLM_MASTER_KEY'      = 'sk-' + (New-Secret)
    'LITELLM_SALT_KEY'        = New-Secret
    'NEXTAUTH_SECRET'         = New-Secret
    'KEYCLOAK_CLIENT_SECRET'  = New-Secret
    'KEYCLOAK_BFF_CLIENT_SECRET' = New-Secret
    'KEYCLOAK_ADMIN_PASSWORD' = $adminPw
    'APP_USER_PASSWORD'       = $userPw
}

$filled = @{}
$appUser = 'appuser'
$out = foreach ($line in (Get-Content $example -Encoding UTF8)) {
    if ($line -match '^APP_USER=(.*)$') { $appUser = $Matches[1] }
    $key = $null
    foreach ($k in $values.Keys) {
        if ($line -match ("^" + [regex]::Escape($k) + "=")) { $key = $k; break }
    }
    if ($key) { $filled[$key] = $true; "$key=$($values[$key])" } else { $line }
}

$missing = @($values.Keys | Where-Object { -not $filled.ContainsKey($_) })
if ($missing.Count -gt 0) {
    Write-Host "[실패] .env.example 에서 못 찾은 항목: $($missing -join ', ')" -ForegroundColor Red
    Write-Host "       .env.example 이 수정된 것 같습니다. .env.example 의 주석을 보고 직접 채우세요."
    exit 1
}

# BOM 없는 UTF-8로 쓴다. BOM이 붙으면 docker compose가 첫 항목을 못 읽는다.
[System.IO.File]::WriteAllLines($target, $out, (New-Object System.Text.UTF8Encoding $false))

Write-Host ""
Write-Host "[완료] .env 를 만들었습니다. 무작위 비밀값 6개는 자동으로 채웠습니다." -ForegroundColor Green
Write-Host ""
Write-Host "  8단계에서 채팅에 로그인할 값 - 메모해 두세요"
Write-Host "    아이디   : $appUser"
Write-Host "    비밀번호 : $userPw"
Write-Host ""
Write-Host "  관리자 화면 로그인 (필요할 때만)"
Write-Host "    아이디   : admin"
Write-Host "    비밀번호 : $adminPw"
Write-Host ""
