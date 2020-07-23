package net.corda.deployment.node

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams
import net.corda.deployments.node.config.BridgeConfigParams

fun createBridgeDeployment(
    namespace: String,
    runId: String,
    bridgeConfigShare: AzureFilesDirectory,
    tunnelStoresShare: AzureFilesDirectory,
    artemisStoresShare: AzureFilesDirectory,
    bridgeStoresShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory,
    tunnelStoresSecretName: String,
    tunnelSSLKeysStorePasswordSecretKey: String,
    tunnelTrustStorePasswordSecretKey: String,
    tunnelEntryPasswordKey: String,
    artemisSecrets: ArtemisSecrets,
    bridgeStoresSecretName: String,
    bridgeKeyStorePasswordKey: String,
    nodeStoresSecretName: String,
    sharedTrustStorePasswordKey: String,
    azureFilesSecretName: String
): V1Deployment {

    val configDirMountName = "config-dir"
    val tunnelStoresDirMountName = "tunnel-dir"
    val localStoresDirMountName = "stores-dir"
    val artemisStoresDirMountName = "artemis-dir"
    val networkParametersMountName = "network-dir"

    return V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withNamespace(namespace)
        .withName("bridge-${runId}")
        .withLabels(listOf("dmz" to "false").toMap())
        .endMetadata()
        .withNewSpec()
        .withNewSelector()
        .withMatchLabels(listOf("run" to "bridge-${runId}").toMap())
        .endSelector()
        .withReplicas(1)
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(listOf("run" to "bridge-${runId}").toMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("float-$runId")
        .withImage("corda/firewall:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-firewall")
        .withEnv(
            V1EnvVarBuilder().withName("JAVA_CAPSULE_ARGS").withValue("-Xms512M -Xmx800M").build(),
            V1EnvVarBuilder().withName("CONFIG_FILE").withValue(BridgeConfigParams.BRIDGE_CONFIG_PATH).build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(BridgeConfigParams.BRIDGE_BASE_DIR).build(),
            //TUNNEL SECRETS
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelSSLKeysStorePasswordSecretKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelTrustStorePasswordSecretKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelEntryPasswordKey
            ),
            //ARTEMIS SECRETS
            secretEnvVar(
                BridgeConfigParams.BRIDGE_ARTEMIS_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecrets.secretName,
                artemisSecrets.keyStorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecrets.secretName,
                artemisSecrets.trustStorePasswordKey
            ),
            //LOCAL SECRETS
            secretEnvVar(
                BridgeConfigParams.BRIDGE_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                bridgeStoresSecretName,
                bridgeKeyStorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecretName,
                sharedTrustStorePasswordKey
            )
        )
        .withNewResources()
        .withRequests(
            listOf(
                "memory" to Quantity("1024Mi"), "cpu" to Quantity(
                    "0.5"
                )
            ).toMap()
        )
        .endResources()
        .withVolumeMounts(
            listOfNotNull(
                V1VolumeMountBuilder()
                    .withName(configDirMountName)
                    .withMountPath(BridgeConfigParams.BRIDGE_CONFIG_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(tunnelStoresDirMountName)
                    .withMountPath(BridgeConfigParams.BRIDGE_TUNNEL_STORES_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(localStoresDirMountName)
                    .withMountPath(BridgeConfigParams.BRIDGE_CERTIFICATES_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(networkParametersMountName)
                    .withMountPath(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(artemisStoresDirMountName)
                    .withMountPath(BridgeConfigParams.BRIDGE_ARTEMIS_STORES_DIR).build()
            )
        )
        .endContainer()
        .withVolumes(
            listOfNotNull(
                azureFileMount(
                    configDirMountName,
                    bridgeConfigShare,
                    azureFilesSecretName,
                    true
                ),
                azureFileMount(
                    tunnelStoresDirMountName,
                    tunnelStoresShare,
                    azureFilesSecretName,
                    true
                ),
                azureFileMount(
                    networkParametersMountName,
                    networkParametersShare,
                    azureFilesSecretName,
                    true
                ),
                azureFileMount(
                    artemisStoresDirMountName,
                    artemisStoresShare,
                    azureFilesSecretName,
                    true
                ),
                azureFileMount(
                    localStoresDirMountName,
                    bridgeStoresShare,
                    azureFilesSecretName,
                    true
                )
            )
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

//fun createBridgeService(bridgeDeployment: V1Deployment): V1Service {
//    return V1ServiceBuilder()
//        .withKind("Service")
//        .withApiVersion("v1")
//        .withNewMetadata()
//        .withNamespace(bridgeDeployment.metadata?.namespace)
//        .withName(bridgeDeployment.metadata?.name)
//        .withLabels(listOf("run" to bridgeDeployment.metadata?.name).toMap())
//        .endMetadata()
//        .withNewSpec()
//        .withType("ClusterIP")
//        .withPorts(
//            V1ServicePortBuilder().withPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
//                .withProtocol("TCP")
//                .withTargetPort(
//                    IntOrString(
//                        bridgeDeployment.spec?.template?.spec?.containers?.first()?.ports?.find { it.name == ARTEMIS_PORT_NAME }?.containerPort
//                            ?: throw IllegalStateException("could not find target port in deployment")
//                    )
//                )
//                .withName(ARTEMIS_PORT_NAME).build()
//        ).withSelector(listOf("run" to artemisDeployment.metadata?.name).toMap())
//        .endSpec()
//        .build()
//
//}