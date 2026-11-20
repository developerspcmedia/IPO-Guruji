package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.PrivateUserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AllotmentDao {
    @Query("SELECT * FROM private_user_profiles ORDER BY isDefault DESC, createdAtMs ASC")
    fun observeProfiles(): Flow<List<PrivateUserProfileEntity>>

    @Query("SELECT * FROM private_user_profiles ORDER BY isDefault DESC, createdAtMs ASC")
    suspend fun getAllProfiles(): List<PrivateUserProfileEntity>

    @Query("SELECT * FROM private_user_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): PrivateUserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: PrivateUserProfileEntity)

    @Query("DELETE FROM private_user_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("DELETE FROM private_user_profiles")
    suspend fun deleteAllProfiles()

    @Query("SELECT * FROM allotment_watches ORDER BY createdAtMs DESC")
    fun observeWatches(): Flow<List<AllotmentWatchEntity>>

    @Query("SELECT * FROM allotment_watches ORDER BY createdAtMs DESC")
    suspend fun getAllWatches(): List<AllotmentWatchEntity>

    @Query("SELECT * FROM allotment_watches WHERE id = :watchId LIMIT 1")
    suspend fun getWatchById(watchId: String): AllotmentWatchEntity?

    @Query("SELECT * FROM allotment_watches WHERE profileId = :profileId AND ipoId = :ipoId LIMIT 1")
    suspend fun findWatchForProfileAndIpo(profileId: String, ipoId: String): AllotmentWatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatch(watch: AllotmentWatchEntity)

    @Update
    suspend fun updateWatch(watch: AllotmentWatchEntity)

    @Query("DELETE FROM allotment_watches WHERE id = :watchId")
    suspend fun deleteWatch(watchId: String)

    @Query("DELETE FROM allotment_watches WHERE profileId = :profileId")
    suspend fun deleteWatchesForProfile(profileId: String)

    @Query("DELETE FROM allotment_watches")
    suspend fun deleteAllWatches()
}
