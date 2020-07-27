package net.corda.deployment.node.float

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1Service
import io.kubernetes.client.openapi.models.V1ServiceBuilder
import io.kubernetes.client.openapi.models.V1ServicePortBuilder
import net.corda.deployment.node.networking.ClusterNetwork
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployments.node.config.FloatConfigParams

class AzureFloatSetup(
    namespace: String,
    shareCreator: AzureFileShareCreator,
    randSuffix: String,
    val clusterNetwork: ClusterNetwork
) :
    FloatSetup(namespace, shareCreator, randSuffix) {


    override fun buildInternalService(floatDeployment: V1Deployment): V1Service {
        return V1ServiceBuilder()
            .withKind("Service")
            .withApiVersion("v1")
            .withNewMetadata()
            .withNamespace(floatDeployment.metadata?.namespace)
            .withName(floatDeployment.metadata?.name)
            .withAnnotations(
                listOf(
                    "service.beta.kubernetes.io/azure-load-balancer-internal" to "true"
                ).toMap()
            )
            .withLabels(listOf("run" to floatDeployment.metadata?.name).toMap())
            .endMetadata()
            .withNewSpec()
            .withType("LoadBalancer")
            .withLoadBalancerIP(clusterNetwork.getNextAvailableDMZInternalIP())
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
}