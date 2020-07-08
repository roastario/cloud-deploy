package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.graphrbac.BuiltInRole
import com.microsoft.azure.management.graphrbac.RoleAssignment
import com.microsoft.azure.management.graphrbac.ServicePrincipal
import com.microsoft.azure.management.keyvault.*
import com.microsoft.azure.management.keyvault.Permissions
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

val bouncyCastleProvider = BouncyCastleProvider()

val LOGGER = LoggerFactory.getLogger("Main")

fun generateRSAKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("RSA", "BC")
    generator.initialize(2048)
    val keyPair = generator.generateKeyPair()
    LOGGER.info("RSA key pair generated.")
    return keyPair
}

fun createSelfSignedCertificate(keyPair: KeyPair, subjectDN: String): Certificate {
    val startDate = Date.from(Instant.now())
    val endDate: Date = Date.from(Instant.now().plus(900, ChronoUnit.DAYS))
    val dnName = X500Name(subjectDN)
    val certSerialNumber = BigInteger("${startDate.time}")
    val signatureAlgorithm = "SHA1WITHRSA"
    val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)
    val certBuilder =
        JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.public)
    return JcaX509CertificateConverter().setProvider(bouncyCastleProvider)
        .getCertificate(certBuilder.build(contentSigner))
}

fun generatePemFile(keyPair: KeyPair, cert: Certificate, path: String) {
    val pemObjectPriv = PemObject("PRIVATE KEY", keyPair.private.encoded)
    val pemObjectCert = PemObject("CERTIFICATE", cert.encoded)
    val pemWriter = PemWriter(FileWriter(path))
    pemWriter.use {
        pemWriter.writeObject(pemObjectPriv)
        pemWriter.writeObject(pemObjectCert)
    }
}


fun printCert(path: String) {
    val fact = CertificateFactory();
    val input = FileInputStream(path);
    val cer: Certificate = fact.engineGenerateCertificate(input);
    println(cer)
}

fun createKeyVault(vaultName: String): Vault {
    val a: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withDefaultSubscription()

    val kv = a.vaults()
        .define(vaultName)
        .withRegion(Region.EUROPE_NORTH).withExistingResourceGroup("stefano-playground").withEmptyAccessPolicy()
        .withAccessFromAllNetworks().create()

    return kv
}

fun createPksc12Store(
    priv: PrivateKey,
    cert: Certificate,
    keyAlias: String,
    keystorePassword: String,
    outputPath: String
) {
    FileOutputStream(outputPath).use {
        val ks = KeyStore.getInstance("PKCS12");
        ks.load(null)
        ks.setKeyEntry(keyAlias, priv, keystorePassword.toCharArray(), arrayOf(cert))
        ks.store(it, keystorePassword.toCharArray())
    }
}

fun configureServicePrincipalAccessToKeyVault(
    sp: ServicePrincipal,
    kv: Vault
): RoleAssignment {

    val a: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withDefaultSubscription()

    val createdRole = a.accessManagement().roleAssignments().define(UUID.randomUUID().toString())
        .forServicePrincipal(sp).withBuiltInRole(BuiltInRole.CONTRIBUTOR)
        .withResourceScope(kv).create()

    val certPermissions = listOf(
        CertificatePermissions.GET,
        CertificatePermissions.LIST,
        CertificatePermissions.UPDATE,
        CertificatePermissions.CREATE,
        CertificatePermissions.IMPORT,
        CertificatePermissions.RECOVER,
        CertificatePermissions.BACKUP,
        CertificatePermissions.RESTORE,
        CertificatePermissions.MANAGECONTACTS,
        CertificatePermissions.MANAGEISSUERS,
        CertificatePermissions.GETISSUERS,
        CertificatePermissions.LISTISSUERS,
        CertificatePermissions.SETISSUERS
    )

    val keyPermissions = listOf(
        KeyPermissions.GET,
        KeyPermissions.LIST,
        KeyPermissions.UPDATE,
        KeyPermissions.CREATE,
        KeyPermissions.IMPORT,
        KeyPermissions.RECOVER,
        KeyPermissions.BACKUP,
        KeyPermissions.RESTORE,
        KeyPermissions.DECRYPT,
        KeyPermissions.ENCRYPT,
        KeyPermissions.UNWRAP_KEY,
        KeyPermissions.WRAP_KEY,
        KeyPermissions.VERIFY,
        KeyPermissions.SIGN
    )

    val secretPermissions = listOf(
        SecretPermissions.GET,
        SecretPermissions.LIST,
        SecretPermissions.SET,
        SecretPermissions.RECOVER,
        SecretPermissions.BACKUP,
        SecretPermissions.RESTORE
    )

    val accessPolicyEntry = AccessPolicyEntry()
        .withPermissions(
            Permissions()
                .withCertificates(certPermissions)
                .withKeys(keyPermissions)
                .withSecrets(secretPermissions)
        )
        .withObjectId(sp.id())
        .withTenantId(UUID.fromString(kv.tenantId()))


    kv.manager().inner().vaults().updateAccessPolicy(
        kv.resourceGroupName(), kv.name(), AccessPolicyUpdateKind.ADD, VaultAccessPolicyProperties().withAccessPolicies(
            listOf(accessPolicyEntry)
        )
    )
    return createdRole
}