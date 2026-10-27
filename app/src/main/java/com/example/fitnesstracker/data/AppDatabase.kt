package com.example.fitnesstracker.data

import android.content.Context
import android.graphics.Color
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

//@database entities list every @entity class this database contains
//version = 1 bump this number and write a migration if the table shape ever changes
//exportSchema = false skips writing a schema history file to disk
@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase: RoomDatabase () {
    //abstract we declare the shape room fills in the reel implementation
    abstract fun sessionDao(): SessionDao

    companion object{
        //guarantees every thread sees the last value of INSTANCE immediately
        @Volatile //not  a stale cached copy
        private var INSTANCE: AppDatabase? = null

        //the only way anything in the app should obtain a database reference
        fun getInstance(context: Context): AppDatabase {
            // if INSTANCE exists return immediately
            //if not synchronized make sure 2 threads cant be created at once
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext, //outlives any single activity screen
                    AppDatabase::class.java,//which databases class to build
                    "fitness_tracker_db" //the actual .db filename on disk
                ).build().also { INSTANCE = it } //cache it so next call skips straight to the top
            }
        }
    }
}