package com.onggijonggi.etl;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Class Name : StubHttpServer.java
 * Description : 단위 테스트용 HTTP 서버. 경로 앞부분마다 정한 상태 코드·본문을 돌려주고 받은 요청을 기록한다.
 */
final class StubHttpServer implements AutoCloseable {

	record Reply(int status, String body) { }

	record Request(String method, String path, String body) { }

	private final HttpServer server;
	private final Map<String, Reply> replies = new ConcurrentHashMap<>();
	final List<Request> requests = new CopyOnWriteArrayList<>();

	StubHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Reply reply = replies.entrySet().stream().filter(entry -> path.startsWith(entry.getKey()))
					.max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
					.map(Map.Entry::getValue).orElse(new Reply(404, "{}"));
			byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	StubHttpServer reply(String pathPrefix, int status, String body) {
		replies.put(pathPrefix, new Reply(status, body));
		return this;
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
