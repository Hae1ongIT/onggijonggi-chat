package com.onggijonggi.api.rag;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Class Name : StubHttpServer.java
 * Description : 검색 테스트용 HTTP 서버(임베딩·Elasticsearch 흉내). 경로 앞부분마다 정한 상태 코드·본문을 돌려주거나, 요청 본문으로
 *               응답을 만드는 함수를 둔다. 받은 요청을 기록한다.
 */
public final class StubHttpServer implements AutoCloseable {

	public record Reply(int status, String body) { }

	public record Request(String method, String path, String body) { }

	private final HttpServer server;
	private final Map<String, Function<String, Reply>> replies = new ConcurrentHashMap<>();
	public final List<Request> requests = new CopyOnWriteArrayList<>();

	public StubHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
			Reply reply = replies.entrySet().stream().filter(entry -> path.startsWith(entry.getKey()))
					.max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
					.map(entry -> entry.getValue().apply(body)).orElse(new Reply(404, "{}"));
			byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	public StubHttpServer reply(String pathPrefix, int status, String body) {
		replies.put(pathPrefix, ignored -> new Reply(status, body));
		return this;
	}

	public StubHttpServer reply(String pathPrefix, Function<String, Reply> responder) {
		replies.put(pathPrefix, responder);
		return this;
	}

	public String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
