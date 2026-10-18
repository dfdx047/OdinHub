package com.dfdx047.odinhub.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {

    @Provides
    fun provideAppOverrideDao(db: AppDatabase): AppOverrideDao {
        return db.appOverrideDao()
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase {
        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "app",
        )
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            // Mantido como rede de segurança para saltos de versão sem migração explícita
            // (ex: instalações muito antigas, v1-v3); a v4->v5 agora preserva os dados.
            .fallbackToDestructiveMigration()
            .build()
    }
}