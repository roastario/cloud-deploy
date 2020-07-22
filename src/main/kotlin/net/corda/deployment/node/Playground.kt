package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.compute.Disk
import com.microsoft.azure.management.compute.DiskSkuTypes
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.rest.LogLevel
import io.kubernetes.client.custom.Quantity
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
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.*
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.time.Duration
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


    val initialRegistrationResultShare = azureFileShareCreator.createDirectoryFor("node-certificates")
    val networkParametersFromInitialRegistrationShare = azureFileShareCreator.createDirectoryFor("network-files")
    val azureFilesSecretName = "azure-files-secret-$randSuffix"
    SecretCreator.createStringSecret(
        azureFilesSecretName,
        listOf(
            "azurestorageaccountname" to initialRegistrationResultShare.storageAccount.name(),
            "azurestorageaccountkey" to initialRegistrationResultShare.storageAccount.keys[0].value()
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
        initialRegistrationResultShare,
        networkParametersFromInitialRegistrationShare
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
        initialRegistrationResultShare,
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


    val generatedTunnelTrustStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)

    val generatedFloatTunnelKeyStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)

    val generatedBridgeTunnelKeyStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRDIGE_KEYSTORE_FILENAME)

    val floatTunnelTrustStoreFileReference =
        floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)

    val floatTunnelKeyStoreFileReference =
        floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)

    floatTunnelTrustStoreFileReference.createFrom(generatedTunnelTrustStoreFileReference)
    floatTunnelKeyStoreFileReference.createFrom(generatedFloatTunnelKeyStoreFileReference)

    val floatConfig = FloatConfigParams.builder()
        .withBaseDir(FloatConfigParams.FLOAT_BASE_DIR)
        .withExpectedBridgeCertificateSubject(BridgeConfigParams.BRIDGE_CERTIFICATE_SUBJECT)
        .withExternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
        .withExternalPort(FloatConfigParams.FLOAT_EXTERNAL_PORT)
        .withInternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
        .withInternalPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
        .withNetworkParametersPath(FloatConfigParams.FLOAT_NETWORK_PARAMETERS_PATH)
        .withTunnelKeyStorePassword(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelKeystorePath(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PATH)
        .withTunnelTrustStorePassword(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelTrustStorePath(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PATH)
        .withTunnelStoresEntryPassword(FloatConfigParams.FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .build()

    val floatConfigShare = azureFileShareCreator.createDirectoryFor("float-config")
    val floatConfigFileReference =
        floatConfigShare.legacyClient.rootDirectoryReference.getFileReference(FloatConfigParams.FLOAT_CONFIG_FILENAME)
    floatConfigFileReference.uploadFromByteArray(ConfigGenerators.generateConfigFromParams(floatConfig).toByteArray(Charsets.UTF_8))

    val floatNetworkParamsShare = azureFileShareCreator.createDirectoryFor("float-network-params")
    val floatNetworkParamsFileReference =
        floatNetworkParamsShare.modernClient.rootDirectoryClient.getFileClient(FloatConfigParams.FLOAT_NETWORK_PARAMS_FILENAME)
    val generatedNetworkParamsFileReference =
        networkParametersFromInitialRegistrationShare.modernClient.rootDirectoryClient.getFileClient(NodeConfigParams.NETWORK_PARAMETERS_FILENAME)

    floatNetworkParamsFileReference.createFrom(generatedNetworkParamsFileReference)

    val floatDeployment = createFloatDeployment(
        namespace,
        randSuffix,
        floatConfigShare,
        floatTunnelShare,
        floatNetworkParamsShare,
        tunnelSecretName,
        tunnelKeyStorePasswordKey,
        tunnelTrustStorePasswordKey,
        tunnelEntryPasswordKey,
        azureFilesSecretName
    )

    println(Yaml.dump(floatDeployment))
    simpleApply.create(floatDeployment, namespace, defaultClientSource())

    val artemisAddress = "artemisAddress"
    val floatAddress = "floatAddress"
    val bridgeConfig = BridgeConfigParams.builder()
        .withArtemisAddress(artemisAddress)
        .withArtemisPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
        .withArtemisKeyStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_SSL_KEYSTORE_PATH)
        .withArtemisKeyStorePassword(BridgeConfigParams.BRIDGE_ARTEMIS_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withArtemisTrustStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PATH)
        .withArtemisTrustStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withFloatAddress(floatAddress)
        .withFloatPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
        .withExpectedFloatCertificateSubject(FloatConfigParams.FLOAT_CERTIFICATE_SUBJECT)
        .withTunnelKeyStorePath(BridgeConfigParams.BRIDGE_TUNNEL_SSL_KEYSTORE_PATH)
        .withTunnelKeyStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelTrustStorePath(BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PATH)
        .withTunnelTrustStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelEntryPassword(BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withNetworkParamsPath(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_PATH)
        .withBridgeKeyStorePath(BridgeConfigParams.BRIDGE_SSL_KEYSTORE_PATH)
        .withBridgeKeyStorePassword(BridgeConfigParams.BRIDGE_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withBridgeTrustStorePath(BridgeConfigParams.BRIDGE_TRUSTSTORE_PATH)
        .withBridgeTrustStorePassword(BridgeConfigParams.BRIDGE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .build()

    val bridgeConfigShare = azureFileShareCreator.createDirectoryFor("bridge-config")
    val bridgeConfigFileReference = bridgeConfigShare.legacyClient.rootDirectoryReference.getFileReference(BridgeConfigParams.BRIDGE_CONFIG_FILENAME)
    bridgeConfigFileReference.uploadFromByteArray(ConfigGenerators.generateConfigFromParams(bridgeConfig).toByteArray(Charsets.UTF_8))

    val bridgeNetworkParamsShare = azureFileShareCreator.createDirectoryFor("bridge-network-params")
    val bridgeNetworkParamsFileReference =
        bridgeNetworkParamsShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_FILENAME)

    bridgeNetworkParamsFileReference.createFrom(generatedNetworkParamsFileReference)

    val bridgeTunnelShare = azureFileShareCreator.createDirectoryFor("bridge-tunnel")
    val bridgeTunnelTrustStoreFileReference =
        bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
    val bridgeTunnelKeyStoreFileReference =
        bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRDIGE_KEYSTORE_FILENAME)
    bridgeTunnelTrustStoreFileReference.createFrom(generatedTunnelTrustStoreFileReference)
    bridgeTunnelKeyStoreFileReference.createFrom(generatedBridgeTunnelKeyStoreFileReference)

    val bridgeArtemisStoresShare = azureFileShareCreator.createDirectoryFor("bridge-artemis-stores")
    val bridgeArtemisKeyStoreFileReference =
        bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME)
    val bridgeArtemisTrustStoreFileReference =
        bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)

    val generatedArtemisBridgeKeyStoreFileReference = artemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME)
    val generatedArtemisTrustStoreFileReference = artemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)

    bridgeArtemisKeyStoreFileReference.createFrom(generatedArtemisBridgeKeyStoreFileReference)
    bridgeArtemisTrustStoreFileReference.createFrom(generatedArtemisTrustStoreFileReference)



    exitProcess(0)
}

