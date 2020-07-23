package net.corda.deployment.node

import com.microsoft.azure.management.compute.Disk
import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.*
import net.corda.deployments.node.config.ArtemisConfigParams

private const val ARTEMIS_PORT_NAME = "artemis-port"

fun createArtemisDeployment(
    devNamespace: String,
    configuredArtemisBroker: ConfiguredArtemisBroker,
    storesShare: GeneratedArtemisStores,
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
            V1ContainerPortBuilder().withName(ARTEMIS_PORT_NAME).withContainerPort(
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
                azureFileMount(brokerBaseDirShare, configuredArtemisBroker.baseDir, false),
                azureFileMount(storesMountName, storesShare.outputDir, true),
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

fun createArtemisService(artemisDeployment: V1Deployment): V1Service {

    return V1ServiceBuilder()
        .withKind("Service")
        .withApiVersion("v1")
        .withNewMetadata()
        .withNamespace(artemisDeployment.metadata?.namespace)
        .withName(artemisDeployment.metadata?.name)
        .withLabels(listOf("run" to artemisDeployment.metadata?.name).toMap())
        .endMetadata()
        .withNewSpec()
        .withType("ClusterIP")
        .withPorts(
            V1ServicePortBuilder().withPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
                .withProtocol("TCP")
                .withTargetPort(
                    IntOrString(
                        artemisDeployment.spec?.template?.spec?.containers?.first()?.ports?.find { it.name == ARTEMIS_PORT_NAME }?.containerPort
                            ?: throw IllegalStateException("could not find target port in deployment")
                    )
                )
                .withName(ARTEMIS_PORT_NAME).build()
        ).withSelector(listOf("run" to artemisDeployment.metadata?.name).toMap())
        .endSpec()
        .build()

}