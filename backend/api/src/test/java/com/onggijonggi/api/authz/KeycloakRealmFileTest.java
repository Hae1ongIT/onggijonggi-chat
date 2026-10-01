package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminUnavailableException;
import com.onggijonggi.api.auth.keycloak.KeycloakPerson;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : KeycloakRealmFileTest.java
 * Description : 배포에 쓰는 infra/config/realm-app.json을 compose와 같은 방식(파일 마운트·환경 변수 치환·--import-realm)으로
 *               실제 Keycloak 26에 넣어, BFF 전용 관리 클라이언트 분리(#326)를 확인한다. 테스트용 realm을 코드로 만들면
 *               파일 자체의 오타·권한 누락을 잡지 못하므로 파일을 그대로 쓴다. 이미 띄운 환경의 전환 절차(INSTALL)도
 *               예전 모양의 realm을 하나 더 만들어 Admin API로 재현한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRealmFileTest {

	private static final String REALM = "app-realm";
	private static final String LOGIN_CLIENT = "ogjg-client";
	private static final String LOGIN_SECRET = "login-secret";
	private static final String BFF_CLIENT = "ogjg-bff";
	private static final String BFF_SECRET = "bff-secret";
	private static final String APP_USER = "appuser";
	private static final String APP_USER_PASSWORD = "appuser-password";
	private static final String FRONTEND = "http://localhost:3000";
	private static final String CLI_CLIENT = "ogjg-cli";

	@Container
	static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
			.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
			.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
			.withEnv("KEYCLOAK_REALM", REALM)
			.withEnv("KEYCLOAK_CLIENT_ID", LOGIN_CLIENT)
			.withEnv("KEYCLOAK_CLIENT_SECRET", LOGIN_SECRET)
			.withEnv("KEYCLOAK_BFF_CLIENT_ID", BFF_CLIENT)
			.withEnv("KEYCLOAK_BFF_CLIENT_SECRET", BFF_SECRET)
			.withEnv("PUBLIC_FRONTEND_URL", FRONTEND)
			.withEnv("APP_USER", APP_USER)
			.withEnv("APP_USER_PASSWORD", APP_USER_PASSWORD)
			.withCopyFileToContainer(MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/realm-app.json")
			.withCommand("start-dev", "--import-realm")
			.withExposedPorts(8080)
			.waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("realm_file").withUsername("test").withPassword("test");

	private final HttpClient http = HttpClient.newHttpClient();
	private final JsonMapper json = new JsonMapper();

	@Test
	void theBffClientReadsWhatTheBffNeedsAndCollectsTheAudit() {
		KeycloakAdminClient bff = adminClient(BFF_CLIENT, BFF_SECRET);

		List<KeycloakPerson> people = bff.listPeople(100).block();
		assertThat(people).extracting(KeycloakPerson::username).contains(APP_USER)
				.noneMatch(username -> username.startsWith("service-account-"));
		String appUser = people.stream().filter(person -> person.username().equals(APP_USER)).findFirst().orElseThrow()
				.subject();
		assertThat(bff.displayName(appUser).block()).isPresent();
		assertThat(bff.listEnabledUserSubjects().block()).contains(appUser);
		assertThat(bff.search(APP_USER, 10).block()).isNotEmpty();
		assertThat(bff.realmManagementUuid().block()).isPresent();

		KeycloakAuditStore store = collectOnce(bff);
		assertThat(store.state().lastError()).isNull();
		assertThat(store.state().lastSuccessAt()).isNotNull();
		assertThat(store.state().eventsConfig()).containsEntry("adminEventsEnabled", true);
	}

	@Test
	void theBffClientHasNoLoginFlowAndOnlyTheThreeReadRoles() {
		KeycloakTestAdmin admin = new KeycloakTestAdmin(baseUrl(), REALM);
		Map<?, ?> client = admin.client(BFF_CLIENT);

		assertThat(client.get("serviceAccountsEnabled")).isEqualTo(true);
		assertThat(client.get("publicClient")).isEqualTo(false);
		assertThat(client.get("standardFlowEnabled")).isEqualTo(false);
		assertThat(client.get("directAccessGrantsEnabled")).isEqualTo(false);
		assertThat(client.get("implicitFlowEnabled")).isEqualTo(false);
		assertThat(admin.managementRoles(admin.serviceAccountUserId(BFF_CLIENT).orElseThrow()))
				.containsExactlyInAnyOrder("view-users", "view-events", "view-realm");
	}

	@Test
	void theLoginSecretCanStillLogInButCannotGetAnAdminToken() throws Exception {
		HttpResponse<String> serviceToken = token(REALM, Map.of("grant_type", "client_credentials",
				"client_id", LOGIN_CLIENT, "client_secret", LOGIN_SECRET));
		assertThat(serviceToken.statusCode()).isIn(400, 401);
		assertThat(serviceToken.body()).doesNotContain("access_token");
		assertThat(new KeycloakTestAdmin(baseUrl(), REALM).serviceAccountUserId(LOGIN_CLIENT)).isEmpty();

		// 로그인 클라이언트로 BFF를 잘못 설정해도 관리 조회는 되지 않고 설정 문제로 드러난다.
		KeycloakAdminClient misconfigured = adminClient(LOGIN_CLIENT, LOGIN_SECRET);
		assertThat(failure(() -> misconfigured.listPeople(10).block()))
				.isInstanceOfSatisfying(KeycloakAdminUnavailableException.class, unavailable -> assertThat(unavailable.reason())
						.isEqualTo(KeycloakAdminUnavailableException.Reason.TOKEN_REJECTED));

		// 브라우저 로그인 설정은 그대로이고, 비밀번호를 받는 흐름(password grant)은 예전부터 꺼져 있다 — 로그인 secret으로
		// 화면 없이 토큰을 받을 수 없다.
		Map<?, ?> login = new KeycloakTestAdmin(baseUrl(), REALM).client(LOGIN_CLIENT);
		assertThat(login.get("standardFlowEnabled")).isEqualTo(true);
		assertThat(login.get("serviceAccountsEnabled")).isEqualTo(false);
		assertThat(login.get("directAccessGrantsEnabled")).isEqualTo(false);
		assertThat(token(REALM, Map.of("grant_type", "password", "client_id", LOGIN_CLIENT, "client_secret", LOGIN_SECRET,
				"username", APP_USER, "password", APP_USER_PASSWORD)).statusCode()).isIn(400, 401);
		assertThat(((List<?>) login.get("redirectUris")).stream().map(String::valueOf).toList())
				.containsExactly(FRONTEND + "/api/auth/callback/keycloak");
	}

	@Test
	void aWrongSecretIsAConfigurationProblemNotAnOutage() {
		KeycloakAdminClient wrong = adminClient(BFF_CLIENT, "not-the-secret");

		assertThat(failure(() -> wrong.listPeople(10).block())).isInstanceOf(KeycloakAdminUnavailableException.class);
		// 표시 이름은 화면 하나를 깨지 않게 지금처럼 빈 값으로 삼킨다.
		assertThat(wrong.displayName("anyone").block()).isEmpty();

		KeycloakAuditStore store = collectOnce(wrong);
		assertThat(store.state().lastError()).isEqualTo("Keycloak 관리 클라이언트 인증 실패(설정 확인, 401)");
	}

	/**
	 * INSTALL의 전환 절차를 예전 모양의 realm(로그인 클라이언트 서비스 계정에 관리 역할)에 그대로 해 본다. 끄기 전에 새 클라이언트로
	 * 동작을 확인하고, 마지막에 로그인 서비스 계정을 끄면 로그인 secret으로는 관리 토큰을 받을 수 없다.
	 */
	@Test
	void theConsoleStepsMoveAnExistingRealmWithoutAGap() throws Exception {
		String legacy = "legacy-realm";
		KeycloakTestAdmin admin = new KeycloakTestAdmin(baseUrl(), legacy);
		admin.createRealm(LOGIN_CLIENT, LOGIN_SECRET);
		admin.grantServiceAccount(LOGIN_CLIENT, List.of("view-users", "view-events", "view-realm"));
		assertThat(token(legacy, Map.of("grant_type", "client_credentials", "client_id", LOGIN_CLIENT,
				"client_secret", LOGIN_SECRET)).statusCode()).isEqualTo(200);

		// 2~4단계: 전용 클라이언트와 역할. 이때까지 로그인 서비스 계정은 그대로라 끊기는 구간이 없다.
		admin.createServiceClient(BFF_CLIENT, BFF_SECRET);
		admin.grantServiceAccount(BFF_CLIENT, List.of("view-users", "view-events", "view-realm"));
		KeycloakAdminClient bff = new KeycloakAdminClient(WebClient.builder(), baseUrl(), legacy, BFF_CLIENT, BFF_SECRET,
				Duration.ZERO);
		assertThat(bff.listEnabledUserSubjects().block()).isNotNull();

		// 6단계: 로그인 서비스 계정 끄기. Keycloak이 서비스 계정 사용자를 지우므로 남은 관리 역할도 함께 사라진다.
		String loginServiceAccount = admin.serviceAccountUserId(LOGIN_CLIENT).orElseThrow();
		assertThat(admin.managementRoles(loginServiceAccount)).isNotEmpty();
		admin.updateClient(LOGIN_CLIENT, Map.of("serviceAccountsEnabled", false));
		assertThat(admin.serviceAccountUserId(LOGIN_CLIENT)).isEmpty();

		// 7단계 확인.
		assertThat(token(legacy, Map.of("grant_type", "client_credentials", "client_id", LOGIN_CLIENT,
				"client_secret", LOGIN_SECRET)).statusCode()).isIn(400, 401);
		assertThat(bff.listEnabledUserSubjects().block()).isNotNull();
	}

	/**
	 * 저장소 스크립트(import-members.mjs)의 로그인. secret 없는 ogjg-cli로 device flow를 시작해 브라우저에서 승인하면 BFF가 받는
	 * 토큰(aud에 로그인 client id)이 나온다. 같은 클라이언트로 비밀번호를 받는 흐름은 거부된다.
	 */
	@Test
	void theScriptClientLogsInThroughTheBrowserOnly() throws Exception {
		KeycloakTestAdmin admin = new KeycloakTestAdmin(baseUrl(), REALM);
		Map<?, ?> cli = admin.client(CLI_CLIENT);
		// 꺼진 채 만든다 — 켜 두면 누구나 승인 코드를 받아 관리자에게 승인을 유도할 수 있다(device code 피싱).
		assertThat(cli.get("enabled")).isEqualTo(false);
		assertThat(post(oidc() + "/auth/device", Map.of("client_id", CLI_CLIENT)).statusCode()).isGreaterThanOrEqualTo(400);
		assertThat(cli.get("consentRequired")).isEqualTo(true);
		// consentRequired면 Keycloak이 fullScopeAllowed를 false로 가져와 토큰에서 realm 역할이 빠진다(실측) — 명시해 둔다.
		assertThat(cli.get("fullScopeAllowed")).isEqualTo(true);
		assertThat(((Map<?, ?>) cli.get("attributes")).get("use.refresh.tokens")).isEqualTo("false");
		admin.updateClient(CLI_CLIENT, Map.of("enabled", true));
		assertThat(cli.get("publicClient")).isEqualTo(true);
		assertThat(cli.get("directAccessGrantsEnabled")).isEqualTo(false);
		assertThat(cli.get("standardFlowEnabled")).isEqualTo(false);
		assertThat(token(REALM, Map.of("grant_type", "password", "client_id", CLI_CLIENT, "username", APP_USER,
				"password", APP_USER_PASSWORD)).statusCode()).isIn(400, 401);

		HttpResponse<String> started = post(oidc() + "/auth/device", Map.of("client_id", CLI_CLIENT, "scope", "openid"));
		assertThat(started.statusCode()).isEqualTo(200);
		Map<?, ?> device = json.readValue(started.body(), Map.class);
		Map<String, String> poll = Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
				"device_code", String.valueOf(device.get("device_code")), "client_id", CLI_CLIENT);
		assertThat(token(REALM, poll).body()).contains("authorization_pending");

		approveInBrowser(String.valueOf(device.get("verification_uri_complete")));
		// Keycloak이 알려 준 간격보다 빨리 다시 물으면 slow_down이다.
		Thread.sleep(((Number) device.get("interval")).longValue() * 1000);

		HttpResponse<String> approved = token(REALM, poll);
		assertThat(approved.statusCode()).isEqualTo(200);
		assertThat(approved.body()).doesNotContain("refresh_token");
		// BFF가 PLATFORM_ADMIN을 토큰의 realm 역할로 판정하므로 역할이 실려 있어야 한다(콘솔로 만든 클라이언트와 같게).
		String approvedToken = String.valueOf(json.readValue(approved.body(), Map.class).get("access_token"));
		assertThat(String.valueOf(json.readValue(Base64.getUrlDecoder().decode(approvedToken.split("\\.")[1]), Map.class)
				.get("realm_access"))).contains("PLATFORM_ADMIN");
		assertThat(audiences(String.valueOf(json.readValue(approved.body(), Map.class).get("access_token"))))
				.contains(LOGIN_CLIENT);
	}

	// ------------------------------------------------------------------ 준비

	/**
	 * 브라우저 대신 Keycloak 화면의 폼을 차례로 낸다: 로그인 → (동의). Keycloak 세션 쿠키에는 Secure가 붙어 Java 기본 쿠키
	 * 관리자가 http로는 다시 보내지 않으므로(브라우저는 localhost라 보낸다) 쿠키와 리다이렉트를 직접 다룬다.
	 */
	private void approveInBrowser(String verificationUri) throws Exception {
		HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		Map<String, String> cookies = new java.util.LinkedHashMap<>();
		String page = visit(browser, cookies, URI.create(verificationUri), null);
		for (int step = 0; step < 5; step++) {
			java.util.regex.Matcher action = java.util.regex.Pattern.compile("<form[^>]*action=\"([^\"]+)\"").matcher(page);
			if (!action.find()) return;
			Map<String, String> fields;
			if (page.contains("name=\"username\"")) {
				fields = Map.of("username", APP_USER, "password", APP_USER_PASSWORD);
			} else if (page.contains("name=\"accept\"")) {
				fields = Map.of("accept", "Yes");
			} else {
				return;
			}
			page = visit(browser, cookies, URI.create(baseUrl()).resolve(action.group(1).replace("&amp;", "&")), fields);
		}
	}

	/** 요청 하나를 보내고 리다이렉트를 따라가며 Set-Cookie를 모아 다음 요청에 싣는다. 마지막 화면의 HTML을 돌려준다. */
	private static String visit(HttpClient browser, Map<String, String> cookies, URI uri, Map<String, String> form)
			throws Exception {
		for (int hop = 0; hop < 10; hop++) {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri);
			if (!cookies.isEmpty()) {
				request.header("Cookie", cookies.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
						.collect(Collectors.joining("; ")));
			}
			if (form != null) {
				request.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(encode(form)));
			}
			HttpResponse<String> response = browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
			for (String header : response.headers().allValues("Set-Cookie")) {
				String pair = header.split(";", 2)[0];
				int equals = pair.indexOf('=');
				if (equals > 0) cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
			}
			Optional<String> location = response.headers().firstValue("Location");
			if (response.statusCode() / 100 != 3 || location.isEmpty()) return response.body();
			uri = uri.resolve(location.get());
			form = null;
		}
		throw new IllegalStateException("리다이렉트가 너무 많다");
	}

	private static String oidc() {
		return baseUrl() + "/realms/" + REALM + "/protocol/openid-connect";
	}

	private HttpResponse<String> post(String url, Map<String, String> form) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encode(form))).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String encode(Map<String, String> form) {
		return form.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}

	private static Path realmFile() {
		Path directory = Path.of("").toAbsolutePath();
		while (directory != null) {
			Path candidate = directory.resolve("infra/config/realm-app.json");
			if (Files.exists(candidate)) return candidate;
			directory = directory.getParent();
		}
		throw new IllegalStateException("infra/config/realm-app.json을 찾지 못했다");
	}

	private static String baseUrl() {
		return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
	}

	private static KeycloakAdminClient adminClient(String clientId, String secret) {
		return new KeycloakAdminClient(WebClient.builder(), baseUrl(), REALM, clientId, secret, Duration.ZERO);
	}

	private static Throwable failure(Runnable call) {
		try {
			call.run();
		} catch (RuntimeException failure) {
			return failure;
		}
		throw new AssertionError("실패해야 한다");
	}

	private HttpResponse<String> token(String realm, Map<String, String> form) throws Exception {
		return post(baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token", form);
	}

	private List<String> audiences(String accessToken) {
		Object audience = json.readValue(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), Map.class).get("aud");
		return audience instanceof List<?> list ? list.stream().map(String::valueOf).toList()
				: Optional.ofNullable(audience).map(String::valueOf).map(List::of).orElse(List.of());
	}

	private static final java.util.concurrent.atomic.AtomicInteger DATABASES = new java.util.concurrent.atomic.AtomicInteger();

	/** 새 DB에서 수집을 한 주기 돌린다 — 감사 행은 지울 수 없어 테스트마다 DB를 나눈다. */
	private KeycloakAuditStore collectOnce(KeycloakAdminClient keycloak) {
		String database = "realm_file_" + DATABASES.incrementAndGet();
		new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl())).execute("create database " + database);
		DriverManagerDataSource dataSource = dataSource(POSTGRES.getJdbcUrl().replace("/realm_file", "/" + database));
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		KeycloakAuditStore store = new KeycloakAuditStore(new JdbcTemplate(dataSource), json);
		new KeycloakAuditCollector(keycloak, new KeycloakAuditClassifier(json), store,
				new DataSourceTransactionManager(dataSource)).runOnce();
		return store;
	}

	private static DriverManagerDataSource dataSource(String url) {
		return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
