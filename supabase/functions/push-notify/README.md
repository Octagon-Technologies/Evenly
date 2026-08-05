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
