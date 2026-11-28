package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.CachedAdminOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AdminOverrideDao {
    @Query("SELECT * FROM admin_ipo_overrides ORDER BY updatedAtMs DESC")
    fun observeAllOverrides(): Flow<List<CachedAdminOverrideEntity>>

    @Query("SELECT * FROM admin_ipo_overrides ORDER BY updatedAtMs DESC")
    suspend fun getAllSnapshot(): List<CachedAdminOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedAdminOverrideEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CachedAdminOverrideEntity)

    @Query("DELETE FROM admin_ipo_overrides WHERE overrideId = :overrideId")
    suspend fun deleteById(overrideId: String)

    @Query("DELETE FROM admin_ipo_overrides")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(items: List<CachedAdminOverrideEntity>) {
        clearAll()
        if (items.isNotEmpty()) {
            upsertAll(items)
        }
    }
}
