package net.corda.deployment.node

import com.google.gson.reflect.TypeToken
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.compute.Disk
import com.microsoft.azure.management.compute.DiskSkuTypes
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.rest.LogLevel
import io.kubernetes.client.PodLogs
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Watch
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.*
import okhttp3.OkHttpClient
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())
    val namespace = "testingzone"
    allowAllFailures { simpleApply.apply(nmsSetup, namespace = namespace) }
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")


    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = RandomStringUtils.randomAlphanumeric(8).toLowerCase()


    val defaultClientSource = { ClientBuilder.defaultClient() }

    val azureFileShareCreator = AzureFileShareCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)

    val nodeCertificatesShare = azureFileShareCreator.createDirectoryFor("node-certificates")
    val networkParametersShare = azureFileShareCreator.createDirectoryFor("network-files")
    val azureFilesSecretName = "azure-files-secret-$randSuffix"
    SecretCreator.createStringSecret(
        azureFilesSecretName,
        listOf(
            "azurestorageaccountname" to nodeCertificatesShare.storageAccount.name(),
            "azurestorageaccountkey" to nodeCertificatesShare.storageAccount.keys[0].value()
        ).toMap()
        , namespace, defaultClientSource
    )

    val dbParams = H2_DB

    val nodeTrustStorePassword = RandomStringUtils.randomAlphanumeric(20)

    val artemisStoresShare = azureFileShareCreator.createDirectoryFor("artemis-stores")

    val artemisSecretsName = "artemis-${randSuffix}"
    val artemisStorePassSecretKey = "artemisstorepass"
    val artemisTrustPassSecretKey = "artemistrustpass"
    val artemisClusterPassSecretKey = "artemisclusterpass";
    val artemisSecret = SecretCreator.createStringSecret(
        artemisSecretsName,
        listOf(
            artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
            artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32),
            artemisClusterPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
        ).toMap()
        , namespace, defaultClientSource
    )
    val generateArtemisStoresJobName = "gen-artemis-stores-${randSuffix}"

    val nodeConfigParams = NodeConfigParams.builder()
        .withX500Name("O=BigCorporation,L=New York,C=US")
        .withEmailAddress("stefano.franz@r3.com")
        .withNodeSSLKeystorePassword(NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withNodeTrustStorePassword(NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withP2pAddress("localhost")
        .withP2pPort(1234)
        .withArtemisServerAddress("localhost")
        .withArtemisServerPort(1234)
        .withArtemisSSLKeyStorePath(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PATH)
        .withArtemisSSLKeyStorePass(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withArtemisTrustStorePath(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PATH)
        .withArtemisTrustStorePass(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withRpcPort(1234)
        .withRpcAdminPort(1234)
        .withDoormanURL("http://networkservices:8080")
        .withNetworkMapURL("http://networkservices:8080")
        .withRpcUsername("u")
        .withRpcPassword("p")
        .withDataSourceClassName(dbParams.type.dataSourceClass)
        .withDataSourceURL(dbParams.jdbcURL)
        .withDataSourceUsername(NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME.toEnvVar())
        .withDataSourcePassword(NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
        .build()

    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipal = servicePrincipalCreator.createClusterServicePrincipal()
    val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)

    val keyVaultParams = AzureKeyVaultConfigParams
        .builder()
        .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
        .withServicePrincipalCredentialsFilePassword(AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withKeyVaultClientId(AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME.toEnvVar())
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()


    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    val nodeConfigFilesSecretName = "node-config-secrets-$randSuffix"

    val nodeConfigFilesSecret = SecretCreator.createStringSecret(
        nodeConfigFilesSecretName,
        listOf(
            NodeConfigParams.NODE_CONFIG_FILENAME to nodeConf,
            NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME to azKvConf
        ).toMap()
        , namespace, defaultClientSource
    )

    val nodeDatasourceSecretName = "node-datasource-secrets-$randSuffix"
    val nodeDatasourceURLSecretKey = "node-datasource-url"
    val nodeDatasourceUsernameSecretKey = "node-datasource-user"
    val nodeDatasourcePasswordSecretyKey = "node-datasource-password"
    val nodeDataSourceSecrets = SecretCreator.createStringSecret(
        nodeDatasourceSecretName,
        listOf(
            nodeDatasourceURLSecretKey to dbParams.jdbcURL,
            nodeDatasourceUsernameSecretKey to RandomStringUtils.randomAlphanumeric(20),
            nodeDatasourcePasswordSecretyKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
    )

    val nodeStoresSecretName = "node-keystores-secrets-$randSuffix"
    val nodeKeyStorePasswordSecretKey = "node-ssl-keystore-password"
    val nodeTrustStorePasswordSecretKey = "node-truststore-password"

    val nodeStoresSecrets = SecretCreator.createStringSecret(
        nodeStoresSecretName,
        listOf(
            nodeKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20),
            nodeTrustStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
    )

    val keyVaultCredentialsSecretName = "az-kv-password-secrets-$randSuffix"
    val azKeyVaultCredentialsFilePasswordKey = "az-kv-password"
    val azKeyVaultCredentialsClientIdKey = "az-kv-client-id"

    val keyVaultCredentialsPasswordSecret = SecretCreator.createStringSecret(
        keyVaultCredentialsSecretName,
        listOf(
            azKeyVaultCredentialsFilePasswordKey to servicePrincipal.p12FilePassword,
            azKeyVaultCredentialsClientIdKey to servicePrincipal.servicePrincipal.applicationId()
        ).toMap()
        , namespace, defaultClientSource
    )

    val p12FileSecretName = "keyvault-auth-file-secrets-$randSuffix"
    val p12Secret = SecretCreator.createByteArraySecret(
        p12FileSecretName,
        listOf(
            AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to servicePrincipal.p12Bytes
        ).toMap(),
        namespace, defaultClientSource
    )

    val jobName = "initial-registration-$randSuffix"

    val initialRegistrationJob = initialRegistrationJob(
        jobName,
        azureFilesSecretName,
        nodeConfigFilesSecretName,
        keyVaultCredentialsSecretName,
        p12FileSecretName,
        azKeyVaultCredentialsFilePasswordKey,
        azKeyVaultCredentialsClientIdKey,
        nodeDatasourceSecretName,
        nodeDatasourceURLSecretKey,
        nodeDatasourceUsernameSecretKey,
        nodeDatasourcePasswordSecretyKey,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        nodeStoresSecretName,
        nodeKeyStorePasswordSecretKey,
        nodeTrustStorePasswordSecretKey,
        nodeCertificatesShare,
        networkParametersShare
    )


    val tunnelStoresShare = azureFileShareCreator.createDirectoryFor("tunnel-stores")
    val tunnelSecretName = "tunnel-store-secrets-$randSuffix"
    val tunnelEntryPasswordKey = "tunnelentrypassword"
    val tunnelKeyStorePasswordKey = "tunnelsslkeystorepassword"
    val tunnelTrustStorePasswordKey = "tunneltruststorepassword";
    val tunnelSecret = SecretCreator.createStringSecret(
        tunnelSecretName,
        listOf(
            tunnelEntryPasswordKey to RandomStringUtils.randomAlphanumeric(32),
            tunnelKeyStorePasswordKey to RandomStringUtils.randomAlphanumeric(32),
            tunnelTrustStorePasswordKey to RandomStringUtils.randomAlphanumeric(32)
        ).toMap()
        , namespace, defaultClientSource
    )

    val generateTunnelStoresJobName = "gen-tunnel-stores-${randSuffix}"

    val generateArtemisStoresJob = generateArtemisStoresJob(
        generateArtemisStoresJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisStoresShare
    )

    val generateTunnelStoresJob = generateTunnelStores(
        generateTunnelStoresJobName,
        azureFilesSecretName,
        tunnelSecretName,
        tunnelKeyStorePasswordKey,
        tunnelTrustStorePasswordKey,
        tunnelEntryPasswordKey,
        tunnelStoresShare
    )

    val artemisInstalledBrokerShare = azureFileShareCreator.createDirectoryFor("artemis-config")
    val configureArtemisJobName = "configure-artemis-$randSuffix"
    val configureArtemisJob = configureArtemis(
        configureArtemisJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisClusterPassSecretKey,
        artemisStoresShare,
        artemisInstalledBrokerShare
    )

    val importNodeToBridgeJobName = "import-node-ssl-to-bridge-${randSuffix}"
    val bridgeCertificatesShare = azureFileShareCreator.createDirectoryFor("bridge-certs")
    val bridgeCertificatesSecretName = "bridge-certs-secerts-$randSuffix"
    val bridgeSSLKeyStorePasswordSecretKey = "bridgesslpassword"
    val bridgeTruststorePasswordSecretKey = "bridgetruststorepassword"

    val bridgeSSLSecret = SecretCreator.createStringSecret(
        bridgeCertificatesSecretName,
        listOf(
            bridgeSSLKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20),
            bridgeTruststorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
    )

    val importNodeKeyStoreToBridgeJob = importNodeKeyStoreToBridgeJob(
        importNodeToBridgeJobName,
        azureFilesSecretName,
        nodeStoresSecretName,
        nodeKeyStorePasswordSecretKey,
        bridgeCertificatesSecretName,
        bridgeSSLKeyStorePasswordSecretKey,
        nodeCertificatesShare,
        bridgeCertificatesShare
    )

    simpleApply.create(initialRegistrationJob, namespace)
    waitForJob(namespace, initialRegistrationJob, defaultClientSource)
    dumpLogsForJob(defaultClientSource, initialRegistrationJob)

    simpleApply.create(generateArtemisStoresJob, namespace)
    waitForJob(namespace, generateArtemisStoresJob, defaultClientSource)
    dumpLogsForJob(defaultClientSource, generateArtemisStoresJob)

    simpleApply.create(generateTunnelStoresJob, namespace)
    waitForJob(namespace, generateTunnelStoresJob, defaultClientSource)
    dumpLogsForJob(defaultClientSource, generateTunnelStoresJob)

    simpleApply.create(configureArtemisJob, namespace)
    waitForJob(namespace, configureArtemisJob, defaultClientSource)
    dumpLogsForJob(defaultClientSource, configureArtemisJob)

    simpleApply.create(importNodeKeyStoreToBridgeJob, namespace)
    waitForJob(namespace, importNodeKeyStoreToBridgeJob, defaultClientSource)
    dumpLogsForJob(defaultClientSource, importNodeKeyStoreToBridgeJob)

    val artemisDeployment =
        createArtemisDeployment(namespace, azureFilesSecretName, artemisInstalledBrokerShare, artemisStoresShare, null, randSuffix)

    println(Yaml.dump(artemisDeployment))
    simpleApply.create(artemisDeployment, namespace, defaultClientSource())

    val floatTunnelShare = azureFileShareCreator.createDirectoryFor("float-tunnel")
    tunnelStoresShare.fileShare.rootDirectoryReference.getFileReference(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_LOCATION)

    exitProcess(0)
}

private fun waitForJob(
    namespace: String,
    job: V1Job,
    clientSource: () -> ApiClient,
    duration: Duration = Duration.ofMinutes(5)
): V1Job {
    val client = clientSource()
    val httpClient: OkHttpClient = client.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    client.httpClient = httpClient
    val api = BatchV1Api(client)
    val watch: Watch<V1Job> = Watch.createWatch(
        client,
        api.listNamespacedJobCall(
            namespace,
            null,
            null,
            null,
            null,
            "job-name=${job.metadata?.name}",
            null,
            null,
            duration.toSeconds().toInt(),
            true,
            null
        ),
        object : TypeToken<Watch.Response<V1Job>>() {}.type
    )

    watch.use {
        watch.forEach {
            if (it.`object`.status?.succeeded == 1) {
                return it.`object`
            } else {
                println("job ${job.metadata?.name} has not completed yet")
            }
        }
    }

    throw TimeoutException("job ${job.metadata?.name} did not complete within expected time")
}

private fun dumpLogsForJob(clientSource: () -> ApiClient, job: V1Job) {
    val client = clientSource()
    val pod = CoreV1Api(client).listNamespacedPod(
        "testingzone",
        "true",
        null,
        null,
        null,
        "job-name=${job.metadata?.name}", 10, null, 30, false
    ).items.first()
    val logs = PodLogs(client.also { it.httpClient = it.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() })
    val logStream = logs.streamNamespacedPodLog(pod)
    logStream.use {
        IOUtils.copy(it, System.out, 128)
    }
}

//private fun createFloatDeployment(): V1Deployment {
//
//}

private fun createArtemisDeployment(
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
            V1ContainerPortBuilder().withName("artemis-port").withContainerPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT).build()
        ).withNewResources()
        .withRequests(listOf("memory" to Quantity("1024Mi"), "cpu" to Quantity("0.5")).toMap())
        .endResources()
        .withVolumeMounts(
//            V1VolumeMountBuilder()
//                .withName(dataMountName)
//                .withMountPath(ArtemisConfigParams.ARTEMIS_DATA_DIRECTORY).build(),
            V1VolumeMountBuilder()
                .withName(brokerBaseDirShare)
                .withMountPath(ArtemisConfigParams.ARTEMIS_BROKER_BASE_DIR).build(),
            V1VolumeMountBuilder()
                .withName(storesMountName)
                .withMountPath(ArtemisConfigParams.ARTEMIS_STORES_DIR).build()
        )
        .endContainer()
        .withVolumes(
            azureFileMount(brokerBaseDirShare, configShare, azureFilesSecretName, true),
            azureFileMount(storesMountName, storesShare, azureFilesSecretName, true)
//            V1VolumeBuilder()
//                .withNewAzureDisk()
//                .withKind("Managed")
//                .withDiskName(dataDisk.name())
//                .withDiskURI(dataDisk.id())
//                .endAzureDisk()
//                .build()
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

private fun createDiskForArtemis(
    azure: Azure,
    resourceGroup: ResourceGroup,
    runId: String
): Disk {

    val disk = azure.disks().define("artemis-$runId")
        .withRegion(resourceGroup.region())
        .withExistingResourceGroup(resourceGroup)
        .withData()
        .withSizeInGB(200)
        .withSku(DiskSkuTypes.PREMIUM_LRS)
        .create()

    return disk

}

private fun importNodeKeyStoreToBridgeJob(
    jobName: String,
    azureFilesSecretName: String,
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

    val importJob = setupImageTaskBuilder(jobName, listOf("import-node-ssl-to-bridge"))
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
            secretEnvVar("NODE_KEYSTORE_PASSWORD", nodeCertificatesSecretName, nodeKeyStorePasswordSecretKey),
            secretEnvVar("BRIDGE_KEYSTORE_PASSWORD", bridgeCertificatesSecretName, bridgeKeyStorePasswordSecretKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, azureFilesSecretName, false),
            azureFileMount(nodeCertificatesMountName, nodeCertificatesShare, azureFilesSecretName, true)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return importJob
}


private fun configureArtemis(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    artemisClusterPassSecretKey: String,
    artemisStoresDirectory: AzureFilesDirectory,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val storesDirMountName = "storesdir"
    val workingDir = ArtemisConfigParams.ARTEMIS_BROKER_BASE_DIR
    return setupImageTaskBuilder(jobName, listOf("configure-artemis"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build(),
            V1VolumeMountBuilder()
                .withName(storesDirMountName)
                .withMountPath(ArtemisConfigParams.ARTEMIS_STORES_DIR).build()
        )
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_USER_X500_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_CERTIFICATE_SUBJECT),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_ADDRESS_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_ALL_LOCAL_ADDRESSES
            ),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT.toString()),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_KEYSTORE_PATH_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PATH),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_DATA_DIR_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_DATA_DIR_PATH),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisStorePassSecretKey),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisTrustPassSecretKey),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_CLUSTER_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisClusterPassSecretKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, azureFilesSecretName, false),
            azureFileMount(storesDirMountName, artemisStoresDirectory, azureFilesSecretName, true)
        )
        .withRestartPolicy("Never")
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
}


