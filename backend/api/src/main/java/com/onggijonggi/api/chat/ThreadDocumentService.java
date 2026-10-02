package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadDocumentService.java
 * Description : Thread 문서의 원본 예약·등록·고정·삭제·업무 이력을 처리한다. 호출부는 boundedElastic에서 실행한다.
 *               원본 통신은 DB 예약 뒤에 수행하고, 늦은 업로드·방 삭제에도 정리 참조를 잃지 않는다.
 */
@Service
public class ThreadDocumentService {
	public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
	private static final Logger log = LoggerFactory.getLogger(ThreadDocumentService.class);
	private static final Set<String> EXTENSIONS = Set.of("txt", "md", "csv", "pdf", "docx");
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ThrRepository threads;
	private final ThrMbrRepository memberships;
	private final AppUserRepository users;
	private final WorkspaceAuthorizer authorizer;
	private final ThreadSourceStorage storage;
	@PersistenceContext private EntityManager entityManager;
	@Value("${app.document.cleanup-enabled:true}") private boolean cleanupEnabled;

	public ThreadDocumentService(JdbcTemplate jdbc, PlatformTransactionManager manager, ThrRepository threads,
			ThrMbrRepository memberships, AppUserRepository users, WorkspaceAuthorizer authorizer, ThreadSourceStorage storage) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
		this.threads = threads;
		this.memberships = memberships;
		this.users = users;
		this.authorizer = authorizer;
		this.storage = storage;
	}

	private record Access(Thr thread, boolean owner, UUID user) {
		boolean writable() { return thread.getStatus() == ThrStatus.ACTIVE; }
	}
	private record Document(UUID id, UUID tenant, UUID thread, UUID uploader, String name, long size,
			String digest, String status, boolean pinned, Instant created, Instant updated) { }
	public record Original(String fileName, byte[] bytes) { }

	private Access access(UUID threadId, CurrentActor actor, boolean write) {
		// Tenant 범위 권한 변경과 동일한 잠금 순서다. 읽기·원본 전송도 최신 판정 뒤 진행한다.
		Thr initial = threads.findById(threadId).orElseThrow(ThreadDocumentService::notFound);
		if (initial.getTenantId() == null) throw notFound();
		jdbc.queryForList("select id from tnn where id = ? for update", initial.getTenantId());
		Thr thread = threads.findByIdForSeqUpdate(threadId).orElseThrow(ThreadDocumentService::notFound);
		entityManager.refresh(thread);
		if (users.findById(actor.userId()).filter(user -> user.getStatus() == AppUserStatus.ACTIVE).isEmpty())
			throw notFound();
		var member = memberships.findByThrIdAndUserIdAndStatus(threadId, actor.userId(), ThrMbrStatus.ACTIVE)
				.orElseThrow(ThreadDocumentService::notFound);
		boolean owner = member.getRole() == ThrMbrRole.OWNER;
		if (thread.getKind() == ThrKind.DIRECT && (!owner || !actor.userId().equals(thread.getDrcOwnUserId())))
			throw notFound();
		if (!authorizer.canViewBlocking(actor.subject(), thread.getWorkspaceNodeId())) throw notFound();
		if (write && thread.getStatus() != ThrStatus.ACTIVE) throw conflict();
		return new Access(thread, owner, actor.userId());
	}

	public ThreadDocumentView.Listing list(UUID thread, CurrentActor actor) {
		return transactions.execute(tx -> {
			Access access = access(thread, actor, false);
			List<ThreadDocumentView> values = jdbc.query("select * from thr_doc where thr_id = ? and status <> 'DELETED' order by created_at, id",
					this::document, thread).stream().map(doc -> view(doc, access)).toList();
			return new ThreadDocumentView.Listing(access.thread().getStatus().name(), access.writable(), values);
		});
	}

	public ThreadDocumentView upload(UUID thread, UUID id, CurrentActor actor, String fileName, byte[] content) {
		validate(fileName, content);
		String digest = digest(content);
		Document reservation;
		try {
		reservation = transactions.execute(tx -> {
			Access access = access(thread, actor, true);
			Document old = find(id, false);
			if (old != null) {
				if (!old.thread().equals(thread) || !old.uploader().equals(actor.userId())) throw notFound();
				if (!old.digest().equals(digest)
						|| !old.name().equals(fileName) || old.size() != content.length) throw conflict();
				if (old.status().equals("DELETED")) throw notFound();
				if (old.status().equals("UPLOADING")) throw conflict();
				if (!old.status().equals("FAILED")) return old;
				// ETL 실패는 등록 완료 사건이 이미 있다. 같은 등록 재시도로 ETL을 자동 다시 시작하지 않는다.
				if (registered(id)) return old;
				jdbc.update("update thr_doc set status = 'UPLOADING', err = null, updated_at = now() where id = ?", id);
			} else {
				jdbc.update("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key, status) values (?, ?, ?, ?, ?, ?, ?, 'UPLOADING')",
						id, access.thread().getTenantId(), thread, actor.userId(), fileName, content.length, digest);
			}
			queue(find(id, true), 180);
			return find(id, true);
		});
		} catch (DuplicateKeyException collision) { throw conflict(); }
		if (!reservation.status().equals("UPLOADING")) return transactions.execute(tx -> view(reservation, access(thread, actor, false)));
		boolean stored = false;
		try {
			storage.save(reservation.tenant(), thread, id, digest, content, reservation.updated().plusSeconds(120));
			stored = true;
			return transactions.execute(tx -> {
				Access access = access(thread, actor, true);
				Document latest = findIn(thread, id);
				if (latest.status().equals("DELETED")) throw notFound();
				if (!latest.status().equals("UPLOADING")) throw conflict();
				jdbc.update("update thr_doc set status = 'PENDING', updated_at = now(), err = null where id = ?", id);
				record(latest, actor.userId(), "REGISTERED", id);
				jdbc.update("delete from thr_doc_end where doc_id = ?", id);
				return view(find(id, true), access);
			});
		} catch (RuntimeException error) {
			int cleanupDelay = stored ? 0 : 180;
			try {
				transactions.executeWithoutResult(tx -> {
					Document doc = find(id, true);
					if (doc == null || doc.status().equals("DELETED") || doc.status().equals("UPLOADING")
							|| (doc.status().equals("FAILED") && !registered(id))) {
						if (doc != null && doc.status().equals("UPLOADING"))
							jdbc.update("update thr_doc set status = 'FAILED', err = 'SOURCE_UPLOAD_FAILED', updated_at = now() where id = ?", id);
						queue(reservation, cleanupDelay);
					}
				});
			} catch (RuntimeException compensationError) {
				log.error("문서 원본 실패 보상 기록 실패 — 예약 정리 작업이 재시도한다: {}", id, compensationError);
			}
			throw error;
		}
	}

	public void change(UUID thread, UUID id, CurrentActor actor, String action, UUID requestId) {
		if (!Set.of("PINNED", "UNPINNED", "DELETED").contains(action)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		transactions.executeWithoutResult(tx -> {
			Access access = access(thread, actor, true);
			Document doc = findIn(thread, id);
			if (!action.equals("PINNED") && !access.owner() && !doc.uploader().equals(actor.userId()))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN);
			var previous = jdbc.queryForList("select evt_kind, act_user_id from thr_doc_evt where doc_id = ? and req_key = ?", id, requestId);
			if (!previous.isEmpty()) {
				if (!action.equals(previous.get(0).get("evt_kind")) || !actor.userId().equals(previous.get(0).get("act_user_id"))) throw conflict();
				return;
			}
			if (doc.status().equals("UPLOADING")) throw conflict();
			if (doc.status().equals("DELETED")) throw notFound();
			if (!action.equals("DELETED") && !registered(id)) throw conflict();
			// 이미 같은 고정 상태면 실제 변경이 없다. 재전송으로 업무 사건을 부풀리지 않는다.
			if ((action.equals("PINNED") && doc.pinned()) || (action.equals("UNPINNED") && !doc.pinned())) return;
			if (action.equals("DELETED")) {
				jdbc.update("update thr_doc set status = 'DELETED', pnn = false, deleted_at = now(), updated_at = now() where id = ?", id);
				queue(doc, 0);
			} else jdbc.update("update thr_doc set pnn = ?, updated_at = now() where id = ?", action.equals("PINNED"), id);
			record(doc, actor.userId(), action, requestId);
		});
	}

	public Original original(UUID thread, UUID id, CurrentActor actor) {
		Document doc = transactions.execute(tx -> {
			access(thread, actor, false);
			Document value = findIn(thread, id);
			if (value.status().equals("DELETED") || value.status().equals("UPLOADING") || !registered(id)) throw notFound();
			return value;
		});
		byte[] bytes = storage.read(doc.tenant(), thread, id, doc.digest());
		if (!digest(bytes).equals(doc.digest())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		// 저장소에서 읽는 동안 방/문서 접근권이 사라졌으면 응답 본문을 전달하지 않는다.
		transactions.executeWithoutResult(tx -> {
			access(thread, actor, false);
			if (findIn(thread, id).status().equals("DELETED")) throw notFound();
		});
		return new Original(doc.name(), bytes);
	}

	/** B가 실제 추출·색인 결과를 반영하는 내부 업무 경계다. 사용자 설정 API는 제공하지 않는다. */
	public boolean processing(UUID id, String expected, String next) {
		if (!(expected.equals("PENDING") && next.equals("PROCESSING"))
				&& !(expected.equals("PROCESSING") && Set.of("READY", "FAILED").contains(next)))
			throw new IllegalArgumentException("허용하지 않은 처리 상태 전이");
		return transactions.execute(tx -> jdbc.update("update thr_doc set status = ?, updated_at = now() where id = ? and status = ?",
				next, id, expected) == 1);
	}

	@Scheduled(fixedDelayString = "${app.document.cleanup-delay-ms:10000}")
	public void cleanup() {
		if (!cleanupEnabled) return;
		cleanupDue();
	}

	/** 제어된 테스트와 운영 스케줄러가 같은 정리 루프를 사용한다. */
	public void cleanupDue() {
		List<UUID> due = jdbc.query("select doc_id from thr_doc_end where next_at <= now() order by next_at limit 20",
				(rs, row) -> rs.getObject(1, UUID.class));
		for (UUID id : due) {
			try {
				transactions.executeWithoutResult(tx -> {
					Document doc = find(id, true);
					var rows = jdbc.queryForList("select * from thr_doc_end where doc_id = ? and next_at <= now() for update", id);
					if (rows.isEmpty()) return;
					var job = rows.get(0);
					if (doc != null && (!Set.of("UPLOADING", "DELETED", "FAILED").contains(doc.status())
							|| (doc.status().equals("FAILED") && registered(id)))) {
						jdbc.update("delete from thr_doc_end where doc_id = ?", id);
						return;
					}
					if (doc != null && doc.status().equals("UPLOADING"))
						jdbc.update("update thr_doc set status = 'FAILED', err = 'SOURCE_UPLOAD_EXPIRED', updated_at = now() where id = ?", id);
					try {
						storage.delete((UUID) job.get("tnn_id"), (UUID) job.get("thr_id"), id, (String) job.get("src_key"));
						jdbc.update("delete from thr_doc_end where doc_id = ?", id);
					} catch (RuntimeException failure) {
						jdbc.update("update thr_doc_end set att_cnt = att_cnt + 1, err = 'SOURCE_DELETE_FAILED', next_at = now() + interval '30 seconds' where doc_id = ?", id);
						log.warn("원본 정리 재시도 대기: {}", id);
					}
				});
			} catch (RuntimeException failure) { log.error("원본 정리 트랜잭션 실패: {}", id, failure); }
		}
	}

	private void queue(Document doc, int seconds) {
		jdbc.update("insert into thr_doc_end(doc_id, tnn_id, thr_id, src_key, next_at) values (?, ?, ?, ?, ?) on conflict(doc_id) do update set next_at = excluded.next_at",
				doc.id(), doc.tenant(), doc.thread(), doc.digest(), java.sql.Timestamp.from(Instant.now().plusSeconds(seconds)));
	}
	private boolean registered(UUID id) {
		return jdbc.queryForObject("select count(*) from thr_doc_evt where doc_id = ? and evt_kind = 'REGISTERED'", Integer.class, id) > 0;
	}
	private void record(Document doc, UUID actor, String action, UUID request) {
		jdbc.update("insert into thr_doc_evt(id, tnn_id, thr_id, doc_id, act_user_id, evt_kind, req_key) values (?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), doc.tenant(), doc.thread(), doc.id(), actor, action, request);
	}
	private Document find(UUID id, boolean lock) {
		var rows = jdbc.query("select * from thr_doc where id = ?" + (lock ? " for update" : ""), this::document, id);
		return rows.isEmpty() ? null : rows.get(0);
	}
	private Document findIn(UUID thread, UUID id) {
		Document doc = find(id, true);
		if (doc == null || !doc.thread().equals(thread)) throw notFound();
		return doc;
	}
	private Document document(ResultSet rs, int row) throws SQLException {
		return new Document(rs.getObject("id", UUID.class), rs.getObject("tnn_id", UUID.class), rs.getObject("thr_id", UUID.class),
				rs.getObject("user_id", UUID.class), rs.getString("file_name"), rs.getLong("file_size"), rs.getString("src_key"),
				rs.getString("status"), rs.getBoolean("pnn"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
	}
	private ThreadDocumentView view(Document doc, Access access) {
		boolean own = doc.uploader().equals(access.user());
		boolean writable = access.writable() && !Set.of("UPLOADING", "DELETED").contains(doc.status());
		boolean manageable = writable && (own || access.owner());
		boolean registered = registered(doc.id());
		return new ThreadDocumentView(doc.id(), doc.name(), doc.size(), doc.status(), doc.pinned(), own,
				writable && registered, manageable && registered, manageable, doc.created());
	}
	private void validate(String name, byte[] content) {
		if (name == null || name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
				|| name.contains("/") || name.contains("\\")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		int dot = name.lastIndexOf('.');
		if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)))
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		if (content.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		if (content.length > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
	}
	static String digest(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
	private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
