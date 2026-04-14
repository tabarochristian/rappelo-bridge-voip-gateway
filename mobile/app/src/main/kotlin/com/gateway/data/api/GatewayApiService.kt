package com.gateway.data.api

import com.gateway.data.api.model.ApiResponse
import com.gateway.data.api.model.CommandResponse
import com.gateway.data.api.model.EventBatch
import com.gateway.data.api.model.HeartbeatRequest
import com.gateway.data.api.model.PollResponse
import com.gateway.data.api.model.RegistrationRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GatewayApiService {

    @POST("api/v1/gateway/register")
    suspend fun register(
        @Body request: RegistrationRequest
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/gateway/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: HeartbeatRequest
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/gateway/{deviceId}/commands")
    suspend fun pollCommands(
        @Path("deviceId") deviceId: String,
        @Query("since") since: Long? = null
    ): Response<ApiResponse<PollResponse>>

    @POST("api/v1/gateway/{deviceId}/commands/{commandId}/result")
    suspend fun reportCommandResult(
        @Path("deviceId") deviceId: String,
        @Path("commandId") commandId: String,
        @Body response: CommandResponse
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/gateway/events")
    suspend fun pushEvents(
        @Body batch: EventBatch
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/gateway/{deviceId}/config")
    suspend fun getConfig(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<Map<String, String>>>
}
