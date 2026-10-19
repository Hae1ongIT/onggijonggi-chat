package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.rag.SearchResult;
import com.onggijonggi.api.rag.ThreadDocumentSearch;
import com.onggijonggi.api.web.WebSources;
import com.onggijonggi.api.web.WebTools;
import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.openai.errors.OpenAIServiceException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadMessageDispatcher.java
 * Description : 협업방 원문 방송과 {@code @AI} FIFO 실행을 방(threadId) 단위로 직렬화한다.
 *               AI 작업은 연결과 떨어져 산다(이슈 #413) — 방을 보는 사람이 모두 떠나도 진행·대기 턴과 워커가 꺼내지 않은
 *               메시지를 끝까지 처리해 저장하고, 할 일이 없고 방에 아무도 없을 때 상태를 지운다. 방송은 그때 그 방의
 *               연결들에게 가고(registry가 지금 세대로 보낸다), 아무도 없으면 버려질 뿐 오류가 아니다.
 */
@Component
public class ThreadMessageDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ThreadMessageDispatcher.class);

	private final RoomSessionRegistry roomSessionRegistry;

	private final LlmChatStreamService llmChatStreamService;

	private final MsgPersistenceService msgPersistenceService;

	private final ThreadDocumentSearch threadDocumentSearch;
	/** 개정본 만들기 도구의 실행. 개정을 다루지 않는 테스트용 생성자는 null을 넣고, 그때 도구는 만들지 못했다고 답한다. */
	private final ThreadDocumentRevision documentRevision;
	private final ThreadDocumentCreation documentCreation;

	private final ObjectMapper json;

	private final String modelId;

	private final Duration turnTimeout;

	private final int maxPendingPerRoom;

	private final int maxContextMessages;

	private final Scheduler deadlineScheduler;

	/** 답변에 웹 검색·웹 읽기 도구가 붙는지(app.web.enabled). 꺼져 있으면 지시에서 웹을 말하지 않는다 — 쓸 수 없는 도구를 제안하지 않게. */
	private final boolean webEnabled;

	/**
	* 방 하나가 워커 앞에 쌓아둘 수 있는 메시지 수. 초과하면 그 자리에서 RATE_LIMITED로 거절한다
	* — 워커가 막혔을 때 메모리가 무한히 늘지 않게 하는 백프레셔다. AI 대기열 한도
	* (app.thread.ai.max-pending-per-room)와는 다른 층이다: 이쪽은 "방송을 기다리는 메시지",
	* 저쪽은 "LLM 실행을 기다리는 턴"이다.
	*/
	private static final int MAX_QUEUED_MESSAGES_PER_ROOM = 100;

	/**
	* 한 번에 예약하는 seq 개수(이슈 #190). 클수록 DB를 덜 부르지만 방이 닫힐 때 버려지는 구멍이
	* 커진다. 구멍은 따라잡기 정의상 무해하므로(빠진 번호를 기다리지 않는다) 왕복을 줄이는 쪽에
	* 무게를 둔다.
	*/
	private static final int SEQ_BLOCK_SIZE = 100;

	/** 서버 종료 때 진행·대기 턴의 실패 저장을 기다리는 상한(이슈 #413). */
	private static final Duration SHUTDOWN_PERSIST_TIMEOUT = Duration.ofSeconds(5);

	/**
	* 웹이 켜진 배포의 UNAVAILABLE 지시에 붙는 규칙 — 요청받고도 도구를 부르지 않거나, 부르지 않고 웹에서 찾은 것처럼
	* 말하는 경우가 실제로 있었다(이슈 #368). 지시는 모델 재량을 좁힐 뿐 강제하지 못한다.
	*/
	static final String WEB_TOOL_HONESTY = "웹 검색을 요청받으면 반드시 웹 검색 도구를 호출해 결과를 확인한 뒤 답하세요. "
			+ "이번 답변에서 웹 검색 도구를 호출하지 않았다면 웹에서 찾았다고 말하지 마세요.";

	/**
	* 웹이 켜진 배포의 FOUND 웹 규칙. 근거가 이미 프롬프트에 있으면 모델이 웹 요청을 받고도 도구를 건너뛰고 웹을 쓴 척하는
	* 경우가 잦아(이슈 #368, 같은 질문 40회 중 호출 20회·쓴 척 13회), "요청했을 때만"이 아니라 요청받았을 때 할 일을
	* 의무형으로 쓰고 도구 이름·피할 표현·예시를 준다(같은 조건 60회 중 호출 60회·쓴 척 0회, 요청 없는 질문은 웹 0회).
	*/
	static final String FOUND_WEB_RULE = "사용자가 이번 대화에서 웹 검색을 요청했다면, 위 문서 근거가 있더라도 먼저 webSearch 도구를 호출해 결과를 확인한 뒤 답하세요. "
			+ "웹 검색을 요청하지 않았다면 웹을 쓰지 말고 웹 검색을 먼저 제안하지도 마세요. "
			+ "이번 답변에서 webSearch 도구의 결과를 받지 않았다면 \"웹 검색 결과\", \"웹에서 찾은\" 같은 표현을 쓰지 말고 웹 검색을 하지 못했다고 말하세요. "
			+ "예: \"웹에서 찾아서 우리 규정과 비교해줘\" → webSearch를 먼저 호출하고, 그 결과와 문서 근거를 함께 비교한다.";

	/** 문서 목록 지시에 태그를 싣는 최대 문서 수와 요약 길이. 그 밖의 문서는 파일 이름만 싣는다. */
	static final int MAX_TAGGED_DOCUMENTS = 20;
	static final int SUMMARY_MAX_CHARS = 200;
	/** 방 문서 검색 도구가 근거를 찾지 못했을 때 모델에 돌려주는 문장. 웹을 쓸지는 모델이 판단한다(이슈 #368). */
	static final String NO_EVIDENCE_RESULT = "이 방에 고정된 문서에서 관련된 근거를 찾지 못했습니다. 문서에 있는 내용처럼 추측하지 마세요.";
	/** 이 턴에서 이미 개정본을 등록한 뒤 다시 부르면 돌려주는 문장의 앞부분. 뒤에 앞서 등록한 결과 문장을 붙인다. */
	static final String ALREADY_REVISED = "개정본은 한 답변에 하나만 등록합니다 — 이번 호출로는 아무 파일도 등록하지 않았습니다. "
			+ "사용자에게는 앞서 등록한 개정본만 알리세요. 앞서 등록한 결과: ";

	private final ConcurrentMap<UUID, RoomAiState> states = new ConcurrentHashMap<>();

	@Autowired
	public ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry,
			LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService,
			ThreadDocumentSearch threadDocumentSearch,
			ThreadDocumentRevision documentRevision,
			ThreadDocumentCreation documentCreation,
			WebTools webTools,
			ObjectMapper json,
			@Value("${app.thread.ai.model:${spring.ai.openai.chat.options.model}}") String modelId,
			@Value("${app.thread.ai.turn-timeout:120s}") Duration turnTimeout,
			@Value("${app.thread.ai.max-pending-per-room:20}") int maxPendingPerRoom,
			@Value("${app.thread.ai.max-context-messages:20}") int maxContextMessages) {
		this(roomSessionRegistry, llmChatStreamService, msgPersistenceService, threadDocumentSearch, json, modelId,
				turnTimeout, maxPendingPerRoom, maxContextMessages, Schedulers.parallel(), webTools.enabled(), documentRevision,
				documentCreation);
	}

	/** 웹 도구가 켜진 배포(기본값)로 만든다. */
	ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService, ThreadDocumentSearch threadDocumentSearch, ObjectMapper json,
			String modelId, Duration turnTimeout, int maxPendingPerRoom, int maxContextMessages,
			Scheduler deadlineScheduler) {
		this(roomSessionRegistry, llmChatStreamService, msgPersistenceService, threadDocumentSearch, json, modelId,
				turnTimeout, maxPendingPerRoom, maxContextMessages, deadlineScheduler, true, null);
	}

	ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService, ThreadDocumentSearch threadDocumentSearch, ObjectMapper json,
			String modelId, Duration turnTimeout, int maxPendingPerRoom, int maxContextMessages,
			Scheduler deadlineScheduler, boolean webEnabled, ThreadDocumentRevision documentRevision) {
		this(roomSessionRegistry, llmChatStreamService, msgPersistenceService, threadDocumentSearch, json, modelId,
				turnTimeout, maxPendingPerRoom, maxContextMessages, deadlineScheduler, webEnabled, documentRevision, null);
	}

	ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService, ThreadDocumentSearch threadDocumentSearch, ObjectMapper json,
			String modelId, Duration turnTimeout, int maxPendingPerRoom, int maxContextMessages,
			Scheduler deadlineScheduler, boolean webEnabled, ThreadDocumentRevision documentRevision,
			ThreadDocumentCreation documentCreation) {
		if (modelId == null || modelId.isBlank()) {
			throw new IllegalArgumentException("app.thread.ai.model must not be blank");
		}
		if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
			throw new IllegalArgumentException("app.thread.ai.turn-timeout must be positive");
		}
		if (maxPendingPerRoom < 0) {
			throw new IllegalArgumentException("app.thread.ai.max-pending-per-room must not be negative");
		}
		if (maxContextMessages < 0) {
			throw new IllegalArgumentException("app.thread.ai.max-context-messages must not be negative");
		}
		this.roomSessionRegistry = roomSessionRegistry;
		this.llmChatStreamService = llmChatStreamService;
		this.msgPersistenceService = msgPersistenceService;
		this.threadDocumentSearch = threadDocumentSearch;
		this.json = json;
		this.modelId = modelId;
		this.turnTimeout = turnTimeout;
		this.maxPendingPerRoom = maxPendingPerRoom;
		this.maxContextMessages = maxContextMessages;
		this.webEnabled = webEnabled;
		this.deadlineScheduler = deadlineScheduler;
		this.documentRevision = documentRevision;
		this.documentCreation = documentCreation;
		// 방 프레임은 registry의 대기열에서 비동기로 나가 실패를 방송한 자리로 돌려받을 수 없다(이슈 #405) — 실패한
		// 프레임의 세대를 registry가 알려 주면 그 방의 AI 작업을 실패로 닫는다(예전에 방송이 예외를 던질 때 하던 처리와 같다).
		roomSessionRegistry.onBroadcastFailure(this::closeAfterBroadcastFailure);
	}

	/**
	* 메시지를 방 워커 큐에 넣는다. 방송·AI 턴 등록은 워커가 방 단위로 순서대로 처리한다(이슈 #190).
	*
	* 여기서 동기로 돌려주는 오류는 **큐에 넣기 전에 판정이 끝나는 것**뿐이다.
	* - MALFORMED_REQUEST: 파싱만으로 안다. 원문 방송은 그대로 하되 AI 턴은 만들지 않는다.
	* - RATE_LIMITED: 큐가 가득 찼다는 사실은 offer 시점에 정확하다.
	*
	* 반대로 방송 성공 여부와 AI 대기열 한도는 워커가 실행해봐야 알 수 있어, 동기로 흉내 내면
	* 근사치가 된다("됐다고 했는데 실제로는 거절"). 그쪽은 워커가 비동기로 알린다.
	*/
	public Optional<ErrorFrame> dispatch(ChatMessageCommand command) {
		ErrorFrame malformed;
		String prompt;
		Agent agent;
		if (command.kind() == ThrKind.DIRECT) {
			// DIRECT는 멘션 여부와 무관하게 모든 정상 발화를 AI에 전달한다 — 파서를 아예 타지 않는다.
			// 역할별 에이전트(이슈 #357)는 협업방 전용이라 DIRECT는 agent가 항상 null이다.
			malformed = null;
			prompt = command.content();
			agent = null;
		} else {
			AiMentionParser.MentionResult mention = AiMentionParser.parse(command.content());
			malformed = mention.ambiguous()
					? new ErrorFrame(command.threadId(), "MALFORMED_REQUEST",
							"한 메시지에는 에이전트를 하나만 멘션해 주세요.", command.traceId())
					: mention.mentioned() && mention.prompt().isBlank()
							? new ErrorFrame(command.threadId(), "MALFORMED_REQUEST",
									"멘션 뒤에 요청 내용을 입력해 주세요.", command.traceId())
							: null;
			// prompt가 null이면 워커는 방송·저장만 하고 AI 턴을 만들지 않는다 — 일반 발화이거나 빈 프롬프트다.
			prompt = malformed == null && mention.mentioned() ? mention.prompt() : null;
			agent = malformed == null && mention.mentioned() ? mention.agent() : null;
		}

		UUID threadId = command.threadId();
		while (true) {
			RoomAiState state = states.computeIfAbsent(threadId, id -> new RoomAiState(id, command.kind()));

			synchronized (state) {
				if (state.closed) {
					// computeIfAbsent와 락 사이에 다른 스레드가 이 상태를 막 닫고 map에서 지웠다(할 일이 없어 지웠거나
					// 실패로 닫았다). map에서 현재(또는 새로 생긴) 상태를 다시 얻어 이어간다.
					continue;
				}
				// 워커가 꺼내기 전에 도착한 취소도 받을 수 있게 큐에 넣는 순간 등록한다(cancel 주석).
				// 인바운드는 연결마다 순서대로 처리되므로, 같은 연결의 chat.cancel은 이 등록 뒤에 온다.
				QueuedMessage queued = new QueuedMessage(command, prompt, agent, new AtomicBoolean());
				TurnRef ref = prompt == null ? null : TurnRef.of(command.turnId(), command.fromSubject());
				if (ref != null) {
					state.inFlight.put(ref, new InFlightTurn(queued, false));
				}
				if (state.inbox.tryEmitNext(queued).isFailure()) {
					if (ref != null) {
						state.inFlight.remove(ref);
					}
					if (command.kind() == ThrKind.DIRECT && command.reservedTurn() != null) {
						persistDirectReservedAgentTerminal(command.reservedTurn(), queued.terminalPersisted(),
								MsgStatus.CANCELLED);
					}
					return Optional.of(new ErrorFrame(command.threadId(), "RATE_LIMITED",
							"이 방의 메시지 대기열이 가득 찼습니다.", command.traceId()));
				}
				state.queuedMessages += 1;
				if (prompt != null) {
					state.unprocessedAiTurns += 1;
				}
			}
			return Optional.ofNullable(malformed);
		}
	}

	/**
	* 대화 밖에서 생긴 줄(입퇴장)의 seq. 지금 대화가 오가는 방이면 그 방의 블록에서 꺼낸다 — DB에서
	* 따로 뽑으면 이미 예약된 블록 뒤의 번호를 받아, 그 뒤에 오간 대화보다 뒤로 정렬되고 따라잡기
	* 커서도 그 대화를 건너뛴다. 대화가 없는 방이면 다음에 예약될 블록보다 앞이라 DB에서 하나 뽑는다.
	*
	* 블로킹이라 호출부가 이벤트 루프 밖이어야 한다.
	*/
	public long allocateSeqBlocking(UUID threadId) {
		RoomAiState state = states.get(threadId);
		if (state != null) {
			boolean open;
			synchronized (state) {
				open = !state.closed;
			}
			// 블록은 DB에 이미 예약돼 있어 그 사이 방이 닫혀도 번호가 겹치지 않는다. 블록이 바닥나면
			// DB를 부르므로 방 잠금 밖에서 꺼낸다.
			if (open) {
				return state.seqBlock.allocate();
			}
		}
		return msgPersistenceService.allocateSeqBlockBlocking(threadId, 1);
	}

	/**
	* 워커가 큐에서 하나씩 꺼내 실행한다 — 방 단위로 직렬이므로 방송 순서가 여기서 확정된다.
	*
	* 방에 아무도 없어 방송이 버려져도(false) 그대로 저장하고 턴을 잇는다(이슈 #413) — 보낸 사람이 방을 떠났어도 이미
	* 받은 발화다. 방송이 예외를 던지면(방 대기열이 꽉 참) 방송 통로가 막힌 상태라 방의 AI 작업을 실패로 닫는다 —
	* 워커에는 그 사람의 연결로 가는 통로가 없어, 남은 대기 턴 취소를 한 번 알리는 것으로 갈음한다. 대기열에 넣은 뒤
	* sink에서 난 실패는 여기가 아니라 closeAfterBroadcastFailure로 온다(이슈 #405).
	*/
	private void process(RoomAiState state, QueuedMessage queued) {
		ChatMessageCommand command = queued.command();
		boolean direct = command.kind() == ThrKind.DIRECT;
		ChatMessageCommand.ReservedTurn reserved = command.reservedTurn();
		// DIRECT는 bootstrap 또는 비동기 저장이 HUMAN msgId·seq를 이미 예약해뒀다(이슈 #162) —
		// 새로 만들면 이미 저장된 행과 어긋난다. COLLAB은 그대로 방송 시점에 새로 확정한다(이슈 #190).
		UUID msgId = direct ? reserved.humanMsgId() : UUID.randomUUID();
		long seq = direct ? reserved.humanSeq() : state.seqBlock.allocate();
		try {
			roomSessionRegistry.broadcastToRoom(command.threadId(),
					new ChatMessageFrame(command.threadId(), msgId, command.clientMsgId(), command.turnId(), seq,
							command.fromSubject(), command.fromDisplayName(), command.content(), command.fileViews()));
		} catch (RuntimeException ignored) {
			closeRoom(state, Closing.FAILURE);
			return;
		}

		if (queued.prompt() == null) {
			// DIRECT는 dispatch()가 항상 prompt를 채우므로 이 분기는 COLLAB 전용이다.
			persistHumanMessageAsync(msgId, seq, command);
			return;
		}

		Mono<List<Msg>> context = direct
				// HUMAN은 이미 DirectChatTurnService가 저장했다 — 문맥만 읽는다(중복 저장 방지).
				? fetchContextOnlyBlocking(command.threadId())
				: persistHumanMessageAndFetchContext(msgId, seq, command);
		PendingTurn pendingTurn = new PendingTurn(command.threadId(), queued.prompt(),
				msgId, command.files(), command.traceId(), command.from(), command.fromSubject(),
				command.fromDisplayName(), TurnRef.of(command.turnId(), command.fromSubject()), command.model(),
				command.timeZone(), context, direct ? reserved : null, queued.terminalPersisted(), queued.agent());
		ActiveTurn turnToStart = null;
		boolean admitted = false;
		// 등록 해제와 대기열 추가를 한 락 안에서 한다 — 사이가 벌어지면 그 틈에 온 취소가 어디서도
		// 턴을 못 찾는다. 대기·취소 통지도 락 안에서 보낸다: 락 밖이면 앞 턴이 막 끝나 이 턴이 먼저
		// 시작되고, 답변 프레임 뒤에 "대기 중"이 도착할 수 있다.
		synchronized (state) {
			// 여기까지 오면 더 이상 "워커가 꼺지 않은" 발화가 아니다 — 아래 어느 가지로 가든
			// 이 발화의 처리는 여기서 끝난다.
			state.unprocessedAiTurns -= 1;
			InFlightTurn inFlight = pendingTurn.ref() == null ? null : state.inFlight.remove(pendingTurn.ref());
			boolean cancelledBeforeQueue = inFlight != null && inFlight.cancelled();
			if (state.closed) {
				// 저장만 하고 끝낸다 — DIRECT는 예약해둔 PENDING AGENT가 영영 PENDING으로 남지
				// 않도록 CANCELLED로 정리한다.
				if (direct) {
					finishDirectReservedAgent(pendingTurn, MsgStatus.FAILED, null);
				}
			} else if (cancelledBeforeQueue) {
				if (direct) {
					finishDirectReservedAgent(pendingTurn, MsgStatus.CANCELLED, ChatAnswerStatus.CANCELLED);
				} else {
					broadcastQuietly(state.threadId, queuedFrame(pendingTurn, ChatQueuedStatus.CANCELLED));
				}
			} else if (state.active == null) {
				turnToStart = new ActiveTurn(pendingTurn, state.seqBlock);
				state.active = turnToStart;
				admitted = true;
			} else if (state.pending.size() >= maxPendingPerRoom) {
				broadcastQuietly(state.threadId, new ErrorFrame(state.threadId, "RATE_LIMITED",
						"이 방의 AI 요청 대기열이 가득 찼습니다.", command.traceId()));
				if (direct) {
					finishDirectReservedAgent(pendingTurn, MsgStatus.DENIED, ChatAnswerStatus.DENIED);
				}
			} else {
				state.pending.addLast(pendingTurn);
				broadcastQuietly(state.threadId, queuedFrame(pendingTurn, ChatQueuedStatus.QUEUED));
				admitted = true;
			}
		}

		if (!admitted) {
			if (!direct) {
				// 턴은 없어도 발화는 이미 방송됐으니 이력에 남긴다. DIRECT는 이미 저장돼 있어 불필요하다.
				persistHumanMessageAsync(msgId, seq, command);
			}
			return;
		}
		// 문맥 조회는 사람 메시지 저장을 겸하므로 턴이 대기 중이어도 지금 시작한다(이슈 #100).
		pendingTurn.context().subscribe();
		if (turnToStart != null) {
			startTurn(state, turnToStart);
		}
	}

	/** DIRECT 전용 — HUMAN이 이미 저장돼 있으므로 최근 COMPLETE 문맥만 읽는다(이슈 #162). */
	private Mono<List<Msg>> fetchContextOnlyBlocking(UUID threadId) {
		return Mono
				.fromCallable(() -> msgPersistenceService.recentCompleteContextBlocking(threadId, maxContextMessages))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("DIRECT 문맥 조회 실패 threadId={}", threadId, e);
					return Mono.just(List.<Msg>of());
				})
				.cache();
	}

	/**
	* DIRECT의 예약된 PENDING AGENT를 CANCELLED·DENIED·FAILED로 닫고, 같은 상태의 `chat.answer`를 방
	* 전체에 방송한다(이슈 #162) — 큐 진입 전 취소·FIFO 초과·방 실패 종료가 모두 이 경로를 쓴다. CANCELLED는
	* 사용자 취소 의미라 cancelBlocking(빈 본문)을, 나머지는 failBlocking을 쓴다. 실패(answerStatus null)는 chat.answer에
	* 실패 상태가 없어 프레임을 보내지 않는다 — 화면은 이력에서 실패로 본다(이슈 #413 결정 8).
	*/
	private void finishDirectReservedAgent(PendingTurn turn, MsgStatus terminalStatus, ChatAnswerStatus answerStatus) {
		ChatMessageCommand.ReservedTurn reserved = turn.reservedTurn();
		persistDirectReservedAgentTerminal(reserved, turn.terminalPersisted(), terminalStatus);
		if (answerStatus != null) {
			broadcastQuietly(turn.threadId(), new ChatAnswerFrame(turn.threadId(), reserved.agentMsgId(), turn.turnId(),
					modelIdFor(turn), reserved.agentSeq(), "", List.of(), false, answerStatus, null, null));
		}
	}

	/** 예약된 DIRECT AGENT의 terminal 전이는 큐·대기·활성 어느 경로에서도 한 번만 실행한다. */
	private Mono<Void> persistDirectReservedAgentTerminal(ChatMessageCommand.ReservedTurn reserved,
			AtomicBoolean terminalPersisted, MsgStatus terminalStatus) {
		if (!terminalPersisted.compareAndSet(false, true)) {
			return Mono.empty();
		}
		return started(Mono.<Void>fromRunnable(() -> {
					if (terminalStatus == MsgStatus.CANCELLED) {
						msgPersistenceService.cancelBlocking(reserved.agentMsgId(), "");
					} else {
						msgPersistenceService.failBlocking(reserved.agentMsgId(), terminalStatus);
					}
				})
				.subscribeOn(Schedulers.boundedElastic())
				.doOnError(e -> log.error("DIRECT 예약 AGENT 종료 저장 실패 msgId={}", reserved.agentMsgId(), e))
				.onErrorComplete());
	}

	/** 저장을 지금 시작하고, 끝을 기다릴 수 있는 Mono를 돌려준다 — 서버 종료 때만 기다린다(closeAll). */
	private static Mono<Void> started(Mono<Void> persistence) {
		Mono<Void> cached = persistence.cache();
		cached.subscribe();
		return cached;
	}

	private static ChatQueuedFrame queuedFrame(PendingTurn turn, ChatQueuedStatus status) {
		return new ChatQueuedFrame(turn.threadId(), turn.turnId(), status);
	}

	/** 방송 실패가 이 경로를 더 망가뜨리지 않게 삼킨다 — 이미 통지 성격의 호출이다. 방에 아무도 없으면 버려진다. */
	private void broadcastQuietly(UUID threadId, WsFrame frame) {
		try {
			roomSessionRegistry.broadcastToRoom(threadId, frame);
		} catch (RuntimeException ignored) {
			// 통지는 한 번만 시도하고 재시도하지 않는다.
		}
	}

	/**
	* replay(이슈 #233)가 지금 이 방에서 아직 살아있는 턴(진행 중이거나, FIFO 대기 중이거나,
	* 워커가 아직 꺼내지 않은)과 같은 AGENT 메시지를 가리키는지 확인한다. active만 보면 안 되는
	* 이유: DIRECT는 여러 탭이 같은 OWNER로 동시에 붙을 수 있고 턴이 큐잉되는 게 정상 동작이라
	* (cancel()의 pending·inFlight 처리와 같은 전제), 아직 시작 전인 정상 대기 턴을 여기서
	* "고아"로 잘못 판정하면 호출부가 그 msg를 FAILED로 닫고 중복 턴을 새로 시작한다 — 원래
	* 턴이 나중에 실제로 끝나도 msg_block_terminal_update 트리거가 그 저장을 막아 응답이
	* 조용히 유실된다.
	*
	* active면 지금까지 누적된 내용을, pending·inFlight면(아직 한 글자도 안 만들어졌다) 빈
	* 문자열을 돌려준다 — 셋 다 아니면 empty(호출부가 DB 상태로 마저 분기한다: 이미 끝났거나
	* 진짜 고아). active가 끝 프레임을 이미 냈으면(다음 턴으로 넘어가기 전) 그 끝 프레임도 함께 준다 — 흐르는 중으로
	* 답하면 끝 프레임이 이미 지나가 화면이 "생성 중"에 머문다(이슈 #413 리뷰).
	*/
	Optional<LiveAnswer> liveAnswer(UUID threadId, UUID agentMsgId) {
		RoomAiState state = states.get(threadId);
		if (state == null) {
			return Optional.empty();
		}
		synchronized (state) {
			if (state.closed) {
				return Optional.empty();
			}
			if (state.active != null && agentMsgId.equals(state.active.msgId)) {
				return Optional.of(new LiveAnswer(state.active.content.toString(), state.active.endFrame));
			}
			for (PendingTurn candidate : state.pending) {
				if (candidate.reservedTurn() != null && agentMsgId.equals(candidate.reservedTurn().agentMsgId())) {
					return Optional.of(new LiveAnswer("", null));
				}
			}
			for (InFlightTurn inFlight : state.inFlight.values()) {
				ChatMessageCommand.ReservedTurn reserved = inFlight.queued().command().reservedTurn();
				if (reserved != null && agentMsgId.equals(reserved.agentMsgId())) {
					return Optional.of(new LiveAnswer("", null));
				}
			}
			return Optional.empty();
		}
	}

	/**
	* 방에 막 들어온 연결에 보낼, 진행 중인 답의 지금 모습(이슈 #413 결정 3·7·9). 구독을 건 뒤에 부른다 — 그 뒤 조각은
	* 방 프레임으로 오고, 겹치는 범위는 화면이 위치로 버린다.
	* - 글자가 있으면 chat.answer(streaming) — delta는 비우고 지금까지 전체를 content에(옛 화면은 모르는 필드라 버린다,
	*   결정 12). 흐르는 조각에 한 번만 실리는 agent·groundingNotice도 싣는다.
	* - 실행 중인 도구가 있으면 그 chat.activity(시작).
	* - 둘 다 없으면 THINKING 활동 — 첫 글자를 기다리는 중이라는 표시다.
	* 진행 중인 턴이 없으면 아무것도 보내지 않는다. 이미 끝을 잡았으면 낸 끝 프레임을 준다(실패는 끝 프레임이 없어 이력에서
	* 보인다).
	*/
	List<WsFrame> snapshotFor(UUID threadId, String subject) {
		RoomAiState state = states.get(threadId);
		if (state == null) {
			return List.of();
		}
		ActiveTurn activeTurn;
		synchronized (state) {
			activeTurn = state.closed ? null : state.active;
		}
		if (activeTurn == null) {
			return List.of();
		}
		if (activeTurn.terminalClaimed.get()) {
			// 끝 프레임이 방송되는 사이 들어온 연결은 그 프레임을 못 받을 수 있다 — 방에 붙고(join) 구독을 걸기(attach)
			// 전에 나간 방송은 받는 사람이 없어 버려진다(이슈 #413 리뷰). 다음 턴으로 넘어가기 전이면 그 끝 프레임을 준다.
			ChatAnswerFrame ended = activeTurn.endFrame;
			return ended == null ? List.of() : List.of(ended.forRecipient(subject));
		}
		List<WsFrame> frames = new ArrayList<>();
		PendingTurn turn = activeTurn.turn;
		if (activeTurn.hasNonBlankOutput.get()) {
			frames.add(new ChatAnswerFrame(threadId, activeTurn.msgId, turn.turnId(), modelIdFor(turn), activeTurn.seq, "",
					List.of(), false, ChatAnswerStatus.STREAMING, activeTurn.groundingNotice, turn.agent(), null,
					activeTurn.content.toString(), subject.equals(turn.fromSubject()), null));
		}
		ToolActivity activity = activeTurn.currentActivity;
		if (activity != null) {
			frames.add(new ChatActivityFrame(threadId, activeTurn.msgId, turn.turnId(), activity.kind(), activity.target(),
					true, turn.agent()));
		} else if (!activeTurn.hasNonBlankOutput.get()) {
			frames.add(new ChatActivityFrame(threadId, activeTurn.msgId, turn.turnId(), ToolActivity.THINKING, null, true,
					turn.agent()));
		}
		return frames;
	}

	/** replay 응답 구성용(이슈 #233) — modelIdFor(PendingTurn)와 같은 기본값 규칙을 외부에 노출한다. */
	String resolveModelId(String requestedModel) {
		return requestedModel == null || requestedModel.isBlank() ? modelId : requestedModel;
	}

	/**
	* 그 세대의 방 프레임 하나가 sink에 들어가지 못했다(registry의 비우기 스레드가 알린다, 이슈 #405). 방송이 실패해
	* 예외를 받았을 때와 같이 방의 AI 작업을 실패로 닫고, 남은 대기 턴 취소를 한 번 알린다. 지금 방의 세대가 아니면
	* 넘긴다 — 비었다 다시 생긴 방에 옛 세대의 늦은 실패가 와서 이어지고 있는 턴을 닫지 않게(이슈 #413).
	*/
	void closeAfterBroadcastFailure(UUID threadId, UUID roomGeneration) {
		if (!roomSessionRegistry.currentGeneration(threadId).map(roomGeneration::equals).orElse(false)) {
			return;
		}
		RoomAiState state = states.get(threadId);
		if (state != null) {
			closeRoom(state, Closing.FAILURE);
		}
	}

	/**
	* 방을 보던 마지막 연결이 떠났다(이슈 #413). AI 작업은 연결과 떨어져 끝까지 가므로 할 일이 없을 때만 상태를 지운다 —
	* 남은 일은 끝날 때마다 closeIfIdle이 다시 본다.
	*/
	public void roomVacated(UUID threadId) {
		RoomAiState state = states.get(threadId);
		if (state != null) {
			closeIfIdle(state);
		}
	}

	/** 방이 지워졌다(이슈 #413) — 저장할 행이 없으니 진행·대기 턴을 저장 없이 끊는다. */
	public void discard(UUID threadId) {
		RoomAiState state = states.get(threadId);
		if (state != null) {
			closeRoom(state, Closing.DELETED);
		}
	}

	/**
	* 턴 하나를 멈춘다(이슈 #160). COLLAB은 지목을 (turnId, 그 발화를 보낸 사람 subject) 짝으로 한다.
	*
	* turnId는 클라이언트가 만든 값이라 그것만으로는 믿지 않는다 — "이 사람이 보낸 발화"의 턴에서만
	* 인정한다. 짝으로 찾으면 남이 부른 턴은 애초에 찾아지지 않는다 — 에코에서 남의 turnId를 읽어
	* 보내도 끊을 수 없고, 같은 값을 우연히 재사용해도 서로 간섭하지 않는다. 보낸 연결이 아니라 사람으로 짝짓는 것은
	* 요청한 사람이 방을 떠났다 돌아와도(새 연결·새로고침·다른 탭) 멈출 수 있게 하기 위해서다(이슈 #413 결정 4).
	* 같은 사람이 같은 turnId를 두 번 보내면 대기 등록이 덮이지만, turnId는 화면이 발화마다 새로 만든다.
	* 그래서 FORBIDDEN이 따로 없고, 못 찾으면 조용히 넘어간다(이미 끝난 턴의 취소와 같은 결과다).
	*
	* DIRECT는 turnId만으로 지목한다(이슈 #162) — 재연결·다른 탭의 같은 OWNER도 취소할 수 있어야
	* 하기 때문이다. 이 커넥션이 그 OWNER인지는 ThreadWebSocketHandler가 구독 인가에서 이미
	* 확인했으므로 여기서 다시 확인하지 않는다.
	*
	* 턴이 있을 수 있는 자리는 셋이다.
	* - 워커가 아직 꺼내지 않았다(inFlight): 표시만 남기고, 워커가 꺼낼 때 턴을 만들지 않는다.
	*   사람 발화 자체는 이미 받은 것이라 방송·저장한다. COLLAB은 chat.queued(cancelled)를,
	*   DIRECT는 예약 AGENT의 chat.answer(cancelled)를 보낸다.
	* - 기다리고 있다(pending): 큐에서 빼고 위와 같은 프레임을 보낸다.
	* - 실행 중이다(active): 구독을 끊고, 그때까지 생성된 내용을 CANCELLED로 저장한 뒤 cancelled 답변 프레임에
	*   그 내용을 실어 보낸다. 화면은 이 프레임에서 스트림을 닫으므로 없으면 중단한 답변이 계속 "생성 중"으로 남는다.
	*/
	public void cancel(UUID threadId, UUID turnId, String subject) {
		RoomAiState state = states.get(threadId);
		if (turnId == null || state == null) {
			return;
		}
		boolean direct = state.kind == ThrKind.DIRECT;
		TurnRef ref = direct ? null : TurnRef.of(turnId, subject);
		if (!direct && ref == null) {
			return;
		}

		ActiveTurn activeTurn;
		synchronized (state) {
			if (state.closed) {
				return;
			}
			if (direct) {
				TurnRef inFlightRef = findInFlightByTurnId(state, turnId);
				if (inFlightRef != null) {
					state.inFlight.computeIfPresent(inFlightRef, (ignored, queued) -> queued.cancel());
					return;
				}
			} else if (state.inFlight.containsKey(ref)) {
				state.inFlight.computeIfPresent(ref, (ignored, queued) -> queued.cancel());
				return;
			}
			boolean activeMatches = direct
					? state.active != null && turnId.equals(state.active.turn.turnId())
					: state.active != null && ref.equals(state.active.turn.ref());
			if (!activeMatches) {
				for (PendingTurn candidate : state.pending) {
					boolean matches = direct ? turnId.equals(candidate.turnId()) : ref.equals(candidate.ref());
					if (matches) {
						state.pending.remove(candidate);
						if (direct) {
							finishDirectReservedAgent(candidate, MsgStatus.CANCELLED, ChatAnswerStatus.CANCELLED);
						} else {
							broadcastQuietly(threadId, queuedFrame(candidate, ChatQueuedStatus.CANCELLED));
						}
						return;
					}
				}
				return;
			}
			// state.active는 비우지 않는다 — advance()가 "끝난 턴이 아직 활성인가"로 경합을 거르므로,
			// 여기서 비우면 다음 대기 턴이 시작되지 않는다.
			activeTurn = state.active;
			if (!activeTurn.claimTerminal()) return;
		}

		stopActive(state, activeTurn);
	}

	/**
	* 실행 중인 턴을 사람의 결정으로 멈춘다(중지·권한 회수) — 받은 내용을 CANCELLED로 저장하고 cancelled 프레임에 실어
	* 보낸 뒤 다음 턴으로 넘긴다. 예전에 협업방은 done을 보내 화면이 "중지됨"을 몰랐다(이슈 #413 결정 11).
	*/
	private void stopActive(RoomAiState state, ActiveTurn activeTurn) {
		activeTurn.subscription.dispose();
		persistAgentCancellation(activeTurn);
		broadcastQuietly(state.threadId, activeTurn.end(endFrame(activeTurn, ChatAnswerStatus.CANCELLED,
				activeTurn.content.toString(), List.of())));
		advance(state, activeTurn);
	}

	/** 끝 프레임(완료·중지·실패) — delta는 비우고 최종 전체 내용을 content에 싣는다(이슈 #413 결정 12). 실패는 빈 내용이다. */
	private ChatAnswerFrame endFrame(ActiveTurn activeTurn, ChatAnswerStatus status, String content,
			List<Citation> citations) {
		return new ChatAnswerFrame(activeTurn.turn.threadId(), activeTurn.msgId, activeTurn.turn.turnId(),
				modelIdFor(activeTurn.turn), activeTurn.seq, "", citations, false, status, activeTurn.groundingNotice,
				activeTurn.turn.agent(), null, content, null, activeTurn.turn.fromSubject());
	}

	/**
	 * 그 사람이 시작한 협업방 AI 턴만 취소한다(권한 회수, #299) — 진행 중인 턴, 대기열, 아직 줄에 오르지 않은
	 * 턴 모두다. 같은 방의 다른 사람 턴과 1:1(DIRECT) 턴은 건드리지 않는다. 1:1은 워크스페이스 권한이 아니라
	 * 소유자 계약으로 지키기 때문이다.
	 */
	public void cancelCollabTurnsFrom(String subject) {
		for (RoomAiState state : states.values()) {
			if (state.kind != ThrKind.COLLAB) {
				continue;
			}
			ActiveTurn activeTurn = null;
			List<PendingTurn> cancelledPending = new ArrayList<>();
			synchronized (state) {
				if (state.closed) {
					continue;
				}
				if (state.active != null && subject.equals(state.active.turn.fromSubject())
						&& state.active.claimTerminal()) {
					activeTurn = state.active;
				}
				for (Iterator<PendingTurn> iterator = state.pending.iterator(); iterator.hasNext();) {
					PendingTurn candidate = iterator.next();
					if (subject.equals(candidate.fromSubject())) {
						iterator.remove();
						cancelledPending.add(candidate);
					}
				}
				state.inFlight.replaceAll((ref, inFlight) -> subject.equals(inFlight.queued().command().fromSubject())
						? inFlight.cancel() : inFlight);
			}
			for (PendingTurn pendingTurn : cancelledPending) {
				broadcastQuietly(state.threadId, queuedFrame(pendingTurn, ChatQueuedStatus.CANCELLED));
			}
			if (activeTurn != null) {
				stopActive(state, activeTurn);
			}
		}
	}

	/** inFlight는 COLLAB처럼 (turnId, subject) 짝으로 키가 잡혀 있다 — DIRECT는 turnId만
	 * 훑어 다른 탭이 등록한 것도 찾는다(이슈 #162). */
	private static TurnRef findInFlightByTurnId(RoomAiState state, UUID turnId) {
		for (TurnRef ref : state.inFlight.keySet()) {
			if (ref.turnId().equals(turnId)) {
				return ref;
			}
		}
		return null;
	}

	/**
	* 서버가 꺼진다 — 진행·대기 턴은 메모리에만 있어 이어 갈 수 없다. 사람이 멈춘 것이 아니라서 "중지됨"이 아니라 실패로
	* 남긴다(이슈 #413 결정 10). 저장은 보통 비동기로 흘려보내지만 여기서는 짧게 기다린다 — 기다리지 않으면 종료와 함께
	* 저장이 사라져 PENDING으로 남는다.
	*/
	@PreDestroy
	void closeAll() {
		List<Mono<Void>> persistences = new ArrayList<>();
		for (RoomAiState state : states.values()) {
			persistences.addAll(closeRoom(state, Closing.FAILURE));
		}
		if (persistences.isEmpty()) {
			return;
		}
		try {
			Mono.when(persistences).block(SHUTDOWN_PERSIST_TIMEOUT);
		} catch (RuntimeException timeout) {
			log.warn("서버 종료 중 AI 턴 실패 저장을 다 기다리지 못했다 count={}", persistences.size());
		}
	}

	/**
	* PENDING msg 생성은 LLM 스트림 시작을 지연시키지 않도록 별도로 fire-and-forget 구독한다
	* (activeTurn.pendingMsgId에 캐시된 Mono로 보관 — 완료/실패 저장 시점에 그 결과를 기다린다).
	* 문맥(turn.context())은 반대로 LLM 호출 자체의 입력이라 결과를 기다려야 한다 — 조회가 끝나야
	* 무엇을 보낼지 정해지므로, 여기서만 스트림 시작이 그만큼 지연된다(이슈 #100).
	* 델타는 개별 저장하지 않고 activeTurn.content에 누적해 턴이 끝났을 때 한 번에 완료 처리한다
	* (PersistingChatStreamService와 동일한 결).
	*
	* LLM 호출 직전에 abandoned()를 보는 이유(이슈 #205): context()는 .cache()된 Mono라, 아래
	* .subscribe()가 불릴 때 이미 완료돼 있으면 그 호출 안에서 flatMapMany까지 동기로 실행된다.
	* 그러면 아래 synchronized 블록의 닫힘 검사는 이미 나간 호출을 되돌리지 못한다 — dispose는 이후
	* 신호만 끊지, 벌어진 부작용을 취소하지는 못하기 때문이다.
	*
	* 비워 돌려줄 때 Flux.empty()가 아니라 never()인 것도 의도된 것이다. empty()면 아래 concatWith가
	* EmptyLlmOutputException을 만들어 handleTurnError로 가는데, 취소된 턴은 방이 살아 있어
	* MODEL_UNAVAILABLE 프레임이 실제로 방송된다 — 취소했는데 잠시 뒤 오류가 뜨는 화면이 된다.
	* never()는 아래 update()가 곧바로 dispose 한다(이미 dispose된 Swap은 새 값을 즉시 정리한다).
	*/
	private void startTurn(RoomAiState state, ActiveTurn activeTurn) {
		activeTurn.pendingMsgId.subscribe();
		logConcurrentTurns(state, activeTurn);

		Disposable subscription = activeTurn.turn.context()
				.flatMap(context -> promptMessages(activeTurn.turn, context))
				.flatMap(messages -> withDocumentTool(activeTurn, messages))
				.flatMapMany(prompt -> {
					if (abandoned(activeTurn)) {
						return Flux.<String>never();
					}
					return withTotalDeadline(Flux.defer(() -> llmChatStreamService.streamChat(
							new ChatStreamRequest(activeTurn.turn.threadId(), modelIdFor(activeTurn.turn),
									prompt.messages(), activeTurn.turn.timeZone(), prompt.documentTool(),
									activeTurn.toolExecutions, activeTurn.webSources, createTool(activeTurn)))));
				})
				.filter(delta -> !delta.isEmpty())
				.doOnNext(delta -> {
					synchronized (state) {
						if (state.closed || activeTurn.terminalClaimed.get()) return;
						int offset = activeTurn.content.length();
						activeTurn.content.append(delta);
						broadcastDelta(activeTurn, delta, offset);
					}
				})
				.concatWith(Flux.defer(() -> activeTurn.hasNonBlankOutput.get()
						? Flux.empty()
						: Flux.error(new EmptyLlmOutputException())))
				.subscribe(ignored -> {
				}, error -> handleTurnError(state, activeTurn, error),
						() -> handleTurnComplete(state, activeTurn, activeTurn.content.toString()));

		synchronized (state) {
			if (!state.closed && state.active == activeTurn) {
				activeTurn.subscription.update(subscription);
			} else {
				subscription.dispose();
			}
		}
	}

	/**
	* 같은 사람이 다른 방에서 돌리고 있는 AI 턴 수를 남긴다(이슈 #413 결정 5) — 턴이 연결과 떨어지자 방을 옮겨 다니며
	* 묻기만 해도 방마다 답이 동시에 돈다. 상한을 두기 전에 실제로 몰리는지 본다. 다른 방의 잠금을 잡지 않고 active만
	* 읽는다(volatile) — 두 방이 동시에 서로를 세면 잠금 순서가 엇갈려 멈출 수 있다. 내용·주소는 남기지 않는다.
	*/
	private void logConcurrentTurns(RoomAiState state, ActiveTurn activeTurn) {
		UUID userId = activeTurn.turn.fromUserId();
		if (userId == null) {
			return;
		}
		long others = states.values().stream()
				.filter(other -> other != state)
				.map(other -> other.active)
				.filter(other -> other != null && userId.equals(other.turn.fromUserId()))
				.count();
		if (others > 0) {
			log.info("사람당 동시 AI 턴 threadId={} traceId={} others={}", state.threadId, activeTurn.turn.traceId(),
					others);
		}
	}

	/**
	* 이 턴이 이미 버려졌는지. 방 닫힘(closeRoom)과 취소(cancel)가 공통으로 내리는 신호가
	* 이 Swap 하나라, 둘을 한 번에 본다. state.closed나 state.active로는 취소를 걸러낼 수 없다 —
	* cancel()은 다음 대기 턴이 시작되도록 state.active를 일부러 비우지 않기 때문이다.
	*/
	private static boolean abandoned(ActiveTurn activeTurn) {
		return activeTurn.subscription.isDisposed();
	}

	/** 발화가 모델을 지정하지 않았으면 서버 기본값(app.thread.ai.model)으로 돌아간다(이슈 #160). */
	private String modelIdFor(PendingTurn turn) {
		return resolveModelId(turn.model());
	}

	/** 저장된 이력의 HUMAN/AGENT를 user/assistant로 매핑하고, 이번 멘션의 발화를 마지막에 붙인다. */
	/**
	* 문맥 메시지에 붙은 첨부를 읽어 프롬프트를 만든다. 첨부를 못 읽으면 첨부 없이 계속한다 — 문맥 조회
	* 실패를 빈 문맥으로 넘기는 것(fetchContextOnlyBlocking)과 같은 결이다.
	*/
	private Mono<List<ChatMessage>> promptMessages(PendingTurn turn, List<Msg> context) {
		List<UUID> msgIds = context.stream().map(Msg::getId).toList();
		return Mono.fromCallable(() -> msgPersistenceService.filesByMessageBlocking(msgIds))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("문맥 첨부 조회 실패 threadId={} traceId={}", turn.threadId(), turn.traceId(), e);
					return Mono.just(Map.of());
				})
				.map(files -> buildPromptMessages(context, files, turn));
	}

	/** 모델에 보낼 메시지와 이 턴의 방 문서 검색 도구(검색할 문서가 없으면 null). */
	private record TurnPrompt(List<ChatMessage> messages, ThreadDocumentTool documentTool) { }

	/**
	* 방에 검색할 고정 문서(고정·READY)가 있으면 방 문서 검색 도구(ThreadDocumentTool)를 붙이고, 어떤 문서가 있는지
	* 이번 발화 바로 앞에 system 메시지로 알린다. 검색은 모델이 도구를 부를 때만 한다 — 예전에는 매 턴 미리 검색해,
	* 문서가 필요 없는 질문(첨부 요약·현재 시각)에도 관련 없는 청크가 출처로 붙었다.
	* 문서 목록을 읽지 못하면 도구 없이 검색 실패와 같은 지시·안내(UNAVAILABLE)로 답한다 — 장애를 조용히 넘기지 않는다.
	* 고정 문서가 없는 방은 도구도 지시도 없다(안내 대상도 아니다, 이슈 #353).
	*/
	private Mono<TurnPrompt> withDocumentTool(ActiveTurn activeTurn, List<ChatMessage> messages) {
		PendingTurn turn = activeTurn.turn;
		return threadDocumentSearch.searchableDocuments(turn.threadId(), actorOf(turn))
				.map(documents -> documents.isEmpty()
						? new TurnPrompt(messages, null)
						: new TurnPrompt(withSystem(messages, documentsPrompt(documents)),
								new ThreadDocumentTool(query -> searchDocuments(activeTurn, query),
										(fileName, edits) -> reviseDocument(activeTurn, fileName, edits))))
				.onErrorResume(error -> {
					activeTurn.groundingNotice = GroundingNotice.UNAVAILABLE;
					log.error("방 문서 목록 조회 실패로 안내를 보낸다 threadId={} traceId={}", turn.threadId(), turn.traceId(), error);
					return Mono.just(new TurnPrompt(withSystem(messages, unavailablePrompt(webEnabled)), null));
				});
	}

	/**
	* 모델이 부른 방 문서 검색(#344)을 실행하고, 모델에 돌려줄 문장을 만든다. 도구 실행 스레드(boundedElastic)에서 불리므로
	* 결과를 기다려도 된다. 검색어는 모델이 대화를 보고 이미 독립 문장으로 썼으므로 이력을 넘기지 않는다(다시 쓰기 생략).
	*
	* 결과마다 돌려주는 문장과 웹 검색 사용 규칙이 다르다(이슈 #368). 웹 도구 자체는 늘 붙는다(LlmChatStreamService).
	* - FOUND: 근거와 함께 "문서 근거를 우선하고, 사용자가 웹 검색을 요청했으면 웹 검색 도구를 부르고, 요청하지 않았으면
	*   웹을 쓰지도 제안하지도 않는다"(FOUND_WEB_RULE). 찾은 청크를 activeTurn.citations에 더한다(이슈 #347).
	* - NO_EVIDENCE: 찾지 못했다는 사실만 돌려준다 — 웹을 쓸지는 모델이 판단한다.
	* - UNAVAILABLE·검색 호출 예외: 문서를 확인하지 못했다고 알리게 한다. 답변은 막지 않는다. 고장 원인은 넣지 않는다.
	*   웹이 켜진 배포에서는 웹 검색 여부를 묻게 하고 WEB_TOOL_HONESTY를 붙인다.
	* 웹이 꺼진 배포에서는 웹을 말하는 문장을 빼고 돌려준다.
	*
	* 화면 안내(GroundingNotice, 이슈 #353)는 이 턴에서 근거를 하나도 찾지 못했을 때만 남긴다 — 모델이 검색어를 바꿔 여러 번
	* 부르다 한 번이라도 찾으면 근거 없음이 아니다.
	*/
	private String searchDocuments(ActiveTurn activeTurn, String query) {
		PendingTurn turn = activeTurn.turn;
		SearchResult result;
		try {
			result = threadDocumentSearch.search(turn.threadId(), actorOf(turn), query, List.of(), modelIdFor(turn))
					.block(turnTimeout);
		} catch (RuntimeException error) {
			log.error("방 문서 검색 호출 실패로 안내를 보낸다 threadId={} traceId={}", turn.threadId(), turn.traceId(), error);
			result = null;
		}
		synchronized (activeTurn) {
			if (result != null && result.status() == SearchResult.Status.FOUND) {
				Set<String> seen = activeTurn.citations.stream().map(Citation::chunkId).collect(Collectors.toSet());
				List<Citation> citations = new ArrayList<>(activeTurn.citations);
				result.chunks().stream().map(this::toCitation).filter(citation -> seen.add(citation.chunkId()))
						.forEach(citations::add);
				activeTurn.citations = List.copyOf(citations);
				activeTurn.groundingNotice = null;
				return groundingPrompt(result, webEnabled);
			}
			boolean unavailable = result == null || result.status() == SearchResult.Status.UNAVAILABLE;
			if (activeTurn.citations.isEmpty()) {
				activeTurn.groundingNotice = unavailable ? GroundingNotice.UNAVAILABLE : GroundingNotice.NO_EVIDENCE;
			}
			if (unavailable && result != null) {
				log.warn("방 문서 검색을 끝내지 못해 근거 없이 답변하게 한다 threadId={} traceId={} reason={}",
						turn.threadId(), turn.traceId(), result.reason());
			} else if (!unavailable) {
				log.info("방 문서 근거를 못 찾았다 threadId={} traceId={} reason={}", turn.threadId(), turn.traceId(), result.reason());
			}
			return unavailable ? unavailablePrompt(webEnabled) : NO_EVIDENCE_RESULT;
		}
	}

	/**
	* 모델이 부른 개정본 만들기. 도구 실행 스레드(boundedElastic)에서 불린다. 결과는 모델에 돌려줄 문장이다.
	* 개정본은 한 답변에 하나만 등록한다 — 모델이 성공한 뒤 같은 도구를 다시 불러 _v1·_v2가 함께 생긴 일이 있었다.
	* 실패(FAILED)는 등록이 아니므로 다시 부를 수 있다. 같은 턴의 호출은 revisionLock으로 한 줄로 세운다.
	*/
	private String reviseDocument(ActiveTurn activeTurn, String fileName, List<ThreadDocumentTool.Edit> edits) {
		if (documentRevision == null) return ThreadDocumentRevision.FAILED + "이 배포에서는 개정본을 만들 수 없습니다.";
		PendingTurn turn = activeTurn.turn;
		synchronized (activeTurn.revisionLock) {
			if (activeTurn.revisionResult != null) {
				log.info("이 턴에서 이미 개정본을 등록해 다시 만들지 않는다 threadId={} traceId={}", turn.threadId(), turn.traceId());
				return ALREADY_REVISED + activeTurn.revisionResult;
			}
			log.info("개정본 만들기 threadId={} traceId={} edits={}", turn.threadId(), turn.traceId(), edits == null ? 0 : edits.size());
			String result = documentRevision.revise(turn.threadId(), actorOf(turn), fileName, edits);
			if (!result.startsWith(ThreadDocumentRevision.FAILED)) activeTurn.revisionResult = result;
			return result;
		}
	}

	/**
	* 이 턴의 새 문서 만들기 도구(#415). 고정 문서가 없는 방에도 붙고, 문서 만들기를 쓰지 않는 배포(documentCreation 없음)에서만 뺀다.
	* 같은 턴의 재호출을 알아보는 키는 턴 id이고, 없으면 추적 id, 그마저 없으면 사람 메시지 id다.
	*/
	private ThreadDocumentCreateTool createTool(ActiveTurn activeTurn) {
		if (documentCreation == null) return null;
		PendingTurn turn = activeTurn.turn;
		Object key = turn.turnId() != null ? turn.turnId() : turn.traceId() != null ? turn.traceId() : turn.humanMsgId();
		return new ThreadDocumentCreateTool(documentCreation, turn.threadId(), actorOf(turn), String.valueOf(key));
	}

	private static CurrentActor actorOf(PendingTurn turn) {
		return new CurrentActor(turn.fromUserId(), turn.fromSubject(), turn.fromDisplayName());
	}

	/**
	* 이 방의 검색할 수 있는 고정 문서와, 문서가 필요한 질문에만 검색 도구를 쓰라는 지시. 파일 이름만으로는 내용을 알기 어려워
	* 태그(#362)가 있으면 카테고리·키워드·요약을 함께 싣는다. 고정 문서가 많아도 지시가 길어지지 않게 태그는 앞의
	* MAX_TAGGED_DOCUMENTS개 문서에만, 요약은 SUMMARY_MAX_CHARS자까지만 싣는다. 태그는 문서를 고르는 데만 쓰게 한다 —
	* 요약만 보고 답하면 검색을 건너뛰어 출처가 붙지 않고, 요약에 없는 내용을 지어낼 수 있다. "요약을 근거로 쓰지 말라"고
	* 요약을 직접 가리키면 오히려 요약으로 답해(로컬 측정), 목록만 보고 답하지 말고 먼저 검색하라고 지시한다.
	*/
	static String documentsPrompt(List<SearchableDocument> documents) {
		StringBuilder prompt = new StringBuilder("이 방에 고정된 문서:\n");
		for (int i = 0; i < documents.size(); i++) {
			SearchableDocument document = documents.get(i);
			prompt.append("- ").append(document.fileName());
			if (i < MAX_TAGGED_DOCUMENTS) {
				List<String> tags = new ArrayList<>();
				if (document.category() != null) tags.add(document.category());
				if (!document.keywords().isEmpty()) tags.add("키워드: " + String.join(", ", document.keywords()));
				if (document.summary() != null && !document.summary().isBlank()) {
					String summary = document.summary().strip();
					tags.add("요약: " + (summary.length() > SUMMARY_MAX_CHARS ? summary.substring(0, SUMMARY_MAX_CHARS) + "…" : summary));
				}
				if (!tags.isEmpty()) prompt.append(" — ").append(String.join(" / ", tags));
			}
			prompt.append('\n');
		}
		return prompt + "목록만 보고 답하지 마세요. 이 문서들과 관련된 질문이면 답하기 전에 반드시 " + ThreadDocumentTool.NAME
				+ "를 먼저 호출하고, 검색 결과로만 답하세요. "
				+ "이 문서의 내용이 있어야 답할 수 있는 질문이면 " + ThreadDocumentTool.NAME + " 도구로 검색한 뒤 그 근거로 답하세요. "
				+ "문서가 필요 없는 질문에는 검색하지 마세요.";
	}

	/** 이번 발화(맨 끝 user) 바로 앞에 system 지시를 끼운다. */
	private static List<ChatMessage> withSystem(List<ChatMessage> messages, String prompt) {
		List<ChatMessage> withPrompt = new ArrayList<>(messages);
		withPrompt.add(messages.size() - 1, new ChatMessage("system", prompt));
		return withPrompt;
	}

	/** 문서 검색을 하지 못한 턴의 지시. 고장 원인(검색 엔진·모델 불일치·설정)은 넣지 않는다. */
	private static String unavailablePrompt(boolean webEnabled) {
		String prompt = "이번 질문에 대해 방에 고정된 문서를 검색하지 못했습니다. 문서 없이 답할 수 있는 일반 질문이면 그대로 답하세요. "
				+ "고정 문서의 내용이 필요한 질문이면 지금 문서를 확인할 수 없다고 알리세요.";
		if (!webEnabled) {
			return prompt;
		}
		return prompt + " 이때 웹 검색은 하지 말고, 답변 끝에 웹 검색으로 답변해 드릴지 물어보세요. "
				+ "사용자가 웹 검색을 요청했거나 앞선 제안에 응했으면 웹 검색으로 답하세요. " + WEB_TOOL_HONESTY;
	}

	/** 찾은 근거와, 근거를 우선하라는 지시. 웹은 사용자가 요청했을 때만 쓰고 먼저 제안하지 않게 한다(웹이 켜진 배포). */
	private static String groundingPrompt(SearchResult result, boolean webEnabled) {
		StringBuilder prompt = new StringBuilder(
				"다음은 이 방에 고정된 문서에서 찾은 근거입니다. 답변에 참고하되, 근거에 없는 내용은 추측하지 마세요. 답변은 이 문서 근거를 우선합니다.\n");
		for (SearchResult.Chunk chunk : result.chunks()) {
			prompt.append("- [").append(chunk.fileName()).append(' ').append(chunk.loc()).append("] ")
					.append(chunk.content()).append('\n');
		}
		// 웹 규칙은 근거 목록 뒤(이번 발화 바로 앞)에 둔다 — 근거 앞에 두면 근거에 묻혀, 요청받고도 도구를 부르지 않고
		// 웹에서 찾은 것처럼 답하는 경우가 있었다(이슈 #368).
		if (webEnabled) {
			prompt.append(FOUND_WEB_RULE);
		}
		return prompt.toString();
	}

	/** 화면·재접속 복구가 쓸 citations 형태로 좁힌다 — 점수는 통과한 채널 것(둘 다 있으면 벡터)을 쓴다. */
	private Citation toCitation(SearchResult.Chunk chunk) {
		Double score = chunk.vectorScore() != null ? chunk.vectorScore() : chunk.keywordScore();
		return new Citation(chunk.documentId().toString(), chunk.fileName(), chunk.content(),
				score == null ? 0.0 : score, chunk.chunkId(), chunk.loc());
	}

	/**
	* 이번 발화의 첨부는 turn.files()에서 붙인다 — COLLAB은 문맥을 이번 메시지 저장 전에 읽어(이슈 #100)
	* 문맥에 이번 메시지가 없다. DIRECT는 이미 저장된 이번 메시지가 문맥에 들어 있어 그쪽에서 첨부가
	* 붙으므로, 같은 파일을 두 번 싣지 않도록 여기서는 뺀다.
	*/
	private static List<ChatMessage> buildPromptMessages(List<Msg> context, Map<UUID, List<MsgFile>> files,
			PendingTurn turn) {
		List<ChatMessage> messages = new ArrayList<>(context.size() + 2);
		// 역할별 페르소나(이슈 #357)는 대화 이력보다 앞에 둬 전체 발화를 그 역할의 관점으로 보게 한다.
		if (turn.agent() != null) {
			messages.add(new ChatMessage("system", turn.agent().systemPrompt()));
		}
		boolean humanInContext = false;
		for (Msg msg : context) {
			// 입퇴장 줄(MembershipNotice)은 본문이 없고 대화도 아니라 모델에 넘기지 않는다.
			if (msg.getAthKind() == AthKind.SYSTEM && msg.getPylJson() != null) {
				continue;
			}
			String role = msg.getAthKind() == AthKind.HUMAN ? "user"
					: msg.getAthKind() == AthKind.SYSTEM ? "system" : "assistant";
			messages.add(new ChatMessage(role, MsgFileService.withFiles(msg.getContent(), files.get(msg.getId()))));
			humanInContext |= msg.getId().equals(turn.humanMsgId());
		}
		messages.add(new ChatMessage("user",
				humanInContext ? turn.prompt() : MsgFileService.withFiles(turn.prompt(), turn.files())));
		return messages;
	}

	private Flux<String> withTotalDeadline(Flux<String> source) {
		return Flux.defer(() -> {
			AtomicBoolean sourceCompleted = new AtomicBoolean();
			return source.doOnComplete(() -> sourceCompleted.set(true))
					.take(turnTimeout, deadlineScheduler)
					.concatWith(Flux.defer(() -> sourceCompleted.get()
							? Flux.empty()
							: Flux.error(new TurnTimeoutException())));
		});
	}

	/**
	* 조각 하나를 방송한다. offset은 이 조각이 답변의 몇 번째 글자(UTF-16 단위)부터인지다 — 조각을 content에 붙이기 전
	* 길이(이슈 #413 결정 3). 화면은 이미 가진 범위를 버려, 들어올 때 받은 지금까지 내용과 겹쳐도 글자가 중복되지 않는다.
	* 앞쪽 공백 조각은 모아 뒀다가 첫 글자와 함께 내보내므로 위치도 함께 들고 있는다.
	*/
	private void broadcastDelta(ActiveTurn activeTurn, String delta, int offset) {
		if (delta.isBlank() && !activeTurn.hasNonBlankOutput.get()) {
			activeTurn.leadingWhitespace.addLast(new Chunk(offset, delta));
			return;
		}
		if (!activeTurn.hasNonBlankOutput.getAndSet(true)) {
			while (!activeTurn.leadingWhitespace.isEmpty()) {
				Chunk chunk = activeTurn.leadingWhitespace.removeFirst();
				broadcastStreamingFrame(activeTurn, chunk.delta(), chunk.offset());
			}
		}
		broadcastStreamingFrame(activeTurn, delta, offset);
	}

	/** 방에 아무도 없어 버려져도(false) 턴은 이어 간다(이슈 #413). 대기열이 꽉 차 던지면 방송 통로 고장으로 본다. */
	private void broadcastStreamingFrame(ActiveTurn activeTurn, String delta, int offset) {
		try {
			roomSessionRegistry.broadcastToRoom(activeTurn.turn.threadId(),
					new ChatAnswerFrame(activeTurn.turn.threadId(), activeTurn.msgId, activeTurn.turn.turnId(),
							modelIdFor(activeTurn.turn), activeTurn.seq, delta, List.of(), false,
							ChatAnswerStatus.STREAMING, activeTurn.groundingNotice, activeTurn.turn.agent(), offset, null,
							null, activeTurn.turn.fromSubject()));
		} catch (RuntimeException error) {
			throw new RoomBroadcastFailureException(error);
		}
	}

	/**
	* 답변이 끝났다. 끝 프레임에 최종 전체 내용을 실어(이슈 #413 결정 12) 방에 아무도 없어도 저장하고 다음 턴으로 간다.
	* 끝 프레임 방송이 실패해도(대기열이 꽉 차 예외를 받음) 응답 생성 자체는 이미 끝난 뒤라, 방을 실패로 닫기 전에
	* COMPLETE로 저장한다 — persistAgentCompletion()의 CAS 가드가 이후 실패 저장 시도를 무시하게 만든다.
	*/
	private void handleTurnComplete(RoomAiState state, ActiveTurn activeTurn, String content) {
		if (!activeTurn.claimTerminal()) return;
		List<Citation> citations = answerCitations(activeTurn);
		try {
			roomSessionRegistry.broadcastToRoom(activeTurn.turn.threadId(),
					activeTurn.end(endFrame(activeTurn, ChatAnswerStatus.DONE, content, citations)));
		} catch (RuntimeException ignored) {
			persistAgentCompletion(activeTurn, content, citations);
			closeRoom(state, Closing.FAILURE);
			return;
		}
		persistAgentCompletion(activeTurn, content, citations);
		advance(state, activeTurn);
	}

	private void handleTurnError(RoomAiState state, ActiveTurn activeTurn, Throwable error) {
		if (error instanceof RoomBroadcastFailureException) {
			closeRoom(state, Closing.FAILURE);
			return;
		}
		if (!activeTurn.claimTerminal()) return;

		// 턴 제한 초과는 모델 오류와 문구를 가른다(이슈 #389) — MODEL_UNAVAILABLE이면 화면이 API 키 안내를 띄운다.
		String code = error instanceof TurnTimeoutException ? "AI_TURN_TIMEOUT"
				: error instanceof OpenAIServiceException || error instanceof EmptyLlmOutputException
						? "MODEL_UNAVAILABLE" : "INTERNAL_ERROR";
		String message = switch (code) {
			case "AI_TURN_TIMEOUT" -> "응답 시간 제한을 넘었습니다.";
			case "MODEL_UNAVAILABLE" -> "모델을 호출할 수 없습니다.";
			default -> "AI 응답 처리 중 오류가 발생했습니다.";
		};
		// 이 경로는 원래 로그가 없다 — 턴 제한 초과는 어디에도 남지 않아 화면의 traceId로 찾을 수 없었다(이슈 #389).
		log.warn("AI 턴 실패 threadId={} traceId={} code={} cause={}", activeTurn.turn.threadId(),
				activeTurn.turn.traceId(), code, error.getClass().getSimpleName());
		// 실패는 받은 내용을 버리고 FAILED로 저장한다(S5 결정 3). 방에 아무도 없어 오류 프레임이 버려져도 저장하고
		// 다음 턴으로 간다 — 돌아온 사람은 이력에서 "답변을 만들지 못했습니다"로 본다(이슈 #413 결정 8).
		persistAgentFailure(activeTurn, MsgStatus.FAILED);
		try {
			// 어느 답이 실패했는지는 msgId를 단 failed 끝 프레임으로 알린다(받은 내용은 버려 content가 빈다). 오류 프레임은
			// 배너 문구용이다 — 거기엔 msgId가 없어 화면이 다른 답을 실패로 오인할 수 있었다(이슈 #413 리뷰).
			roomSessionRegistry.broadcastToRoom(activeTurn.turn.threadId(),
					activeTurn.end(endFrame(activeTurn, ChatAnswerStatus.FAILED, "", List.of())));
			roomSessionRegistry.broadcastToRoom(activeTurn.turn.threadId(),
					new ErrorFrame(activeTurn.turn.threadId(), code, message, activeTurn.turn.traceId()));
		} catch (RuntimeException ignored) {
			closeRoom(state, Closing.FAILURE);
			return;
		}
		advance(state, activeTurn);
	}

	private void advance(RoomAiState state, ActiveTurn finishedTurn) {
		ActiveTurn nextTurn = null;
		PendingTurn next;
		synchronized (state) {
			if (state.closed || state.active != finishedTurn) {
				return;
			}
			next = state.pending.peekFirst();
			if (next != null) {
				try {
					nextTurn = new ActiveTurn(next, state.seqBlock);
				} catch (RuntimeException failure) {
					// 다음 턴을 만들지 못했다(seq 블록 예약 등 DB 실패). 그대로 두면 끝난 턴이 active로 남아 방이 영영 할 일이
					// 있는 상태가 된다 — 예전엔 연결이 끊길 때 세대를 닫아 정리됐지만 이제 그 경로가 없다(이슈 #413 리뷰).
					log.error("다음 AI 턴을 시작하지 못했다 threadId={} traceId={}", state.threadId, next.traceId(),
							failure);
				}
				if (nextTurn != null) {
					state.pending.pollFirst();
				}
			}
			if (next == null || nextTurn != null) {
				state.active = nextTurn;
			}
		}
		if (next == null) {
			closeIfIdle(state);
			return;
		}
		if (nextTurn == null) {
			closeRoom(state, Closing.FAILURE);
			return;
		}
		startTurn(state, nextTurn);
	}

	/**
	* 할 일이 없고(진행·대기 턴, 워커가 꺼내지 않은 메시지 모두 없음) 방에 아무도 없으면 상태를 지운다(이슈 #413).
	* 방이 빈 때(roomVacated)와 일이 하나 끝날 때(advance·워커) 부른다. 방 확인은 registry 맵 읽기 하나라 잠금 순서가
	* 생기지 않는다. 늦게 보거나 일찍 봐도 할 일이 없을 때만 지우므로, 다음 발화는 새 상태를 만든다.
	*/
	private void closeIfIdle(RoomAiState state) {
		synchronized (state) {
			if (state.closed || state.active != null || !state.pending.isEmpty() || state.queuedMessages > 0
					|| roomSessionRegistry.hasRoom(state.threadId)) {
				return;
			}
			state.closed = true;
			states.remove(state.threadId, state);
		}
		state.inbox.tryEmitComplete();
		state.worker.dispose();
	}

	/**
	* 방의 AI 작업을 일이 남은 채로 닫는다.
	* - FAILURE: 방송 통로 고장·서버 종료 — 사람이 멈춘 것이 아니라 진행 턴과 1:1 예약 행을 FAILED로 남긴다(이슈 #413
	*   결정 10). 남은 대기 턴이 있었으면 취소를 한 번 알린다.
	* - DELETED: 방이 지워졌다 — 저장할 행이 없어 끊기만 한다.
	* 돌려주는 Mono는 시작된 실패 저장이다 — 서버 종료 때만 기다린다.
	*/
	private List<Mono<Void>> closeRoom(RoomAiState state, Closing closing) {
		ActiveTurn activeTurn;
		List<PendingTurn> pendingTurns;
		List<InFlightTurn> inFlightTurns;
		boolean waitingTurnsCancelled;
		synchronized (state) {
			if (state.closed) {
				return List.of();
			}
			state.closed = true;
			states.remove(state.threadId, state);
			// pending만 보면 알림을 놓친다(이슈 #206). dispatch()는 inbox에 넣기만 하고 돌아오므로,
			// 워커가 아직 꺼내지 않은 @AI 발화는 pending에 없고 inFlight에만 있다. 그 상태에서 앞 턴이
			// 방송 실패로 방을 닫으면 그 발화는 조용히 사라지고 보낸 사람은 아무 설명도 못 받았다.
			waitingTurnsCancelled = !state.pending.isEmpty() || state.unprocessedAiTurns > 0;
			pendingTurns = new ArrayList<>(state.pending);
			inFlightTurns = new ArrayList<>(state.inFlight.values());
			state.pending.clear();
			state.inFlight.clear();
			activeTurn = state.active;
			state.active = null;
		}
		// 큐에 남은 메시지는 처리할 통로가 막혔거나(실패) 방이 없다(삭제) — 워커째로 정리한다.
		state.inbox.tryEmitComplete();
		state.worker.dispose();
		if (activeTurn != null) {
			activeTurn.subscription.dispose();
		}
		if (closing == Closing.DELETED) {
			// 저장할 행은 없지만, 아직 붙어 있는 연결의 말풍선이 "생성 중"에 남지 않게 중지를 한 번 알린다.
			if (activeTurn != null && activeTurn.claimTerminal()) {
				broadcastQuietly(state.threadId, endFrame(activeTurn, ChatAnswerStatus.CANCELLED,
						activeTurn.content.toString(), List.of()));
			}
			return List.of();
		}
		List<Mono<Void>> persistences = new ArrayList<>();
		if (activeTurn != null && activeTurn.claimTerminal()) {
			persistences.add(persistAgentFailure(activeTurn, MsgStatus.FAILED));
		}
		if (state.kind == ThrKind.DIRECT) {
			pendingTurns.forEach(turn -> {
				if (turn.reservedTurn() != null) {
					persistences.add(persistDirectReservedAgentTerminal(turn.reservedTurn(), turn.terminalPersisted(),
							MsgStatus.FAILED));
				}
			});
			inFlightTurns.forEach(turn -> {
				ChatMessageCommand.ReservedTurn reserved = turn.queued().command().reservedTurn();
				if (reserved != null) {
					persistences.add(persistDirectReservedAgentTerminal(reserved, turn.queued().terminalPersisted(),
							MsgStatus.FAILED));
				}
			});
		}
		if (waitingTurnsCancelled) {
			try {
				roomSessionRegistry.broadcastToRoom(state.threadId, new ErrorFrame(state.threadId,
						"MESSAGE_DELIVERY_FAILED", "대기 중인 AI 요청이 취소되었습니다.", UUID.randomUUID().toString()));
			} catch (RuntimeException ignored) {
				// The notification uses the failed broadcast path once and is intentionally not retried.
			}
		}
		return persistences;
	}

	/**
	* 방송이 이미 끝난 뒤 fire-and-forget으로 저장한다 — Mono.fromCallable(...).subscribeOn(...)은
	* 스케줄만 하고 즉시 반환되므로, 이 메서드를 synchronized(state) 블록 안에서 불러도 락을
	* 블로킹하지 않는다. 저장 실패는 로그만 남기고 삼킨다(채팅 자체를 막지 않는다).
	*/
	private void persistHumanMessageAsync(UUID msgId, long seq, ChatMessageCommand command) {
		Mono.fromCallable(() -> msgPersistenceService.persistHumanMessageBlocking(msgId, seq,
					command.threadId(), command.from(), command.content(), command.fileIds()))
				.subscribeOn(Schedulers.boundedElastic())
				.doOnError(e -> log.error("HUMAN 메시지 저장 실패 threadId={} traceId={}", command.threadId(),
						command.traceId(), e))
				.onErrorComplete()
				.subscribe();
	}

	/**
	* {@code @AI} 멘션 발화는 문맥 조회 결과가 LLM 호출의 입력이 되므로, 결과를 캐시해 startTurn()이
	* 기다렸다가 쓸 수 있게 한다(persistHumanMessageAsync와 달리 fire-and-forget이 아니다). 조회+저장
	* 순서는 MsgPersistenceService 쪽에서 한 트랜잭션으로 보장한다 — 이 발화 자신이 문맥에 중복으로
	* 끼지 않도록.
	*
	* 여기서 구독하지 않는다(이슈 #160). 턴이 받아들여지지 않으면(시작 전 취소·대기열 초과) 문맥을
	* 읽을 이유가 없어 호출부가 persistHumanMessageAsync로 갈음한다 — 둘 중 하나만 구독해야 사람
	* 메시지가 두 번 저장되지 않는다.
	*/
	private Mono<List<Msg>> persistHumanMessageAndFetchContext(UUID msgId, long seq, ChatMessageCommand command) {
		return Mono
				.fromCallable(() -> msgPersistenceService.persistHumanMessageAndFetchContextBlocking(
						msgId, seq, command.threadId(), command.from(), command.content(), command.fileIds(),
						maxContextMessages))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("문맥 조회 및 HUMAN 메시지 저장 실패 threadId={} traceId={}", command.threadId(),
							command.traceId(), e);
					return Mono.just(List.<Msg>of());
				})
				.cache();
	}

	/**
	* PENDING 생성이 실패해 pendingMsgId가 비어있으면(empty) 완료 저장도 조용히 건너뛴다.
	* terminalPersisted CAS로 같은 턴에 대해 완료/실패 저장이 두 번 이상 시도되는 것을 막는다 —
	* completed_at·status 전이는 한 번만 유효하고, DB 트리거도 두 번째 UPDATE를 거부한다.
	*/
	private void persistAgentCompletion(ActiveTurn activeTurn, String content, List<Citation> citations) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		String citationsJson = citationsJson(citations);
		String agentJson = Agent.toPylJson(json, activeTurn.turn.agent());
		activeTurn.pendingMsgId
				.flatMap(msgId -> Mono
						.fromRunnable(() -> msgPersistenceService.completeBlocking(msgId, content, citationsJson, agentJson))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 완료 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete()
				.subscribe();
	}

	/**
	* 답변에 남길 근거 — 방 문서 근거 뒤에 이 턴의 웹 출처(이슈 #401)를 붙인다. 완료 프레임과 저장이 같은 값을 쓴다.
	* 웹 출처는 답변이 끝날 때 한 번에 싣는다 — 방 문서 근거처럼 STREAMING·취소 프레임에는 없다. 턴이 끝날 때 한 번만 만들어
	* 프레임과 저장에 같이 넘긴다 — 그 사이 늦게 끝난 도구가 출처를 더해도 둘이 어긋나지 않는다.
	* 웹 출처가 있으면 개수만 로그에 남긴다(주소·검색어는 남기지 않는다) — "새로고침하니 출처가 없다"를 진단할 때
	* 모은 수와 남긴 수(상한 20)를 본다.
	*/
	private static List<Citation> answerCitations(ActiveTurn activeTurn) {
		List<WebSources.Source> web = activeTurn.webSources.sources();
		if (web.isEmpty()) return activeTurn.citations;
		log.info("웹 출처 threadId={} traceId={} collected={} kept={} read={}", activeTurn.turn.threadId(),
				activeTurn.turn.traceId(), activeTurn.webSources.collected(), web.size(),
				web.stream().filter(WebSources.Source::read).count());
		List<Citation> citations = new ArrayList<>(activeTurn.citations);
		for (WebSources.Source source : web) {
			citations.add(Citation.web(source.url(), source.title(), source.snippet(), source.query(), source.read()));
		}
		return List.copyOf(citations);
	}

	/** 비어 있으면 null — 재접속 복구(ThreadWebSocketHandler)가 null을 "출처 없음"으로 본다. */
	private String citationsJson(List<Citation> citations) {
		if (citations.isEmpty()) return null;
		try {
			return json.writeValueAsString(citations);
		} catch (RuntimeException e) {
			log.error("citations 직렬화 실패", e);
			return null;
		}
	}

	private Mono<Void> persistAgentFailure(ActiveTurn activeTurn, MsgStatus terminalStatus) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return Mono.empty();
		}
		return started(activeTurn.pendingMsgId
				.flatMap(msgId -> Mono.<Void>fromRunnable(() -> msgPersistenceService.failBlocking(msgId, terminalStatus))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 실패 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete());
	}

	/** 중단된 턴을 그때까지 생성된 내용과 함께 CANCELLED로 닫는다. 내용은 구독을 끊은 뒤 읽는다. */
	private void persistAgentCancellation(ActiveTurn activeTurn) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		String partialContent = activeTurn.content.toString();
		activeTurn.pendingMsgId
				.flatMap(msgId -> Mono.fromRunnable(() -> msgPersistenceService.cancelBlocking(msgId, partialContent))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 취소 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete()
				.subscribe();
	}

	/**
	* 아직 메모리에 있는 답변(liveAnswer) — 지금까지 내용과, 이미 끝 프레임을 냈으면 그 프레임(아니면 null).
	*/
	record LiveAnswer(String content, ChatAnswerFrame endFrame) {
	}

	/** 일이 남은 채로 방의 AI 작업을 닫는 까닭(closeRoom). 할 일이 없을 때 지우는 것은 closeIfIdle이다. */
	private enum Closing {
		FAILURE, DELETED
	}

	/** 아직 내보내지 않은 앞쪽 공백 조각과 그 위치(broadcastDelta). */
	private record Chunk(int offset, String delta) {
	}

	/**
	* 취소가 턴을 찾는 키(이슈 #160). turnId만으로는 믿지 않고 그 발화를 보낸 사람과 짝으로 쓴다 —
	* 이유는 cancel() 주석에 있다. 발화에 turnId가 없으면 null이고, 그 턴은 취소할 수 없다.
	*/
	private record TurnRef(UUID turnId, String subject) {

		static TurnRef of(UUID turnId, String subject) {
			return turnId == null ? null : new TurnRef(turnId, subject);
		}
	}

	/**
	* 방 하나의 seq 공급원. 블록을 예약해두고 메모리에서 꺼내 쓰다가, 다 쓰면 그때만 DB를 부른다.
	*
	* 워커(사람 메시지)와 턴 시작(AI 답변) 두 경로가 부르므로 잠근다 — 다만 정상 경로는 메모리
	* 연산뿐이라 잡는 시간이 거의 없고, DB를 부르는 것은 SEQ_BLOCK_SIZE건에 한 번이다.
	*/
	private final class SeqBlock {

		private final UUID threadId;

		private long next;

		private int remaining;

		SeqBlock(UUID threadId) {
			this.threadId = threadId;
		}

		synchronized long allocate() {
			if (remaining == 0) {
				next = msgPersistenceService.allocateSeqBlockBlocking(threadId, SEQ_BLOCK_SIZE);
				remaining = SEQ_BLOCK_SIZE;
			}
			remaining--;
			return next++;
		}
	}

	/** 워커가 꺼내 처리할 한 건. prompt가 null이면 AI 턴을 만들지 않는다(일반 발화·빈 프롬프트). */
	private record QueuedMessage(ChatMessageCommand command, String prompt, Agent agent,
			AtomicBoolean terminalPersisted) {
	}

	/** inbox에 들어간 예약 DIRECT 턴은 worker가 꺼내기 전에도 terminal 상태를 공유해야 한다. */
	private record InFlightTurn(QueuedMessage queued, boolean cancelled) {

		InFlightTurn cancel() {
			return new InFlightTurn(queued, true);
		}
	}

	/**
	* ref는 취소 지목 키다(이슈 #160). model이 null이면 서버 기본값을 쓴다. reservedTurn은 DIRECT만
	* 채운다 — `DirectChatTurnService`가 이미 만든 PENDING AGENT를 가리킨다(이슈 #162). humanMsgId·files는
	* 이 턴을 부른 발화의 msg id와 첨부다 — 프롬프트에 첨부를 붙일 때 쓴다. timeZone은 그 발화자의 브라우저
	* 시간대다 — 협업방에서도 턴을 부른 사람 기준으로 현재 시각 도구가 답한다. agent는 `@SOL`·`@FIN`·
	* `@LAW`로 멘션됐을 때만 채워진다(이슈 #357) — `@AI`거나 DIRECT면 null이다.
	*/
	private record PendingTurn(UUID threadId, String prompt, UUID humanMsgId,
			List<MsgFile> files, String traceId, UUID fromUserId, String fromSubject, String fromDisplayName,
			TurnRef ref, String model, String timeZone, Mono<List<Msg>> context,
			ChatMessageCommand.ReservedTurn reservedTurn, AtomicBoolean terminalPersisted, Agent agent) {

		UUID turnId() {
			return ref == null ? null : ref.turnId();
		}
	}

	private final class ActiveTurn {

		private final PendingTurn turn;

		private final Disposable.Swap subscription = Disposables.swap();

		private final AtomicBoolean hasNonBlankOutput = new AtomicBoolean();

		private final AtomicBoolean terminalClaimed = new AtomicBoolean();

		private final Deque<Chunk> leadingWhitespace = new ArrayDeque<>();

		/** 지금 실행 중인 도구(이슈 #413 결정 7) — 들어온 화면에 진행 표시를 바로 보내려고 하나만 기억한다. 도구 실행
		 * 스레드가 쓰고 구독 스레드가 읽는다. */
		private volatile ToolActivity currentActivity;

		/** 지금까지 생성된 답변. 스트림 스레드가 쓰고 취소하는 스레드가 읽어서 StringBuffer다(이슈 #160). */
		private final StringBuffer content = new StringBuffer();

		/** 모델이 부른 방 문서 검색(FOUND)으로 찾은 근거(이슈 #347). 도구 실행 스레드가 activeTurn을 잡고 새 목록으로
		 * 바꾸고, 완료 프레임이 읽는다. */
		private volatile List<Citation> citations = List.of();

		/** 이 턴의 웹 출처(이슈 #401). 웹 도구가 toolContext로 받아 성공한 검색·읽기를 남기고, 완료 프레임·저장이 꺼낸다.
		 * 턴마다 새로 만들어 다른 턴과 섞이지 않는다. */
		private final WebSources webSources = new WebSources();

		/** 이 턴에서 방 문서 근거를 찾지 못했을 때 STREAMING·DONE 프레임에 함께 실을 안내(이슈 #353). 문서 목록 조회 실패면
		 * LLM 호출 전에, 검색 도구가 근거를 못 찾으면 그 호출 뒤에 정해져 이후 프레임부터 실린다. citations와 같이 volatile이다. */
		private volatile GroundingNotice groundingNotice;

		/** 이 턴의 개정본 만들기를 한 줄로 세운다. revisionResult는 이 잠금 안에서만 읽고 쓴다. */
		private final Object revisionLock = new Object();

		/** 이 턴에서 등록에 성공한 개정본 만들기의 결과 문장. 없으면 아직 등록하지 않았다. */
		private String revisionResult;

		/**
		* PENDING 행 생성 결과(성공 시 msgId, 실패 시 empty)를 캐시해 여러 번 구독해도 한 번만
		* 실행한다 — startTurn()이 즉시 fire-and-forget으로 시작하고, 완료/실패 저장은 이 Mono가
		* 끝날 때까지 기다렸다가 msgId를 얻는다.
		*/
		private final Mono<UUID> pendingMsgId;

		/** 이 턴의 AGENT 메시지 id·seq. 턴이 시작되는 순간 확정되므로 답변이 대화에서 차지할
		 * 자리도 그때 정해진다 — 뒤따라 도착한 사람 메시지가 이 답변 앞으로 끼어들지 않는다. */
		private final UUID msgId;

		private final long seq;

		/** 완료/실패 저장이 이 턴에 대해 이미 한 번 시도됐는지 — 두 번째 시도는 조용히 건너뛴다. */
		private final AtomicBoolean terminalPersisted;

		/** 이 턴의 도구 실행 기록(이슈 #390). 턴마다 새로 만들어 다른 턴과 섞이지 않는다 — 끝난 턴의 도구가 늦게
		 * 끝나도 그 턴의 것에만 쌓인다. */
		private final TurnToolExecutions toolExecutions;

		/** 이 턴이 낸 끝 프레임(완료·중지). 다음 턴으로 넘어가기 전에 들어온 연결·1:1 재전송에 다시 준다(이슈 #413). */
		private volatile ChatAnswerFrame endFrame;

		boolean claimTerminal() {
			return terminalClaimed.compareAndSet(false, true);
		}

		/** 끝 프레임을 기억하고 그대로 돌려준다. */
		ChatAnswerFrame end(ChatAnswerFrame frame) {
			endFrame = frame;
			return frame;
		}

		/**
		* 이 턴의 도구 실행 시작·끝을 방에 활동 프레임으로 알린다(이슈 #397). 이미 끝난 턴(완료·중지·오류)의 늦은
		* 알림은 보내지 않는다 — 끝난 턴에서도 이미 요청된 도구는 끝까지 돌기 때문이다(#390). 진행 표시는 화면 힌트라
		* 방송이 아니라 부가 통보로 보낸다(notifyRoom) — 넘치거나 전송이 실패하면 버려질 뿐 답변 세대를 닫지 않고,
		* 방송 대기열 자리도 먹지 않는다. 버려진 "끝"은 화면이 답변 종료·종결 오류·연결 끊김에서 지운다.
		*/
		private final class ActivityBroadcaster implements ToolExecutionListener {

			@Override
			public void started(String toolName, String input) {
				ToolActivity.of(toolName, input, json).ifPresent(activity -> broadcast(activity, true));
			}

			@Override
			public void finished(ToolExecution execution) {
				ToolActivity.of(execution.toolName(), execution.input(), json)
						.ifPresent(activity -> broadcast(activity, false));
			}

			private void broadcast(ToolActivity activity, boolean active) {
				currentActivity = active ? activity : null;
				if (terminalClaimed.get()) return;
				send(activity, active);
				// 위 확인과 넣기 사이에 턴이 끝났으면 "시작"이 종료 프레임 뒤에 들어갔을 수 있다 — 그러면 화면은 지울
				// 계기를 다시 못 받아(그 뒤 "끝"은 위에서 막힌다) 표시가 남는다. 다시 보고 끝났으면 "끝"을 바로 잇는다.
				if (active && terminalClaimed.get()) {
					send(activity, false);
				}
			}

			private void send(ToolActivity activity, boolean active) {
				// 턴이 시작될 때의 방 세대가 아니라 지금 방으로 보낸다 — 비었다 다시 붙은 화면에도 가야 한다(이슈 #413).
				roomSessionRegistry.notifyRoom(turn.threadId(),
						new ChatActivityFrame(turn.threadId(), msgId, turn.turnId(), activity.kind(), activity.target(),
								active, turn.agent()));
			}
		}

		ActiveTurn(PendingTurn turn, SeqBlock seqBlock) {
			this.turn = turn;
			this.terminalPersisted = turn.terminalPersisted();
			this.toolExecutions = new TurnToolExecutions(turn.threadId(), turn.traceId(),
					List.of(new ActivityBroadcaster()));
			if (turn.reservedTurn() != null) {
				// DIRECT — PENDING AGENT는 DirectChatTurnService가 이미 만들어뒀다(이슈 #162).
				// 새로 만들지 않고 그 msgId·seq를 그대로 쓴다.
				this.msgId = turn.reservedTurn().agentMsgId();
				this.seq = turn.reservedTurn().agentSeq();
				this.pendingMsgId = Mono.just(this.msgId).cache();
			} else {
				this.msgId = UUID.randomUUID();
				this.seq = seqBlock.allocate();
				this.pendingMsgId = Mono.fromCallable(() -> msgPersistenceService.createPendingAgentMessageBlocking(
							this.msgId, this.seq, turn.threadId()))
						.subscribeOn(Schedulers.boundedElastic())
						.map(Msg::getId)
						.onErrorResume(e -> {
							log.error("PENDING agent 메시지 생성 실패 threadId={}", turn.threadId(), e);
							return Mono.empty();
						})
						.cache();
			}
		}
	}

	/**
	* 방 하나의 상태 — 방송을 기다리는 메시지 큐와 LLM 실행을 기다리는 AI 턴 FIFO를 함께 든다.
	*
	* 두 큐를 합치지 않는다(이슈 #190). AI 턴은 스트리밍이 끝날 때까지 살아 있고 일반 메시지는
	* 방송 한 번으로 끝나는데, 한 큐에 넣으면 긴 턴이 뒤따르는 짧은 메시지를 통째로 막는다.
	*/
	private final class RoomAiState {

		/** 방송을 기다리는 메시지. 가득 차면 tryEmitNext가 실패해 호출자가 RATE_LIMITED를 받는다. */
		private final Sinks.Many<QueuedMessage> inbox = Sinks.many().unicast()
				.onBackpressureBuffer(new ArrayBlockingQueue<QueuedMessage>(MAX_QUEUED_MESSAGES_PER_ROOM));

		private final Deque<PendingTurn> pending = new ArrayDeque<>();

		/**
		* 큐에 넣었지만 워커가 아직 꺼내지 않은 {@code @AI} 발화(이슈 #160). 값이 true면 그 사이에 취소됐다.
		* 워커가 꺼낼 때 지운다.
		*/
		private final Map<TurnRef, InFlightTurn> inFlight = new HashMap<>();

		/**
		* 큐에 넣었지만 워커가 아직 처리하지 않은 {@code @AI} 발화 수(이슈 #206). 방이 닫힐 때
		* "취소된 요청이 있었는가"를 판정하는 데 쓴다 — 그 발화는 pending에 아직 없기 때문이다.
		*
		* 위 inFlight로 갈음하지 않는 것은 그쪽이 turnId를 실은 발화만 담기 때문이다
		* (TurnRef.of는 turnId가 null이면 null). turnId 없는 {@code @AI} 발화도 유효한 계약이라
		* (frames.ts) 그쪽만 보면 같은 구멍이 남는다.
		*/
		private int unprocessedAiTurns;

		/**
		* 큐에 넣었지만 워커가 아직 처리를 끝내지 않은 메시지 수 — 일반 발화까지 센다(이슈 #413). 워커가 꺼내 처리해야
		* 방송·저장되므로, 방에 아무도 없어도 이것이 0이 될 때까지 상태를 지우지 않는다(closeIfIdle).
		*/
		private int queuedMessages;

		private final UUID threadId;

		private final SeqBlock seqBlock;

		/** 방이 처음 만들어질 때의 발화가 정한 종류(이슈 #162) — cancel()이 DIRECT·COLLAB 인가
		 * 방식을 가르는 데 쓴다. 방 하나의 kind는 그 수명 내내 바뀌지 않는다. */
		private final ThrKind kind;

		/**
		* 이 방의 유일한 워커. prefetch 1로 두어 한 번에 한 건만 꺼내 처리한다 — 방송 순서가
		* 여기서 확정된다. 한 건이 실패해도 방 전체를 죽이지 않도록 예외를 삼킨다.
		*/
		private final Disposable worker;

		/** 다른 방의 턴을 셀 때(logConcurrentTurns) 잠금 없이 읽어 volatile이다. 쓰기는 늘 잠금 안이다. */
		private volatile ActiveTurn active;

		private boolean closed;

		RoomAiState(UUID threadId, ThrKind kind) {
			this.threadId = threadId;
			this.seqBlock = new SeqBlock(threadId);
			this.kind = kind;
			this.worker = inbox.asFlux()
					.publishOn(Schedulers.boundedElastic(), 1)
					.subscribe(queued -> {
						try {
							process(this, queued);
						} catch (RuntimeException error) {
							// 처리 중 예외(사람 메시지 seq 예약·턴 생성의 DB 실패 등)면 그 발화의 대기 등록이 남아 방이 영영 비지 않는다.
							// 방송·저장 통로가 막힌 것과 같게 방의 AI 작업을 실패로 닫는다(이슈 #413 리뷰).
							log.error("방 워커가 메시지를 처리하지 못했다 threadId={}", threadId, error);
							closeRoom(this, Closing.FAILURE);
						} finally {
							synchronized (this) {
								queuedMessages -= 1;
							}
							closeIfIdle(this);
						}
					}, error -> {
						log.error("방 워커가 비정상 종료했다 threadId={}", threadId, error);
						closeRoom(this, Closing.FAILURE);
					});
		}
	}

	private static final class TurnTimeoutException extends RuntimeException {
	}

	private static final class EmptyLlmOutputException extends RuntimeException {
	}

	private static final class RoomBroadcastFailureException extends RuntimeException {

		RoomBroadcastFailureException(Throwable cause) {
			super(cause);
		}
	}

}
