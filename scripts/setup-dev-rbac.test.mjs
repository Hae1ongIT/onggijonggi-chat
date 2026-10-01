// 개발 환경 초기 설정의 비밀번호 모드·기존 데이터 보호·멱등 배정·CLI 복구를 검증한다.
// 사용: node --test scripts/setup-dev-rbac.test.mjs (테스트 러너가 실행하므로 shebang은 두지 않는다.)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { ACCOUNTS, ensureAccounts, manualPasswords, options, parseEnv, prepareEnvironment,
	privateJson, request, resetVolume, seedAssignments, setEnv, verifyAssignments, verifyAudit, withCliEnabled } from './setup-dev-rbac.mjs';

const template = readFileSync(new URL('../infra/.env.example', import.meta.url), 'utf8');
const passwords = { APP_USER_PASSWORD: 'app-password-123', KEYCLOAK_ADMIN_PASSWORD: 'admin-password-123',
	accounts: Object.fromEntries(ACCOUNTS.map(a => [a.username, `${a.username}-password-123`])) };

test('자동 모드는 공개 예제 자격증명을 모두 교체하고 RBAC를 활성화한다', () => {
	let count = 0;
	const { env } = prepareEnvironment(template, true, () => `generated-${++count}`);
	assert.equal(count, 9);
	assert.equal(env.SPRING_PROFILE, 'prod,casbin');
	assert.equal(env.COMPOSE_PROFILES, 'casbin');
	assert.equal(env.APP_USER_PASSWORD, 'generated-3');
	assert.match(env.LITELLM_MASTER_KEY, /^sk-generated-/);
});

test('기존 env 비밀번호·secret·LLM 설정은 재실행해도 보존한다', () => {
	const { env } = prepareEnvironment(template, false, () => { throw new Error('생성하면 안 됨'); });
	for (const key of ['APP_USER_PASSWORD', 'POSTGRES_PASSWORD', 'KEYCLOAK_CLIENT_SECRET', 'KEYCLOAK_BFF_CLIENT_SECRET', 'LLM_MODEL']) {
		assert.equal(env[key], parseEnv(template)[key]);
	}
});

test('직접 지정 모드는 APP·Keycloak 비밀번호를 사용하고 나머지 secret은 생성한다', () => {
	const { env } = prepareEnvironment(template, true, () => 'generated', manualPasswords(passwords));
	assert.equal(env.APP_USER_PASSWORD, passwords.APP_USER_PASSWORD);
	assert.equal(env.KEYCLOAK_ADMIN_PASSWORD, passwords.KEYCLOAK_ADMIN_PASSWORD);
	assert.equal(env.POSTGRES_PASSWORD, 'generated');
});

test('직접 지정 모드에서도 기존 계정 비밀번호는 교체하지 않는다', () => {
	assert.equal(prepareEnvironment(template, false, undefined, passwords).env.APP_USER_PASSWORD, parseEnv(template).APP_USER_PASSWORD);
});

test('기존 DB 비밀번호가 비어 있으면 임의 생성하지 않는다', () => {
	assert.throws(() => prepareEnvironment(setEnv(template, { POSTGRES_PASSWORD: '' }), false), /임의 변경/);
});

