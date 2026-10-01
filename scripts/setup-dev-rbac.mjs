#!/usr/bin/env node
// 개발용 Compose 환경과 Keycloak 관리 클라이언트·예시 계정·DB 조직 배정을 준비한다.
// 사용: node -- scripts/setup-dev-rbac.mjs [--env-file infra/.env] [--compose-file infra/docker-compose.yml]
//       직접 비밀번호 지정: --password-mode manual --password-file <Git에서 제외한 JSON 파일>
//       초기화는 --reset --confirm-reset <Compose 프로젝트명>을 함께 지정할 때만 수행한다.
// 기존 사용자 비밀번호와 업무 데이터는 기본적으로 보존한다. 관리자 브라우저 승인은 한 번 필요하다.

import { chmodSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deviceLogin } from './import-members.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TIMEOUT = 20_000;
export const ACCOUNTS = [
	{ username: 'rbac-admin', name: '예시 Workspace 관리자', team: 'hr', rank: 'TL' },
	{ username: 'rbac-viewer', name: '예시 일반 사용자', team: 'demo-viewers', rank: 'S' },
	{ username: 'rbac-finance', name: '예시 재무팀 관리자', team: 'fin', rank: 'B' },
];

export function parseEnv(text) {
	const env = {};
	for (const line of text.split(/\r?\n/)) {
		const match = /^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
		if (match) {
			let value = match[2];
			if (/^['"]/.test(value)) {
				if (value.length < 2 || value.at(-1) !== value[0]) throw new Error(`닫히지 않은 환경변수 따옴표: ${match[1]}`);
				value = value.slice(1, -1);
			}
			env[match[1]] = value;
		}
	}
	return env;
}

export function setEnv(text, changes) {
	const remaining = { ...changes };
	const lines = text.split(/\r?\n/).map(line => {
		const key = /^\s*([A-Z][A-Z0-9_]*)\s*=/.exec(line)?.[1];
		if (!key || !Object.hasOwn(changes, key)) return line;
		delete remaining[key];
		return `${key}=${changes[key]}`;
	});
	return `${lines.join('\n').trimEnd()}\n${Object.entries(remaining).map(([k, v]) => `${k}=${v}\n`).join('')}`;
}

export function options(args) {
	const result = { envFile: join(ROOT, 'infra', '.env'), envTemplate: join(ROOT, 'infra', '.env.example'), composeFiles: [], reset: false, passwordMode: 'auto' };
	for (let i = 0; i < args.length; i++) {
		const arg = args[i];
		if (arg === '--reset') result.reset = true;
		else if (['--env-file', '--env-template', '--compose-file', '--project-name', '--confirm-reset', '--password-mode', '--password-file'].includes(arg)) {
			const value = args[++i];
			if (!value || value.startsWith('--')) throw new Error(`${arg} 값이 필요하다`);
			if (arg === '--env-file') result.envFile = resolve(value);
			if (arg === '--env-template') result.envTemplate = resolve(value);
			if (arg === '--compose-file') result.composeFiles.push(resolve(value));
			if (arg === '--project-name') result.project = value;
			if (arg === '--confirm-reset') result.confirmReset = value;
			if (arg === '--password-mode') result.passwordMode = value;
			if (arg === '--password-file') result.passwordFile = resolve(value);
		} else throw new Error(`알 수 없는 옵션: ${arg}`);
	}
	if (result.composeFiles.length === 0) result.composeFiles.push(join(ROOT, 'infra', 'docker-compose.yml'));
	if (result.confirmReset && !result.reset) throw new Error('--confirm-reset은 --reset과 함께 사용한다');
	if (!['auto', 'manual'].includes(result.passwordMode)) throw new Error('--password-mode는 auto 또는 manual이다');
	if (result.passwordMode === 'manual' && !result.passwordFile) throw new Error('직접 지정 모드에는 --password-file이 필요하다');
	if (result.passwordMode === 'auto' && result.passwordFile) throw new Error('--password-file은 manual 모드에서만 사용한다');
	return result;
}

export function manualPasswords(input) {
	for (const value of [input.APP_USER_PASSWORD, input.KEYCLOAK_ADMIN_PASSWORD, ...ACCOUNTS.map(a => input.accounts?.[a.username])]) {
		if (typeof value !== 'string' || value.length < 12 || /[\r\n\0]/.test(value)) throw new Error('직접 지정 비밀번호는 줄바꿈 없이 12자 이상이어야 한다');
	}
	return input;
}

export function privateJson(text, label) {
	try { return JSON.parse(text); }
	catch { throw new Error(`${label} JSON 형식이 잘못됐다. 비밀값을 로그에 출력하지 않으므로 입력 파일을 직접 확인한다.`); }
}

function command(args, quiet = false) {
	const result = spawnSync('docker', args, { cwd: ROOT, encoding: 'utf8',
		stdio: quiet ? 'pipe' : 'inherit', timeout: 30 * 60_000, maxBuffer: 16 * 1024 * 1024 });
	if (result.error || result.status !== 0) {
		// config/inspect 응답이나 외부 오류 본문에는 비밀값이 들어갈 수 있어 그대로 출력하지 않는다.
		throw new Error(`Docker 명령 실패 (${args[0]} ${args[1] || ''}, 종료 코드 ${result.status ?? '없음'})`);
	}
	return result.stdout?.trim() || '';
}

function privateWrite(path, text) {
	writeFileSync(path, text, { mode: 0o600 });
	chmodSync(path, 0o600);
}

export function prepareEnvironment(text, fresh, secret = () => randomBytes(30).toString('base64url'), passwords) {
	const env = parseEnv(text);
	const changes = { SPRING_PROFILE: [...new Set(['prod', 'casbin', ...(env.SPRING_PROFILE || '').split(',').filter(Boolean)])].join(','),
		COMPOSE_PROFILES: [...new Set(['casbin', ...(env.COMPOSE_PROFILES || '').split(',').filter(Boolean)])].join(',') };
	const secrets = ['POSTGRES_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'APP_USER_PASSWORD',
		'KEYCLOAK_CLIENT_SECRET', 'KEYCLOAK_BFF_CLIENT_SECRET', 'LITELLM_MASTER_KEY',
		'LITELLM_SALT_KEY', 'NEXTAUTH_SECRET', 'DOCUMENT_WORKER_INTERNAL_API_KEY'];
	for (const key of secrets) {
		if (fresh || !env[key]) {
			if (!fresh && ['POSTGRES_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'APP_USER_PASSWORD', 'KEYCLOAK_CLIENT_SECRET'].includes(key)) {
				throw new Error(`기존 환경의 ${key}가 비어 있다. DB/계정 비밀번호를 임의 변경하지 않으므로 실제 값을 먼저 확인한다.`);
			}
			changes[key] = key === 'LITELLM_MASTER_KEY' ? `sk-${secret()}` : secret();
		}
	}
	if (fresh && passwords) {
		// 따옴표를 쓰지 않는 Compose env 값에는 $ 치환·# 주석 해석을 피하기 위해 JSON 문자열과 다른 제약을 둔다.
		for (const key of ['APP_USER_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD']) {
			if (!/^[A-Za-z0-9_!@%+=:.,/-]{12,}$/.test(passwords[key])) throw new Error(`${key}에는 영문·숫자·_!@%+=:.,/-만 사용할 수 있다`);
			changes[key] = passwords[key];
		}
	}
	const output = setEnv(text, changes);
	const result = parseEnv(output);
	for (const key of ['PUBLIC_FRONTEND_URL', 'PUBLIC_BFF_URL', 'PUBLIC_KEYCLOAK_URL']) {
		const url = new URL(result[key]);
		if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error(`잘못된 공개 주소: ${key}`);
	}
	if (result.NEXTAUTH_URL !== result.PUBLIC_FRONTEND_URL) throw new Error('NEXTAUTH_URL과 PUBLIC_FRONTEND_URL을 일치시킨다');
	if (result.SPRING_FLYWAY_TARGET && result.SPRING_FLYWAY_TARGET !== 'latest') throw new Error('이 명령은 최신 개발 DB만 지원한다. 기존 절체용 Flyway target을 먼저 확인한다.');
	if (result.KEYCLOAK_CLIENT_ID === result.KEYCLOAK_BFF_CLIENT_ID || ['ogjg-cli', 'realm-management'].includes(result.KEYCLOAK_BFF_CLIENT_ID)) {
		throw new Error('로그인·BFF·CLI 클라이언트 ID는 서로 달라야 한다');
	}
	return { text: output, env: result };
}

export function resetVolume(config, confirmed, inspected) {
	if (!confirmed || confirmed !== config.name) throw new Error(`초기화하려면 --reset --confirm-reset ${config.name}을 지정한다`);
	const volume = config.volumes?.['postgres-data'];
	if (!volume?.name || volume.external) throw new Error('관리 대상 PostgreSQL 볼륨을 확인할 수 없어 초기화를 거부한다');
	const mount = config.services?.postgres?.volumes?.find(v => v.target === '/var/lib/postgresql/data');
	if (mount?.type !== 'volume' || mount.source !== 'postgres-data') throw new Error('PostgreSQL이 관리 대상 볼륨을 사용하지 않아 초기화를 거부한다');
	if (inspected && (inspected.Name !== volume.name
		|| inspected.Labels?.['com.docker.compose.project'] !== config.name
		|| inspected.Labels?.['com.docker.compose.volume'] !== 'postgres-data')) {
		throw new Error('볼륨 소유 프로젝트가 달라 초기화를 거부한다');
	}
	return volume.name;
}

const form = body => ({ method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body) });
export async function request(url, init = {}, fetcher = fetch) {
	const response = await fetcher(url, { ...init, signal: AbortSignal.timeout(TIMEOUT) });
	if (!response.ok) throw new Error(`HTTP ${response.status}: ${new URL(url).pathname} (서버 로그에서 원인을 확인한다)`);
	if (response.status === 204 || response.headers.get('content-length') === '0') return null;
	const text = await response.text();
	return text ? JSON.parse(text) : null;
}

function json(method, body, token) {
	return { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
		...(body === undefined ? {} : { body: JSON.stringify(body) }) };
}

async function waitFor(url, label) {
	const deadline = Date.now() + 6 * 60_000;
	while (Date.now() < deadline) {
		try { if ((await fetch(url, { signal: AbortSignal.timeout(5000) })).ok) return; } catch { /* 기동 중 연결 실패는 재시도한다. */ }
		await new Promise(resolve => setTimeout(resolve, 2000));
	}
	throw new Error(`${label} 기동 확인 시간 초과`);
}

export async function ensureAccounts(admin, env, state, save, passwords) {
	const userRole = await admin('/roles/USER');
	const platformRole = await admin('/roles/PLATFORM_ADMIN');
	const app = await admin(`/users?username=${encodeURIComponent(env.APP_USER)}&exact=true`);
	if (app.length !== 1 || !app[0].enabled) throw new Error('활성 APP_USER 계정을 확인할 수 없다');
	await admin(`/users/${app[0].id}/role-mappings/realm`, 'POST', [userRole, platformRole]);
	const users = [{ username: env.APP_USER, subject: app[0].id, team: 'hr', rank: 'TL' }];
	for (const account of ACCOUNTS) {
		const found = await admin(`/users?username=${encodeURIComponent(account.username)}&exact=true`);
		if (found.length && (found.length !== 1 || !found[0].enabled
			|| state.accounts[account.username]?.subject !== found[0].id)) {
			throw new Error(`예시 계정과 같은 이름의 기존 사용자 ${account.username}는 변경하지 않는다`);
		}
		if (!found.length) {
			state.accounts[account.username] ??= { password: passwords?.accounts[account.username] ?? randomBytes(18).toString('base64url') };
			save(); // 생성 도중 실패해도 같은 자격증명으로 재실행할 수 있다.
			await admin('/users', 'POST', { username: account.username, enabled: true,
				email: `${account.username}@example.invalid`, emailVerified: true, firstName: account.name, lastName: 'Demo',
				credentials: [{ type: 'password', value: state.accounts[account.username].password, temporary: false }] });
		}
		const created = found.length ? found : await admin(`/users?username=${encodeURIComponent(account.username)}&exact=true`);
		if (created.length !== 1) throw new Error(`계정 생성 결과 확인 실패: ${account.username}`);
		if (!found.length) {
			state.accounts[account.username].subject = created[0].id;
			save();
		}
		if (!created[0].firstName || !created[0].lastName) {
			await admin(`/users/${created[0].id}`, 'PUT', { ...created[0],
				firstName: created[0].firstName || account.name, lastName: created[0].lastName || 'Demo' });
		}
		await admin(`/users/${created[0].id}/role-mappings/realm`, 'POST', [userRole]);
		users.push({ ...account, subject: created[0].id });
	}
	return users;
}

export async function seedAssignments(api, users) {
	const tenants = await api('/api/platform/rbac/tenants');
	if (tenants.length !== 1 || tenants[0].key !== 'ogjg' || tenants[0].status !== 'ACTIVE') {
		throw new Error('예시 구성은 활성 ogjg Tenant 하나만 지원한다. 기존 업무 Tenant는 변경하지 않는다.');
	}
	const orgPath = '/api/platform/rbac/tenants/ogjg/org-units';
	let orgs = await api(orgPath);
	for (const key of ['hr', 'fin', 'legal']) {
		if (!orgs.some(o => o.key === key && o.status === 'ACTIVE')) throw new Error(`예시 bootstrap 조직 ${key}가 없다`);
	}
	const before = await api('/api/platform/rbac/admin/overview');
	for (const user of users) {
		const person = before.people.find(p => p.subject === user.subject);
		const expectedTeam = orgs.find(o => o.key === user.team);
		if (person?.teamId && (person.teamId !== expectedTeam?.id || person.rank !== user.rank)) {
			throw new Error(`기존 ${user.username} 조직·직급 배정이 달라 변경하지 않는다`);
		}
	}
	const existingViewer = orgs.find(o => o.key === 'demo-viewers');
	if (existingViewer && (existingViewer.name !== '예시 일반 사용자' || existingViewer.status !== 'ACTIVE' || existingViewer.declared)) {
		throw new Error('기존 demo-viewers 조직이 예시 구성과 달라 변경하지 않는다');
	}
	if (!existingViewer) {
		await api(orgPath, 'POST', { key: 'demo-viewers', name: '예시 일반 사용자' });
		orgs = await api(orgPath);
	}
	for (const user of users) {
		const team = orgs.find(o => o.key === user.team);
		if (!team) throw new Error(`조직이 없다: ${user.team}`);
		await api(`/api/platform/rbac/admin/people/${encodeURIComponent(user.subject)}/assignment`, 'PUT', { teamId: team.id, rank: user.rank });
	}
	const overview = await api('/api/platform/rbac/admin/overview');
	const hr = overview.workspaces.find(w => w.key === 'hr');
	if (!hr) throw new Error('인사팀 Workspace를 확인할 수 없다');
	const viewer = orgs.find(o => o.key === 'demo-viewers');
	const grants = await api(`/api/rbac/workspaces/${hr.id}/grants`);
	const viewerGrants = grants.filter(g => g.orgUnitId === viewer.id);
	if (viewerGrants.some(g => g.role !== 'VIEWER')) throw new Error('예시 일반 사용자에게 기존 관리 부여가 있어 자동 변경하지 않는다');
	if (viewerGrants.length === 0) await api(`/api/rbac/workspaces/${hr.id}/grants`, 'POST', { orgUnitId: viewer.id, role: 'VIEWER' });
	return { hrId: hr.id };
}

export async function verifyAssignments(api, users, hrId) {
	const overview = await api('/api/platform/rbac/admin/overview');
	for (const user of users) {
		const person = overview.people.find(p => p.subject === user.subject);
		const team = overview.teams.find(t => t.key === user.team);
		if (!person?.enabled || person.teamId !== team?.id || person.rank !== user.rank) throw new Error(`배정 검증 실패: ${user.username}`);
		if (user.team !== 'demo-viewers' && !person.visible.length) throw new Error(`Workspace VIEW 검증 실패: ${user.username}`);
		if (user.team === 'demo-viewers' && !person.visible.includes(hrId)) throw new Error('일반 사용자 인사팀 VIEW 검증 실패');
	}
	const context = await api('/api/rbac/admin/context');
	if (!context.workspaceManagement) throw new Error('승인 계정에 Workspace 관리 권한이 없다');
	const validation = await api('/api/platform/rbac/cutover-validation', 'POST');
	if (!Array.isArray(validation.failures) || validation.failures.length) throw new Error('절체 사전 검증 실패: 기존 활성 사용자·방의 배정과 VIEW를 확인한다. 이 명령은 기존 사용자를 임의 배정하지 않는다.');
}

export async function withCliEnabled(admin, client, operation) {
	await admin(`/clients/${client.id}`, 'PUT', { ...client, enabled: true });
	try { return await operation(); }
	finally { await admin(`/clients/${client.id}`, 'PUT', client); }
}

export async function verifyAudit(api, wait = () => new Promise(resolve => setTimeout(resolve, 2000))) {
	for (let attempt = 0; attempt < 45; attempt++) {
		const page = await api('/api/platform/rbac/keycloak-audits?limit=1');
		const status = page.collector;
		if (status?.adminEventsEnabled === true && status.lastSuccessAt
			&& (!status.lastErrorAt || Date.parse(status.lastSuccessAt) > Date.parse(status.lastErrorAt))) return;
		await wait();
	}
	throw new Error('감사 수집 최근 성공을 확인할 수 없다. BFF/Keycloak 로그와 관리 클라이언트 조회 권한을 확인한다.');
}

async function main() {
	const opt = options(process.argv.slice(2));
	console.log('개발용 예시 초기 설정을 준비한다. 기존 데이터는 --reset 없이는 삭제하지 않는다.');
	command(['compose', 'version'], true);
	const fresh = !existsSync(opt.envFile);
	for (const path of [opt.envFile, `${opt.envFile}.dev-setup.json`, ...(opt.passwordFile ? [opt.passwordFile] : [])]) {
		if (spawnSync('git', ['check-ignore', '--quiet', '--', path], { cwd: ROOT }).status !== 0) {
			throw new Error(`자격증명 파일이 Git ignore 대상이 아니다: ${path}`);
		}
	}
	const source = readFileSync(fresh ? opt.envTemplate : opt.envFile, 'utf8');
	const passwords = opt.passwordMode === 'manual' ? manualPasswords(privateJson(readFileSync(opt.passwordFile, 'utf8'), '비밀번호 파일')) : undefined;
	const prepared = prepareEnvironment(source, fresh, undefined, passwords);
	const env = prepared.env;
	const statePath = `${opt.envFile}.dev-setup.json`;
	const state = existsSync(statePath) ? privateJson(readFileSync(statePath, 'utf8'), '계정 상태 파일') : { accounts: {} };
	state.accounts ??= {};
	const save = () => privateWrite(statePath, `${JSON.stringify(state, null, 2)}\n`);
	privateWrite(opt.envFile, prepared.text);
	const compose = ['compose', '--env-file', opt.envFile, ...opt.composeFiles.flatMap(file => ['-f', file]),
		...(opt.project ? ['--project-name', opt.project] : []), '--profile', 'casbin'];
	const run = (args, quiet = false) => command([...compose, ...args], quiet);
	const config = JSON.parse(run(['config', '--format', 'json'], true));
	if (opt.reset) {
		const name = resetVolume(config, opt.confirmReset);
		const names = command(['volume', 'ls', '--format', '{{.Name}}'], true).split('\n');
		if (names.includes(name)) {
			const inspected = JSON.parse(command(['volume', 'inspect', name], true))[0];
			resetVolume(config, opt.confirmReset, inspected);
			console.log(`명시 초기화: 프로젝트 ${config.name}, PostgreSQL/Keycloak 볼륨 ${name}. 다른 볼륨은 보존한다.`);
			run(['down']);
			command(['volume', 'rm', name]);
		}
	}
	run(['up', '-d', '--wait', '--wait-timeout', '360', 'postgres', 'keycloak']);
	// 적용 migration을 건너뛰거나 기존 데이터의 점검창 절체를 자동 수행하지 않는다.
	const safeDb = run(['exec', '-T', 'postgres', 'psql', '-U', env.POSTGRES_USER, '-d', env.POSTGRES_DB, '-At', '-c',
		"select case when to_regclass('public.flyway_schema_history') is null and to_regclass('public.thr') is null and to_regclass('public.chat_sess') is null then 'EMPTY' when to_regclass('public.ctv') is not null then 'CURRENT' else 'LEGACY' end"], true);
	if (!['EMPTY', 'CURRENT'].includes(safeDb)) throw new Error('기존 절체 전 DB다. 점검창 절체를 따로 수행하거나 --reset으로 개발 데이터 초기화를 명시한다.');
	const kc = env.PUBLIC_KEYCLOAK_URL.replace(/\/$/, '');
	const realm = env.KEYCLOAK_REALM;
	await waitFor(`${kc}/realms/master/.well-known/openid-configuration`, 'Keycloak');
	let adminToken;
	let adminExpires = 0;
	const admin = async (path, method = 'GET', body) => {
		if (Date.now() >= adminExpires) {
			const grant = await request(`${kc}/realms/master/protocol/openid-connect/token`, form({
				grant_type: 'password', client_id: 'admin-cli', username: env.KEYCLOAK_ADMIN, password: env.KEYCLOAK_ADMIN_PASSWORD }));
			adminToken = grant.access_token;
			adminExpires = Date.now() + Math.max(1, grant.expires_in - 10) * 1000;
		}
		return request(`${kc}/admin/realms/${encodeURIComponent(realm)}${path}`, json(method, body, adminToken));
	};
	const template = JSON.parse(readFileSync(join(ROOT, 'infra/config/realm-app.json'), 'utf8').replace(/\$\{([A-Z0-9_]+)\}/g,
		(_, key) => JSON.stringify(env[key] ?? '').slice(1, -1)));
	await admin('', 'PUT', { adminEventsEnabled: true, adminEventsDetailsEnabled: true });
	for (const role of template.roles.realm) {
		if (!(await admin('/roles')).some(r => r.name === role.name)) await admin('/roles', 'POST', role);
	}
	for (const definition of template.clients) {
		const matches = await admin(`/clients?clientId=${encodeURIComponent(definition.clientId)}`);
		if (matches.length === 0) await admin('/clients', 'POST', definition);
	}
	const clients = await admin('/clients');
	const bff = clients.find(c => c.clientId === env.KEYCLOAK_BFF_CLIENT_ID);
	const cli = clients.find(c => c.clientId === 'ogjg-cli');
	const login = clients.find(c => c.clientId === env.KEYCLOAK_CLIENT_ID);
	if (!bff || !cli || !login) throw new Error('필수 클라이언트를 확인할 수 없다');
	const loginSecret = await admin(`/clients/${login.id}/client-secret`);
	if (loginSecret.value !== env.KEYCLOAK_CLIENT_SECRET) throw new Error('기존 로그인 client secret과 env가 다르다. 기존 secret은 자동 변경하지 않는다.');
	await admin(`/clients/${login.id}`, 'PUT', { ...login,
		redirectUris: [...new Set([...(login.redirectUris || []), `${env.PUBLIC_FRONTEND_URL}/api/auth/callback/keycloak`])],
		webOrigins: [...new Set([...(login.webOrigins || []), env.PUBLIC_FRONTEND_URL])],
		attributes: { ...login.attributes, 'post.logout.redirect.uris': env.PUBLIC_FRONTEND_URL } });
	await admin(`/clients/${bff.id}`, 'PUT', { ...bff, ...template.clients.find(c => c.clientId === bff.clientId) });
	const service = await admin(`/clients/${bff.id}/service-account-user`);
	const management = clients.find(c => c.clientId === 'realm-management');
	const roles = await admin(`/clients/${management.id}/roles`);
	const required = ['view-users', 'view-events', 'view-realm'].map(name => {
		const role = roles.find(r => r.name === name);
		if (!role) throw new Error(`관리 역할이 없다: ${name}`);
		return role;
	});
	const currentRoles = await admin(`/users/${service.id}/role-mappings/clients/${management.id}`);
	const extraRoles = currentRoles.filter(role => !required.some(wanted => wanted.id === role.id));
	if (extraRoles.length) await admin(`/users/${service.id}/role-mappings/clients/${management.id}`, 'DELETE', extraRoles);
	await admin(`/users/${service.id}/role-mappings/clients/${management.id}`, 'POST', required);
	const users = await ensureAccounts(admin, env, state, save, passwords);
	// CLI의 안전한 device 설정을 준비하되 원래 enabled 상태는 작업 뒤 복구한다.
	const cliConfig = { ...cli, ...template.clients.find(c => c.clientId === 'ogjg-cli'), enabled: cli.enabled };
	await admin(`/clients/${cli.id}`, 'PUT', cliConfig);
	run(['up', '-d', '--build', '--wait', '--wait-timeout', '600']);
	await waitFor(`${env.PUBLIC_BFF_URL}/actuator/health`, 'BFF');
	await waitFor(`${env.PUBLIC_FRONTEND_URL}/api/auth/providers`, '프런트');
	console.log(`자격증명은 ${opt.envFile} (APP_USER)와 ${statePath} (예시 계정)에 저장했다. 로그에는 출력하지 않는다.`);
	await withCliEnabled(admin, cliConfig, async () => {
		const token = await deviceLogin(kc, env, 'ogjg-cli');
		const api = (path, method = 'GET', body) => request(`${env.PUBLIC_BFF_URL}${path}`, json(method, body, token));
		await api('/api/platform/rbac/admin/overview'); // BFF 전용 클라이언트로 사용자 목록을 읽는지 먼저 확인한다.
		if (login.serviceAccountsEnabled) await admin(`/clients/${login.id}`, 'PUT', { ...login, serviceAccountsEnabled: false });
		const { hrId } = await seedAssignments(api, users);
		await verifyAssignments(api, users, hrId);
		await verifyAudit(api);
	});
	state.lastSuccess = new Date().toISOString();
	state.frontend = env.PUBLIC_FRONTEND_URL;
	save();
	console.log(`완료: ${env.PUBLIC_FRONTEND_URL}/admin/permissions\n플랫폼 관리: ${env.APP_USER}; Workspace 관리: rbac-admin / rbac-finance; 일반 조회: rbac-viewer`);
	console.log('재실행은 기존 계정 비밀번호와 데이터를 보존한다. 이 명령은 운영 DB 절체 표지를 만들지 않는다.');
	if (!env.LLM_API_KEY && !env.OPENAI_API_KEY && !env.GEMINI_API_KEY && !env.ANTHROPIC_API_KEY) {
		console.log('안내: 외부 LLM API 키가 없다. 권한·로그인 준비와 별개로 실제 AI 답변에는 INSTALL_models.md에 맞는 모델 설정이 필요하다.');
	}
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main().catch(error => { console.error(`초기 설정 실패: ${error.message}`); process.exitCode = 1; });
}
