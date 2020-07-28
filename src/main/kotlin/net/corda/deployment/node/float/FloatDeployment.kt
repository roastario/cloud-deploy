package net.corda.deployment.node.float

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployment.node.FirewallTunnelSecrets
import net.corda.deployment.node.azureFileMount
import net.corda.deployment.node.secretEnvVar
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.FloatConfigParams

internal const val FLOAT_EXTERNAL_PORT_NAME = "external-port"
internal const val FLOAT_INTERNAL_PORT_NAME = "internal-port"


fun createFloatDeployment(
    namespace: String,
    runId: String,
    floatConfigShare: AzureFilesDirectory,
    tunnelStoresShare: AzureFilesDirectory,
    firewallTunnelSecrets: FirewallTunnelSecrets
): V1Deployment {
    val configDirMountName = "config-dir"
    val tunnelStoresMountName = "tunnel-stores-dir"
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
        .withImage("corda/enterprise-firewall:4.5")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-firewall")
        .withEnv(
            V1EnvVarBuilder().withName("JVM_ARGS").withValue("-Xms512M -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=80").build(),
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
            V1ContainerPortBuilder().withName(FLOAT_EXTERNAL_PORT_NAME).withContainerPort(
                FloatConfigParams.FLOAT_EXTERNAL_PORT
            ).build(),
            V1ContainerPortBuilder().withName(FLOAT_INTERNAL_PORT_NAME).withContainerPort(
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
                    .withMountPath(FloatConfigParams.FLOAT_TUNNEL_STORES_DIR).build()
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

fun createIntraClusterInternalFloatService(floatDeployment: V1Deployment): V1Service {
    return V1ServiceBuilder()
        .withKind("Service")
        .withApiVersion("v1")
        .withNewMetadata()
        .withNamespace(floatDeployment.metadata?.namespace)
        .withName(floatDeployment.metadata?.name)
        .withLabels(listOf("run" to floatDeployment.metadata?.name).toMap())
        .endMetadata()
        .withNewSpec()
        .withType("ClusterIP")
        .withPorts(
            V1ServicePortBuilder().withPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
                .withProtocol("TCP")
                .withTargetPort(
                    IntOrString(
                        floatDeployment.spec?.template?.spec?.containers?.first()?.ports?.find { it.name == FLOAT_INTERNAL_PORT_NAME }?.containerPort
                            ?: throw IllegalStateException("could not find target port in deployment")
                    )
                )
                .withName(FLOAT_INTERNAL_PORT_NAME).build()
        ).withSelector(listOf("run" to floatDeployment.metadata?.name).toMap())
        .endSpec()
        .build()
}

