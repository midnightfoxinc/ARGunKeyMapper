package com.argun.mapper.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_mappings")
data class ButtonBinding(
    @PrimaryKey val argunButton: String,
    val phoneKeyCode: Int,
    val phoneKeyName: String,
    val action: Int = 0
)