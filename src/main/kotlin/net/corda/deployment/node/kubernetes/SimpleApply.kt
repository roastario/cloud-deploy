package net.corda.deployment.node.kubernetes

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1ObjectMeta
import java.lang.IllegalStateException
import java.lang.reflect.InvocationTargetException
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.max

fun parseVersionStringToInt(className: String): Int {
    val versionSuffixRegex = Regex("V(\\d+.*)$")
    val versionMatchResult = versionSuffixRegex.find(className)
    val kubeApiString = versionMatchResult?.groups?.get(1)?.value
    val versionTypes = arrayOf("alpha", "beta")
    val result = Regex(pattern = "(^\\d+)([a-z]*)(\\d*)").find(kubeApiString ?: "")
    val version = result?.groups?.get(1)?.value?.toInt() ?: 0
    val type = max(result?.groups?.get(2)?.value?.let { versionTypes.indexOf(it) } ?: 0, 0)
    val typeLevel = result?.groups?.get(3)?.value?.toIntOrNull() ?: 0
    return version * 100 + type * 10 + typeLevel
}

data class ApiClassAndMethodPair(
    val apiClassInfo: ClassInfo,
    val type: Class<*>,
    val createMethod: MethodInfo,
    val readMethod: MethodInfo?,
    val replaceMethod: MethodInfo?,
    val deleteMethod: MethodInfo?,
    val namespaced: Boolean
) :
    Comparable<ApiClassAndMethodPair> {


    override fun compareTo(other: ApiClassAndMethodPair): Int {
        val ourApiVersion = parseVersionStringToInt(apiClassInfo.name)
        val otherApiVersion = parseVersionStringToInt(other.apiClassInfo.name)
        return ourApiVersion.compareTo(otherApiVersion)
    }

}


val methodMap: Lazy<HashMap<Class<*>, PriorityQueue<ApiClassAndMethodPair>>> = lazy {

    val apis = ClassGraph().acceptPackages("io.kubernetes.client.openapi.apis")
        .enableMethodInfo()
        .scan().allClasses.toList().filter { !it.name.contains("alpha") }

    HashMap<Class<*>, PriorityQueue<ApiClassAndMethodPair>>().also { map ->
        apis.forEach { classInfo ->

            val interestingMethods = classInfo.methodInfo.filter { methodInfo ->
                methodInfo.parameterInfo.map { it.typeSignatureOrTypeDescriptor.toStringWithSimpleNames() }
                    .contains(methodInfo.typeSignatureOrTypeDescriptor.resultType.toStringWithSimpleNames())
                    .and(methodInfo.name.startsWith("create").or(methodInfo.name.startsWith("createNamespaced")))
            }

            val methodPairs = interestingMethods.map {
                val createMethod = it
                println("looking for: ${it.name.replace("create", "read")}")
                val findMethod = classInfo.getMethodInfo(it.name.replace("create", "read")).firstOrNull()
                println("looking for: ${it.name.replace("create", "replace")}")
                val replaceMethod = classInfo.getMethodInfo(it.name.replace("create", "replace")).firstOrNull()
                println("looking for: ${it.name.replace("create", "delete")}")
                val deleteMethod = classInfo.getMethodInfo(it.name.replace("create", "delete")).firstOrNull()
                ApiClassAndMethodPair(
                    classInfo,
                    Class.forName(it.typeSignatureOrTypeDescriptor.resultType?.toString()),
                    createMethod,
                    findMethod,
                    replaceMethod,
                    deleteMethod,
                    createMethod.name.startsWith("createNamespaced")
                )
            }

            methodPairs.forEach {
                map.computeIfAbsent(it.type) {
                    PriorityQueue()
                }.add(it)
            }

        }

    }
}

interface SimpleApplier {
    fun create(o: Any, namespace: String, apiClient: ApiClient)
    fun apply(o: List<Any>, namespace: String, apiClient: ApiClient)

    fun create(o: Any, namespace: String, apiClient: () -> ApiClient) = create(o, namespace, apiClient())
    fun apply(o: List<Any>, namespace: String, apiClient: () -> ApiClient) = apply(o, namespace, apiClient())
}

val simpleApply = object : SimpleApplier {
    override fun create(o: Any, namespace: String, apiClient: ApiClient) {
        when (val classOfObjectToCreate = o.javaClass) {
            in methodMap.value -> {
                val metaDataMethod = allowAllFailures { classOfObjectToCreate.getMethod("getMetadata") }
                val metaData = metaDataMethod?.invoke(o) as? V1ObjectMeta
                val info = methodMap.value[classOfObjectToCreate]!!.first()
                val apiClass = Class.forName(info.apiClassInfo.name)
                val apiInstance = apiClass.getConstructor(ApiClient::class.java).newInstance(apiClient)
                val method = info.createMethod.loadClassAndGetMethod()
                println("using: ${apiClass.canonicalName}.${method.name} to create an instance of ${classOfObjectToCreate.canonicalName} (name=${metaData?.name})")
                if (info.namespaced) {
                    try {
                        method.invoke(apiInstance, namespace, o, "true", null, null)
                    } catch (e: InvocationTargetException) {
                        if (e.cause is ApiException) {
                            System.err.println((e.cause as ApiException).responseBody)
                        }
                        throw e
                    }
                } else {
                    try {
                        method.invoke(apiInstance, o, null, null, null)
                    } catch (e: InvocationTargetException) {
                        if (e.cause is ApiException) {
                            System.err.println((e.cause as ApiException).responseBody)
                        }
                        throw e
                    }
                }
            }
            else -> {
                throw IllegalStateException("unknown type: " + classOfObjectToCreate.canonicalName)
            }
        }
    }

    override fun apply(o: List<Any>, namespace: String, apiClient: ApiClient) {
        o.forEach { create(it, namespace, apiClient) }
    }
}