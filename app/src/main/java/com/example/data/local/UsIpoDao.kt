package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.UsIpoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsIpoDao {
    @Query("SELECT * FROM us_ipos ORDER BY eventDateEpochMs DESC, companyName ASC")
    fun observeAllUsIpos(): Flow<List<UsIpoEntity>>

    @Query("SELECT * FROM us_ipos")
    suspend fun getAllSnapshot(): List<UsIpoEntity>

    @Query("SELECT * FROM us_ipos WHERE dealId = :dealId LIMIT 1")
    fun observeById(dealId: String): Flow<UsIpoEntity?>

    @Query("SELECT * FROM us_ipos WHERE dealId = :dealId LIMIT 1")
    suspend fun getById(dealId: String): UsIpoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<UsIpoEntity>)

    @Query("DELETE FROM us_ipos")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(items: List<UsIpoEntity>) {
        clearAll()
        if (items.isNotEmpty()) {
            upsertAll(items)
        }
    }

    @Query(
        "UPDATE us_ipos SET secFormType = :formType, secFilingUrl = :filingUrl, sourceLabel = :sourceLabel WHERE dealId = :dealId",
    )
    suspend fun updateSecFiling(
        dealId: String,
        formType: String,
        filingUrl: String,
        sourceLabel: String,
    )
}
