package no1.libs.dbmigration

import jakarta.persistence.Entity
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.ImplicitNamingStrategy
import org.hibernate.boot.model.naming.PhysicalNamingStrategy
import org.hibernate.boot.model.relational.ColumnOrderingStrategy
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.cfg.JdbcSettings
import org.hibernate.cfg.MappingSettings
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver
import org.hibernate.engine.jdbc.dialect.spi.DatabaseMetaDataDialectResolutionInfoAdapter
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.env.Environment
import org.springframework.core.type.filter.AnnotationTypeFilter
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@ConfigurationProperties("db.migration")
class SchemaMigrationProperties {
	var enabled: Boolean = true
	var entityPackage: String = ""
	var migrationDirectory: String = "src/main/resources/db/migration"
	var historyTable: String = "flyway_schema_history"
	var sequenceStarts: Map<String, Long> = emptyMap()
}

@AutoConfiguration
@ConditionalOnClass(Entity::class)
@ConditionalOnProperty(prefix = "db.migration", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SchemaMigrationProperties::class)
open class SchemaMigrationAutoConfiguration {
	@Bean
	open fun schemaMigrationCheck(
		environment: Environment,
		properties: SchemaMigrationProperties,
		dataSource: ObjectProvider<DataSource>,
		applicationContext: ApplicationContext,
		physicalNaming: ObjectProvider<PhysicalNamingStrategy>,
		implicitNaming: ObjectProvider<ImplicitNamingStrategy>,
		columnOrdering: ObjectProvider<ColumnOrderingStrategy>,
	): SmartInitializingSingleton = SchemaMigrationCheck(
	environment,
	properties,
	dataSource,
	applicationContext,
	physicalNaming.ifAvailable ?: EntitySuffixPhysicalNamingStrategy(),
	implicitNaming.ifAvailable ?: ReadableForeignKeyNamingStrategy(),
	columnOrdering.ifAvailable ?: EntityFieldColumnOrderingStrategy(),
)
}

