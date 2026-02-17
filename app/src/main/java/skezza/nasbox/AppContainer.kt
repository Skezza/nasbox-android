package skezza.nasbox

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skezza.nasbox.data.db.DatabaseProvider
import skezza.nasbox.data.discovery.AndroidSmbServerDiscoveryScanner
import skezza.nasbox.data.discovery.SmbServerDiscoveryScanner
import skezza.nasbox.data.media.AndroidMediaStoreDataSource
import skezza.nasbox.data.media.MediaStoreDataSource
import skezza.nasbox.data.repository.DefaultBackupRecordRepository
import skezza.nasbox.data.repository.DefaultPlanRepository
import skezza.nasbox.data.repository.DefaultRunLogRepository
import skezza.nasbox.data.repository.DefaultRunRepository
import skezza.nasbox.data.repository.DefaultServerRepository
import skezza.nasbox.data.repository.BackupRecordRepository
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.schedule.WorkManagerPlanScheduleCoordinator
import skezza.nasbox.data.security.AndroidKeystoreCredentialStore
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbShareRpcEnumerator
import skezza.nasbox.data.smb.SmbjClient
import skezza.nasbox.data.smb.SmbjRpcShareEnumerator
import skezza.nasbox.domain.schedule.PlanScheduleCoordinator
import skezza.nasbox.domain.discovery.DiscoverSmbServersUseCase
import skezza.nasbox.domain.media.ListMediaAlbumsUseCase
import skezza.nasbox.domain.smb.BrowseSmbDestinationUseCase
import skezza.nasbox.domain.smb.TestSmbConnectionUseCase
import skezza.nasbox.domain.sync.EnqueuePlanRunUseCase
import skezza.nasbox.domain.sync.MarkRunInterruptedUseCase
import skezza.nasbox.domain.sync.ReconcileStaleActiveRunsUseCase
import skezza.nasbox.domain.sync.RunPlanBackupUseCase
import skezza.nasbox.domain.sync.StopRunUseCase

class AppContainer(context: Context) {
    private val database = DatabaseProvider.get(context)
    private val workManager: WorkManager = WorkManager.getInstance(context)

    val serverRepository: ServerRepository = DefaultServerRepository(database.serverDao())
    val planRepository: PlanRepository = DefaultPlanRepository(database.planDao())
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(context)
    val backupRecordRepository: BackupRecordRepository = DefaultBackupRecordRepository(database.backupRecordDao())
    val runRepository: RunRepository = DefaultRunRepository(database.runDao())
    val runLogRepository: RunLogRepository = DefaultRunLogRepository(database.runLogDao())
    private val smbClient: SmbClient = SmbjClient()
    private val smbShareRpcEnumerator: SmbShareRpcEnumerator = SmbjRpcShareEnumerator()
    private val smbServerDiscoveryScanner: SmbServerDiscoveryScanner = AndroidSmbServerDiscoveryScanner(context)
    private val mediaStoreDataSource: MediaStoreDataSource = AndroidMediaStoreDataSource(context)

    val testSmbConnectionUseCase: TestSmbConnectionUseCase = TestSmbConnectionUseCase(
        serverRepository = serverRepository,
        credentialStore = credentialStore,
        smbClient = smbClient,
    )

    val discoverSmbServersUseCase: DiscoverSmbServersUseCase = DiscoverSmbServersUseCase(
        scanner = smbServerDiscoveryScanner,
    )

    val browseSmbDestinationUseCase: BrowseSmbDestinationUseCase = BrowseSmbDestinationUseCase(
        smbClient = smbClient,
        shareRpcEnumerator = smbShareRpcEnumerator,
    )

    val listMediaAlbumsUseCase: ListMediaAlbumsUseCase = ListMediaAlbumsUseCase(
        mediaStoreDataSource = mediaStoreDataSource,
    )

    val runPlanBackupUseCase: RunPlanBackupUseCase = RunPlanBackupUseCase(
        planRepository = planRepository,
        serverRepository = serverRepository,
        backupRecordRepository = backupRecordRepository,
        runRepository = runRepository,
        runLogRepository = runLogRepository,
        credentialStore = credentialStore,
        mediaStoreDataSource = mediaStoreDataSource,
        smbClient = smbClient,
    )

    val enqueuePlanRunUseCase: EnqueuePlanRunUseCase = EnqueuePlanRunUseCase(
        workManager = workManager,
        planRepository = planRepository,
    )

    val markRunInterruptedUseCase: MarkRunInterruptedUseCase = MarkRunInterruptedUseCase(
        runRepository = runRepository,
        runLogRepository = runLogRepository,
    )

    val stopRunUseCase: StopRunUseCase = StopRunUseCase(
        runRepository = runRepository,
        runLogRepository = runLogRepository,
        cancelQueuedPlanRunWork = enqueuePlanRunUseCase::cancelQueuedPlanRunWork,
    )

    val reconcileStaleActiveRunsUseCase: ReconcileStaleActiveRunsUseCase = ReconcileStaleActiveRunsUseCase(
        runRepository = runRepository,
        runLogRepository = runLogRepository,
    )

    val planScheduleCoordinator: PlanScheduleCoordinator = WorkManagerPlanScheduleCoordinator(
        workManager = workManager,
        planRepository = planRepository,
        cancelQueuedPlanRunWork = enqueuePlanRunUseCase::cancelQueuedPlanRunWork,
    )

    suspend fun reconcileSchedulesOnStartup() {
        planScheduleCoordinator.reconcileSchedules()
    }

    suspend fun reconcileRunsOnStartup() {
        val hasActiveQueueWorker = runCatching {
            withContext(Dispatchers.IO) {
                workManager
                    .getWorkInfosByTag(EnqueuePlanRunUseCase.TAG_RUN_WORK)
                    .get()
            }.any { workInfo ->
                workInfo.state in ACTIVE_WORK_STATES
            }
        }.getOrDefault(false)
        reconcileStaleActiveRunsUseCase(forceFinalizeActive = !hasActiveQueueWorker)
    }

    companion object {
        private val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )
    }
}