private fun generateTunnelStores(
    jobName: String,
    azureFilesSecretName: String,
    tunnelSecretName: String,
    tunnelKeyStorePasswordKey: String,
    tunnelTrustStorePasswordKey: String,
    tunnelEntryPasswordKey: String,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val workingDir = "/tmp/tunnelGeneration"
    return setupImageTaskBuilder(jobName, listOf("generate-tunnel-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION
            ),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT
            ),
            keyValueEnvVar(BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY_ENV_VAR_NAME, BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY),
            keyValueEnvVar(BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY_ENV_VAR_NAME, BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY),
            secretEnvVar(BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME, tunnelSecretName, tunnelKeyStorePasswordKey),
            secretEnvVar(BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME, tunnelSecretName, tunnelTrustStorePasswordKey),
            secretEnvVar(BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME, tunnelSecretName, tunnelEntryPasswordKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, azureFilesSecretName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}


private fun generateArtemisStoresJob(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    workingDir: AzureFilesDirectory
): V1Job {
    val initialRegistrationJob = setupImageTaskBuilder(jobName, listOf("generate-artemis-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName("azureworkingdir")
                .withMountPath("/tmp/artemisGeneration").build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", "/tmp/artemisGeneration"),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_UNIT
            ),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_CERTIFICATE_LOCALITY_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_CERTIFICATE_LOCALITY),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_CERTIFICATE_COUNTRY_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_CERTIFICATE_COUNTRY),
            secretEnvVar("ARTEMIS_STORE_PASS", artemisSecretsName, artemisStorePassSecretKey),
            secretEnvVar("ARTEMIS_TRUST_PASS", artemisSecretsName, artemisTrustPassSecretKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount("azureworkingdir", workingDir, azureFilesSecretName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}


private fun initialRegistrationJob(
    jobName: String,
    azureFilesSecretName: String,
    nodeConfigSecretsName: String,
    credentialsSecretName: String,
    p12FileSecretName: String,
    azKeyVaultCredentialsFilePasswordKey: String,
    azKeyVaultCredentialsClientIdKey: String,
    nodeDatasourceSecretName: String,
    nodeDatasourceURLSecretKey: String,
    nodeDatasourceUsernameSecretKey: String,
    nodeDatasourcePasswordSecretyKey: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    nodeStoresSecretName: String,
    nodeKeyStorePasswordSecretKey: String,
    nodeTrustStorePasswordSecretKey: String,
    certificatesShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory
): V1Job {
    val p12FileFolderMountName = "azurehsmcredentialsdir"
    val configFilesFolderMountName = "azurecordaconfigdir"
    val certificatesFolderMountName = "azurecordacertificatesdir"
    val networkFolderMountName = "networkdir"
    val initialRegistrationJob = setupImageTaskBuilder(jobName, listOf("perform-registration"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(p12FileFolderMountName)
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(configFilesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName(certificatesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(networkFolderMountName)
                .withMountPath(NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            keyValueEnvVar("TRUST_ROOT_DOWNLOAD_URL", "http://networkservices:8080/truststore"),
            keyValueEnvVar("TRUST_ROOT_PATH", NodeConfigParams.NODE_NETWORK_TRUST_ROOT_PATH),
            keyValueEnvVar("NETWORK_TRUSTSTORE_PASSWORD", "trustpass"),
            keyValueEnvVar("BASE_DIR", NodeConfigParams.NODE_BASE_DIR),
            keyValueEnvVar("CONFIG_FILE_PATH", NodeConfigParams.NODE_CONFIG_PATH),
            keyValueEnvVar("CERTIFICATE_SAVE_FOLDER", NodeConfigParams.NODE_CERTIFICATES_DIR),
            keyValueEnvVar("NETWORK_PARAMETERS_SAVE_FOLDER", NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR),
            secretEnvVar(
                AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME,
                credentialsSecretName,
                azKeyVaultCredentialsFilePasswordKey
            ),
            secretEnvVar(
                AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME,
                credentialsSecretName,
                azKeyVaultCredentialsClientIdKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_URL_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourceURLSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourceUsernameSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourcePasswordSecretyKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecretsName,
                artemisTrustPassSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecretsName,
                artemisStorePassSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecretName,
                nodeKeyStorePasswordSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecretName,
                nodeTrustStorePasswordSecretKey
            )
        )
        .endContainer()
        .withVolumes(
            secretVolumeWithAll(p12FileFolderMountName, p12FileSecretName),
            secretVolumeWithAll(configFilesFolderMountName, nodeConfigSecretsName),
            azureFileMount(certificatesFolderMountName, certificatesShare, azureFilesSecretName, false),
            azureFileMount(networkFolderMountName, networkParametersShare, azureFilesSecretName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}

private fun setupImageTaskBuilder(
    jobName: String,
    command: List<String>
): V1PodSpecFluent.ContainersNested<V1PodTemplateSpecFluent.SpecNested<V1JobSpecFluent.TemplateNested<V1JobFluent.SpecNested<V1JobBuilder>>>> {
    return V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withTtlSecondsAfterFinished(100)
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand(command)
}

private fun azureFileMount(
    mountName: String,
    share: AzureFilesDirectory,
    azureFilesSecretName: String,
    readOnly: Boolean
): V1Volume {
    return V1VolumeBuilder()
        .withName(mountName)
        .withNewAzureFile()
        .withShareName(share.fileShare.name)
        .withSecretName(azureFilesSecretName)
        .withReadOnly(readOnly)
        .endAzureFile()
        .build()
}

private fun secretVolumeWithAll(
    mountName: String,
    secretName: String
): V1Volume {
    return V1VolumeBuilder()
        .withName(mountName)
        .withNewSecret()
        .withSecretName(secretName)
        .endSecret()
        .build()
}

private fun secretEnvVar(
    key: String,
    secretName: String,
    secretKey: String
): V1EnvVar {
    return V1EnvVarBuilder().withName(key)
        .withNewValueFrom()
        .withNewSecretKeyRef()
        .withName(secretName)
        .withKey(secretKey)
        .endSecretKeyRef()
        .endValueFrom()
        .build()
}

private fun keyValueEnvVar(key: String?, value: String?): V1EnvVar {
    return V1EnvVarBuilder()
        .withName(key)
        .withValue(value)
        .build()
}

private fun licenceAcceptEnvVar() = keyValueEnvVar("ACCEPT_LICENSE", "Y")

fun String.toEnvVar(): String {
    return "\${$this}"
}