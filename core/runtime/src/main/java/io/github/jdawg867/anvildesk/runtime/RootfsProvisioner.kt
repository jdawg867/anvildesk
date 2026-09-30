package io.github.jdawg867.anvildesk.runtime

sealed interface RootfsProvisioningState {
    data object NotInstalled : RootfsProvisioningState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : RootfsProvisioningState
    data object Verifying : RootfsProvisioningState
    data object Extracting : RootfsProvisioningState
    data class Ready(val record: RootfsInstallRecord) : RootfsProvisioningState
    data class Failed(val message: String) : RootfsProvisioningState
}

class RootfsProvisioner(private val store: RootfsInstallStore) {
    fun currentState(manifest: RootfsManifest): RootfsProvisioningState {
        val record = store.currentRecord(manifest.id) ?: return RootfsProvisioningState.NotInstalled
        return if (record.sha256 == manifest.sha256 &&
            record.version == manifest.version &&
            record.architecture == manifest.architecture
        ) {
            RootfsProvisioningState.Ready(record)
        } else {
            RootfsProvisioningState.NotInstalled
        }
    }

    fun provision(
        manifest: RootfsManifest,
        onState: (RootfsProvisioningState) -> Unit = {},
    ): RootfsInstallRecord {
        RootfsManifestValidator.requireValid(manifest)

        val existing = currentState(manifest)
        if (existing is RootfsProvisioningState.Ready) {
            onState(existing)
            return existing.record
        }

        val archive = store.downloadFile(manifest)
        try {
            onState(RootfsProvisioningState.Downloading(0L, null))
            VerifiedRootfsDownloader.download(
                manifest = manifest,
                destination = archive,
                onProgress = { downloaded, total ->
                    onState(RootfsProvisioningState.Downloading(downloaded, total))
                },
            )

            onState(RootfsProvisioningState.Verifying)
            if (!Sha256.matches(archive, manifest.sha256)) {
                throw SecurityException("Verified download changed before installation")
            }

            onState(RootfsProvisioningState.Extracting)
            val record = store.installVerifiedArchive(manifest, archive)
            if (archive.exists() && !archive.delete()) {
                // The verified archive is not part of the installed state. Failure to remove it is non-fatal.
            }

            val ready = RootfsProvisioningState.Ready(record)
            onState(ready)
            return record
        } catch (error: Throwable) {
            archive.delete()
            store.cleanupAbandonedStaging(manifest.id)
            onState(
                RootfsProvisioningState.Failed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
            throw error
        }
    }
}
