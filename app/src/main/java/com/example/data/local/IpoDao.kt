package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.IpoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IpoDao {
    @Query("SELECT * FROM ipos ORDER BY sortOrder ASC")
    fun observeAllIpos(): Flow<List<IpoEntity>>

    @Query("SELECT * FROM ipos WHERE id = :id LIMIT 1")
    fun observeIpoById(id: String): Flow<IpoEntity?>

    @Query("SELECT * FROM ipos ORDER BY sortOrder ASC")
    suspend fun getAllSnapshot(): List<IpoEntity>

    @Query("SELECT COUNT(*) FROM ipos")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ipos: List<IpoEntity>)

    @Query("DELETE FROM ipos WHERE id NOT IN (:activeIds)")
    suspend fun deleteObsoleteIds(activeIds: List<String>)

    @Query("UPDATE ipos SET userLots = :lots WHERE id = :id")
    suspend fun updateUserLots(id: String, lots: Int)

    @Query("UPDATE ipos SET updatedAgoLabel = :label")
    suspend fun updateFreshnessLabel(label: String)
}