class SchemaMigrationCheck(
	private val environment: Environment,
	private val properties: SchemaMigrationProperties,
	private val dataSource: ObjectProvider<DataSource>,
	private val applicationContext: ApplicationContext,
	private val physicalNaming: PhysicalNamingStrategy,
	private val implicitNaming: ImplicitNamingStrategy,
	private val columnOrdering: ColumnOrderingStrategy,
) : SmartInitializingSingleton {
	// A non-empty diff is written as the next Flyway script, then startup fails so the script is reviewed before it runs.
	override fun afterSingletonsInstantiated() {
		if (suppressed()) return
		openConnection().use { connection ->
			val meta = connection.metaData
			val dialect = StandardDialectResolver().resolveDialect(DatabaseMetaDataDialectResolutionInfoAdapter(meta))
				?: error("Schema migration found no Hibernate dialect for ${meta.databaseProductName} ${meta.databaseProductVersion}")
			val rules = sqlRules(connection, properties.sequenceStarts)
			val registry = StandardServiceRegistryBuilder()
				.applySetting(JdbcSettings.DIALECT, dialect)
				.applySetting(JdbcSettings.JAKARTA_HBM2DDL_DB_NAME, meta.databaseProductName)
				.applySetting(JdbcSettings.JAKARTA_HBM2DDL_DB_MAJOR_VERSION, meta.databaseMajorVersion)
				.applySetting(JdbcSettings.JAKARTA_HBM2DDL_DB_MINOR_VERSION, meta.databaseMinorVersion)
				.applySetting(MappingSettings.PREFERRED_INSTANT_JDBC_TYPE, instantJdbcType())
				.applySetting(MappingSettings.PREFER_NATIVE_ENUM_TYPES, environment.getProperty(NATIVE_ENUMS) ?: "false")
				.build()
			try {
				val sources = MetadataSources(registry)
				entityClasses().forEach(sources::addAnnotatedClass)
				val metadata = sources.metadataBuilder
					.applyImplicitNamingStrategy(implicitNaming)
					.applyPhysicalNamingStrategy(physicalNaming)
					.applyColumnOrderingStrategy(columnOrdering)
					.build()
				val required = prepareModel(metadata, rules.defaultSchema)
				val actual = readCatalog(connection, properties.historyTable, rules.defaultSchema)
				val statements = migrationStatements(required, actual, rules)
				if (statements.isEmpty()) return
				publish(statements)
				throw SchemaManagementException(statements.joinToString("\n"))
			} finally {
				StandardServiceRegistryBuilder.destroy(registry)
			}
		}
	}

	// Both Hibernate schema-generation actions set to none means this check stays quiet.
	private fun suppressed(): Boolean {
		val database = environment.getProperty(DATABASE_ACTION)
		val scripts = environment.getProperty(SCRIPTS_ACTION)
		return database != null && scripts != null &&
			database.equals("none", ignoreCase = true) &&
			scripts.equals("none", ignoreCase = true)
	}

	private fun openConnection(): Connection {
		dataSource.ifAvailable?.let { return it.connection }
		val url = environment.getProperty("spring.datasource.url")
			?: environment.getProperty("spring.flyway.url")
			?: error("Schema migration needs a JDBC DataSource, spring.datasource.url, or spring.flyway.url")
		val user = environment.getProperty("spring.datasource.username")
			?: environment.getProperty("spring.flyway.user")
		val password = environment.getProperty("spring.datasource.password")
			?: environment.getProperty("spring.flyway.password")
		return if (user == null) DriverManager.getConnection(url) else DriverManager.getConnection(url, user, password)
	}

	private fun instantJdbcType(): String {
		val configured = environment.getProperty(INSTANT_JDBC_TYPE) ?: "TIMESTAMP"
		return when (configured.lowercase()) {
			"timestamptz", "timestamp_utc", "timestamp-with-time-zone" -> "TIMESTAMP_UTC"
			"timestamp" -> "TIMESTAMP"
			else -> configured
		}
	}

	private fun entityClasses(): List<Class<*>> {
		val bases = properties.entityPackage.takeIf { it.isNotBlank() }?.let(::listOf) ?: applicationPackages()
		val scanner = ClassPathScanningCandidateComponentProvider(false)
		scanner.setResourceLoader(applicationContext)
		scanner.addIncludeFilter(AnnotationTypeFilter(Entity::class.java))
		val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
		val classes = bases.flatMap { scanner.findCandidateComponents(it) }
			.map { Class.forName(it.beanClassName, true, loader) }
			.distinctBy { it.name }
		check(classes.isNotEmpty()) { "Schema migration found no @Entity classes under ${bases.joinToString()}" }
		return classes
	}

	private fun applicationPackages(): List<String> {
		check(AutoConfigurationPackages.has(applicationContext)) {
			"Schema migration needs db.migration.entity-package"
		}
		return AutoConfigurationPackages.get(applicationContext)
	}

	private fun publish(statements: List<String>) {
		val directory = File(properties.migrationDirectory)
		if (!directory.isDirectory) return
		val version = (directory.listFiles()?.mapNotNull { file ->
			VERSION.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
		}?.maxOrNull() ?: 0) + 1
		val stamp = LocalDateTime.now().format(STAMP)
		val migration = File(directory, migrationFileName(version, stamp, gitBranch()))
		migration.writeText(statements.joinToString(separator = "\n", postfix = "\n"))
		log.warn("Schema differs from the entity model. Wrote {}", migration.path)
	}

	private fun gitBranch(): String {
		val process = try {
			ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start()
		} catch (failure: java.io.IOException) {
			throw IllegalStateException("Schema migration could not read the current git branch", failure)
		}
		val branch = process.inputStream.bufferedReader().use { it.readText() }.trim()
		check(process.waitFor() == 0 && branch.isNotEmpty()) { "Schema migration could not read the current git branch" }
		return branch
	}

	private companion object {
		const val DATABASE_ACTION = "spring.jpa.properties.jakarta.persistence.schema-generation.database.action"
		const val SCRIPTS_ACTION = "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action"
		const val INSTANT_JDBC_TYPE = "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type"
		const val NATIVE_ENUMS = "spring.jpa.properties.hibernate.type.prefer_native_enum_types"
		val VERSION = Regex("""V(\d+)__.*\.sql""")
		val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd_HHmm")
		val log = LoggerFactory.getLogger(SchemaMigrationCheck::class.java)
	}
}
