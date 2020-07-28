package net.corda.deployment.node

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.BridgeConfigParams

fun createBridgeDeployment(
    namespace: String,
    runId: String,
    bridgeConfigShare: AzureFilesDirectory,
    tunnelStoresShare: AzureFilesDirectory,
    artemisStoresShare: AzureFilesDirectory,
    bridgeStoresShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory,
    firewallTunnelSecrets: FirewallTunnelSecrets,
    artemisSecrets: ArtemisSecrets,
    bridgeStoresSecretName: String,
    bridgeKeyStorePasswordKey: String,
    nodeStoresSecretName: String,
    sharedTrustStorePasswordKey: String
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
        .withImage("corda/enterprise-firewall:4.5")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-firewall")
        .withEnv(
            V1EnvVarBuilder().withName("JVM_ARGS").withValue("-Xms512M -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=80").build(),
            V1EnvVarBuilder().withName("CONFIG_FILE").withValue(BridgeConfigParams.BRIDGE_CONFIG_PATH).build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(BridgeConfigParams.BRIDGE_BASE_DIR).build(),
            //TUNNEL SECRETS
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.keystorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.truststorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.entryPasswordKey
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
                bridgeConfigShare.toK8sMount(configDirMountName, true),
                tunnelStoresShare.toK8sMount(tunnelStoresDirMountName, true),
                networkParametersShare.toK8sMount(networkParametersMountName, true),
                artemisStoresShare.toK8sMount(artemisStoresDirMountName, true),
                bridgeStoresShare.toK8sMount(localStoresDirMountName, true)
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