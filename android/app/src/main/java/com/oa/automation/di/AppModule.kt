package com.oa.automation.di

import androidx.room.Room
import com.oa.automation.application.usecase.GenerateReportUseCase
import com.oa.automation.application.usecase.GenerateJourneyEditionUseCase
import com.oa.automation.application.usecase.CreatePublishedPostSnapshotUseCase
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.application.usecase.StopRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.domain.repository.JourneyEditionRepository
import com.oa.automation.domain.repository.PublishedPostRepository
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.domain.repository.ScheduledMeetingRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.audio.MeetingAudioArchiveService
import com.oa.automation.infrastructure.audio.ImportedAudioStore
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.account.ProfileAvatarCodec
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.infrastructure.community.CommunitySyncEnqueuer
import com.oa.automation.infrastructure.community.CommunitySyncScheduler
import com.oa.automation.infrastructure.community.CommunitySyncProcessor
import com.oa.automation.infrastructure.community.PublishedPostMediaStore
import com.oa.automation.infrastructure.db.AppDatabase
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.llm.AgentQuotaService
import com.oa.automation.infrastructure.location.DeviceLocationProvider
import com.oa.automation.infrastructure.notification.ScheduledMeetingNotificationScheduler
import com.oa.automation.infrastructure.repository.MeetingRepositoryImpl
import com.oa.automation.infrastructure.repository.JourneyRepositoryImpl
import com.oa.automation.infrastructure.repository.JourneyEditionRepositoryImpl
import com.oa.automation.infrastructure.repository.PublishedPostRepositoryImpl
import com.oa.automation.infrastructure.repository.ReportRepositoryImpl
import com.oa.automation.infrastructure.repository.ScheduledMeetingRepositoryImpl
import com.oa.automation.infrastructure.repository.StageDraftRepositoryImpl
import com.oa.automation.infrastructure.repository.CommunitySyncRepositoryImpl
import com.oa.automation.application.usecase.GenerateStageDraftUseCase
import com.oa.automation.infrastructure.service.RecordingSessionController
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.infrastructure.textimport.SharedTextImportCoordinator
import com.oa.automation.infrastructure.textimport.ExternalTextSourceLauncher
import com.oa.automation.infrastructure.update.AppUpdateService
import com.oa.automation.ui.screen.home.HomeViewModel
import com.oa.automation.ui.screen.account.AccountViewModel
import com.oa.automation.ui.screen.account.CommunityModerationViewModel
import com.oa.automation.ui.screen.community.CommunityPostDetailViewModel
import com.oa.automation.ui.screen.community.CommunityViewModel
import com.oa.automation.ui.screen.login.LoginViewModel
import com.oa.automation.ui.screen.login.RegisterViewModel
import com.oa.automation.ui.screen.recording.RecordingViewModel
import com.oa.automation.ui.screen.report.ReportViewModel
import com.oa.automation.ui.screen.settings.SettingsViewModel
import com.oa.automation.ui.screen.vip.VipViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Data Layer
    single { ConfigDataStore(androidContext()) }
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "meeting_notes.db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .addMigrations(AppDatabase.MIGRATION_7_8)
            .addMigrations(AppDatabase.MIGRATION_8_9)
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .addMigrations(AppDatabase.MIGRATION_10_11)
            .addMigrations(AppDatabase.MIGRATION_11_12)
            .addMigrations(AppDatabase.MIGRATION_12_13)
            .build()
    }
    single { get<AppDatabase>().meetingDao() }
    single { get<AppDatabase>().reportDao() }
    single { get<AppDatabase>().scheduledMeetingDao() }
    single { get<AppDatabase>().journeyDao() }
    single { get<AppDatabase>().stageDraftDao() }
    single { get<AppDatabase>().journeyEditionDao() }
    single { get<AppDatabase>().publishedPostDao() }
    single { get<AppDatabase>().communitySyncOutboxDao() }
    single { get<AppDatabase>().publishedPostMediaDao() }

    // Infrastructure
    single<MeetingRepository> { MeetingRepositoryImpl(get()) }
    single<JourneyRepository> { JourneyRepositoryImpl(get()) }
    single<StageDraftRepository> { StageDraftRepositoryImpl(get()) }
    single<JourneyEditionRepository> { JourneyEditionRepositoryImpl(get()) }
    single<PublishedPostRepository> { PublishedPostRepositoryImpl(get()) }
    single<CommunitySyncEnqueuer> { CommunitySyncScheduler(androidContext()) }
    single<com.oa.automation.domain.repository.CommunitySyncRepository> {
        CommunitySyncRepositoryImpl(get(), get(), get())
    }
    single { DeviceLocationProvider(androidContext()) }
    single { MeetingAttachmentStore(androidContext(), get(), get()) }
    single<ReportRepository> { ReportRepositoryImpl(get()) }
    single<ScheduledMeetingRepository> { ScheduledMeetingRepositoryImpl(get()) }
    single { AudioRecorder(androidContext()) }
    single { MeetingAudioArchiveService(androidContext(), get(), get()) }
    single { ImportedAudioStore(androidContext()) }
    single { AppUpdateService(androidContext()) }
    single { StreamingSttClient() }
    single { BackgroundTaskScheduler(androidContext()) }
    single { ScheduledMeetingNotificationScheduler(androidContext()) }
    single { RecordingSessionController(get(), get(), get(), get()) }
    single { SharedTextImportCoordinator(androidContext()) }
    single { ExternalTextSourceLauncher(androidContext()) }
    single { LLMEngine(get()) }
    single { AgentQuotaService() }
    single { AccountApiService() }
    single { AccountSessionSynchronizer(get(), get()) }
    single { CommunitySyncProcessor(get(), get(), get(), get(), get()) }
    single { PublishedPostMediaStore(androidContext(), get(), get()) }
    single { ProfileAvatarCodec(androidContext()) }

    // Use Cases
    factory { StartRecordingUseCase(get()) }
    factory { StopRecordingUseCase(get(), get(), get()) }
    factory { GenerateReportUseCase(get(), get(), get(), get()) }
    factory { GenerateStageDraftUseCase(get(), get(), get(), get()) }
    factory { GenerateJourneyEditionUseCase(get(), get(), get()) }
    factory { CreatePublishedPostSnapshotUseCase(get(), get(), get(), get()) }

    // ViewModels
    viewModel { LoginViewModel(get(), get()) }
    viewModel { RegisterViewModel(get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AccountViewModel(get(), get(), get(), get(), get()) }
    viewModel { CommunityModerationViewModel(get(), get()) }
    viewModel { CommunityViewModel(get(), get()) }
    viewModel { CommunityPostDetailViewModel(get(), get()) }
    viewModel { RecordingViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { ReportViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { VipViewModel(get(), get(), get(), get(), get()) }
}
