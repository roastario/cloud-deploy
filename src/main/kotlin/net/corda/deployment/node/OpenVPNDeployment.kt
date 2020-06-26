package net.corda.deployment.node

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.io.FileReader


class OpenVPNDeployment {


    fun `do something!`(namespace: String, serverHost: String) {

        val openVpnPvc: V1PersistentVolumeClaim = V1PersistentVolumeClaimBuilder()
            .withKind("PersistentVolumeClaim")
            .withApiVersion("v1")
            .withNewMetadata()
            .withNamespace(namespace)
            .withName("openvpn-pki-pvc")
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withNewResources()
            .withRequests((mapOf("storage" to Quantity("1Gi"))))
            .endResources()
            .endSpec()
            .build()


        val volumeMount = V1VolumeMountBuilder().withName("openvpn-pki-storage").withMountPath("/etc/openvpn").build()

        val openVPNDeployment: V1Deployment = V1DeploymentBuilder()
            .withKind("Deployment")
            .withApiVersion("apps/v1")
            .withNewMetadata()
            .withName("openvpn")
            .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .withMatchLabels(mapOf("run" to "openvpn"))
            .endSelector()
            .withReplicas(1)
            .withNewTemplate()
            .withNewMetadata()
            .withLabels(mapOf("run" to "openvpn"))
            .endMetadata()
            .withNewSpec()
            .withVolumes(
                V1VolumeBuilder().withName("openvpn-pki-storage")
                    .withPersistentVolumeClaim(
                        V1PersistentVolumeClaimVolumeSource().claimName(openVpnPvc.metadata!!.name)
                    ).build()
            )
            .addNewInitContainer()
            .withName("openvpn-chown-data")
            .withImage("busybox:latest")
            .withImagePullPolicy("IfNotPresent")
            .withCommand("chown")
            .withArgs("-Rv", "999:999", "/etc/openvpn")
            .withVolumeMounts(volumeMount)
            .endInitContainer()

            .addNewInitContainer()
            .withName("openvpn-gen-config")
            .withImage("kylemanna/openvpn:latest")
            .withImagePullPolicy("IfNotPresent")
            .withCommand("ovpn_genconfig")
            .withArgs("-u", "udp://${serverHost}")
            .withVolumeMounts(volumeMount)
            .endInitContainer()

            .addNewInitContainer()
            .withName("openvpn-init-pki")
            .withImage("kylemanna/openvpn:latest")
            .withImagePullPolicy("IfNotPresent")
            .withCommand("ovpn_initpki")
            .withVolumeMounts(volumeMount)
            .endInitContainer()

            .addNewInitContainer()
            .withName("openvpn-create-pki-infra")
            .withImage("kylemanna/openvpn:latest")
            .withImagePullPolicy("IfNotPresent")
            .withCommand("easyrsa")
            .withArgs("build-client-full", "CONTROL", "nopass")
            .withVolumeMounts(volumeMount)
            .endInitContainer()

            .addNewContainer()
            .withName("openvpn")
            .withImage("kylemanna/openvpn:latest")
            .withImagePullPolicy("IfNotPresent")
            .withPorts(
                V1ContainerPortBuilder().withName("openvpnport").withProtocol("UDP").withContainerPort(1194).build()
            )
            .withVolumeMounts(volumeMount)
            .withNewResources()
            .withRequests(listOf("memory" to Quantity("256Mi"), "cpu" to Quantity("0.2")).toMap())
            .endResources()
            .withNewSecurityContext()
            .withNewCapabilities()
            .addNewAdd("NET_ADMIN")
            .endCapabilities()
            .withNewRunAsNonRoot(true)
            .endSecurityContext()
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()



        // loading the out-of-cluster config, a kubeconfig from file-system
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(File(File(System.getProperty("user.home"), ".kube"), "config")))).build()
        Configuration.setDefaultApiClient(client)
        val appsV1Api = AppsV1Api()
        val coreApi = CoreV1Api()

        coreApi.listNode(null, null, null, null, null, null, null, null, null)

        try{
            val volumeClaim = coreApi.createNamespacedPersistentVolumeClaim(namespace, openVpnPvc, null, null, null)
            val dep = appsV1Api.createNamespacedDeployment(namespace, openVPNDeployment, null, null, null)
        }catch (e: ApiException){
            println(e.responseBody)
        }

    }


}

fun main(args: Array<String>) {
    OpenVPNDeployment().`do something!`("testspace", "localhost")
}