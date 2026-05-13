package com.oa.automation.di

import androidx.room.Room
import com.oa.automation.application.usecase.GenerateReportUseCase
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.application.usecase.StopRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.db.AppDatabase
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.repository.MeetingRepositoryImpl
import com.oa.automation.infrastructure.repository.ReportRepositoryImpl
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.ui.screen.home.HomeViewModel
import com.oa.automation.ui.screen.recording.RecordingViewModel
import com.oa.automation.ui.screen.report.ReportViewModel
import com.oa.automation.ui.screen.settings.SettingsViewModel
import com.oa.automation.ui.screen.vip.VipViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // Data Layer
    single { ConfigDataStore(androidContext()) }
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "meeting_notes.db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().meetingDao() }
    single { get<AppDatabase>().reportDao() }

    // Infrastructure
    single<MeetingRepository> { MeetingRepositoryImpl(get()) }
    single<ReportRepository> { ReportRepositoryImpl(get()) }
    single { AudioRecorder(androidContext()) }
    single { StreamingSttClient() }
    single { LLMEngine(get()) }

    // Use Cases
    factory { StartRecordingUseCase(get()) }
    factory { StopRecordingUseCase(get(), get(), get()) }
    factory { GenerateReportUseCase(get(), get(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { RecordingViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { ReportViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { VipViewModel(get(), get()) }
}
