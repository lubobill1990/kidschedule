-- 成员资料:emoji 头像 + 自更新 RPC(display_name / avatar_emoji)
-- role 不可经此修改,防止成员自提权,所以不开放 update RLS 而走 security definer RPC

alter table public.family_members add column if not exists avatar_emoji text;

create or replace function public.update_my_profile(
  p_family_id uuid,
  p_display_name text,
  p_avatar_emoji text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'not authenticated';
  end if;
  update family_members
    set display_name = p_display_name,
        avatar_emoji = p_avatar_emoji
  where family_id = p_family_id and user_id = auth.uid();
  if not found then
    raise exception 'not a member of this family';
  end if;
end;
$$;
