package com.samford.patient.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        VisitEntity::class,
        DocumentEntity::class,
        DocumentVersionEntity::class,
        PageEntity::class,
        RecipientEntity::class,
        LabResultEntity::class,
        FeedItemEntity::class,
        SyncStateEntity::class,
        BlobEntity::class,
        PendingAckEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SamfordDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao
    abstract fun sync(): SyncDao
    abstract fun blobs(): BlobDao
    abstract fun acks(): AckDao

    companion object {
        const val NAME = "samford-patient.db"

        fun create(context: Context): SamfordDatabase =
            Room.databaseBuilder(context.applicationContext, SamfordDatabase::class.java, NAME)
                // This is a cache of server records: on an incompatible schema change, rebuild it from the server.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): SamfordDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SamfordDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
