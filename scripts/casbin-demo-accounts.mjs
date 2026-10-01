#!/usr/bin/env node
// casbin 프로필을 켜고 권한 판정을 확인할 시험 계정(demo1~demo7)을 로컬 Keycloak에 만든다.
// 사용: node scripts/casbin-demo-accounts.mjs
//
// --disable-cli 를 주면 계정은 건드리지 않고 ogjg-cli만 끈다(데모를 마친 뒤).
// 계정을 만들고, 배정 스크립트가 브라우저 로그인에 쓰는 ogjg-cli 클라이언트를 켠다(realm 파일은 꺼진 채 만든다 —
// 켜 두면 device code 피싱 경로가 되므로 데모에서만 켠다). 팀·직급 배정은 따로 넣는다 —
// node scripts/import-members.mjs infra/config/demo-members.csv --apply (demo7은 미배정 확인용이라 CSV에 없다).
// 이미 있는 계정은 이메일·이름·비밀번호를 아래 표대로 덮어쓰고 USER 역할을 붙인다 — 여러 번 돌려도 결과가 같다.
// 비밀번호는 계정 이름과 같다. realm-app.json에 적지 않은 이유: 이미 만든 로컬 realm에는 반영되지 않아서다.
//
// 계정을 만들려면 manage-users 권한이 필요한데 bff의 서비스 계정(ogjg-bff)은 조회 역할(view-*)뿐이라, Keycloak 관리자
// (infra/.env의 KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD)로 master realm에서 토큰을 받는다.
// Keycloak 주소는 KEYCLOAK_URL로 바꿀 수 있고 기본은 compose가 여는 http://localhost:8081이다.
//
// 의존성 없음(node 내장만) — 저장소 스크립트의 무설치 단독 실행 전제를 따른다.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ENV_PATH = join(ROOT, 'infra', '.env');

// 팀·직급은 CSV 임포트에서 넣는다. 여기 적은 것은 그때 쓸 기대값(설명용)이다.
const ACCOUNTS = [
	{ username: 'demo1', lastName: '송', firstName: '강호' }, // hr, TL
	{ username: 'demo2', lastName: '전', firstName: '도연' }, // hr, K
	{ username: 'demo3', lastName: '황', firstName: '정민' }, // hr, D
	{ username: 'demo4', lastName: '김', firstName: '혜수' }, // hr, S
	{ username: 'demo5', lastName: '이', firstName: '병헌' }, // fin, B
	{ username: 'demo6', lastName: '윤', firstName: '여정' }, // legal, B
	{ username: 'demo7', lastName: '마', firstName: '동석' }, // 미배정
];

function readEnv(path) {
	if (!existsSync(path)) throw new Error(`${path}가 없다. infra/.env.example을 복사해 먼저 만든다.`);
	const values = {};
	for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
		const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
		if (match) values[match[1]] = match[2];
	}
	return values;
}

async function call(method, url, token, body) {
	const response = await fetch(url, {
		method,
		headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
		body: body ? JSON.stringify(body) : undefined,
	});
	if (!response.ok) throw new Error(`${method} ${url} → ${response.status} ${await response.text()}`);
	return response;
}

async function adminToken(base, username, password) {
	const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username, password }),
	});
	if (!response.ok) throw new Error(`Keycloak 관리자 토큰을 받지 못했다 → ${response.status} ${await response.text()}`);
	return (await response.json()).access_token;
}

async function main() {
	const env = readEnv(ENV_PATH);
	const base = (process.env.KEYCLOAK_URL || 'http://localhost:8081').replace(/\/$/, '');
	const realm = env.KEYCLOAK_REALM || 'app-realm';
	const token = await adminToken(base, env.KEYCLOAK_ADMIN || 'admin', env.KEYCLOAK_ADMIN_PASSWORD);
	const api = `${base}/admin/realms/${encodeURIComponent(realm)}`;
	// 데모가 끝나면 ogjg-cli를 다시 끈다 — 켜 둔 채면 device code 피싱 경로가 계속 열려 있다.
	if (process.argv.includes('--disable-cli')) {
		await setScriptClient(api, token, false);
		return;
	}
	const userRole = await (await call('GET', `${api}/roles/USER`, token)).json();

	for (const account of ACCOUNTS) {
		const profile = {
			username: account.username,
			email: `${account.username}@example.com`,
			emailVerified: true,
			enabled: true,
			firstName: account.firstName,
			lastName: account.lastName,
		};
		const password = { type: 'password', value: account.username, temporary: false };
		const found = await (await call('GET', `${api}/users?exact=true&username=${account.username}`, token)).json();
		let id = found[0]?.id;
		if (id) {
			await call('PUT', `${api}/users/${id}`, token, profile);
			await call('PUT', `${api}/users/${id}/reset-password`, token, password);
			console.log(`${account.username}: 이미 있어 덮어씀 (${account.lastName}${account.firstName})`);
		} else {
			const created = await call('POST', `${api}/users`, token, { ...profile, credentials: [password] });
			id = created.headers.get('location').split('/').pop();
			console.log(`${account.username}: 만듦 (${account.lastName}${account.firstName})`);
		}
		// 새 사용자에게 USER가 자동으로 붙지 않는다(realm에 defaultRole 설정이 없다). 이미 있으면 Keycloak이 그대로 둔다.
		await call('POST', `${api}/users/${id}/role-mappings/realm`, token, [userRole]);
	}
	await setScriptClient(api, token, true);
}

/** 배정 스크립트의 브라우저 로그인용 ogjg-cli를 켜거나 끈다. 없으면(이 클라이언트가 생기기 전에 만든 realm) 만드는 방법을 알린다. */
async function setScriptClient(api, token, enabled) {
	const clientId = process.env.CLI_CLIENT_ID || 'ogjg-cli';
	const [client] = await (await call('GET', `${api}/clients?clientId=${encodeURIComponent(clientId)}`, token)).json();
	if (!client) {
		console.log(`${clientId} 클라이언트가 없다 — INSTALL.md「Keycloak 관리 클라이언트와 권한 변경 감사」 8단계로 만든다.`);
		return;
	}
	if (client.enabled !== enabled) await call('PUT', `${api}/clients/${client.id}`, token, { ...client, enabled });
	console.log(enabled
		? `${clientId}: 켬 — 배정을 마치면 node scripts/casbin-demo-accounts.mjs --disable-cli 로 끈다`
		: `${clientId}: 끔`);
}

main().catch((error) => {
	console.error(error.message);
	// process.exit()는 쓰지 않는다 — Windows의 Node에서 fetch 연결이 닫히는 중에 부르면 libuv 단언으로 죽는다.
	process.exitCode = 1;
});
