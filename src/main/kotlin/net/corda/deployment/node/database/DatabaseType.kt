package net.corda.deployment.node.database

import net.corda.deployment.node.GradleDependency

enum class DatabaseType(
    val dataSourceClass: String,
    val needsDriver: Boolean = true,
    val driverDependencies: List<GradleDependency>,
    val driverClass: String = dataSourceClass
) {
    MS_SQL(
        "com.microsoft.sqlserver.jdbc.SQLServerDataSource",
        needsDriver = true,
        driverDependencies = listOf(
            GradleDependency(
                "com.microsoft.sqlserver",
                "mssql-jdbc",
                "6.4.0.jre8"
            )
        ),
        driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    ),

    H2("org.h2.jdbcx.JdbcDataSource", false, emptyList())
}

data class DatabaseConfigParams(
    val jdbcURL: String,
    val username: String,
    val password: String,
    val type: DatabaseType
)

val H2_DB = DatabaseConfigParams(
    jdbcURL = "jdbc:h2:file:\"\${baseDirectory}\"/persistence;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;LOCK_TIMEOUT=10000",
    username = "sa",
    password = "password",
    type = DatabaseType.H2
)
