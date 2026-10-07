package com.argun.mapper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.argun.mapper.data.entity.SavedDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedDeviceDao {

    @Query("SELECT * FROM saved_devices ORDER BY lastSeen DESC")
    fun getAll(): Flow<List<SavedDevice>>

    @Query("SELECT * FROM saved_devices WHERE address = :address LIMIT 1")
    suspend fun get(address: String): SavedDevice?

    @Query("SELECT * FROM saved_devices ORDER BY lastSeen DESC LIMIT 1")
    suspend fun getMostRecent(): SavedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: SavedDevice)

    @Query("DELETE FROM saved_devices WHERE address = :address")
    suspend fun forget(address: String)

    @Query("DELETE FROM saved_devices")
    suspend fun forgetAll()
}