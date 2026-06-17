package da.chelimo.sharecost.data.remote.supabase

/**
 * Supabase project credentials (06 §5.5). These are **placeholders** — replace [URL] and [ANON_KEY]
 * with your project's values (Supabase dashboard → Project Settings → API). The anon key is a public,
 * RLS-gated key and is safe to ship in the client.
 *
 * While [isConfigured] is false the app stays fully local (the [da.chelimo.sharecost.data.auth.StubAuthSession]
 * + Room-only path); flipping in real credentials switches DI to the Supabase auth + sync path.
 */
object SupabaseConfig {
    const val URL: String = "https://wfpfgbipjmkysalfmyub.supabase.co"
    const val ANON_KEY: String = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndmcGZnYmlwam1reXNhbGZteXViIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE1NDEzNTUsImV4cCI6MjA5NzExNzM1NX0.wFUaDdhtMNF_FjEZU0p-GaPhrsiS80evxFO16QKLGJ0"

    val isConfigured: Boolean
        get() = !URL.contains("YOUR_PROJECT_REF") && ANON_KEY.isNotBlank() && !ANON_KEY.startsWith("YOUR_")
}
