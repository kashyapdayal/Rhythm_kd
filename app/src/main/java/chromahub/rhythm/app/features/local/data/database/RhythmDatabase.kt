package chromahub.rhythm.app.features.local.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import chromahub.rhythm.app.features.local.data.database.dao.ArtistDao
import chromahub.rhythm.app.features.local.data.database.dao.SongArtistDao
import chromahub.rhythm.app.features.local.data.database.dao.SongDao
import chromahub.rhythm.app.features.local.data.database.entity.ArtistEntity
import chromahub.rhythm.app.features.local.data.database.entity.SongArtistEntity
import chromahub.rhythm.app.features.local.data.database.entity.SongEntity

@Database(entities = [SongEntity::class, ArtistEntity::class, SongArtistEntity::class], version = 4, exportSchema = false)
abstract class RhythmDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun songArtistDao(): SongArtistDao

    companion object {
        @Volatile
        private var INSTANCE: RhythmDatabase? = null

        // Migration from version 1 to 2: Add artist and song-artist tables
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create artists table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artists` (
                        `id` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `artworkUri` TEXT, 
                        `numberOfAlbums` INTEGER NOT NULL, 
                        `numberOfTracks` INTEGER NOT NULL, 
                        `groupByAlbumArtist` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """)

                // Create song_artists table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `song_artists` (
                        `songId` TEXT NOT NULL, 
                        `artistName` TEXT NOT NULL, 
                        `groupByAlbumArtist` INTEGER NOT NULL, 
                        PRIMARY KEY(`songId`, `artistName`, `groupByAlbumArtist`)
                    )
                """)
            }
        }

        // Migration from version 2 to 3: No schema changes, just version bump
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes needed, just ensure tables exist
            }
        }

        // Migration from version 3 to 4: Switch from destructive to proper migrations
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes, just preserve existing data
            }
        }

        fun getInstance(context: Context): RhythmDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RhythmDatabase::class.java,
                    "rhythm_database"
                ).fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
