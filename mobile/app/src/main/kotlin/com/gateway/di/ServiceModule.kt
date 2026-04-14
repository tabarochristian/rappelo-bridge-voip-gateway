package com.gateway.di

import com.gateway.bridge.AudioRouter
import com.gateway.bridge.AudioRouterImpl
import com.gateway.bridge.CallBridge
import com.gateway.bridge.CallBridgeImpl
import com.gateway.queue.CallQueue
import com.gateway.queue.CallQueueImpl
import com.gateway.queue.QueueRepository
import com.gateway.queue.QueueRepositoryImpl
import com.gateway.queue.SmsQueue
import com.gateway.queue.SmsQueueImpl
import com.gateway.telephony.gsm.DualSimManager
import com.gateway.telephony.gsm.DualSimManagerImpl
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.gsm.GsmCallManagerImpl
import com.gateway.telephony.gsm.SmsGsmManager
import com.gateway.telephony.gsm.SmsGsmManagerImpl
import com.gateway.telephony.sip.SipEngine
import com.gateway.telephony.sip.SipEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindGsmCallManager(impl: GsmCallManagerImpl): GsmCallManager

    @Binds
    @Singleton
    abstract fun bindDualSimManager(impl: DualSimManagerImpl): DualSimManager

    @Binds
    @Singleton
    abstract fun bindSmsGsmManager(impl: SmsGsmManagerImpl): SmsGsmManager

    @Binds
    @Singleton
    abstract fun bindSipEngine(impl: SipEngineImpl): SipEngine

    @Binds
    @Singleton
    abstract fun bindCallBridge(impl: CallBridgeImpl): CallBridge

    @Binds
    @Singleton
    abstract fun bindAudioRouter(impl: AudioRouterImpl): AudioRouter

    @Binds
    @Singleton
    abstract fun bindCallQueue(impl: CallQueueImpl): CallQueue

    @Binds
    @Singleton
    abstract fun bindSmsQueue(impl: SmsQueueImpl): SmsQueue

    @Binds
    @Singleton
    abstract fun bindQueueRepository(impl: QueueRepositoryImpl): QueueRepository
}
