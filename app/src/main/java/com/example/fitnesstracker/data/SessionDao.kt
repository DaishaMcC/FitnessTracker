package com.example.fitnesstracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao //marks this interface as a set of database operations Room should implement
interface SessionDao {
    //@Insert - Room writes the INSERT INTO sessions (...) SQL for us automatically,
    @Insert //just from the shape of the SessionEntity parameter
    suspend fun insertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentSessions(): List<SessionEntity>
}