package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.BridgeConfigParams
import net.corda.deployments.node.config.NodeConfigParams

fun importNodeKeyStoreToBridgeJob(
    jobName: String,
    nodeCertificatesSecretName: String,
    nodeKeyStorePasswordSecretKey: String,
    bridgeCertificatesSecretName: String,
    bridgeKeyStorePasswordSecretKey: String,
    nodeCertificatesShare: AzureFilesDirectory,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirPath = "/tmp/bridgeImport"
    val nodeCertificatesPath = "/tmp/nodeCerts"
    val workingDirMountName = "azureworkingdir"
    val nodeCertificatesMountName = "azurenodecerts"
    val nodeSSLKeystorePath = "$nodeCertificatesPath/${NodeConfigParams.NODE_SSL_KEYSTORE_FILENAME}"
    val bridgeSSLKeystorePath = "$workingDirPath/${BridgeConfigParams.BRIDGE_SSL_KEYSTORE_FILENAME}"

    val importJob = baseSetupJobBuilder(jobName, listOf("import-node-ssl-to-bridge"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDirPath).build(),
            V1VolumeMountBuilder()
                .withName(nodeCertificatesMountName)
                .withMountPath(nodeCertificatesPath).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDirPath),
            keyValueEnvVar(
                "NODE_KEYSTORE_TO_IMPORT",
                nodeSSLKeystorePath
            ),
            keyValueEnvVar(
                "BRIDGE_KEYSTORE",
                bridgeSSLKeystorePath
            ),
            secretEnvVar(
                "NODE_KEYSTORE_PASSWORD",
                nodeCertificatesSecretName,
                nodeKeyStorePasswordSecretKey
            ),
            secretEnvVar(
                "BRIDGE_KEYSTORE_PASSWORD",
                bridgeCertificatesSecretName,
                bridgeKeyStorePasswordSecretKey
            )
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, false),
            azureFileMount(
                nodeCertificatesMountName,
                nodeCertificatesShare,
                true
            )
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return importJob
}