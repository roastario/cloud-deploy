package net.corda.deployment.node

import com.microsoft.aad.msal4j.*
import com.microsoft.azure.AzureEnvironment
import com.microsoft.azure.credentials.AzureTokenCredentials
import java.io.File
import java.net.URI

/*
class TokenCacheAspect(fileName: String) : ITokenCacheAccessAspect {


    private var data: String
    override fun beforeCacheAccess(iTokenCacheAccessContext: ITokenCacheAccessContext) {
        iTokenCacheAccessContext.tokenCache().deserialize(data)
    }

    override fun afterCacheAccess(iTokenCacheAccessContext: ITokenCacheAccessContext) {
        data = iTokenCacheAccessContext.tokenCache().serialize()
        backingFile.writeText(data, Charsets.UTF_8)
    }

    companion object {
        val homeDir = File(System.getProperty("user.home"))
        val deployerDir = File(homeDir, ".azDeploy").also { it.mkdirs() }
        private val backingFile = File(
            deployerDir,
            "az.config"
        ).also {
            if (!it.exists()) {
                it.createNewFile()
                it.writeBytes(Thread.currentThread().contextClassLoader.getResourceAsStream("sample_cache.json").readBytes())
            }
        }

        private fun readDataFromFile(resource: String): String {
            return backingFile.readText(Charsets.UTF_8)
        }
    }

    init {
        data = readDataFromFile(fileName)
    }
}
*/

class DeviceCodeTokenCredentials(environment: AzureEnvironment, domain: String?, private val scope: Set<String>? = null) :
    AzureTokenCredentials(environment, domain) {
    val cachingGetter = DeviceCodeFlow
    override fun getToken(resource: String): String {
        return cachingGetter.acquireTokenDeviceCode(resource, scope).accessToken()
    }
}

object DeviceCodeFlow {
    private const val CLIENT_ID = "2c0737b0-272a-4111-b7de-634b7f6b084b"

    //private val defaultScope = setOf("https://graph.microsoft.com/Directory.Read")
    //private val defaultScope = setOf("https://management.azure.com/user_impersonation")
    private val defaultScope = setOf("https://graph.windows.net/.default")
    //private val defaultScope = setOf("https://graph.windows.net/common/applications/user_impersonation")

    private const val AUTHORITY = "https://login.microsoftonline.com/common/"

    public val SCOPES_GRAPH = setOf("https://graph.windows.net/.default")
    public val SCOPES_MANAGEMENT = setOf("https://management.azure.com/user_impersonation")

    @Throws(Exception::class)
    fun acquireTokenDeviceCode(res: String, scope: Set<String>? = null): IAuthenticationResult {
        //val tokenCacheAspect = TokenCacheAspect("sample_cache.json")
        val pca = PublicClientApplication.builder(CLIENT_ID)
            .authority(AUTHORITY)
            //.setTokenCacheAccessAspect(tokenCacheAspect)
            .build()
        val accountsInCache = pca.accounts.join()
        val account = accountsInCache.filter { !it.username().startsWith("John") }.firstOrNull()
        val result: IAuthenticationResult
        var scopes = scope
        if (scopes == null) {
            scopes = defaultScope
        }
        result = try {
            if (account != null) {
                val silentParameters = SilentParameters
                    .builder(scopes, account)
                    .build()
                pca.acquireTokenSilently(silentParameters).join()
            } else {
                val parameters = InteractiveRequestParameters
                    .builder(URI("http://localhost"))
                    .scopes(scopes)
                    .build();
                pca.acquireToken(parameters).join()
            }
        } catch (e: Exception) {
            if (e.cause is MsalException) {
                val parameters = InteractiveRequestParameters
                    .builder(URI("http://localhost"))
                    .scopes(scope)
                    .build();
                pca.acquireToken(parameters).join()
            } else {
                throw e
            }

        }
        return result
    }
}