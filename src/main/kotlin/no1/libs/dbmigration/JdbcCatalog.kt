package no1.libs.dbmigration

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException

internal fun readCatalog(connection: Connection, historyTable: String, defaultSchema: String): SchemaModel {
	val meta = connection.metaData
	val scopes = readScopes(connection, meta, defaultSchema)
	val sequences = readSequences(connection, meta, scopes)
	val checks = readChecks(connection)
	val uniques = readUniqueConstraints(connection)
	val tables = mutableListOf<TableModel>()
	for (scope in scopes) {
		meta.getTables(scope.catalog, scope.schema, "%", arrayOf("TABLE")).use { rows ->
			while (rows.next()) {
				val table = rows.getString("TABLE_NAME")?.lowercase() ?: continue
				// Flyway's history table is not part of the entity model.
				if (table == historyTable.lowercase()) continue
				tables += readTable(meta, scope, table, checks, uniques)
			}
		}
	}
	return SchemaModel(scopes.map { it.label }.toSet(), sequences, tables)
}

internal fun sqlRules(connection: Connection, sequenceStarts: Map<String, Long>): SqlRules {
	val meta = connection.metaData
	val product = meta.databaseProductName.lowercase()
	val quote = meta.identifierQuoteString?.trim().orEmpty().ifBlank { "\"" }
	return SqlRules(
		identifierQuote = quote,
		// Only these products accept CASCADE on drop table, drop sequence, and drop schema.
		cascade = product.contains("postgres") || product.contains("h2"),
		defaultSchema = currentNamespace(connection, meta),
		sequenceStarts = sequenceStarts,
	)
}

private data class Scope(val catalog: String?, val schema: String?, val label: String)

private fun currentNamespace(connection: Connection, meta: DatabaseMetaData): String {
	val schema = if (meta.supportsSchemasInTableDefinitions()) optional { connection.schema } else null
	val catalog = optional { connection.catalog }
	return schema?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
		?: catalog?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
		?: "public"
}

private fun readScopes(connection: Connection, meta: DatabaseMetaData, defaultSchema: String): List<Scope> {
	val catalogs = meta.supportsCatalogsInTableDefinitions()
	val schemas = meta.supportsSchemasInTableDefinitions()
	val currentCatalog = optional { connection.catalog }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
	if (schemas) {
		val found = linkedSetOf<String>()
		meta.schemas.use { rows ->
			while (rows.next()) {
				val schema = rows.getString("TABLE_SCHEM")?.lowercase() ?: continue
				if (systemSchema(schema)) continue
				if (catalogs && currentCatalog != null) {
					val catalog = rows.text("TABLE_CATALOG")?.lowercase()
					if (catalog != null && catalog != currentCatalog) continue
				}
				found += schema
			}
		}
		if (found.isEmpty()) found += defaultSchema
		val catalog = if (catalogs) currentCatalog else null
		return found.map { Scope(catalog, it, it) }
	}
	if (catalogs) {
		val found = linkedSetOf<String>()
		meta.catalogs.use { rows ->
			while (rows.next()) {
				val catalog = rows.getString("TABLE_CAT")?.lowercase() ?: continue
				if (!systemSchema(catalog)) found += catalog
			}
		}
		if (found.isEmpty() && currentCatalog != null) found += currentCatalog
		if (found.isEmpty()) found += defaultSchema
		return found.map { Scope(it, null, it) }
	}
	return listOf(Scope(currentCatalog, defaultSchema, defaultSchema))
}

private fun readSequences(connection: Connection, meta: DatabaseMetaData, scopes: List<Scope>): Set<SequenceName> {
	val labels = scopes.map { it.label }.toSet()
	val found = linkedSetOf<SequenceName>()
	try {
		connection.createStatement().use { statement ->
			statement.executeQuery("select sequence_schema, sequence_name from information_schema.sequences").use { rows ->
				while (rows.next()) {
					val schema = rows.getString(1)?.lowercase() ?: continue
					val name = rows.getString(2)?.lowercase() ?: continue
					if (schema in labels) found += SequenceName(schema, name)
				}
			}
		}
		return found
	} catch (failure: SQLException) {
		log.debug("information_schema.sequences is not available: {}", failure.message)
	}
	for (scope in scopes) {
		try {
			meta.getTables(scope.catalog, scope.schema, "%", arrayOf("SEQUENCE")).use { rows ->
				while (rows.next()) {
					val name = rows.getString("TABLE_NAME")?.lowercase() ?: continue
					found += SequenceName(scope.label, name)
				}
			}
		} catch (ignored: SQLException) {
			log.debug("Sequence lookup failed for {}: {}", scope.label, ignored.message)
		}
	}
	return found
}

