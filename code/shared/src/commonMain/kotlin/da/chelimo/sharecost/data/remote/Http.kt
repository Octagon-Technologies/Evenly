package da.chelimo.sharecost.data.remote

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared Ktor client config applied by every platform engine (OkHttp on Android, Darwin on iOS).
 * `ignoreUnknownKeys` keeps deserialization resilient to fields a server adds later (04 §9).
 */
fun HttpClientConfig<*>.installShareCostDefaults() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }
}
