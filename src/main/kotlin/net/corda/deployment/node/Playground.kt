package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import io.kubernetes.client.PodLogs
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.BridgeConfigParams
import net.corda.deployments.node.config.NodeConfigParams
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextUInt


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())
    allowAllFailures { simpleApply.apply(nmsSetup, namespace = "testingzone") }
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")


    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = Random.nextUInt().toString(36).toLowerCase()

    val defaultClient = ClientBuilder.defaultClient().also { it.isDebugging = true }

    val azureFileShareCreator = AzureFileShareCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)

    val certificatesShare = azureFileShareCreator.createDirectoryFor("certificates")
    val networkParametersShare = azureFileShareCreator.createDirectoryFor("network-files")
    val azureFilesSecretName = "azure-files-secret-$randSuffix"
    SecretCreator.createStringSecret(
        azureFilesSecretName,
        listOf(
            "azurestorageaccountname" to certificatesShare.storageAccount.name(),
            "azurestorageaccountkey" to certificatesShare.storageAccount.keys[0].value()
        ).toMap()
        , "testingzone", defaultClient
    )

    val dbParams = H2_DB

    val nodeSSLStorePassword = RandomStringUtils.randomAlphanumeric(20)
    val nodeTrustStorePassword = RandomStringUtils.randomAlphanumeric(20)

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
        .withDataSourceUsername(dbParams.username)
        .withDataSourcePassword(dbParams.password)
        .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
        .build()

    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipal = servicePrincipalCreator.createClusterServicePrincipal()
    val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)

    val keyVaultParams = AzureKeyVaultConfigParams
        .builder()
        .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
        .withServicePrincipalCredentialsFilePassword("\${${AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME}}")
        .withKeyVaultClientId("\${${AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME}}")
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()


    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    val nodeConfigSecretName = "node-config-secrets-$randSuffix"

    val nodeConfigSecrets = SecretCreator.createStringSecret(
        nodeConfigSecretName,
        listOf(
            NodeConfigParams.NODE_CONFIG_FILENAME to nodeConf,
            NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME to azKvConf
        ).toMap()
        , "testingzone", defaultClient
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
        , "testingzone", defaultClient
    )

    val p12FileSecretName = "keyvault-auth-file-secrets-$randSuffix"
    SecretCreator.createByteArraySecret(
        p12FileSecretName,
        listOf(
            AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to servicePrincipal.p12Bytes
        ).toMap(),
        "testingzone", defaultClient
    )


    val jobName = "initial-registration-$randSuffix"

    val initialRegistrationJob = initialRegistrationJob(
        jobName,
        azureFilesSecretName,
        nodeConfigSecretName,
        keyVaultCredentialsSecretName,
        p12FileSecretName,
        azKeyVaultCredentialsFilePasswordKey,
        azKeyVaultCredentialsClientIdKey,
        certificatesShare,
        networkParametersShare
    )

    val artemisStoreDirectory = azureFileShareCreator.createDirectoryFor("artemisstores")

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
        , "testingzone", defaultClient
    )
    val generateArtemisStoresJobName = "gen-artemis-stores-${randSuffix}"


    val tunnelStoresDirectory = azureFileShareCreator.createDirectoryFor("tunnelstores")
    val tunnelSecretName = "tunnelstoresecret-$randSuffix"
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
        , "testingzone", defaultClient
    )

    val generateTunnelStoresJobName = "gen-tunnel-stores-${randSuffix}"

    val generateArtemisStoresJob = generateArtemisStoresJob(
        generateArtemisStoresJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisStoreDirectory
    )

    val generateTunnelStoresJob = generateTunnelStores(
        generateTunnelStoresJobName,
        azureFilesSecretName,
        tunnelSecretName,
        tunnelKeyStorePasswordKey,
        tunnelTrustStorePasswordKey,
        tunnelEntryPasswordKey,
        tunnelStoresDirectory
    )

    val artemisConfigShare = azureFileShareCreator.createDirectoryFor("artemis-config")
    val configureArtemisJobName = "configure-artemis-$randSuffix"
    val configureArtemisJob = configureArtemis(
        configureArtemisJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisClusterPassSecretKey,
        artemisConfigShare
    )

    simpleApply.create(initialRegistrationJob, "testingzone")
    simpleApply.create(generateArtemisStoresJob, "testingzone")
    simpleApply.create(generateTunnelStoresJob, "testingzone")
    simpleApply.create(configureArtemisJob, "testingzone")



    dumpLogsForJob(defaultClient, initialRegistrationJob.metadata?.name!!)
    dumpLogsForJob(defaultClient, generateArtemisStoresJob.metadata?.name!!)
    dumpLogsForJob(defaultClient, generateTunnelStoresJob.metadata?.name!!)


}