private fun readUniqueConstraints(connection: Connection): Map<Pair<String, String>, List<KeyModel>>? {
	val grouped = linkedMapOf<Pair<String, String>, LinkedHashMap<String, MutableList<Pair<Int, String>>>>()
	try {
		connection.createStatement().use { statement ->
			statement.executeQuery(
				"""
				select tc.table_schema, tc.table_name, tc.constraint_name, kcu.column_name, kcu.ordinal_position
				from information_schema.table_constraints tc
				join information_schema.key_column_usage kcu
					on kcu.constraint_schema = tc.constraint_schema
					and kcu.constraint_name = tc.constraint_name
					and kcu.table_schema = tc.table_schema
					and kcu.table_name = tc.table_name
				where tc.constraint_type = 'UNIQUE'
				""".trimIndent(),
			).use { rows ->
				while (rows.next()) {
					val schema = rows.getString(1)?.lowercase() ?: continue
					val table = rows.getString(2)?.lowercase() ?: continue
					val name = rows.getString(3)?.lowercase() ?: continue
					val column = rows.getString(4)?.lowercase() ?: continue
					val constraints = grouped.getOrPut(schema to table) { linkedMapOf() }
					constraints.getOrPut(name) { mutableListOf() } += rows.getInt(5) to column
				}
			}
		}
	} catch (failure: SQLException) {
		log.debug("information_schema unique constraints are not available: {}", failure.message)
		return null
	}
	return grouped.mapValues { (_, constraints) ->
		constraints.map { (name, columns) -> KeyModel(name, columns.sortedBy { it.first }.map { it.second }) }
	}
}

// information_schema stores Postgres's rewritten check text. pg_catalog keeps the original expression.
private fun readChecks(connection: Connection): Map<Triple<String, String, String>, CheckModel> {
	val product = connection.metaData.databaseProductName.lowercase()
	if (product.contains("postgres")) {
		readPostgresChecks(connection)?.let { return it }
	}
	return readInformationSchemaChecks(connection)
}

private fun readPostgresChecks(connection: Connection): Map<Triple<String, String, String>, CheckModel>? {
	val checks = linkedMapOf<Triple<String, String, String>, CheckModel>()
	try {
		connection.createStatement().use { statement ->
			statement.executeQuery(
				"""
				select n.nspname,
					c.relname,
					con.conname,
					pg_get_constraintdef(con.oid, true),
					(
						select string_agg(att.attname, ',' order by u.ord)
						from unnest(con.conkey) with ordinality as u(attnum, ord)
						join pg_catalog.pg_attribute att
							on att.attrelid = con.conrelid and att.attnum = u.attnum
					)
				from pg_catalog.pg_constraint con
				join pg_catalog.pg_class c on c.oid = con.conrelid
				join pg_catalog.pg_namespace n on n.oid = c.relnamespace
				where con.contype = 'c'
					and n.nspname <> 'information_schema'
					and n.nspname not like 'pg_%'
				""".trimIndent(),
			).use { rows ->
				while (rows.next()) {
					val schema = rows.getString(1)?.lowercase() ?: continue
					val table = rows.getString(2)?.lowercase() ?: continue
					val name = rows.getString(3)?.lowercase() ?: continue
					val expression = rows.getString(4) ?: continue
					val columns = rows.getString(5)?.split(',')?.map { it.lowercase() }.orEmpty()
					checks[Triple(schema, table, name)] = CheckModel(name, columns, expression)
				}
			}
		}
	} catch (failure: SQLException) {
		log.debug("pg_catalog check constraints are not available: {}", failure.message)
		return null
	}
	return checks
}

