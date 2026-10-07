-- 04·DATA
-- 변경 이유: #348 — READY 문서의 검색 조각을 문서 상태를 바꾸지 않고 다시 만드는 회차(다시 만들기·자동 복구)와 운영자 일괄 재처리 요청을 추가한다.
-- 기존 데이터 전제: 기존 회차는 모두 문서 상태를 바꾸는 처리(INGEST)다 — 최초 처리와 사용자 재처리.
-- 이관·중단 조건: 데이터 삭제 없음. 기존 행은 기본값 INGEST로 채워진다.

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
-- 남아 있다), RECOVER는 조각이 이미 없으므로 문서를 FAILED로 바꾼다.
-- rbl_id는 운영자 요청이 만든 회차의 요청 ID(진행 상황 집계용). 자동으로 만든 회차는 비어 있다. 회차 행처럼 FK 없이 둔다.
alter table thr_doc_run add column run_kind varchar(16) not null default 'INGEST'
    check (run_kind in ('INGEST', 'REBUILD', 'RECOVER'));
alter table thr_doc_run add column rbl_id uuid;
create index ix_thr_doc_run_rbl on thr_doc_run(rbl_id) where rbl_id is not null;
