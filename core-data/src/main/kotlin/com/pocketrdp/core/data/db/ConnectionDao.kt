package com.pocketrdp.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.pocketrdp.core.data.model.ConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY last_used_at DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun findById(id: Long): ConnectionEntity?

    @Upsert
    suspend fun upsert(entity: ConnectionEntity): Long

    @Delete
    suspend fun delete(entity: ConnectionEntity)

    @Query("UPDATE connections SET last_used_at = :timestamp WHERE id = :id")
    suspend fun touchLastUsed(id: Long, timestamp: Long)

    @Query("UPDATE connections SET cert_thumb_sha256 = :thumb WHERE id = :id")
    suspend fun setCertThumbprint(id: Long, thumb: String)
}
