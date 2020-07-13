package net.corda.deployment.node.config

import net.corda.deployments.node.config.SubstitutableSource
import org.apache.commons.io.IOUtils
import org.apache.commons.text.StringSubstitutor

class ConfigGenerators {

    companion object {
        fun generateConfigFromParams(params: SubstitutableSource): String {
            val targetConfig = params.javaClass.getAnnotation(SubstitutableSource.SubstitutionTarget::class.java)?.targetConfig
                ?: throw IllegalStateException("${params.javaClass} is missing the required ${SubstitutableSource.SubstitutionTarget::class} annotation")
            val configString = Thread.currentThread().contextClassLoader.getResourceAsStream(targetConfig).use {
                IOUtils.toString(it, Charsets.UTF_8)
            }
            return StringSubstitutor(params.toSubstitutionMap(), "#{", "}").replace(configString)
        }
    }

}