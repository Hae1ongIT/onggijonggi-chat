package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : ThreadDocumentPostgresTest.java
 * Description : 실제 최신 Flyway·PostgreSQL에서 방 문서 인가, 원자 등록, 고정, 삭제와 원본 정리 경합을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ThreadDocumentPostgresTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("thread_docs").withUsername("test").withPassword("test");
	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
	}
	@Autowired ThreadDocumentService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired ThrRepository threads;
	@Autowired ThrMbrRepository members;
	@Autowired RbacBootstrapService bootstrap;
	@MockitoBean ThreadSourceStorage storage;
	@MockitoBean WorkspaceAuthorizer authorizer;
	CurrentActor owner;
	CurrentActor member;
	UUID room;
	UUID tenant;
	byte[] bytes = "등록 원문".getBytes(StandardCharsets.UTF_8);

	@BeforeEach void setup() {
		reset(storage, authorizer);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		bootstrap.runCurrentConfiguration();
		jdbc.update("delete from thr");
		jdbc.update("delete from thr_doc_end");
		tenant = jdbc.queryForObject("select id from tnn where tnn_key='ogjg'", UUID.class);
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		owner = actor(); member = actor();
		Thr value = Thr.collab(owner.userId(), "문서 방");
		value.placeIn(tenant, common);
		threads.saveAndFlush(value); room = value.getId();
		members.saveAndFlush(new ThrMbr(room, owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		members.saveAndFlush(new ThrMbr(room, member.userId(), ThrMbrRole.MEMBER, owner.userId()));
	}

	@Test void registeredOriginalIsPendingUnpinnedAndRetryDoesNotDuplicateEvents() {
		UUID id = UUID.randomUUID();
		var first = upload(id, member);
		assertThat(first.status()).isEqualTo("PENDING");
		assertThat(first.pinned()).isFalse();
		assertThat(service.upload(room, id, member, "guide.txt", bytes)).isEqualTo(first);
		verify(storage, times(1)).save(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), eq(bytes), any());
		assertThat(events(id)).isEqualTo(1);
		when(storage.read(tenant, room, id, ThreadDocumentService.digest(bytes))).thenReturn(bytes);
		assertThat(service.original(room, id, owner).bytes()).isEqualTo(bytes);
		assertThat(service.list(room, member).documents()).hasSize(1);
	}

	@Test void participantCanPinOthersButOnlyUploaderOrOwnerCanUnpinOrDelete() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, member, "PINNED", UUID.randomUUID());
		assertThat(service.list(room, member).documents().get(0).pinned()).isTrue();
		status(() -> service.change(room, id, member, "UNPINNED", UUID.randomUUID()), HttpStatus.FORBIDDEN);
		status(() -> service.change(room, id, member, "DELETED", UUID.randomUUID()), HttpStatus.FORBIDDEN);
		UUID request = UUID.randomUUID();
		service.change(room, id, owner, "DELETED", request);
		service.change(room, id, owner, "DELETED", request);
		assertThat(events(id)).isEqualTo(3);
		assertThat(service.list(room, owner).documents()).isEmpty();
		status(() -> service.original(room, id, owner), HttpStatus.NOT_FOUND);
		assertThat(service.processing(id, "PENDING", "PROCESSING")).isFalse();
	}

	@Test void departureKeepsDocumentButRemovesUploaderAccess() {
		UUID id = UUID.randomUUID(); upload(id, member);
		jdbc.update("update thr_mbr set status='LEFT',ended_at=now(),end_rsn='LEFT' where thr_id=? and user_id=?", room, member.userId());
		status(() -> service.list(room, member), HttpStatus.NOT_FOUND);
		assertThat(service.list(room, owner).documents()).hasSize(1);
		service.change(room, id, owner, "PINNED", UUID.randomUUID());
		jdbc.update("update app_user set status='INACTIVE', inactive_at=now() where id=?", member.userId());
		assertThat(service.list(room, owner).documents()).hasSize(1);
	}

	@Test void lockedAndArchivedRoomsAreReadableButNeverWritable() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		for (String state : new String[]{"LOCKED", "ARCHIVED"}) {
			jdbc.update("update thr set status=?,locked_at=now(),archived_at=case when ?='ARCHIVED' then now() else null end where id=?", state, state, room);
			var listing = service.list(room, owner);
			assertThat(listing.canUpload()).isFalse();
			assertThat(listing.documents().get(0).canPin()).isFalse();
			status(() -> upload(UUID.randomUUID(), owner), HttpStatus.CONFLICT);
			status(() -> service.change(room, id, owner, "DELETED", UUID.randomUUID()), HttpStatus.CONFLICT);
		}
	}

	@Test void latestWorkspaceAndAccountAuthorizationIsRequiredIncludingOriginalSecondCheck() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(false);
		status(() -> service.list(room, owner), HttpStatus.NOT_FOUND);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		when(storage.read(any(), any(), any(), any())).thenAnswer(invocation -> {
			jdbc.update("update app_user set status='INACTIVE', inactive_at=now() where id=?", owner.userId());
			return bytes;
		});
		status(() -> service.original(room, id, owner), HttpStatus.NOT_FOUND);
	}

	@Test void failedStorageCannotPinOrReadAndCleanupFailureRetries() {
		UUID id = UUID.randomUUID();
		doThrow(new IllegalStateException("저장 실패")).when(storage).save(any(), any(), any(), any(), any(), any());
		assertThatThrownBy(() -> upload(id, owner)).isInstanceOf(IllegalStateException.class);
		assertThat(events(id)).isZero();
		assertThat(service.list(room, owner).documents().get(0).canPin()).isFalse();
		status(() -> service.change(room, id, owner, "PINNED", UUID.randomUUID()), HttpStatus.CONFLICT);
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second'");
		doThrow(new IllegalStateException("정리 실패")).when(storage).delete(any(), any(), any(), any());
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select att_cnt from thr_doc_end where doc_id=?", Integer.class, id)).isEqualTo(1);
		doNothing().when(storage).delete(any(), any(), any(), any());
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second'");
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isZero();
	}

	@Test void roomDeletionRetainsCleanupReferenceAndIndividualDeleteRetainsEvent() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		assertThat(events(id)).isEqualTo(2);
		jdbc.update("delete from thr where id=?", room);
		assertThat(jdbc.queryForObject("select count(*) from thr_doc", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_evt", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isEqualTo(1);
		service.cleanupDue();
		verify(storage).delete(tenant, room, id, ThreadDocumentService.digest(bytes));
	}

	@Test void lateUploadAfterRoomDeletionCannotRegisterAndStillQueuesCleanup() {
		UUID id = UUID.randomUUID();
		doAnswer(invocation -> { jdbc.update("delete from thr where id=?", room); return null; })
				.when(storage).save(any(), any(), any(), any(), any(), any());
		status(() -> upload(id, owner), HttpStatus.NOT_FOUND);
		assertThat(events(id)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isEqualTo(1);
		service.cleanupDue();
		verify(storage).delete(tenant, room, id, ThreadDocumentService.digest(bytes));
	}

	@Test void registeredFailureKeepsOriginalAndDeletedProcessingCannotResurrect() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		assertThat(service.processing(id, "PENDING", "PROCESSING")).isTrue();
		assertThat(service.processing(id, "PROCESSING", "FAILED")).isTrue();
		assertThat(upload(id, owner).status()).isEqualTo("FAILED");
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any());
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		assertThat(service.processing(id, "PROCESSING", "READY")).isFalse();
	}

	@Test void invalidFileAndMismatchedRegistrationAreRejected() {
		status(() -> service.upload(room, UUID.randomUUID(), owner, "../x.txt", bytes), HttpStatus.BAD_REQUEST);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.exe", bytes), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.txt", new byte[0]), HttpStatus.BAD_REQUEST);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.txt", new byte[ThreadDocumentService.MAX_FILE_BYTES+1]), HttpStatus.CONTENT_TOO_LARGE);
		UUID id = UUID.randomUUID(); upload(id, owner);
		status(() -> service.upload(room, id, owner, "guide.txt", new byte[]{1}), HttpStatus.CONFLICT);
		status(() -> upload(id, member), HttpStatus.NOT_FOUND);
	}

	@Test void eventFailureRollsBackPinAndRegistrationFinalization() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		jdbc.execute("alter table thr_doc_evt add constraint test_event_failure check (evt_kind <> 'PINNED')");
		try {
			assertThatThrownBy(() -> service.change(room, id, owner, "PINNED", UUID.randomUUID()))
					.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThat(service.list(room, owner).documents().get(0).pinned()).isFalse();
			assertThat(events(id)).isEqualTo(1);
		} finally { jdbc.execute("alter table thr_doc_evt drop constraint test_event_failure"); }
		jdbc.execute("alter table thr_doc_evt add constraint test_registration_failure check (evt_kind <> 'REGISTERED') not valid");
		UUID failed = UUID.randomUUID();
		try {
			assertThatThrownBy(() -> upload(failed, owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThat(events(failed)).isZero();
			assertThat(jdbc.queryForObject("select status from thr_doc where id=?", String.class, failed)).isEqualTo("FAILED");
			assertThat(jdbc.queryForObject("select count(*) from thr_doc_end where doc_id=?", Integer.class, failed)).isEqualTo(1);
		} finally { jdbc.execute("alter table thr_doc_evt drop constraint test_registration_failure"); }
	}

	@Test void directOwnerOnlyAndCrossTenantForeignKeyAreEnforced() {
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		Thr direct = Thr.direct(UUID.randomUUID(), owner.userId(), "개인 문서");
		direct.placeIn(tenant, common); threads.saveAndFlush(direct);
		members.saveAndFlush(new ThrMbr(direct.getId(), owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		members.saveAndFlush(new ThrMbr(direct.getId(), member.userId(), ThrMbrRole.MEMBER, owner.userId()));
		UUID id = UUID.randomUUID();
		service.upload(direct.getId(), id, owner, "guide.txt", bytes);
		status(() -> service.list(direct.getId(), member), HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> jdbc.update("update thr_doc set tnn_id=? where id=?", UUID.randomUUID(), id))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(service.list(direct.getId(), owner).documents()).hasSize(1);
	}

	@Test void simultaneousRegistrationCannotOverwriteUploadingReservation() {
		UUID id = UUID.randomUUID();
		doAnswer(invocation -> {
			status(() -> upload(id, owner), HttpStatus.CONFLICT);
			return null;
		}).when(storage).save(any(), any(), any(), any(), any(), any());
		assertThat(upload(id, owner).status()).isEqualTo("PENDING");
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any());
		assertThat(events(id)).isEqualTo(1);
	}

	@Test void legacyDocumentsAreNeverAssignedMovedOrDeleted() {
		UUID old = UUID.randomUUID(); UUID version = UUID.randomUUID();
		jdbc.update("insert into doc(id,doc_key,file_name,org_path,acc_tag,emb_mdl,chnk_ver,dept) values (?,?,'legacy.pdf','legacy/path',array['OLD'],'legacy','1','legacy')", old, "legacy-"+old);
		jdbc.update("insert into doc_ver(id,doc_id,ver_seq,file_name,org_path,uploaded_by) values (?,?,1,'legacy.pdf','legacy/path','legacy-user')", version, old);
		jdbc.update("update doc set cur_ver_id=? where id=?", version, old);
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		jdbc.update("delete from thr where id=?", room);
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select org_path from doc where id=?", String.class, old)).isEqualTo("legacy/path");
		assertThat(jdbc.queryForObject("select cur_ver_id from doc where id=?", UUID.class, old)).isEqualTo(version);
		assertThat(jdbc.queryForObject("select count(*) from doc_ver where id=?", Integer.class, version)).isEqualTo(1);
	}

	@Test void nonParticipantCannotReadOrWriteEvenWithKnownDocumentId() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		CurrentActor outsider = actor();
		status(() -> service.list(room, outsider), HttpStatus.NOT_FOUND);
		status(() -> service.original(room, id, outsider), HttpStatus.NOT_FOUND);
		status(() -> service.upload(room, UUID.randomUUID(), outsider, "guide.txt", bytes), HttpStatus.NOT_FOUND);
		status(() -> service.change(room, id, outsider, "PINNED", UUID.randomUUID()), HttpStatus.NOT_FOUND);
		verify(storage, never()).read(any(), any(), any(), any());
	}

	private ThreadDocumentView upload(UUID id, CurrentActor actor) { return service.upload(room, id, actor, "guide.txt", bytes); }
	private int events(UUID id) { return jdbc.queryForObject("select count(*) from thr_doc_evt where doc_id=?", Integer.class, id); }
	private CurrentActor actor() {
		UUID id = UUID.randomUUID(); String subject = "doc-"+id;
		jdbc.update("insert into app_user(id,keycloak_subj) values (?,?)", id, subject);
		return new CurrentActor(id, subject, subject);
	}
	private void status(Runnable action, HttpStatus expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(expected));
	}
}