test('따옴표 env를 읽고 주석과 미변경 설정은 보존한다', () => {
	assert.deepEqual(parseEnv('# comment\nAPP_USER="hello"\nNEXTAUTH_SECRET=abc'), { APP_USER: 'hello', NEXTAUTH_SECRET: 'abc' });
	assert.match(setEnv('# comment\nAPP_USER=x\n', { APP_USER: 'y', NEW: 'z' }), /# comment\nAPP_USER=y\nNEW=z\n/);
	assert.throws(() => parseEnv('APP_USER="no'), /따옴표/);
});

test('공개 주소 불일치·기존 절체 target·클라이언트 충돌을 거부한다', () => {
	for (const changes of [{ NEXTAUTH_URL: 'http://other' }, { SPRING_FLYWAY_TARGET: '12' },
		{ KEYCLOAK_BFF_CLIENT_ID: 'ogjg-client' }, { PUBLIC_BFF_URL: 'file:///test' }]) {
		assert.throws(() => prepareEnvironment(setEnv(template, changes), false));
	}
});

test('자동/직접 지정 옵션과 필수 파일을 구분한다', () => {
	assert.equal(options([]).passwordMode, 'auto');
	assert.equal(options(['--password-mode', 'manual', '--password-file', 'infra/.env.passwords.json']).passwordMode, 'manual');
	assert.throws(() => options(['--password-mode', 'manual']), /필요/);
	assert.throws(() => options(['--password-file', 'secret.json']), /manual/);
	assert.throws(() => options(['--env-file']), /값/);
	assert.throws(() => options(['--no-check']), /알 수 없는/);
});

test('직접 지정 파일의 필수 비밀번호와 길이·줄바꿈을 검사한다', () => {
	assert.throws(() => manualPasswords({}), /12자/);
	assert.throws(() => manualPasswords({ ...passwords, APP_USER_PASSWORD: 'short' }), /12자/);
	assert.throws(() => manualPasswords({ ...passwords, APP_USER_PASSWORD: 'long\npassword123' }), /줄바꿈/);
	assert.throws(() => prepareEnvironment(template, true, undefined, { ...passwords, APP_USER_PASSWORD: 'unsafe$reference123' }), /사용할 수/);
});

const config = { name: 'test-only', volumes: { 'postgres-data': { name: 'test-only_postgres-data' } },
	services: { postgres: { volumes: [{ type: 'volume', source: 'postgres-data', target: '/var/lib/postgresql/data' }] } } };
test('초기화는 정확한 프로젝트 확인과 볼륨 소유 확인을 요구한다', () => {
	assert.throws(() => resetVolume(config), /confirm-reset/);
	assert.throws(() => resetVolume(config, 'other'), /confirm-reset/);
	assert.throws(() => resetVolume(config, 'test-only', { Name: 'test-only_postgres-data', Labels: { 'com.docker.compose.project': 'other' } }), /소유/);
	assert.throws(() => resetVolume({ ...config, volumes: { 'postgres-data': { name: 'external', external: true } } }, 'test-only'), /확인/);
	assert.throws(() => resetVolume({ ...config, services: { postgres: { volumes: [] } } }, 'test-only'), /사용하지 않아/);
	assert.equal(resetVolume(config, 'test-only', { Name: 'test-only_postgres-data', Labels: { 'com.docker.compose.project': 'test-only', 'com.docker.compose.volume': 'postgres-data' } }), 'test-only_postgres-data');
});

test('CLI는 성공과 중간 실패 양쪽에서 원래 enabled 상태로 돌아간다', async () => {
	for (const enabled of [true, false]) {
		for (const fails of [true, false]) {
			const updates = [];
			const admin = async (path, method, body) => { updates.push(body.enabled); };
			const operation = withCliEnabled(admin, { id: 'cli', enabled }, async () => {
				if (fails) throw new Error('중간 실패');
			});
			if (fails) await assert.rejects(operation, /중간 실패/); else await operation;
			assert.deepEqual(updates, [true, enabled]);
		}
	}
});

test('HTTP 오류 로그에 응답의 secret·token 본문을 노출하지 않는다', async () => {
	await assert.rejects(request('http://test/clients', {}, async () => new Response('secret-private', { status: 403 })),
		error => error.message.includes('403') && !error.message.includes('secret-private'));
	assert.equal(await request('http://test/clients', {}, async () => new Response(null, { status: 204 })), null);
});

test('잘못된 비밀번호 JSON 오류에도 입력 비밀값을 노출하지 않는다', () => {
	assert.throws(() => privateJson('private-password-in-broken-json', '비밀번호 파일'),
		error => error.message.includes('형식') && !error.message.includes('private-password'));
});

function accountAdmin(existing = [], writes = []) {
	const users = [{ id: 'app', username: 'appuser', enabled: true }, ...existing];
	return async (path, method = 'GET', body) => {
		if (path.startsWith('/roles/')) return { id: path, name: path.split('/').at(-1) };
		if (path.startsWith('/users?')) return users.filter(u => u.username === new URLSearchParams(path.split('?')[1]).get('username'));
		if (method === 'POST') {
			writes.push({ path, body });
			if (path === '/users') users.push({ ...body, id: body.username });
		}
	};
}

test('자동 계정은 무작위 비밀번호를 저장하고 password grant를 만들지 않는다', async () => {
	const writes = []; const state = { accounts: {} }; let saves = 0;
	const users = await ensureAccounts(accountAdmin([], writes), { APP_USER: 'appuser' }, state, () => saves++);
	assert.equal(users.length, 4);
	assert.equal(saves, 6);
	for (const account of ACCOUNTS) assert.ok(state.accounts[account.username].password.length >= 24);
	assert.equal(writes.filter(w => w.path === '/users').length, 3);
});

test('수동 계정은 지정한 비밀번호로 생성한다', async () => {
	const writes = []; const state = { accounts: {} };
	await ensureAccounts(accountAdmin([], writes), { APP_USER: 'appuser' }, state, () => {}, passwords);
	for (const write of writes.filter(w => w.path === '/users')) assert.equal(write.body.credentials[0].value, passwords.accounts[write.body.username]);
});

test('이미 생성한 예시 계정의 비밀번호는 재설정하지 않는다', async () => {
	const writes = [];
	const existing = ACCOUNTS.map(a => ({ id: a.username, username: a.username, enabled: true }));
	const accounts = Object.fromEntries(ACCOUNTS.map(a => [a.username, { subject: a.username }]));
	await ensureAccounts(accountAdmin(existing, writes), { APP_USER: 'appuser' }, { accounts }, () => { throw new Error('비밀번호 저장 금지'); }, passwords);
	assert.equal(writes.filter(w => w.path === '/users' || w.path.includes('reset-password')).length, 0);
});

test('일반 사용자와 이름이 충돌하거나 비활성 예시 계정이면 덮어쓰지 않는다', async () => {
	for (const existing of [{ id: 'x', username: 'rbac-admin', enabled: true },
		{ id: 'x', username: 'rbac-admin', enabled: false, attributes: { dev_setup: ['ogjg-dev-setup-v1'] } }]) {
		await assert.rejects(ensureAccounts(accountAdmin([existing]), { APP_USER: 'appuser' }, { accounts: {} }, () => {}), /변경하지 않는다/);
	}
});

function fakeApi({ mismatch = false, tenantCount = 1, failure = false } = {}) {
	const calls = [];
	const orgs = ['hr', 'fin', 'legal', 'demo-viewers'].map(key => ({ id: key, key, name: key === 'demo-viewers' ? '예시 일반 사용자' : key, status: 'ACTIVE', declared: key !== 'demo-viewers' }));
	const users = [{ username: 'appuser', subject: 'app', team: 'hr', rank: 'TL' }, ...ACCOUNTS.map(a => ({ ...a, subject: a.username }))];
	const people = users.map(u => ({ subject: u.subject, teamId: u.team, rank: u.rank, enabled: true, visible: ['hr-node'] }));
	if (mismatch) people[0].teamId = 'fin';
	const api = async (path, method = 'GET', body) => {
		calls.push({ path, method, body });
		if (path.endsWith('/tenants')) return Array.from({ length: tenantCount }, () => ({ key: 'ogjg', status: 'ACTIVE' }));
		if (path.endsWith('/org-units')) return orgs;
		if (path.endsWith('/overview')) return { teams: orgs, workspaces: [{ id: 'hr-node', key: 'hr' }], people };
		if (path.endsWith('/grants')) return [{ orgUnitId: 'demo-viewers', role: 'VIEWER' }];
		if (path.endsWith('/context')) return { workspaceManagement: true };
		if (path.endsWith('/cutover-validation')) return { failures: failure ? [{ code: 'MISSING' }] : [] };
		return null;
	};
	return { api, calls, users };
}

test('DB 배정과 VIEW·사전 검증을 실제 응답 형식으로 확인한다', async () => {
	const { api, users } = fakeApi();
	assert.deepEqual(await seedAssignments(api, users), { hrId: 'hr-node' });
	await verifyAssignments(api, users, 'hr-node');
});

test('기존 조직 배정 충돌·여러 Tenant는 배정 쓰기 전에 멈춘다', async () => {
	for (const opts of [{ mismatch: true }, { tenantCount: 2 }]) {
		const { api, users, calls } = fakeApi(opts);
		await assert.rejects(seedAssignments(api, users));
		assert.ok(calls.every(c => c.method === 'GET'));
	}
});

test('사전 검증 failures가 있으면 완료로 표시하지 않는다', async () => {
	const { api, users } = fakeApi({ failure: true });
	await assert.rejects(verifyAssignments(api, users, 'hr-node'), /사전 검증 실패/);
});

test('감사 수집은 최근 성공과 저장 활성 확인까지 기다린다', async () => {
	let tries = 0;
	await verifyAudit(async () => ({ collector: ++tries < 3 ? { adminEventsEnabled: false }
		: { adminEventsEnabled: true, lastSuccessAt: '2026-10-01T00:00:01Z', lastErrorAt: '2026-10-01T00:00:00Z' } }), async () => {});
	assert.equal(tries, 3);
});

test('감사 성공보다 최근 오류가 있으면 완료를 표시하지 않는다', async () => {
	await assert.rejects(verifyAudit(async () => ({ collector: { adminEventsEnabled: true,
		lastSuccessAt: '2026-10-01T00:00:00Z', lastErrorAt: '2026-10-01T00:00:01Z' } }), async () => {}), /감사 수집/);
});
