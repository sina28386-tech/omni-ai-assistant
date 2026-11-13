package com.omni.aiassistant.di

import androidx.room.Room
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.core.logging.DiagnosticsExporter
import com.omni.aiassistant.core.logging.SafeLogger
import com.omni.aiassistant.core.network.AiProviderGateway
import com.omni.aiassistant.core.network.OpenAiCompatibleGateway
import com.omni.aiassistant.core.network.RetrofitServiceFactory
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.core.security.PortableBackupCipher
import com.omni.aiassistant.data.attachment.AttachmentStore
import com.omni.aiassistant.data.backup.BackupManager
import com.omni.aiassistant.data.conversation.ConversationRepositoryImpl
import com.omni.aiassistant.data.db.AppDatabase
import com.omni.aiassistant.data.provider.ProviderRepositoryImpl
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.DeviceActionExecutor
import com.omni.aiassistant.device.DeviceInfoProvider
import com.omni.aiassistant.device.FileAgentRunner
import com.omni.aiassistant.device.FileIndexProvider
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import com.omni.aiassistant.data.memory.UserMemoryRepository
import com.omni.aiassistant.data.style.UserStyleRepository
import com.omni.aiassistant.data.verbs.UserVerbRepository
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.device.context.EnvironmentContextProvider
import com.omni.aiassistant.feature.chat.AgentContextProvider
import com.omni.aiassistant.feature.chat.ChatViewModel
import com.omni.aiassistant.feature.bots.BotsViewModel
import com.omni.aiassistant.feature.conversations.ConversationsViewModel
import com.omni.aiassistant.feature.providers.ProvidersViewModel
import com.omni.aiassistant.feature.settings.SettingsViewModel
import com.omni.aiassistant.feature.voice.VoiceViewModel
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
            encodeDefaults = false
            coerceInputValues = true
        }
    }
    single { SafeLogger() }
    single { KeystoreAead() }
    single { PortableBackupCipher() }
    single {
        val logging = HttpLoggingInterceptor { message -> get<SafeLogger>().info("HTTP", message) }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
            redactHeader("X-Api-Key")
        }
        OkHttpClient.Builder()
            // Provider traffic carries prompts, images and keys. Never follow a
            // redirect automatically: a 307/308 can replay the body and custom
            // headers to a different origin.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .retryOnConnectionFailure(true)
            .build()
    }
    single { RetrofitServiceFactory(get(), get()) }
    single { AttachmentStore(androidContext()) }
    single<AiProviderGateway> {
        val store = get<AttachmentStore>()
        OpenAiCompatibleGateway(
            retrofitFactory = get(),
            baseClient = get(),
            json = get(),
            attachmentSource = { path -> store.safeFile(path).readBytes() },
            logger = get(),
            platformCleartextAllowed = BuildConfig.ALLOW_LOCAL_CLEARTEXT,
        )
    }

    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "omni_ai.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().conversationDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().attachmentDao() }

    single { SettingsRepository(androidContext(), get()) }
    single { UserVerbRepository(androidContext(), get()) }
    single { UserMemoryRepository(androidContext(), get()) }
    single { UserStyleRepository(androidContext(), get()) }
    single { EnvironmentContextProvider(androidContext(), get<CapabilityCenter>(), get()) }
    single { AgentContextProvider(get(), get(), get(), get()) }
    single<ProviderRepository> { ProviderRepositoryImpl(get(), get(), get()) }
    single<ConversationRepository> { ConversationRepositoryImpl(get(), get(), get(), get(), get(), get()) }
    single { BackupManager(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { DiagnosticsExporter(androidContext(), get(), get()) }
    single { DeviceActionExecutor(androidContext()) }
    single { DeviceInfoProvider(androidContext()) }
    single {
        val context = androidContext()
        FileIndexProvider(
            listOf(
                java.io.File(context.filesDir, "agent").apply { mkdirs() },
                java.io.File(context.filesDir, "attachments"),
                context.cacheDir,
            ),
        )
    }
    single { FileAgentRunner(androidContext(), get()) }

    viewModel { ConversationsViewModel(get(), get(), get()) }
    viewModel { BotsViewModel(get(), get(), get()) }
    viewModel { ProvidersViewModel(get(), get(), get(), get()) }
    viewModel { (conversationId: String) -> ChatViewModel(conversationId, get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { VoiceViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