fun ShareFileClient.createFrom(source: ShareFileClient, timeout: Duration = Duration.ofMinutes(5)) {
    val sizeToCopy = source.properties.contentLength
    if (!this.exists()) {
        this.create(sizeToCopy)
    }
    val poller = this.beginCopy(
        source.fileUrl,
        null,
        null
    )
    poller.waitForCompletion(timeout)
}


fun createFloatDeployment(
    namespace: String,
    runId: String,
    floatConfigShare: AzureFilesDirectory,
    tunnelStoresShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory,
    tunnelStoresSecretName: String,
    tunnelSSLKeysStorePasswordSecretKey: String,
    tunnelTrustStorePasswordSecretKey: String,
    tunnelEntryPasswordKey: String,
    azureFilesSecretName: String
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
            V1EnvVarBuilder().withName(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME).withValue(FloatConfigParams.FLOAT_BASE_DIR).build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(FloatConfigParams.FLOAT_BASE_DIR).build(),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelSSLKeysStorePasswordSecretKey
            ),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelTrustStorePasswordSecretKey
            ),
            secretEnvVar(
                FloatConfigParams.FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                tunnelStoresSecretName,
                tunnelEntryPasswordKey
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
                azureFileMount(configDirMountName, floatConfigShare, azureFilesSecretName, true),
                azureFileMount(tunnelStoresMountName, tunnelStoresShare, azureFilesSecretName, true),
                azureFileMount(networkParametersMountName, networkParametersShare, azureFilesSecretName, true)
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

fun createDiskForArtemis(
    azure: Azure,
    resourceGroup: ResourceGroup,
    runId: String
): Disk {

    return azure.disks().define("artemis-$runId")
        .withRegion(resourceGroup.region())
        .withExistingResourceGroup(resourceGroup)
        .withData()
        .withSizeInGB(200)
        .withSku(DiskSkuTypes.PREMIUM_LRS)
        .create()

}


fun String.toEnvVar(): String {
    return "\${$this}"
}