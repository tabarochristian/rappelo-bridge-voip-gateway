package com.gateway.di

import android.content.Context
import com.gateway.BuildConfig
import com.gateway.data.api.GatewayApiService
import com.gateway.data.prefs.EncryptedPrefsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = false
            coerceInputValues = true
        }
    }

    @Provides
    @Singleton
    @Named("AuthInterceptor")
    fun provideAuthInterceptor(prefsManager: EncryptedPrefsManager): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val token = prefsManager.getApiToken()
            
            val newRequest = if (token.isNotEmpty()) {
                originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .build()
            } else {
                originalRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .build()
            }
            
            chain.proceed(newRequest)
        }
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }
    }

    @Provides
    @Singleton
    fun provideCertificatePinner(prefsManager: EncryptedPrefsManager): CertificatePinner? {
        if (!BuildConfig.ENABLE_CERT_PINNING) {
            return null
        }
        
        val apiHost = try {
            val url = prefsManager.getApiBaseUrl()
            if (url.isNotEmpty()) {
                java.net.URL(url).host
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        
        return apiHost?.let { host ->
            CertificatePinner.Builder()
                .add(host, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @Named("AuthInterceptor") authInterceptor: Interceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        certificatePinner: CertificatePinner?
    ): OkHttpClient {
        return OkHttpClient.Builder().apply {
            addInterceptor(authInterceptor)
            addInterceptor(loggingInterceptor)
            certificatePinner?.let { certificatePinner(it) }
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
        }.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        prefsManager: EncryptedPrefsManager,
        json: Json
    ): Retrofit {
        val baseUrl = prefsManager.getApiBaseUrl().ifEmpty { BuildConfig.API_BASE_URL }
        
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideGatewayApiService(retrofit: Retrofit): GatewayApiService {
        return retrofit.create(GatewayApiService::class.java)
    }
}
