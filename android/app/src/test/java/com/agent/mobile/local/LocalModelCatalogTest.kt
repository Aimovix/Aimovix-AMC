package com.agent.mobile.local

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalModelCatalogTest {

    private val catalog by lazy {
        // The bundled asset and the Termux copy must stay the same file.
        val asset = File("src/main/assets/model_catalog.json").readText()
        assertEquals(File("../../termux-bridge/model_catalog.json").readText(), asset)
        LocalModelCatalog.parse(asset)
    }

    private fun phone(ramGb: Double, freeGb: Long = 64, features: Set<String> = setOf("asimddp", "i8mm")) = DeviceProfile(
        totalRamMb = (ramGb * 1024).toLong(), availRamMb = (ramGb * 512).toLong(), freeStorageMb = freeGb * 1024,
        abis = listOf("arm64-v8a"), cpuFeatures = features, performanceCores = 4
    )

    private fun pick(p: DeviceProfile) = LocalModelCatalog.recommend(p, catalog) as? Recommendation.Model

    @Test
    fun tiersFollowTheRam() {
        assertEquals("qwen3.5-4b-q4_k_m", pick(phone(11.6))!!.model.id)
        assertEquals(16384, pick(phone(16.0))!!.ctx)
        val eight = pick(phone(7.6))!!
        assertEquals("qwen3.5-4b-q4_0", eight.model.id)
        assertEquals(8192, eight.ctx)
        assertEquals("qwen3.5-2b-q4_k_m", pick(phone(5.7))!!.model.id)
        assertTrue(LocalModelCatalog.recommend(phone(3.8), catalog) is Recommendation.NotRecommended)
    }

    @Test
    fun experimentalModelsAndNon64BitPhonesAreNeverChosen() {
        assertTrue(catalog.any { it.tier == "experimental" })
        assertNotEquals("experimental", pick(phone(24.0))!!.model.tier)
        val armv7 = phone(12.0).copy(abis = listOf("armeabi-v7a"))
        assertTrue(LocalModelCatalog.recommend(armv7, catalog) is Recommendation.NotRecommended)
    }

    @Test
    fun storageAndCpuAreChecked() {
        assertTrue(LocalModelCatalog.recommend(phone(12.0, freeGb = 1), catalog) is Recommendation.NotRecommended)
        val slowCpu = pick(phone(12.0, features = emptySet()))!!
        assertTrue(slowCpu.notes.any { it.contains("dot-product") })
    }

    @Test
    fun smallerModelForTheBenchmarkFallback() {
        val primary = catalog.first { it.tier == "primary" }
        assertEquals("speed", LocalModelCatalog.smallerThan(primary, catalog)!!.tier)
        val small = catalog.first { it.tier == "small" }
        assertNull(LocalModelCatalog.smallerThan(small, catalog))
    }

    @Test
    fun cpuFeaturesAndPerformanceCores() {
        val cpuinfo = "processor : 0\nFeatures : fp asimd evtstrm aes asimddp i8mm\nprocessor : 1\nFeatures : fp asimd\n"
        assertTrue(LocalModelCatalog.parseCpuFeatures(cpuinfo).containsAll(setOf("asimddp", "i8mm", "fp")))
        assertEquals(4, LocalModelCatalog.performanceCores(listOf(1800000L, 1800000L, 1800000L, 1800000L, 2800000L, 2800000L, 2800000L, 3300000L)))
        assertEquals(4, LocalModelCatalog.performanceCores(listOf(2000000L, 2000000L, 2000000L, 2000000L)))
    }

    @Test
    fun localConfigKeepsTheCloudModelAsFallback() {
        val cloud = ModelConfig(provider = ProviderType.OPENAI, modelName = "gpt-x", apiKey = "k", baseUrl = "https://api.example/v1")
        val local = LocalModelCatalog.localConfig(cloud)
        assertEquals(ProviderType.LOCAL, local.provider)
        assertEquals("http://127.0.0.1:8080/v1", local.baseUrl)
        assertEquals(ProviderType.OPENAI, local.fallbackProvider)
        assertEquals("k", local.fallbackApiKey)
        // Running setup again keeps the original cloud fallback.
        assertEquals(ProviderType.OPENAI, LocalModelCatalog.localConfig(local).fallbackProvider)
    }
}
