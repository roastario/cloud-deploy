package net.corda.deployment.node.database

enum class DatabaseType(
    val dataSourceClass: String,
    val driverGradleGroup: String,
    val driverGradleArtifact: String,
    val driverGradleVersion: String,
    val needsDriver: Boolean = true,
    val driverClass: String = dataSourceClass
) {
    PG_9_6(
        dataSourceClass = "org.postgresql.ds.PGSimpleDataSource",
        driverGradleGroup = "org.postgresql",
        driverGradleArtifact = "postgresql",
        driverGradleVersion = "42.2.8",
        driverClass = "org.postgresql.Driver"
    ),
    PG_10_10(
        dataSourceClass = "org.postgresql.ds.PGSimpleDataSource",
        driverGradleGroup = "org.postgresql",
        driverGradleArtifact = "postgresql",
        driverGradleVersion = "42.2.8",
        driverClass = "org.postgresql.Driver"
    ),
    PG_11_5(
        dataSourceClass = "org.postgresql.ds.PGSimpleDataSource",
        driverGradleGroup = "org.postgresql",
        driverGradleArtifact = "postgresql",
        driverGradleVersion = "42.2.8",
        driverClass = "org.postgresql.Driver"
    ),
    MS_SQL(
        "com.microsoft.sqlserver.jdbc.SQLServerDataSource",
        "com.microsoft.sqlserver",
        "mssql-jdbc",
        "6.4.0.jre8",
        needsDriver = true,
        driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    ),
    ORACLE_12_R2(
        dataSourceClass = "oracle.jdbc.pool.OracleDataSource",
        driverGradleGroup = "com.oracle.ojdbc",
        driverGradleArtifact = "ojdbc8",
        driverGradleVersion = "19.3.0.0",
        driverClass = "oracle.jdbc.driver.OracleDriver"
    ),
    H2("org.h2.jdbcx.JdbcDataSource", "", "", "", false)
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
