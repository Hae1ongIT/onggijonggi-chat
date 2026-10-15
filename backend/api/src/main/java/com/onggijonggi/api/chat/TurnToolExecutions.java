package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Class Name : TurnToolExecutions.java
 * Description : 한 AI 턴의 도구 실행 공통 지점(이슈 #390). 이 턴에 붙는 도구를 모두 감싸, 호출마다 실행 직전·시작·결과를
 *               한 곳에서 본다. 도구 루프(Spring AI ToolCallingAdvisor)와 스트림은 그대로 두고 도구 하나(ToolCallback)만
 *               감싼다 — 감싸도 모델이 보는 도구 정의(이름·설명·인자)는 원본 그대로다.
 *               턴마다 새로 만든다. 다른 턴의 실행이 섞이지 않고, 끝난 턴의 도구가 늦게 끝나도 그 턴의 기록에만 쌓인다.
 *               방 문서 도구의 출처·안내는 지금처럼 도구 안에서 쌓는다. 진행 표시(#397)가 리스너를 붙여 쓴다.
 */
public final class TurnToolExecutions {

	private static final Logger log = LoggerFactory.getLogger(TurnToolExecutions.class);

	private final UUID threadId;
	private final String traceId;
	private final List<ToolExecutionListener> listeners;
	/** 도구는 boundedElastic에서 실행되고 기록은 다른 스레드가 읽는다. */
	private final List<ToolExecution> executions = new CopyOnWriteArrayList<>();

	public TurnToolExecutions(UUID threadId, String traceId, List<ToolExecutionListener> listeners) {
		this.threadId = threadId;
		this.traceId = traceId;
		this.listeners = List.copyOf(listeners);
	}

	/** 도구들을 이 턴의 공통 지점으로 감싼다. */
	public ToolCallback[] wrap(ToolCallback[] callbacks) {
		return Arrays.stream(callbacks).map(Wrapped::new).toArray(ToolCallback[]::new);
	}

	/** 지금까지 끝난 도구 실행. 실행 순서대로다. */
	public List<ToolExecution> executions() {
		return List.copyOf(executions);
	}

	private void notifyStarted(String toolName, String input) {
		for (ToolExecutionListener listener : listeners) {
			try {
				listener.started(toolName, input);
			} catch (RuntimeException error) {
				log.warn("도구 실행 시작 알림 실패 threadId={} traceId={} tool={}", threadId, traceId, toolName, error);
			}
		}
	}

	private void finish(ToolExecution execution) {
		executions.add(execution);
		// 인자·결과·예외 메시지는 남기지 않는다 — 사내 내용이 섞인 웹 검색어·규정 본문이 들어갈 수 있다.
		// 예외로 끝난 호출은 운영(debug 꺼짐)에서도 보이게 warn으로 남긴다.
		if (execution.succeeded()) {
			log.debug("도구 실행 threadId={} traceId={} tool={} elapsedMs={} result=ok", threadId, traceId,
					execution.toolName(), execution.elapsed().toMillis());
		} else {
			log.warn("도구 실행 threadId={} traceId={} tool={} elapsedMs={} result=failed:{}", threadId, traceId,
					execution.toolName(), execution.elapsed().toMillis(), execution.failure());
		}
		for (ToolExecutionListener listener : listeners) {
			try {
				listener.finished(execution);
			} catch (RuntimeException error) {
				log.warn("도구 실행 결과 알림 실패 threadId={} traceId={} tool={}", threadId, traceId,
						execution.toolName(), error);
			}
		}
	}

	private final class Wrapped implements ToolCallback {

		private final ToolCallback delegate;

		private Wrapped(ToolCallback delegate) {
			this.delegate = delegate;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return delegate.getToolDefinition();
		}

		@Override
		public ToolMetadata getToolMetadata() {
			return delegate.getToolMetadata();
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			String toolName = delegate.getToolDefinition().name();
			// 실행 직전 자리 — 권한 재확인·승인·끝난 턴의 도구 막기가 들어올 곳이다. 지금은 모두 통과시킨다.
			notifyStarted(toolName, toolInput);
			long started = System.nanoTime();
			String output;
			try {
				output = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
			} catch (RuntimeException | Error error) {
				// 시작·끝 알림을 짝으로 쓰는 리스너(진행 표시)가 끝나지 않은 채 남지 않게 Error도 기록한다.
				// 예외를 모델에 돌려줄 문장으로 바꾸는 일은 지금처럼 Spring AI가 한다.
				finish(new ToolExecution(toolName, toolInput, null, error.getClass().getSimpleName(),
						Duration.ofNanos(System.nanoTime() - started)));
				throw error;
			}
			// 성공 기록은 try 밖에서 한다 — 끝 알림 쪽 고장이 같은 호출을 실패로 한 번 더 기록하지 않게.
			finish(new ToolExecution(toolName, toolInput, output == null ? 0 : output.length(), null,
					Duration.ofNanos(System.nanoTime() - started)));
			return output;
		}
	}
}
