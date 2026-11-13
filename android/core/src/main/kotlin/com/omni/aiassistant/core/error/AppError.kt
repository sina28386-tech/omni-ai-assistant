package com.omni.aiassistant.core.error

sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Validation(message: String) : AppError(message)
    class Authentication(message: String = "Authentication failed") : AppError(message)
    class Permission(message: String) : AppError(message)
    class Network(message: String, cause: Throwable? = null) : AppError(message, cause)
    class RateLimit(message: String = "Rate limit reached") : AppError(message)
    class InsufficientCredit(message: String = "Insufficient credit or quota") : AppError(message)
    class Provider(message: String) : AppError(message)
    class Database(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Encryption(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Backup(message: String, cause: Throwable? = null) : AppError(message, cause)
    class UnsupportedFeature(message: String) : AppError(message)
    class ContextLength(message: String) : AppError(message)
    class ModelUnavailable(message: String) : AppError(message)
    class Timeout(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Tls(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Endpoint(message: String) : AppError(message)
}
