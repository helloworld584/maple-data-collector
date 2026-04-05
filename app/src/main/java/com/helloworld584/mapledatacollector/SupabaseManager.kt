package com.helloworld584.mapledatacollector

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

@Serializable
data class PriceHistoryRecord(
    val game: String = "maple",
    val item_id: String,
    val item_name: String,
    val item_type: String = "consumable",
    val date: String,           // ISO format: YYYY-MM-DD
    val price: Double,
    val volume: Int,
    val listing_count: Int? = null
)

@Serializable
data class ItemMetaRecord(
    val game: String = "maple",
    val item_id: String,
    val item_name: String,
    val is_targetable: Boolean = true,
    val item_type: String = "consumable"
)

class SupabaseManager(url: String, key: String) {

    private val client = createSupabaseClient(
        supabaseUrl = url,
        supabaseKey = key
    ) {
        install(Postgrest)
    }

    suspend fun upsertPriceHistory(records: List<PriceHistoryRecord>): Result<Int> {
        return try {
            client.from("price_history").upsert(records)
            Result.success(records.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertItemMeta(records: List<ItemMetaRecord>): Result<Int> {
        return try {
            client.from("item_meta").upsert(records)
            Result.success(records.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getExistingItemIds(game: String = "maple"): Set<String> {
        return try {
            client.from("item_meta")
                .select { filter { eq("game", game) } }
                .decodeList<ItemMetaRecord>()
                .map { it.item_id }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
