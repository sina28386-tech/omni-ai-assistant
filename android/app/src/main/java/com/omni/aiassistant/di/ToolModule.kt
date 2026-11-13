package com.omni.aiassistant.di

import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.device.tools.ToolAuditLog
import com.omni.aiassistant.device.tools.ToolDispatcher
import com.omni.aiassistant.device.tools.ToolRegistry
import com.omni.aiassistant.device.tools.comm.CommunicationToolGroup
import com.omni.aiassistant.device.tools.files.FilesToolGroup
import com.omni.aiassistant.device.tools.media.MediaToolGroup
import com.omni.aiassistant.device.tools.memory.MemoryToolGroup
import com.omni.aiassistant.device.tools.pc.PcToolGroup
import com.omni.aiassistant.device.tools.screen.ScreenToolGroup
import com.omni.aiassistant.device.tools.system.SystemToolGroup
import com.omni.aiassistant.device.tools.web.WebToolGroup
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Capability layer. Tools are registered here (and only here) so the dispatcher
 * always sees exactly the compile-time set of actions; there is no dynamic
 * plugin surface.
 *
 * The tool list is intentionally assembled explicitly instead of using Koin's
 * `getAll()`: a missing binding must be a compile error, not a runtime surprise
 * that silently disables half of the assistant.
 */
val toolModule = module {
    single { CapabilityCenter(androidContext()) }
    single { ToolAuditLog(androidContext(), get(), get()) }
    single {
        ToolRegistry(
            tools = CommunicationToolGroup(androidContext(), get()).tools() +
                FilesToolGroup(androidContext(), get()).tools() +
                SystemToolGroup(androidContext(), get()).tools() +
                ScreenToolGroup(androidContext(), get(), get<SettingsRepository>()).tools() +
                PcToolGroup(androidContext(), get(), get<SettingsRepository>()).tools() +
                WebToolGroup(androidContext(), get(), get<SettingsRepository>(), get()).tools() +
                MediaToolGroup(androidContext(), get()).tools() +
                MemoryToolGroup(androidContext(), get(), get()).tools(),
        )
    }
    single { ToolDispatcher(get(), get(), get(), get()) }
}
