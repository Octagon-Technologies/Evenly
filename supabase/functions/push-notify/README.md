# `push-notify` edge function (F7)

Sends an FCM notification to the device tokens of a group's members. **Wired, but delivers nothing** until
the FCM credential is configured: every call then returns `200 {sent:0, wouldSend:n, skipped:"FCM_SERVICE_ACCOUNT not set"}`,
which doubles as a dry run of who would have been notified.

## Who calls it
Two `AFTER INSERT` triggers in `schema.sql` (`expenses_push_notify`, `settlements_push_notify`) build the
message text in SQL and queue a `pg_net` POST through `push_dispatch()`:

| Event | Recipients | Pref | Example |
| --- | --- | --- | --- |
| Expense inserted | every ACTIVE member except its creator | `newExpenses` | "Bob added Dinner at Nopa" / "$84.00 in New group" |
| Payment inserted | each party who did not record it | `payments` | "Bob paid you $21.00" / "In New group" |

The sync push is an `INSERT ... ON CONFLICT DO UPDATE`, and `AFTER INSERT` fires only for rows actually
inserted, so a re-push or an edit never re-notifies. Payment edit replacements (`original_settlement_id`)
are skipped. Every trigger path swallows its own errors: a failed push must never refuse the row.

## What it does
Given `{ groupId, title, body, data?, excludeUserId?, onlyUserIds?, pref? }` it:
1. resolves the group's ACTIVE members (minus `excludeUserId`, narrowed to `onlyUserIds`),
2. drops anyone who opted out of `pref` (`newExpenses` / `payments` / `conflictReminders` — the columns on `users`),
3. looks up their rows in `device_tokens`,
4. mints an OAuth token from the service account and POSTs each one via FCM HTTP v1, deleting any token
   FCM reports as `UNREGISTERED`.

## Auth
`verify_jwt = false` (see `config.toml`); the function's own check accepts only `PUSH_NOTIFY_SECRET` or the
runtime service-role key as the Bearer. The triggers send `PUSH_NOTIFY_SECRET`, mirrored in Vault as
`push_notify_service_key`. They used to be meant to send the service-role JWT, and every such call was
refused: the runtime's `SUPABASE_SERVICE_ROLE_KEY` is not that string. The welcome-email webhook on
`auth.users` still hardcodes it and has been 403ing for the same reason.

## To turn delivery on
1. Firebase console → Project settings → Service accounts → **Generate new private key** (a JSON file).
   The service account needs the *Firebase Cloud Messaging API*.
2. Set it as a secret (single line of JSON):
   ```bash
   supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)" --project-ref wfpfgbipjmkysalfmyub
   ```
3. iOS also needs the Push Notifications capability and an APNs key uploaded to Firebase
   (`code/iosApp/PUSH_SETUP.md` §3).

## Checking it
```sql
select id, status_code, content, created from net._http_response order by id desc limit 10;
```

## Group deletion (the one event where a push is not optional)

Any ACTIVE member can delete a group **for everyone**, recoverable for 30 days from Home → Recently
deleted. The in-app half of that is built and works without push: the group's own screen is replaced by
a gate naming who deleted it with a Restore button, and a "Recently deleted (n)" row appears on Home.
The push is what makes it *timely* rather than discovered-later, and it is the mitigation the design
leans on for letting one member erase five people's shared ledger.

**This is not a plain UPDATE webhook.** A Dashboard webhook on `groups` UPDATE fires on every rename and
every invite-token rotation too, so the notification has to be conditioned on the tombstone actually
appearing. When `FCM_SERVICE_ACCOUNT` is set, wire it as an `AFTER UPDATE` trigger that fires only on
the NULL → non-NULL transition:

```sql
-- Requires pg_net (not currently enabled) and the service-role key in Vault. Neither is set up today,
-- which is why this is a recipe and not a migration: turning push on is one decision, not per-event.
create or replace function public.notify_group_deleted()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if old.deleted_at is null and new.deleted_at is not null then
    perform net.http_post(
      url     := '<project-url>/functions/v1/push-notify',
      headers := jsonb_build_object('Authorization', 'Bearer ' || <service-role-key-from-vault>,
                                    'Content-Type', 'application/json'),
      body    := jsonb_build_object(
        'groupId',       new.id,
        'title',         (select display_name from public.users where id = new.deleted_by)
                           || ' deleted ' || new.name,
        'body',          'It is in Recently deleted for 30 days. Tap to restore it for everyone.',
        'excludeUserId', new.deleted_by,
        'data',          jsonb_build_object('route', 'recently_deleted', 'groupId', new.id)
      ));
  end if;
  return new;
end $$;
```

Deliberately **no `pref`**: the three opt-outs are `newExpenses` / `payments` / `conflictReminders`, and
a group vanishing is none of those. It is not activity in a group, it is the group ending, and someone
who muted expense notifications has not asked to stop being told their ledger was deleted.
