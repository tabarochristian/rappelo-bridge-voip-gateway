package com.gateway.di

import android.content.Context
import com.gateway.data.db.AppDatabase
import com.gateway.data.db.dao.EventOutboxDao
import com.gateway.data.db.dao.GatewayLogDao
import com.gateway.data.db.dao.QueuedCallDao
import com.gateway.data.db.dao.SmsLogDao
import com.gateway.data.db.dao.SmsQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideQueuedCallDao(database: AppDatabase): QueuedCallDao {
        return database.queuedCallDao()
    }

    @Provides
    @Singleton
    fun provideSmsQueueDao(database: AppDatabase): SmsQueueDao {
        return database.smsQueueDao()
    }

    @Provides
    @Singleton
    fun provideSmsLogDao(database: AppDatabase): SmsLogDao {
        return database.smsLogDao()
    }

    @Provides
    @Singleton
    fun provideEventOutboxDao(database: AppDatabase): EventOutboxDao {
        return database.eventOutboxDao()
    }

    @Provides
    @Singleton
    fun provideGatewayLogDao(database: AppDatabase): GatewayLogDao {
        return database.gatewayLogDao()
    }
}
