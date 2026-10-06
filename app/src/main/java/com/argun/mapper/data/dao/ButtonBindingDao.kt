package com.argun.mapper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.argun.mapper.data.entity.ButtonBinding
import kotlinx.coroutines.flow.Flow

@Dao
interface ButtonBindingDao {

    @Query("SELECT * FROM key_mappings ORDER BY argunButton")
    fun getAllBindings(): Flow<List<ButtonBinding>>

    @Query("SELECT * FROM key_mappings WHERE argunButton = :button LIMIT 1")
    suspend fun getBinding(button: String): ButtonBinding?

    @Query("SELECT phoneKeyCode FROM key_mappings WHERE argunButton = :button LIMIT 1")
    suspend fun getKeyCodeFor(button: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(binding: ButtonBinding)

    @Query("DELETE FROM key_mappings")
    suspend fun clearAll()
}