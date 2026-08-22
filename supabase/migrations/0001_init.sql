-- KidSchedule 0001_init: schema + RLS + LWW push RPC
-- 协议见 docs/sync-protocol.md

-- is_family_member 等 sql 函数先于表定义,跳过创建期函数体校验
set check_function_bodies = off;

-- ============================================================
-- 通用
-- ============================================================

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- family_members 的 RLS 会在多处引用成员关系,用 security definer 函数避免策略递归
create or replace function public.is_family_member(fid uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1 from family_members
    where family_id = fid and user_id = auth.uid()
  );
$$;

create or replace function public.is_family_owner(fid uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1 from family_members
    where family_id = fid and user_id = auth.uid() and role = 'owner'
  );
$$;

-- ============================================================
-- 表
-- ============================================================

create table public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.family_members (
  family_id uuid not null references families(id),
  user_id uuid not null references auth.users(id),
  role text not null default 'member' check (role in ('owner', 'member')),
  display_name text,
  created_at timestamptz not null default now(),
  primary key (family_id, user_id)
);

create table public.invites (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references families(id),
  code text not null unique,
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null,
  used_by uuid references auth.users(id),
  used_at timestamptz,
  created_at timestamptz not null default now()
);

-- 以下为客户端同步实体:id 客户端生成,双时间戳见协议 §1
create table public.babies (
  id uuid primary key,
  family_id uuid not null references families(id),
  name text not null,
  birthday date,
  avatar_path text,
  deleted_at timestamptz,
  client_updated_at timestamptz not null,
  device_id text not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.activity_types (
  id uuid primary key,
  family_id uuid not null references families(id),
  name text not null,
  icon text,
  color text,
  kind text not null check (kind in ('instant', 'duration')),
  default_max_duration_sec integer,
  reminder_mode text not null default 'off' check (reminder_mode in ('auto', 'fixed', 'off')),
  reminder_fixed_interval_sec integer,
  sort_order integer not null default 0,
  deleted_at timestamptz,
  client_updated_at timestamptz not null,
  device_id text not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint duration_needs_max check (kind <> 'duration' or default_max_duration_sec is not null)
);

create table public.events (
  id uuid primary key,
  family_id uuid not null references families(id),
  baby_id uuid not null references babies(id),
  activity_type_id uuid not null references activity_types(id),
  started_at timestamptz not null,
  ended_at timestamptz,
  status text not null check (status in ('ongoing', 'done')),
  auto_ended boolean not null default false,
  note text,
  deleted_at timestamptz,
  client_updated_at timestamptz not null,
  device_id text not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint done_has_end check (status <> 'done' or ended_at is not null)
);

-- 协议 §7:同一宝宝+行为至多一条进行中
create unique index uniq_ongoing_event
  on public.events (baby_id, activity_type_id)
  where status = 'ongoing' and deleted_at is null;

-- 增量拉取游标索引(协议 §9)
create index idx_babies_cursor on public.babies (family_id, updated_at, id);
create index idx_activity_types_cursor on public.activity_types (family_id, updated_at, id);
create index idx_events_cursor on public.events (family_id, updated_at, id);
create index idx_events_timeline on public.events (baby_id, started_at desc);

create table public.event_attachments (
  id uuid primary key,
  event_id uuid not null references events(id),
  family_id uuid not null references families(id),
  storage_path text,
  upload_state text not null default 'pending' check (upload_state in ('pending', 'uploaded')),
  deleted_at timestamptz,
  client_updated_at timestamptz not null,
  device_id text not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index idx_event_attachments_cursor on public.event_attachments (family_id, updated_at, id);

-- updated_at 一律由服务端触发器写(协议 §1)
create trigger trg_families_updated before insert or update on public.families
  for each row execute function public.set_updated_at();
create trigger trg_babies_updated before insert or update on public.babies
  for each row execute function public.set_updated_at();
create trigger trg_activity_types_updated before insert or update on public.activity_types
  for each row execute function public.set_updated_at();
create trigger trg_events_updated before insert or update on public.events
  for each row execute function public.set_updated_at();
create trigger trg_event_attachments_updated before insert or update on public.event_attachments
  for each row execute function public.set_updated_at();

-- ============================================================
-- RLS
-- ============================================================

alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.invites enable row level security;
alter table public.babies enable row level security;
alter table public.activity_types enable row level security;
alter table public.events enable row level security;
alter table public.event_attachments enable row level security;

create policy families_select on public.families
  for select using (public.is_family_member(id));
create policy families_update on public.families
  for update using (public.is_family_owner(id));
-- families 的 insert 只经 create_family RPC(security definer),无 insert 策略

create policy family_members_select on public.family_members
  for select using (public.is_family_member(family_id));
create policy family_members_delete on public.family_members
  for delete using (user_id = auth.uid() or public.is_family_owner(family_id));
-- insert 只经 create_family / accept_invite RPC

create policy invites_select on public.invites
  for select using (public.is_family_member(family_id));
create policy invites_insert on public.invites
  for insert with check (public.is_family_member(family_id) and created_by = auth.uid());
-- 按 code 兑换经 accept_invite RPC(security definer)

-- 同步实体:家庭成员全权限(push RPC 为 security invoker,依赖这些策略)
create policy babies_all on public.babies
  for all using (public.is_family_member(family_id))
  with check (public.is_family_member(family_id));
create policy activity_types_all on public.activity_types
  for all using (public.is_family_member(family_id))
  with check (public.is_family_member(family_id));
create policy events_all on public.events
  for all using (public.is_family_member(family_id))
  with check (public.is_family_member(family_id));
create policy event_attachments_all on public.event_attachments
  for all using (public.is_family_member(family_id))
  with check (public.is_family_member(family_id));

-- ============================================================
-- 家庭 / 邀请 RPC
-- ============================================================

create or replace function public.create_family(p_name text, p_display_name text default null)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_fid uuid;
begin
  if auth.uid() is null then
    raise exception 'not authenticated';
  end if;
  insert into families (name, created_by) values (p_name, auth.uid()) returning id into v_fid;
  insert into family_members (family_id, user_id, role, display_name)
    values (v_fid, auth.uid(), 'owner', p_display_name);
  return v_fid;
end;
$$;

create or replace function public.create_invite(p_family_id uuid, p_ttl_hours integer default 72)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code text;
begin
  if not public.is_family_member(p_family_id) then
    raise exception 'not a family member';
  end if;
  -- 8 位大写字母数字,去掉易混字符
  v_code := upper(
    array_to_string(array(
      select substr('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', (floor(random() * 32) + 1)::int, 1)
      from generate_series(1, 8)
    ), '')
  );
  insert into invites (family_id, code, created_by, expires_at)
    values (p_family_id, v_code, auth.uid(), now() + make_interval(hours => p_ttl_hours));
  return v_code;
end;
$$;

create or replace function public.accept_invite(p_code text, p_display_name text default null)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_invite record;
begin
  if auth.uid() is null then
    raise exception 'not authenticated';
  end if;
  select * into v_invite
    from invites
    where code = upper(p_code) and used_by is null and expires_at > now()
    for update;
  if not found then
    raise exception 'invalid or expired invite';
  end if;
  insert into family_members (family_id, user_id, role, display_name)
    values (v_invite.family_id, auth.uid(), 'member', p_display_name)
    on conflict (family_id, user_id) do nothing;
  update invites set used_by = auth.uid(), used_at = now() where id = v_invite.id;
  return v_invite.family_id;
end;
$$;

-- ============================================================
-- LWW push RPC(协议 §4 §6)
-- security invoker:行级权限完全由 RLS 保证
-- 返回逐行结果:{id, outcome: applied|stale|rejected, reason}
-- ============================================================

create or replace function public.push_babies(rows jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  r jsonb;
  v_applied boolean;
  results jsonb := '[]'::jsonb;
begin
  for r in select * from jsonb_array_elements(rows) loop
    insert into babies (id, family_id, name, birthday, avatar_path, deleted_at,
                        client_updated_at, device_id, created_by)
    values (
      (r->>'id')::uuid, (r->>'family_id')::uuid, r->>'name', (r->>'birthday')::date,
      r->>'avatar_path', (r->>'deleted_at')::timestamptz,
      (r->>'client_updated_at')::timestamptz, r->>'device_id', auth.uid()
    )
    on conflict (id) do update set
      name = excluded.name,
      birthday = excluded.birthday,
      avatar_path = excluded.avatar_path,
      deleted_at = excluded.deleted_at,
      client_updated_at = excluded.client_updated_at,
      device_id = excluded.device_id
    where excluded.client_updated_at > babies.client_updated_at
       or (excluded.client_updated_at = babies.client_updated_at
           and excluded.device_id > babies.device_id);
    v_applied := found;
    results := results || jsonb_build_object(
      'id', r->>'id',
      'outcome', case when v_applied then 'applied' else 'stale' end,
      'reason', null
    );
  end loop;
  return results;
end;
$$;

create or replace function public.push_activity_types(rows jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  r jsonb;
  v_applied boolean;
  results jsonb := '[]'::jsonb;
begin
  for r in select * from jsonb_array_elements(rows) loop
    insert into activity_types (id, family_id, name, icon, color, kind,
                                default_max_duration_sec, reminder_mode,
                                reminder_fixed_interval_sec, sort_order, deleted_at,
                                client_updated_at, device_id, created_by)
    values (
      (r->>'id')::uuid, (r->>'family_id')::uuid, r->>'name', r->>'icon', r->>'color',
      r->>'kind', (r->>'default_max_duration_sec')::integer, r->>'reminder_mode',
      (r->>'reminder_fixed_interval_sec')::integer, (r->>'sort_order')::integer,
      (r->>'deleted_at')::timestamptz,
      (r->>'client_updated_at')::timestamptz, r->>'device_id', auth.uid()
    )
    on conflict (id) do update set
      name = excluded.name,
      icon = excluded.icon,
      color = excluded.color,
      kind = excluded.kind,
      default_max_duration_sec = excluded.default_max_duration_sec,
      reminder_mode = excluded.reminder_mode,
      reminder_fixed_interval_sec = excluded.reminder_fixed_interval_sec,
      sort_order = excluded.sort_order,
      deleted_at = excluded.deleted_at,
      client_updated_at = excluded.client_updated_at,
      device_id = excluded.device_id
    where excluded.client_updated_at > activity_types.client_updated_at
       or (excluded.client_updated_at = activity_types.client_updated_at
           and excluded.device_id > activity_types.device_id);
    v_applied := found;
    results := results || jsonb_build_object(
      'id', r->>'id',
      'outcome', case when v_applied then 'applied' else 'stale' end,
      'reason', null
    );
  end loop;
  return results;
end;
$$;

create or replace function public.push_events(rows jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  r jsonb;
  v_applied boolean;
  results jsonb := '[]'::jsonb;
begin
  for r in select * from jsonb_array_elements(rows) loop
    begin
      insert into events (id, family_id, baby_id, activity_type_id, started_at, ended_at,
                          status, auto_ended, note, deleted_at,
                          client_updated_at, device_id, created_by)
      values (
        (r->>'id')::uuid, (r->>'family_id')::uuid, (r->>'baby_id')::uuid,
        (r->>'activity_type_id')::uuid, (r->>'started_at')::timestamptz,
        (r->>'ended_at')::timestamptz, r->>'status',
        coalesce((r->>'auto_ended')::boolean, false), r->>'note',
        (r->>'deleted_at')::timestamptz,
        (r->>'client_updated_at')::timestamptz, r->>'device_id', auth.uid()
      )
      on conflict (id) do update set
        started_at = excluded.started_at,
        ended_at = excluded.ended_at,
        status = excluded.status,
        auto_ended = excluded.auto_ended,
        note = excluded.note,
        deleted_at = excluded.deleted_at,
        client_updated_at = excluded.client_updated_at,
        device_id = excluded.device_id
      where excluded.client_updated_at > events.client_updated_at
         or (excluded.client_updated_at = events.client_updated_at
             and excluded.device_id > events.device_id);
      v_applied := found;
      results := results || jsonb_build_object(
        'id', r->>'id',
        'outcome', case when v_applied then 'applied' else 'stale' end,
        'reason', null
      );
    exception when unique_violation then
      -- uniq_ongoing_event:远端已有同宝宝同行为的进行中事件(协议 §4 §7)
      results := results || jsonb_build_object(
        'id', r->>'id',
        'outcome', 'rejected',
        'reason', 'ongoing_conflict'
      );
    end;
  end loop;
  return results;
end;
$$;

create or replace function public.push_event_attachments(rows jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  r jsonb;
  v_applied boolean;
  results jsonb := '[]'::jsonb;
begin
  for r in select * from jsonb_array_elements(rows) loop
    insert into event_attachments (id, event_id, family_id, storage_path, upload_state,
                                   deleted_at, client_updated_at, device_id, created_by)
    values (
      (r->>'id')::uuid, (r->>'event_id')::uuid, (r->>'family_id')::uuid,
      r->>'storage_path', coalesce(r->>'upload_state', 'pending'),
      (r->>'deleted_at')::timestamptz,
      (r->>'client_updated_at')::timestamptz, r->>'device_id', auth.uid()
    )
    on conflict (id) do update set
      storage_path = excluded.storage_path,
      upload_state = excluded.upload_state,
      deleted_at = excluded.deleted_at,
      client_updated_at = excluded.client_updated_at,
      device_id = excluded.device_id
    where excluded.client_updated_at > event_attachments.client_updated_at
       or (excluded.client_updated_at = event_attachments.client_updated_at
           and excluded.device_id > event_attachments.device_id);
    v_applied := found;
    results := results || jsonb_build_object(
      'id', r->>'id',
      'outcome', case when v_applied then 'applied' else 'stale' end,
      'reason', null
    );
  end loop;
  return results;
end;
$$;

-- ============================================================
-- Realtime(协议 §10:只当变化信号)
-- ============================================================

alter publication supabase_realtime add table public.babies;
alter publication supabase_realtime add table public.activity_types;
alter publication supabase_realtime add table public.events;
alter publication supabase_realtime add table public.event_attachments;

-- ============================================================
-- Storage:附件桶,路径 attachments/{family_id}/{event_id}/{attachment_id}.jpg
-- ============================================================

insert into storage.buckets (id, name, public) values ('attachments', 'attachments', false);

create policy attachments_select on storage.objects
  for select using (
    bucket_id = 'attachments'
    and public.is_family_member(((storage.foldername(name))[1])::uuid)
  );
create policy attachments_insert on storage.objects
  for insert with check (
    bucket_id = 'attachments'
    and public.is_family_member(((storage.foldername(name))[1])::uuid)
  );
create policy attachments_delete on storage.objects
  for delete using (
    bucket_id = 'attachments'
    and public.is_family_member(((storage.foldername(name))[1])::uuid)
  );
