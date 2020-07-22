package net.corda.deployment.node

import com.microsoft.azure.management.compute.Disk
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams

fun createArtemisDeployment(
    devNamespace: String,
    azureFilesSecretName: String,
    configShare: AzureFilesDirectory,
    storesShare: AzureFilesDirectory,
    dataDisk: Disk?,
    runId: String
): V1Deployment {
    val dataMountName = "artemis-data"
    val brokerBaseDirShare = "artemis-config"
    val storesMountName = "artemis-stores"
    val artemisDeployment = V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withName("artemis-$runId")
        .withNamespace(devNamespace)
        .withLabels(listOf("dmz" to "false").toMap())
        .endMetadata()
        .withNewSpec()
        .withNewSelector()
        .withMatchLabels(listOf("run" to "artemis-$runId").toMap())
        .endSelector()
        .withReplicas(1)
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(listOf("run" to "artemis-$runId").toMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("artemis-$runId")
        .withImage("corda/setup:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand("run-artemis")
        .withEnv(V1EnvVarBuilder().withName("JAVA_ARGS").withValue("-XX:+UseParallelGC -Xms512M -Xmx768M").build())
        .withPorts(
            V1ContainerPortBuilder().withName("artemis-port").withContainerPort(
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT
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
                dataDisk?.let { _ ->
                    V1VolumeMountBuilder()
                        .withName(dataMountName)
                        .withMountPath(ArtemisConfigParams.ARTEMIS_DATA_DIR_PATH).build()
                },
                V1VolumeMountBuilder()
                    .withName(brokerBaseDirShare)
                    .withMountPath(ArtemisConfigParams.ARTEMIS_BROKER_BASE_DIR).build(),
                V1VolumeMountBuilder()
                    .withName(storesMountName)
                    .withMountPath(ArtemisConfigParams.ARTEMIS_STORES_DIR).build()
            )
        )
        .endContainer()
        .withVolumes(
            listOfNotNull(
                azureFileMount(brokerBaseDirShare, configShare, azureFilesSecretName, false),
                azureFileMount(storesMountName, storesShare, azureFilesSecretName, true),
                dataDisk?.let {
                    V1VolumeBuilder()
                        .withNewAzureDisk()
                        .withKind("Managed")
                        .withDiskName(dataDisk.name())
                        .withDiskURI(dataDisk.id())
                        .withReadOnly(false)
                        .endAzureDisk()
                        .build()
                })
        )
        .withNewSecurityContext()
        //artemis is 1001
        .withRunAsUser(1001)
        .withRunAsGroup(1001)
        .withFsGroup(1001)
        .withRunAsNonRoot(true)
        .endSecurityContext()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()

    return artemisDeployment

}