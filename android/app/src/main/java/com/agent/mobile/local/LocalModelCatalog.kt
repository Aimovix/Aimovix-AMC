package com.agent.mobile.local

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import org.json.JSONObject

/** What the phone can run, as far as choosing a local model is concerned. */
data class DeviceProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val freeStorageMb: Long,
    val abis: List<String>,
    val cpuFeatures: Set<String>,
    val performanceCores: Int,
    val socModel: String? = null
) {
    val arm64: Boolean get() = abis.any { it == "arm64-v8a" }
    /** Dot-product instructions: llama.cpp's fast int8 kernels for Q4 quantizations. */
    val dotProd: Boolean get() = "asimddp" in cpuFeatures
    val i8mm: Boolean get() = "i8mm" in cpuFeatures
}

data class CatalogModel(
    val id: String,
    val title: String,
    val file: String,
    val url: String,
    val sha256: String,
    val sizeMb: Long,
    val minRamMb: Long,
    val ctx: Int,
    val tier: String
) {
    /** Checksum known: the download can be verified, not only its GGUF header. */
    val verified: Boolean get() = sha256.length == 64
}

/** The choice for this phone, or why none fits. */
sealed class Recommendation {
    data class Model(val model: CatalogModel, val ctx: Int, val threads: Int, val notes: List<String>) : Recommendation()
    data class NotRecommended(val reason: String) : Recommendation()
}

/**
 * The local model catalog (bundled copy of termux-bridge/model_catalog.json; the release can ship
 * a newer one) and the rule that picks a model for a phone.
 *
 * Rule of thumb: model + KV cache ≤ 40 % of the total RAM, with 1.5 GB kept free beyond that, and
 * no local model below 6 GB RAM. Tiers: 12 GB+ the 4B Q4_K_M with 16k context, 8–11 GB the 4B
 * Q4_0 (ARM repacking, faster) with 8k, 6–8 GB the 2B for screen steps. Experimental entries are
 * never chosen automatically. The benchmark after the download has the last word.
 */
object LocalModelCatalog {
    const val MIN_RAM_MB = 6L * 1024 - 512
    const val RESERVE_MB = 1536L
    const val RAM_SHARE = 0.40
    const val MIN_CTX = 8192
    private val TIER_ORDER = listOf("primary", "speed", "small")

    fun parse(json: String): List<CatalogModel> {
        val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val m = models.optJSONObject(i) ?: return@mapNotNull null
            val file = m.optString("file")
            val url = m.optString("url")
            if (file.isBlank() || !url.startsWith("https://")) return@mapNotNull null
            CatalogModel(
                id = m.optString("id", file),
                title = m.optString("title", file),
                file = file,
                url = url,
                sha256 = m.optString("sha256"),
                sizeMb = m.optLong("size_mb"),
                minRamMb = m.optLong("min_ram_mb"),
                ctx = m.optInt("ctx", MIN_CTX),
                tier = m.optString("tier", "experimental")
            )
        }
    }

    /** KV cache estimate for a ~4B model with q8_0 cache (grouped-query attention). */
    fun kvCacheMb(ctx: Int): Long = ctx / 1024L * 36L

    fun recommend(profile: DeviceProfile, catalog: List<CatalogModel>): Recommendation {
        if (!profile.arm64) return Recommendation.NotRecommended("Local models need a 64-bit ARM phone (arm64-v8a).")
        if (profile.totalRamMb < MIN_RAM_MB) {
            return Recommendation.NotRecommended(
                "This phone has ${profile.totalRamMb / 1024} GB RAM. Below 6 GB a local model is too slow and too weak for tool calls; use a cloud model."
            )
        }
        val budget = (profile.totalRamMb * RAM_SHARE).toLong()
        val candidates = catalog.filter { it.tier in TIER_ORDER }.sortedBy { TIER_ORDER.indexOf(it.tier) }
        for (model in candidates) {
            if (model.minRamMb > profile.totalRamMb) continue
            if (profile.freeStorageMb < model.sizeMb + model.sizeMb / 5) continue
            val ctx = generateSequence(model.ctx) { it / 2 }.takeWhile { it >= MIN_CTX }
                .firstOrNull { model.sizeMb + kvCacheMb(it) <= budget } ?: continue
            val notes = buildList {
                if (!profile.dotProd) add("The CPU has no dot-product instructions; expect a slow model.")
                if (profile.availRamMb < model.sizeMb + kvCacheMb(ctx) + RESERVE_MB) {
                    add("Little free memory right now; close other apps before using the local model.")
                }
                if (!model.verified) add("The catalog has no checksum for this file yet; only its GGUF header is checked.")
            }
            return Recommendation.Model(model, ctx, threads = profile.performanceCores.coerceIn(2, 8), notes = notes)
        }
        val free = profile.freeStorageMb / 1024
        return Recommendation.NotRecommended("No catalog model fits this phone (RAM ${profile.totalRamMb / 1024} GB, free storage $free GB).")
    }

    /** The next smaller model when the benchmark is too slow (speed tier, then small tier). */
    fun smallerThan(current: CatalogModel, catalog: List<CatalogModel>): CatalogModel? {
        val idx = TIER_ORDER.indexOf(current.tier)
        return catalog.filter { TIER_ORDER.indexOf(it.tier) > idx }.minByOrNull { TIER_ORDER.indexOf(it.tier) }
    }

    /** Model settings for llama-server on this phone; the previous (cloud) model becomes the fallback. */
    fun localConfig(previous: ModelConfig, port: Int = 8080): ModelConfig {
        val keepFallback = previous.provider == ProviderType.LOCAL
        return ModelConfig(
            provider = ProviderType.LOCAL,
            modelName = "default",
            apiKey = "",
            baseUrl = "http://127.0.0.1:$port/v1",
            fallbackProvider = if (keepFallback) previous.fallbackProvider else previous.provider,
            fallbackModelName = if (keepFallback) previous.fallbackModelName else previous.modelName,
            fallbackApiKey = if (keepFallback) previous.fallbackApiKey else previous.apiKey,
            fallbackBaseUrl = if (keepFallback) previous.fallbackBaseUrl else previous.baseUrl
        )
    }

    /** CPU feature flags from /proc/cpuinfo ("Features : fp asimd ... asimddp ... i8mm"). */
    fun parseCpuFeatures(cpuinfo: String): Set<String> =
        cpuinfo.lineSequence()
            .filter { it.trim().startsWith("Features", ignoreCase = true) }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")).asSequence() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Performance cores: CPUs whose maximum frequency is above the slowest cluster's. */
    fun performanceCores(maxFreqsKhz: List<Long>): Int {
        if (maxFreqsKhz.isEmpty()) return 4
        val min = maxFreqsKhz.min()
        val fast = maxFreqsKhz.count { it > min }
        return if (fast > 0) fast else maxFreqsKhz.size
    }
}
