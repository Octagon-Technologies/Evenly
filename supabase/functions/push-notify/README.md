# `push-notify` edge function (F7)

Sends an FCM notification to the device tokens of a group's members. **Deployed but inert** until the
FCM credential is configured — without it, every call returns `200 {sent:0, skipped:"FCM_SERVICE_ACCOUNT not set"}`,
so any trigger that calls it is safe.

## What it does
Given `{ groupId, title, body, data?, excludeUserId?, pref? }` it:
1. resolves the group's ACTIVE members (minus `excludeUserId`),
2. drops anyone who opted out of `pref` (`newExpenses` / `payments` / `conflictReminders` — the columns on `users`),
3. looks up their rows in `device_tokens`,
4. mints an OAuth token from the service account and POSTs each one via FCM HTTP v1.

It reads tokens with the service role (`SUPABASE_SERVICE_ROLE_KEY`, auto-injected), so the caller's own
permissions don't matter — but `verify_jwt` is on, so callers must send a valid bearer (a DB webhook sends
the service-role key; the app sends the user JWT).

## To turn it on
1. Firebase console → Project settings → Service accounts → **Generate new private key** (a JSON file).
   The service account needs the *Firebase Cloud Messaging API*.
2. Set it as a secret (single line of JSON):
   ```bash
   supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)" --project-ref wfpfgbipjmkysalfmyub
   ```
3. (Optional, to fire automatically) add a **Database Webhook** (Dashboard → Database → Webhooks) on
   `expenses` / `settlements` INSERT that POSTs to this function. Build the body from the row, e.g.
   `{ "groupId": record.group_id, "title": "New expense", "body": record.title, "pref": "newExpenses",
   "excludeUserId": record.created_by }`. Or invoke it from your own server logic.

## Manual test
```bash
curl -X POST "https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/push-notify" \
  -H "Authorization: Bearer <SERVICE_ROLE_KEY>" -H "Content-Type: application/json" \
  -d '{"groupId":"<group-uuid>","title":"Hi","body":"Test","pref":"newExpenses"}'
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
