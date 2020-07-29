package net.corda.deployment.node

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1ContainerPortBuilder
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1DeploymentBuilder
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.NodeConfigParams

const val NODE_RPC_PORT_NAME = "node-rpc"

fun createNodeDeployment(
    namespace: String,
    runId: String,
    artemisDirShare: AzureFilesDirectory,
    certificatesDirShare: AzureFilesDirectory,
    configDirShare: AzureFilesDirectory,
    driversShareDir: AzureFilesDirectory,
    cordappsDirShare: AzureFilesDirectory,
    artemisSecrets: ArtemisSecrets,
    nodeStoresSecrets: NodeStoresSecrets,
    keyVaultSecrets: KeyVaultSecrets,
    databaseSecrets: NodeDatabaseSecrets
): V1Deployment {
    val hsmConfigDirMountName = "azurehsmcredentialsdir"
    val nodeConfigDirMountName = "azurecordaconfigdir"
    val nodeCertificatesDirMountName = "azurecordacertificatesdir"
    val artemisDirMountName = "artemisstoresdir"
    val nodeDriversDirMountName = "driversdir"
    val nodeCordappsDirMountName = "cordappsdir"

    return V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withNamespace(namespace)
        .withName("node-${runId}")
        .withLabels(listOf("dmz" to "false").toMap())
        .endMetadata()
        .withNewSpec()
        .withNewSelector()
        .withMatchLabels(listOf("run" to "node-$runId").toMap())
        .endSelector()
        .withReplicas(1)
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(listOf("run" to "node-$runId").toMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("node-$runId")
        .withImage("corda/corda-enterprise-java-zulu1.8-4.6-snapshot:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-corda")
        .withEnv(
            keyValueEnvVar("CORDA_ARGS", "--verbose"),
            keyValueEnvVar(
                "JVM_ARGS",
                "-Xms512M -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=80"
            ),
            licenceAcceptEnvVar(),
            secretEnvVar(
                AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME,
                keyVaultSecrets.credentialPasswordsSecretName,
                keyVaultSecrets.azKeyVaultCredentialsFilePasswordKey
            ),
            secretEnvVar(
                AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME,
                keyVaultSecrets.credentialPasswordsSecretName,
                keyVaultSecrets.azKeyVaultCredentialsClientIdKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_URL_ENV_VAR_NAME,
                databaseSecrets.secretName,
                databaseSecrets.nodeDataSourceURLKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME,
                databaseSecrets.secretName,
                databaseSecrets.nodeDataSourceUsernameKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME,
                databaseSecrets.secretName,
                databaseSecrets.nodeDatasourcePasswordKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecrets.secretName,
                artemisSecrets.trustStorePasswordKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecrets.secretName,
                artemisSecrets.keyStorePasswordKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecrets.secretName,
                nodeStoresSecrets.nodeKeyStorePasswordKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecrets.secretName,
                nodeStoresSecrets.sharedTrustStorePasswordKey
            )
        )
        .withPorts(
            V1ContainerPortBuilder().withName(NODE_RPC_PORT_NAME).withContainerPort(
                NodeConfigParams.NODE_RPC_PORT
            ).build()
        ).withNewResources()
        .withRequests(
            listOf(
                "memory" to Quantity("2048"), "cpu" to Quantity(
                    "1.5"
                )
            ).toMap()
        )
        .endResources()
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(hsmConfigDirMountName)
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(nodeConfigDirMountName)
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName(nodeCertificatesDirMountName)
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(artemisDirMountName)
                .withMountPath(NodeConfigParams.NODE_ARTEMIS_STORES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(nodeDriversDirMountName)
                .withMountPath(NodeConfigParams.NODE_DRIVERS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(nodeCordappsDirMountName)
                .withMountPath(NodeConfigParams.NODE_CORDAPPS_DIR).build()
        )
        .endContainer()
        .withVolumes(
            secretVolumeWithAll(hsmConfigDirMountName, keyVaultSecrets.credentialAndConfigFilesSecretName),
            azureFileMount(
                nodeConfigDirMountName,
                configDirShare,
                true
            ),
            azureFileMount(
                nodeCertificatesDirMountName,
                certificatesDirShare,
                true
            ),
            azureFileMount(
                artemisDirMountName,
                artemisDirShare,
                true
            ),
            azureFileMount(nodeDriversDirMountName, driversShareDir, true),
            cordappsDirShare.toK8sMount(nodeCordappsDirMountName, false)
        )
        .withNewSecurityContext()
        //corda is 1000
        .withRunAsUser(1000)
        .withRunAsGroup(1000)
        .withFsGroup(1000)
        .withRunAsNonRoot(true)
        .endSecurityContext()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}