private fun readInformationSchemaChecks(connection: Connection): Map<Triple<String, String, String>, CheckModel> {
	val columns = mutableMapOf<Pair<String, String>, MutableList<String>>()
	try {
		connection.createStatement().use { statement ->
			statement.executeQuery(
				"""
				select constraint_schema, constraint_name, column_name
				from information_schema.constraint_column_usage
				""".trimIndent(),
			).use { rows ->
				while (rows.next()) {
					val schema = rows.getString(1)?.lowercase() ?: continue
					val name = rows.getString(2)?.lowercase() ?: continue
					val column = rows.getString(3)?.lowercase() ?: continue
					columns.getOrPut(schema to name) { mutableListOf() } += column
				}
			}
		}
	} catch (failure: SQLException) {
		log.debug("information_schema.constraint_column_usage is not available: {}", failure.message)
	}
	val checks = linkedMapOf<Triple<String, String, String>, CheckModel>()
	try {
		connection.createStatement().use { statement ->
			statement.executeQuery(
				"""
				select tc.table_schema, tc.table_name, tc.constraint_name, cc.check_clause
				from information_schema.table_constraints tc
				join information_schema.check_constraints cc
					on cc.constraint_schema = tc.constraint_schema
					and cc.constraint_name = tc.constraint_name
				where tc.constraint_type = 'CHECK'
				""".trimIndent(),
			).use { rows ->
				while (rows.next()) {
					val schema = rows.getString(1)?.lowercase() ?: continue
					val table = rows.getString(2)?.lowercase() ?: continue
					val name = rows.getString(3)?.lowercase() ?: continue
					val expression = rows.getString(4) ?: continue
					checks[Triple(schema, table, name)] = CheckModel(name, columns[schema to name].orEmpty(), expression)
				}
			}
		}
	} catch (failure: SQLException) {
		log.debug("information_schema check constraints are not available: {}", failure.message)
	}
	return checks
}

private fun readTable(
	meta: DatabaseMetaData,
	scope: Scope,
	table: String,
	checks: Map<Triple<String, String, String>, CheckModel>,
	uniques: Map<Pair<String, String>, List<KeyModel>>?,
): TableModel {
	val schema = scope.label
	val columns = mutableListOf<ColumnModel>()
	meta.getColumns(scope.catalog, scope.schema, table, "%").use { rows ->
		val ordered = mutableListOf<Pair<Int, ColumnModel>>()
		while (rows.next()) {
			val position = rows.getInt("ORDINAL_POSITION")
			val typeName = rows.getString("TYPE_NAME") ?: "varchar"
			val size = rows.getInt("COLUMN_SIZE")
			ordered += position to ColumnModel(
				name = rows.getString("COLUMN_NAME")?.lowercase() ?: continue,
				sqlType = columnType(typeName, size),
				nullable = rows.getString("IS_NULLABLE").equals("YES", ignoreCase = true),
				defaultSql = rows.getString("COLUMN_DEF"),
			)
		}
		columns += ordered.sortedBy { it.first }.map { it.second }
	}
	val primaryKey = mutableListOf<Pair<Int, String>>()
	var primaryKeyName: String? = null
	meta.getPrimaryKeys(scope.catalog, scope.schema, table).use { rows ->
		while (rows.next()) {
			primaryKeyName = rows.getString("PK_NAME")?.lowercase()
			val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
			primaryKey += rows.getShort("KEY_SEQ").toInt() to column
		}
	}
	val foreignKeys = readForeignKeys(meta, scope, table)
	val uniqueKeys = (uniques?.get(schema to table) ?: emptyList()).toMutableList()
	val indexes = mutableListOf<IndexModel>()
	// When information_schema unique constraints are unavailable, unique indexes stand in for them.
	readIndexes(meta, scope, table, primaryKeyName, uniqueKeys, indexes, classifyUniqueIndexes = uniques == null)
	return TableModel(
		schema = schema,
		name = table,
		columns = columns,
		primaryKey = primaryKey.sortedBy { it.first }.map { it.second },
		primaryKeyName = primaryKeyName,
		uniques = uniqueKeys,
		foreignKeys = foreignKeys,
		checks = checks.filter { it.key.first == schema && it.key.second == table }.values.toList(),
		indexes = indexes,
	)
}

