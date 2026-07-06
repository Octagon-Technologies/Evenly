package da.chelimo.sharecost.data.remote.supabase

/**
 * Supabase project credentials (06 §5.5). [URL]/[ANON_KEY] point at the hosted cloud project by
 * default. The anon key is a public, RLS-gated key and is safe to ship in the client.
 *
 * While [isConfigured] is false the app stays fully local (the [da.chelimo.sharecost.data.auth.StubAuthSession]
 * + Room-only path); flipping in real credentials switches DI to the Supabase auth + sync path.
 *
 * **Local dev (emulator/simulator) — set [USE_LOCAL] = true** to point at a `supabase start` Docker
 * stack (`supabase/config.toml`) instead of the metered cloud project, so emulator churn during
 * testing never counts against its quota. [LOCAL_ANON_KEY]/[LOCAL_JWT_SECRET] are the CLI's fixed
 * local-dev defaults (same for every `supabase start`, not a real secret). [LOCAL_URL] targets
 * `10.0.2.2` — the Android emulator's alias for the host machine's `127.0.0.1` (the emulator has its
 * own loopback, so plain `127.0.0.1` doesn't reach the host). Running on the iOS Simulator or a
 * physical device instead? Swap it for `http://127.0.0.1:54421` (Simulator shares the host network)
 * or `http://<your-lan-ip>:54421` (physical device on the same network).
 */
object SupabaseConfig {
    private const val USE_LOCAL: Boolean = false

    private const val CLOUD_URL: String = "https://wfpfgbipjmkysalfmyub.supabase.co"
    private const val CLOUD_ANON_KEY: String = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndmcGZnYmlwam1reXNhbGZteXViIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE1NDEzNTUsImV4cCI6MjA5NzExNzM1NX0.wFUaDdhtMNF_FjEZU0p-GaPhrsiS80evxFO16QKLGJ0"

    private const val LOCAL_URL: String = "http://10.0.2.2:54421"
    private const val LOCAL_ANON_KEY: String = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"

    val URL: String = if (USE_LOCAL) LOCAL_URL else CLOUD_URL
    val ANON_KEY: String = if (USE_LOCAL) LOCAL_ANON_KEY else CLOUD_ANON_KEY

    val isConfigured: Boolean
        get() = !URL.contains("YOUR_PROJECT_REF") && ANON_KEY.isNotBlank() && !ANON_KEY.startsWith("YOUR_")
}
