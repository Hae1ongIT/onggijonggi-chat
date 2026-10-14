-- 04·DATA
-- 변경 이유: #340 — 등록된 방 문서의 ETL 처리 회차(작업 전달·선점·재시도·처리 설정 기록)와 사용자 재처리 사건을 추가한다.
-- 기존 데이터 전제: 이미 등록된 PENDING 문서는 처리 회차가 없다. 아래에서 1회차를 만들어 ETL이 이어서 처리하게 한다.
-- 이관·중단 조건: 데이터 삭제 없음. 문서 행(thr_doc)은 바꾸지 않는다.

-- 처리 회차. 문서 행과 분리해 A의 고정·삭제 행 잠금과 서로 막지 않게 한다.
-- 방·문서가 지워져도 색인 정리 대상을 잃지 않도록 FK 없이 snapshot으로 둔다(thr_doc_end와 같은 이유).
create table thr_doc_run (
    id uuid primary key,
    doc_id uuid not null,
    tnn_id uuid not null,
    thr_id uuid not null,
    run_seq integer not null check (run_seq > 0),
    status varchar(16) not null check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED', 'PURGED')),
    att_cnt integer not null default 0 check (att_cnt >= 0),
    next_at timestamptz not null default now(),
    err varchar(64),
    emb_mdl varchar(128),
    emb_dim integer,
    chnk_cnf varchar(128),
    chunk_cnt integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_thr_doc_run_seq unique (doc_id, run_seq)
);
-- 문서당 진행 중 회차는 하나뿐이다.
create unique index uq_thr_doc_run_active on thr_doc_run(doc_id) where status in ('PENDING', 'RUNNING');
create index ix_thr_doc_run_due on thr_doc_run(status, next_at);

-- 이미 등록됐지만 아직 처리되지 않은 문서는 1회차를 만든다.
insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status)
    select gen_random_uuid(), d.id, d.tnn_id, d.thr_id, 1, 'PENDING'
    from thr_doc d
    where d.status = 'PENDING'
      and exists (select 1 from thr_doc_evt e where e.doc_id = d.id and e.evt_kind = 'REGISTERED');

-- 사용자 재처리 요청을 기존 변경 사건과 같은 멱등 규칙(doc_id, req_key)으로 남긴다.
alter table thr_doc_evt drop constraint thr_doc_evt_evt_kind_check;
alter table thr_doc_evt add constraint thr_doc_evt_evt_kind_check
    check (evt_kind in ('REGISTERED', 'PINNED', 'UNPINNED', 'DELETED', 'REPROCESSED'));
