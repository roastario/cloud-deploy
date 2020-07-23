package net.corda.deployment.node

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.FloatConfigParams

fun createFloatDeployment(
    namespace: String,
    runId: String,
    floatConfigShare: AzureFilesDirectory,
    tunnelStoresShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory,
    firewallTunnelSecrets: FirewallTunnelSecrets
): V1Deployment {
    val configDirMountName = "config-dir"
    val tunnelStoresMountName = "tunnel-stores-dir"
    val networkParametersMountName = "network-parameters-dir"
    return V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withNamespace(namespace)
        .withName("float-${runId}")
        .withLabels(listOf("dmz" to "true").toMap())
        .endMetadata()
        .withNewSpec()
        .withNewSelector()
        .withMatchLabels(listOf("run" to "float-$runId").toMap())
        .endSelector()
        .withReplicas(1)
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(listOf("run" to "float-$runId").toMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("float-$runId")
        .withImage("corda/firewall:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-firewall")
        .withEnv(
            V1EnvVarBuilder().withName("JAVA_CAPSULE_ARGS").withValue("-Xms512M -Xmx800M").build(),
            V1EnvVarBuilder().withName("CONFIG_FILE").withValue(FloatConfigParams.FLOAT_CONFIG_PATH).build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(FloatConfigParams.FLOAT_BASE_DIR).build(),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.keystorePasswordKey
            ),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.truststorePasswordKey
            ),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.entryPasswordKey
            )
        )
        .withPorts(
            V1ContainerPortBuilder().withName("float-external").withContainerPort(
                FloatConfigParams.FLOAT_EXTERNAL_PORT
            ).build(),
            V1ContainerPortBuilder().withName("float-internal").withContainerPort(
                FloatConfigParams.FLOAT_INTERNAL_PORT
            ).build()
        ).withNewResources()
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
                    .withMountPath(FloatConfigParams.FLOAT_CONFIG_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(tunnelStoresMountName)
                    .withMountPath(FloatConfigParams.FLOAT_TUNNEL_STORES_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(networkParametersMountName)
                    .withMountPath(FloatConfigParams.FLOAT_NETWORK_DIR).build()
            )
        )
        .endContainer()
        .withVolumes(
            listOfNotNull(
                azureFileMount(
                    configDirMountName,
                    floatConfigShare,
                    true
                ),
                azureFileMount(
                    tunnelStoresMountName,
                    tunnelStoresShare,
                    true
                ),
                azureFileMount(
                    networkParametersMountName,
                    networkParametersShare,
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