package com.hanfengruyue.pocketrdp.core.data.di

import android.content.Context
import com.hanfengruyue.pocketrdp.core.data.db.ConnectionDao
import com.hanfengruyue.pocketrdp.core.data.db.PocketRdpDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun providePocketRdpDatabase(@ApplicationContext context: Context): PocketRdpDatabase =
        PocketRdpDatabase.create(context)

    @Provides
    fun provideConnectionDao(db: PocketRdpDatabase): ConnectionDao = db.connectionDao()
}