private fun dumpLogsForJob(defaultClient: ApiClient, jobName: String) {
    while (CoreV1Api(defaultClient).listNamespacedPod(
            "testingzone",
            "true",
            null,
            null,
            null,
            "job-name=${jobName}", 10, null, 30, false
        ).items.firstOrNull()?.status?.containerStatuses?.firstOrNull { it.ready }?.ready != true
    ) {
        println("No matching job, waiting")
        Thread.sleep(5000)
    }


    val pod = CoreV1Api(defaultClient).listNamespacedPod(
        "testingzone",
        "true",
        null,
        null,
        null,
        "job-name=${jobName}", 10, null, 30, false
    ).items.first()
    val logs = PodLogs(defaultClient.also { it.httpClient = it.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() })
    IOUtils.copy(logs.streamNamespacedPodLog(pod), System.out, 128)
}

private fun configureArtemis(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    artemisClusterPassSecretKey: String,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val workingDir = "/tmp/artemisConfig"
    return V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withImagePullPolicy("IfNotPresent")
        .withCommand(listOf("configure-artemis"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build()
        )
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_USER_X500_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_CERTIFICATE_SUBJECT),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_ACCEPTOR_ADDRESS_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_ACCEPTOR_ALL_LOCAL_ADDRESSES),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT.toString()),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_KEYSTORE_PATH_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PATH),
            keyValueEnvVar(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH_ENV_VAR_NAME, ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisStorePassSecretKey),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisTrustPassSecretKey),
            secretEnvVar(ArtemisConfigParams.ARTEMIS_CLUSTER_PASSWORD_ENV_VAR_NAME, artemisSecretsName, artemisClusterPassSecretKey)
        )
        .endContainer()
        .withVolumes(
            V1VolumeBuilder()
                .withName(workingDirMountName)
                .withNewAzureFile()
                .withShareName(workingDirShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()
        )
        .withRestartPolicy("Never")
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
    val key = BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME
    val value = BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION
    val bridgeTunnelKeystorePasswordEnvVarName = BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME
    return V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withCommand(listOf("generate-tunnel-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME,
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
            V1VolumeBuilder()
                .withName(workingDirMountName)
                .withNewAzureFile()
                .withShareName(workingDirShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()
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
    val initialRegistrationJob = V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withCommand(listOf("generate-artemis-keystores"))
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
            V1VolumeBuilder()
                .withName("azureworkingdir")
                .withNewAzureFile()
                .withShareName(workingDir.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}

private fun licenceAcceptEnvVar() = keyValueEnvVar("ACCEPT_LICENSE", "Y")

private fun initialRegistrationJob(
    jobName: String,
    azureFilesSecretName: String,
    nodeConfigSecretsName: String,
    credentialsSecretName: String,
    p12FileSecretName: String,
    azKeyVaultCredentialsFilePasswordKey: String,
    azKeyVaultCredentialsClientIdKey: String,
    certificatesShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory
): V1Job {
    val p12FileFolderMountName = "azurehsmcredentialsdir"
    val configFilesFolderMountName = "azurecordaconfigdir"
    val certificatesFolderMountName = "azurecordacertificatesdir"
    val networkFolderMountName = "networkdir"

    val initialRegistrationJob = V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withCommand(listOf("perform-registration"))
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
            keyValueEnvVar("TRUSTSTORE_PASSWORD", "trustpass"),
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
            )
        )
        .endContainer()
        .withVolumes(
            secretVolumeWithAll(p12FileFolderMountName, p12FileSecretName),
            secretVolumeWithAll(configFilesFolderMountName, nodeConfigSecretsName),
            V1VolumeBuilder()
                .withName(certificatesFolderMountName)
                .withNewAzureFile()
                .withShareName(certificatesShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false)
                .endAzureFile()
                .build(),
            V1VolumeBuilder()
                .withName(networkFolderMountName)
                .withNewAzureFile()
                .withShareName(networkParametersShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false)
                .endAzureFile()
                .build()
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}

private fun secretVolumeWithAll(
    p12FileFolderMountName: String,
    p12FileSecretName: String
): V1Volume? {
    return V1VolumeBuilder()
        .withName(p12FileFolderMountName)
        .withNewSecret()
        .withSecretName(p12FileSecretName)
        .endSecret()
        .build()
}

private fun secretEnvVar(
    key: String,
    secretName: String,
    tunnelKeyStorePasswordKey: String
): V1EnvVar {
    return V1EnvVarBuilder().withName(key)
        .withNewValueFrom()
        .withNewSecretKeyRef()
        .withName(secretName)
        .withKey(tunnelKeyStorePasswordKey)
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

fun String.toEnvVar(): String {
    return "\${$this}"
}