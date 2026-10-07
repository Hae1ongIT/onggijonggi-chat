-- 04·DATA
-- 변경 이유: #348 — READY 문서의 검색 조각을 문서 상태를 바꾸지 않고 다시 만드는 회차(다시 만들기·자동 복구)와 운영자 일괄 재처리 요청을 추가한다.
-- 기존 데이터 전제: 기존 회차는 모두 문서 상태를 바꾸는 처리(INGEST)다 — 최초 처리와 사용자 재처리.
-- 이관·중단 조건: 데이터 삭제 없음. 기존 행은 기본값 INGEST로 채워진다.

-- 긴 트랜잭션 뒤에서 thr_doc_run 잠금을 기다리며 검색(읽기)까지 막지 않게 한다. 넘으면 실패하고 다음 배포에서 다시 한다.
set local lock_timeout = '10s';

-- 운영자 일괄 재처리 요청. 대상 선정과 회차 생성은 ETL이 한다(BFF는 ETL의 청킹 설정을 모른다).
-- kind: ALL(READY 전체) · OUTDATED(현재 회차의 임베딩 모델·차원·청킹 설정이 지금 ETL 설정과 다른 문서).
-- trg_cnt는 ETL이 회차를 만든 뒤 채운다. 대기 중 요청은 하나뿐이다.
create table doc_rbl (
    id uuid primary key,
    kind varchar(16) not null check (kind in ('ALL', 'OUTDATED')),
    status varchar(16) not null check (status in ('PENDING', 'COMPLETED')),
    req_subj varchar(255) not null,
    trg_cnt integer check (trg_cnt >= 0),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    check ((status = 'COMPLETED') = (completed_at is not null and trg_cnt is not null))
);
create unique index uq_doc_rbl_pending on doc_rbl((status)) where status = 'PENDING';

-- 회차 종류. INGEST는 문서 상태를 PENDING → PROCESSING → READY|FAILED로 바꾸는 처리, REBUILD·RECOVER는 READY 문서의
-- 조각을 상태를 바꾸지 않고 다시 만든다(처리 중에도 이전 회차로 검색된다). 실패 시 REBUILD는 문서를 READY로 두고(이전 조각이
-- 남아 있다), RECOVER는 조각이 이미 없으므로 원본 손상·없음 같은 영구 실패면 문서를 FAILED로 바꾼다(일시 장애 소진은 READY로 두고 다음 대조가 다시 잡는다).
-- rbl_id는 운영자 요청이 만든 회차의 요청 ID(진행 상황 집계용). 자동으로 만든 회차는 비어 있다. 회차 행처럼 FK 없이 둔다.
alter table thr_doc_run add column run_kind varchar(16) not null default 'INGEST'
    check (run_kind in ('INGEST', 'REBUILD', 'RECOVER'));
alter table thr_doc_run add column rbl_id uuid;
-- 요청별 진행 집계(남은·실패 수)를 회차 행을 읽지 않고 인덱스만으로 센다.
create index ix_thr_doc_run_rbl on thr_doc_run(rbl_id) include (status, err) where rbl_id is not null;
-- 회차 선점 순서: 사용자 처리(INGEST)를 먼저, 그다음 기한 순. 일괄 다시 만들기로 대기 회차가 수만 건 쌓여도 정렬 없이 앞에서 집는다.
create index ix_thr_doc_run_claim on thr_doc_run((run_kind <> 'INGEST'), next_at, id) where status in ('PENDING', 'RUNNING');
-- 정리 대상 조회: 정리를 마친 PURGED 행(계속 늘어난다)을 빼고 오래된 순으로 본다.
create index ix_thr_doc_run_sweep on thr_doc_run(updated_at) where status in ('DONE', 'FAILED', 'CANCELLED');
