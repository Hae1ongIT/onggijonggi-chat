-- 04·DATA: Keycloak 역할·계정 변경 감사(#304). Keycloak admin event는 realm 관리자가 콘솔에서 지울 수 있어,
-- BFF가 주기적으로 읽어 이 append-only 테이블에 옮겨 둔다. authz_adt는 Tenant 단위이고 행위자가 app_user여야 해
-- realm 전체 역할(PLATFORM_ADMIN)과 Keycloak 관리자 행위자를 담을 수 없으므로 따로 둔다.
-- 기존 데이터 전제: 없다(새 테이블). 수집 상태 행 하나를 미리 넣는다.
-- 이관·중단 조건: 없다.

create table keycloak_adt (
    id              uuid          not null,
    keycloak_evt_id varchar(255),
    evt_kind        varchar(32)   not null,
    evt_at          timestamptz   not null,
    act_subj        varchar(255),
    act_json        jsonb         not null,
    trg_subj        varchar(255),
    role            varchar(255),
    trg_path        varchar(1024) not null,
    trg_ref         jsonb         not null,
    created_at      timestamptz   not null default now(),
    primary key (id),
    constraint keycloak_adt_evt_value check (evt_kind in (
        'ROLE_GRANTED', 'ROLE_REVOKED', 'GROUP_JOINED', 'GROUP_LEFT', 'GROUP_ROLE_GRANTED', 'GROUP_ROLE_REVOKED',
        'ROLE_DEFINITION_CHANGED', 'GROUP_DELETED', 'MANAGEMENT_ROLE_GRANTED', 'MANAGEMENT_ROLE_REVOKED',
        'USER_CREATED_WITH_ACCESS', 'USER_ENABLED', 'USER_DISABLED', 'USER_DELETED', 'EVENT_CONFIG_CHANGED',
        'ROLE_HELD_AT_START', 'BASELINE_RECORDED')),
    -- 같은 이벤트를 두 번 읽어도 한 행. 역할이 없는 이벤트도 막도록 NULL을 같은 값으로 본다. 한 이벤트의 여러 행은
    -- 역할(role)·그룹(trg_path)으로, 이벤트 id가 없는 기준선 행은 보유자(trg_subj)로 갈린다.
    constraint uq_keycloak_adt_evt unique nulls not distinct (keycloak_evt_id, trg_subj, trg_path, role)
);

-- 기준선은 한 번만 잡는다.
create unique index ux_keycloak_adt_baseline on keycloak_adt (evt_kind) where evt_kind = 'BASELINE_RECORDED';
create index ix_keycloak_adt_evt_at on keycloak_adt (evt_at desc, id desc);

-- 감사 행은 UPDATE·DELETE·TRUNCATE를 모두 거부한다. replica 모드에서도 우회되지 않게 ENABLE ALWAYS로 고정한다.
create or replace function keycloak_adt_append_only()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'keycloak audit rows are append-only';
end;
$$;

create trigger trg_keycloak_adt_append_only
before update or delete on keycloak_adt
for each row execute function keycloak_adt_append_only();
create trigger trg_keycloak_adt_no_truncate
before truncate on keycloak_adt
for each statement execute function keycloak_adt_append_only();
alter table keycloak_adt enable always trigger trg_keycloak_adt_append_only;
alter table keycloak_adt enable always trigger trg_keycloak_adt_no_truncate;

-- 수집기 상태(커서, 마지막 성공·실패, 마지막으로 읽은 이벤트 설정). 감사 기록이 아니라 고칠 수 있다.
create table keycloak_adt_crs (
    id              smallint     not null primary key check (id = 1),
    evt_at          timestamptz,
    keycloak_evt_id varchar(255),
    last_run_at     timestamptz,
    last_err_at     timestamptz,
    err_text        varchar(512),
    evt_cnf_json    jsonb
);

insert into keycloak_adt_crs (id) values (1);
