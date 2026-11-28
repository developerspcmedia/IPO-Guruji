package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.CachedAdminOverrideEntity
import com.example.data.model.IpoEntity
import com.example.data.model.PrivateUserProfileEntity
import com.example.data.model.UsIpoEntity
import com.example.security.CryptoBox

@Database(
    entities = [
        IpoEntity::class,
        PrivateUserProfileEntity::class,
        AllotmentWatchEntity::class,
        UsIpoEntity::class,
        CachedAdminOverrideEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(IpoTypeConverters::class)
abstract class IpoDatabase : RoomDatabase() {
    abstract fun ipoDao(): IpoDao
    abstract fun allotmentDao(): AllotmentDao
    abstract fun usIpoDao(): UsIpoDao
    abstract fun adminOverrideDao(): AdminOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: IpoDatabase? = null

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `us_ipos` (
                        `dealId` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `companyName` TEXT NOT NULL,
                        `exchange` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `priceRange` TEXT NOT NULL,
                        `sharesOffered` TEXT NOT NULL,
                        `dealSizeUsd` TEXT NOT NULL,
                        `dealSizeNumericUsd` REAL NOT NULL,
                        `eventDate` TEXT NOT NULL,
                        `eventDateEpochMs` INTEGER NOT NULL,
                        `secFormType` TEXT NOT NULL,
                        `secFilingUrl` TEXT NOT NULL,
                        `nasdaqUrl` TEXT NOT NULL,
                        `sourceLabel` TEXT NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`dealId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `admin_ipo_overrides` (
                        `overrideId` TEXT NOT NULL,
                        `targetIpoId` TEXT NOT NULL,
                        `normalizedKey` TEXT NOT NULL,
                        `companyName` TEXT NOT NULL,
                        `published` INTEGER NOT NULL,
                        `customIpo` INTEGER NOT NULL,
                        `hidden` INTEGER NOT NULL,
                        `shortCode` TEXT,
                        `sector` TEXT,
                        `boardType` TEXT,
                        `exchange` TEXT,
                        `status` TEXT,
                        `openDate` TEXT,
                        `closeDate` TEXT,
                        `allotmentDate` TEXT,
                        `listingDate` TEXT,
                        `priceLow` INTEGER,
                        `priceHigh` INTEGER,
                        `faceValue` INTEGER,
                        `lotSize` INTEGER,
                        `issueSizeCr` REAL,
                        `freshIssueCr` REAL,
                        `ofsCr` REAL,
                        `gmpRupees` INTEGER,
                        `kostakRupees` INTEGER,
                        `qibSubX` REAL,
                        `bNiiSubX` REAL,
                        `sNiiSubX` REAL,
                        `retailSubX` REAL,
                        `employeeSubX` REAL,
                        `totalSubX` REAL,
                        `registrarName` TEXT,
                        `registrarUrl` TEXT,
                        `overview` TEXT,
                        `adminNote` TEXT,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`overrideId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `ipos` ADD COLUMN `liveApplicationCounts` TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE `ipos` ADD COLUMN `subscriptionSourceLabel` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val updates = mutableListOf<Array<Any>>()
                db.query(
                    "SELECT `id`, `panNumber`, `dematId`, `upiHandle` FROM `private_user_profiles`",
                ).use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow("id")
                    val panIdx = cursor.getColumnIndexOrThrow("panNumber")
                    val dematIdx = cursor.getColumnIndexOrThrow("dematId")
                    val upiIdx = cursor.getColumnIndexOrThrow("upiHandle")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        val pan = cursor.getString(panIdx).orEmpty()
                        val demat = cursor.getString(dematIdx).orEmpty()
                        val upi = cursor.getString(upiIdx).orEmpty()

                        val encryptedPan = if (pan.isNotEmpty() && !CryptoBox.isEncrypted(pan)) {
                            CryptoBox.encrypt(pan)
                        } else {
                            pan
                        }
                        val encryptedDemat = if (demat.isNotEmpty() && !CryptoBox.isEncrypted(demat)) {
                            CryptoBox.encrypt(demat)
                        } else {
                            demat
                        }
                        val encryptedUpi = if (upi.isNotEmpty() && !CryptoBox.isEncrypted(upi)) {
                            CryptoBox.encrypt(upi)
                        } else {
                            upi
                        }
                        updates.add(arrayOf(encryptedPan, encryptedDemat, encryptedUpi, id))
                    }
                }
                for (bindArgs in updates) {
                    db.execSQL(
                        "UPDATE `private_user_profiles` SET `panNumber` = ?, `dematId` = ?, `upiHandle` = ? WHERE `id` = ?",
                        bindArgs,
                    )
                }
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `ipos` ADD COLUMN `subscriptionUpdatedAtMs` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun getInstance(context: Context): IpoDatabase {
            return INSTANCE ?: synchronized(this) {
                // private_user_profiles and allotment_watches hold user data and must never be dropped
                // by a fallback migration. Any future schema change must ship an explicit Migration.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IpoDatabase::class.java,
                    "ipo_pulse.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
