package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.error.AppError
import org.junit.Assert.*
import org.junit.Test

class ApiErrorMapperTest {
    @Test fun mapsAuthenticationAndQuota() {
        assertTrue(ApiErrorMapper.fromHttp(401, "bad key") is AppError.Authentication)
        assertTrue(ApiErrorMapper.fromHttp(429, "quota exceeded") is AppError.InsufficientCredit)
        assertTrue(ApiErrorMapper.fromHttp(429, "too many requests") is AppError.RateLimit)
    }

    @Test fun mapsContextAndModelErrors() {
        assertTrue(ApiErrorMapper.fromHttp(400, "context token limit exceeded") is AppError.ContextLength)
        assertTrue(ApiErrorMapper.fromHttp(404, "missing") is AppError.ModelUnavailable)
    }

    @Test fun redactsKeyLikeFragments() {
        val error = ApiErrorMapper.fromHttp(400, "key=supersecret123 request rejected")
        assertFalse(error.message.orEmpty().contains("supersecret123"))
    }
}
