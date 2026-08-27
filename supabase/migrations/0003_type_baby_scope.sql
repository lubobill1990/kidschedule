-- 行为类型按宝宝区分:baby_id 为空 = 所有宝宝通用
alter table public.activity_types
  add column baby_id uuid references public.babies(id);

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
    insert into activity_types (id, family_id, baby_id, name, icon, color, kind,
                                default_max_duration_sec, reminder_mode,
                                reminder_fixed_interval_sec, sort_order, deleted_at,
                                client_updated_at, device_id, created_by)
    values (
      (r->>'id')::uuid, (r->>'family_id')::uuid, (r->>'baby_id')::uuid,
      r->>'name', r->>'icon', r->>'color',
      r->>'kind', (r->>'default_max_duration_sec')::integer, r->>'reminder_mode',
      (r->>'reminder_fixed_interval_sec')::integer, (r->>'sort_order')::integer,
      (r->>'deleted_at')::timestamptz,
      (r->>'client_updated_at')::timestamptz, r->>'device_id', auth.uid()
    )
    on conflict (id) do update set
      baby_id = excluded.baby_id,
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