private fun readForeignKeys(meta: DatabaseMetaData, scope: Scope, table: String): List<ForeignKeyModel> {
	val grouped = linkedMapOf<String, ForeignKeyBuilder>()
	meta.getImportedKeys(scope.catalog, scope.schema, table).use { rows ->
		while (rows.next()) {
			val name = rows.getString("FK_NAME")?.lowercase() ?: continue
			val referencedTable = rows.getString("PKTABLE_NAME")?.lowercase() ?: continue
			val builder = grouped.getOrPut(name) {
				ForeignKeyBuilder(
					name,
					rows.getString("PKTABLE_SCHEM")?.lowercase()?.ifBlank { null }
						?: rows.text("PKTABLE_CAT")?.lowercase()?.ifBlank { null }
						?: scope.label,
					referencedTable,
				)
			}
			val column = rows.getString("FKCOLUMN_NAME")?.lowercase() ?: continue
			val referenced = rows.getString("PKCOLUMN_NAME")?.lowercase() ?: continue
			builder.columns += rows.getShort("KEY_SEQ").toInt() to column
			builder.referenced += rows.getShort("KEY_SEQ").toInt() to referenced
		}
	}
	return grouped.values.map { it.toModel() }
}

private fun readIndexes(
	meta: DatabaseMetaData,
	scope: Scope,
	table: String,
	primaryKeyName: String?,
	uniques: MutableList<KeyModel>,
	indexes: MutableList<IndexModel>,
	classifyUniqueIndexes: Boolean,
) {
	val constraintNames = uniques.map { it.name }.toSet()
	val grouped = linkedMapOf<String, IndexBuilder>()
	meta.getIndexInfo(scope.catalog, scope.schema, table, false, false).use { rows ->
		while (rows.next()) {
			if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue
			val name = rows.getString("INDEX_NAME")?.lowercase() ?: continue
			if (name == primaryKeyName || name in constraintNames) continue
			val builder = grouped.getOrPut(name) { IndexBuilder(name, !rows.getBoolean("NON_UNIQUE")) }
			val column = rows.getString("COLUMN_NAME")?.lowercase()
			val position = rows.getShort("ORDINAL_POSITION").toInt()
			if (column == null) builder.expression = true else builder.columns += position to column
		}
	}
	for (index in grouped.values) {
		val columns = index.columns.sortedBy { it.first }.map { it.second }
		if (classifyUniqueIndexes && index.unique && !index.expression) {
			uniques += KeyModel(index.name, columns)
		} else {
			indexes += IndexModel(index.name, index.unique, columns, index.expression)
		}
	}
}

private fun columnType(typeName: String, size: Int): String {
	val type = typeName.lowercase().replace("character varying", "varchar")
	val sized = type == "varchar" || type == "character" || type == "char" || type == "nvarchar" || type == "nchar"
	if (sized && size > 0 && size < 1_000_000) return "$type($size)"
	return type
}

private class ForeignKeyBuilder(
	val name: String,
	val referencedSchema: String,
	val referencedTable: String,
	val columns: MutableList<Pair<Int, String>> = mutableListOf(),
	val referenced: MutableList<Pair<Int, String>> = mutableListOf(),
) {
	fun toModel() = ForeignKeyModel(
		name,
		columns.sortedBy { it.first }.map { it.second },
		referencedSchema,
		referencedTable,
		referenced.sortedBy { it.first }.map { it.second },
	)
}

private class IndexBuilder(
	val name: String,
	val unique: Boolean,
	var expression: Boolean = false,
	val columns: MutableList<Pair<Int, String>> = mutableListOf(),
)

private fun ResultSet.text(column: String): String? = try {
	getString(column)
} catch (_: SQLException) {
	null
}

private inline fun <T> optional(block: () -> T): T? = try {
	block()
} catch (_: SQLException) {
	null
}

private val log = LoggerFactory.getLogger("no1.libs.dbmigration.JdbcCatalog")
