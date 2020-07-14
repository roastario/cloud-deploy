package net.corda.deployment.node.principals

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.graphrbac.BuiltInRole
import com.microsoft.azure.management.graphrbac.ServicePrincipal
import com.microsoft.azure.management.resources.ResourceGroup
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.math.BigInteger
import java.nio.file.Files
import java.security.*
import java.security.cert.Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*


class ServicePrincipalCreator(private val azure: Azure, private val resourceGroup: ResourceGroup, private val runSuffix: String) {

    fun createClusterServicePrincipal(
    ): PrincipalAndCredentials {
        val servicePrincipalKeyPair = generateRSAKeyPair()
        val servicePrincipalCert = createSelfSignedCertificate(servicePrincipalKeyPair, "CN=CLI-Login")
        val servicePrincipalPassword = RandomStringUtils.randomGraph(16)
        val createdSP = azure.accessManagement().servicePrincipals().define("testingspforaks$runSuffix")
            .withNewApplication("http://testingspforaks${runSuffix}")
            .withNewRoleInResourceGroup(BuiltInRole.CONTRIBUTOR, resourceGroup)
            .definePasswordCredential("cliLoginPwd")
            .withPasswordValue(servicePrincipalPassword)
            .attach()
            .defineCertificateCredential("cliLoginCert")
            .withAsymmetricX509Certificate()
            .withPublicKey(servicePrincipalCert.encoded)
            .attach()
            .create()

        val p12KeyStorePassword = RandomStringUtils.randomGraph(16)
        val pemFile = generatePemFile(servicePrincipalKeyPair, servicePrincipalCert)
        val keyAlias = "key-vault-login-key"
        val p12File = createPksc12Store(servicePrincipalKeyPair.private, servicePrincipalCert, keyAlias, p12KeyStorePassword)

        return PrincipalAndCredentials(
            createdSP,
            servicePrincipalPassword,
            servicePrincipalKeyPair,
            servicePrincipalCert,
            pemFile, p12File, p12KeyStorePassword, keyAlias
        )
    }
}

data class PrincipalAndCredentials(
    val servicePrincipal: ServicePrincipal,
    val servicePrincipalPassword: String,
    val servicePrincipalKeyPair: KeyPair,
    val servicePrincipalCert: Certificate,
    val pemFile: File,
    val p12File: File,
    val p12FilePassword: String,
    val p12KeyAlias: String
)

private fun createPksc12Store(
    priv: PrivateKey,
    cert: Certificate,
    keyAlias: String,
    keystorePassword: String
): File {
    val tempFile = Files.createTempFile("sp", ".pem").toFile()
    FileOutputStream(tempFile).use {
        val ks = KeyStore.getInstance("PKCS12");
        ks.load(null)
        ks.setKeyEntry(keyAlias, priv, keystorePassword.toCharArray(), arrayOf(cert))
        ks.store(it, keystorePassword.toCharArray())
    }
    return tempFile
}

private fun generatePemFile(keyPair: KeyPair, cert: Certificate): File {
    val pemObjectPriv = PemObject("PRIVATE KEY", keyPair.private.encoded)
    val pemObjectCert = PemObject("CERTIFICATE", cert.encoded)
    val tempFile = Files.createTempFile("sp", ".pem").toFile()
    val pemWriter = PemWriter(FileWriter(tempFile))
    pemWriter.use {
        pemWriter.writeObject(pemObjectPriv)
        pemWriter.writeObject(pemObjectCert)
    }
    return tempFile
}

private fun generateRSAKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("RSA", "BC")
    generator.initialize(2048)
    return generator.generateKeyPair()
}

private fun createSelfSignedCertificate(keyPair: KeyPair, subjectDN: String): Certificate {
    val startDate = Date.from(Instant.now())
    val endDate: Date = Date.from(Instant.now().plus(900, ChronoUnit.DAYS))
    val dnName = X500Name(subjectDN)
    val certSerialNumber = BigInteger("${startDate.time}")
    val signatureAlgorithm = "SHA1WITHRSA"
    val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)
    val certBuilder =
        JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.public)
    return JcaX509CertificateConverter().setProvider(Security.getProvider("BC"))
        .getCertificate(certBuilder.build(contentSigner))
}