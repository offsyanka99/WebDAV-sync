package org.vovchenko.webdavsync.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.FolderPairDao
import org.vovchenko.webdavsync.data.local.SyncFileStateDao
import org.vovchenko.webdavsync.data.local.SyncLogDao
import org.vovchenko.webdavsync.data.local.WebDavAccountDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideWebDavAccountDao(db: AppDatabase): WebDavAccountDao = db.webDavAccountDao()

    @Provides
    fun provideFolderPairDao(db: AppDatabase): FolderPairDao = db.folderPairDao()

    @Provides
    fun provideSyncFileStateDao(db: AppDatabase): SyncFileStateDao = db.syncFileStateDao()

    @Provides
    fun provideSyncLogDao(db: AppDatabase): SyncLogDao = db.syncLogDao()

    // CredentialStore/TrustedCertStore/SettingsDataStore take a plain android.content.Context
    // constructor param, so provide it here rather than adding a Hilt qualifier to each.
    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context
}
