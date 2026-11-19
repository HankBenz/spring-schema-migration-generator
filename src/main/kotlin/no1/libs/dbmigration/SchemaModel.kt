package no1.libs.dbmigration

data class SqlRules(
	val identifierQuote: String = "\"",
	val cascade: Boolean = true,
	val defaultSchema: String = "public",
	val sequenceStarts: Map<String, Long> = emptyMap(),
)

data class SchemaModel(
	val schemas: Set<String>,
	val sequences: Set<SequenceName>,
	val tables: List<TableModel>,
)

data class SequenceName(val schema: String, val name: String)

data class TableModel(
	val schema: String,
	val name: String,
	val columns: List<ColumnModel> = emptyList(),
	val primaryKey: List<String> = emptyList(),
	val primaryKeyName: String? = null,
	val uniques: List<KeyModel> = emptyList(),
	val foreignKeys: List<ForeignKeyModel> = emptyList(),
	val checks: List<CheckModel> = emptyList(),
	val indexes: List<IndexModel> = emptyList(),
)

data class ColumnModel(
	val name: String,
	val sqlType: String,
	val nullable: Boolean,
	val defaultSql: String? = null,
)

data class KeyModel(val name: String, val columns: List<String>)

data class ForeignKeyModel(
	val name: String,
	val columns: List<String>,
	val referencedSchema: String,
	val referencedTable: String,
	val referencedColumns: List<String>,
)

data class CheckModel(val name: String?, val columns: List<String>, val expression: String)

data class IndexModel(
	val name: String,
	val unique: Boolean,
	val columns: List<String>,
	val expression: Boolean = false,
)

fun migrationStatements(required: SchemaModel, actual: SchemaModel, rules: SqlRules = SqlRules()): List<String> {
	val statements = mutableListOf<String>()
	val requiredTables = required.tables.associateBy { it.schema to it.name }
	val actualTables = actual.tables.associateBy { it.schema to it.name }
	val protectedSchemas = setOf(rules.defaultSchema, "public", "dbo")
	val droppedSchemas = actual.schemas.filter { it !in required.schemas && it !in protectedSchemas && !systemSchema(it) }.toSet()

	for (table in actual.tables.filter { (it.schema to it.name) !in requiredTables && it.schema !in droppedSchemas }) {
		statements += "drop table ${qualify(table.schema, table.name, rules)}${cascade(rules)};"
	}
	for ((key, table) in actualTables) {
		val model = requiredTables[key] ?: continue
		statements += dropForeignKeys(table, model, rules)
		statements += dropIndexes(table, model, rules)
		statements += dropChecks(table, model, rules)
		statements += dropUniques(table, model, rules)
		statements += dropPrimaryKey(table, model, rules)
		statements += dropColumns(table, model, rules)
	}
	for (sequence in actual.sequences.filter { it !in required.sequences && it.schema !in droppedSchemas }) {
		statements += "drop sequence ${qualify(sequence.schema, sequence.name, rules)}${cascade(rules)};"
	}
	for (schema in droppedSchemas.sorted()) statements += "drop schema ${quoteIdentifier(schema, rules)}${cascade(rules)};"

	for (schema in required.schemas.filter { it != rules.defaultSchema && it !in actual.schemas }.sorted()) {
		statements += "create schema ${quoteIdentifier(schema, rules)};"
	}
	for (sequence in required.sequences.filter { it !in actual.sequences }.sortedWith(compareBy({ it.schema }, { it.name }))) {
		val start = rules.sequenceStarts[sequence.name]?.let { " start with $it increment by 1" }.orEmpty()
		statements += "create sequence ${qualify(sequence.schema, sequence.name, rules)}$start;"
	}
	for (table in tablesInDependencyOrder(required.tables)) {
		val existing = actualTables[table.schema to table.name]
		if (existing == null) {
			statements += createTable(table, rules)
		} else {
			statements += alterColumns(table, existing, rules)
		}
		statements += createPrimaryKey(table, existing, rules)
		statements += createUniques(table, existing, rules)
		statements += createForeignKeys(table, existing, rules)
		statements += createChecks(table, existing, rules)
		statements += createIndexes(table, existing, rules)
	}
	return statements
}

private fun cascade(rules: SqlRules) = if (rules.cascade) " cascade" else ""

private fun dropForeignKeys(actual: TableModel, required: TableModel, rules: SqlRules): List<String> =
	actual.foreignKeys.filter { existing -> required.foreignKeys.none { sameForeignKey(it, existing) } }
		.map { "alter table ${qualify(actual.schema, actual.name, rules)} drop constraint ${quoteIdentifier(it.name, rules)};" }

private fun dropIndexes(actual: TableModel, required: TableModel, rules: SqlRules): List<String> {
	val ownedByConstraint = (actual.uniques.map { it.name } + listOfNotNull(actual.primaryKeyName)).toSet()
	return actual.indexes.filter { existing ->
		existing.name !in ownedByConstraint && required.indexes.none { sameIndex(it, existing) }
	}.map { "drop index ${qualify(actual.schema, it.name, rules)};" }
}

private fun dropChecks(actual: TableModel, required: TableModel, rules: SqlRules): List<String> =
	actual.checks.filter { existing -> existing.name != null && required.checks.none { sameCheck(it, existing) } }
		.map { "alter table ${qualify(actual.schema, actual.name, rules)} drop constraint ${quoteIdentifier(it.name!!, rules)};" }

private fun dropUniques(actual: TableModel, required: TableModel, rules: SqlRules): List<String> =
	actual.uniques.filter { existing -> required.uniques.none { sameUnique(it, existing) } }
		.map { "alter table ${qualify(actual.schema, actual.name, rules)} drop constraint ${quoteIdentifier(it.name, rules)};" }

private fun dropPrimaryKey(actual: TableModel, required: TableModel, rules: SqlRules): List<String> {
	if (actual.primaryKey.isEmpty() || actual.primaryKey == required.primaryKey || actual.primaryKeyName == null) return emptyList()
	return listOf("alter table ${qualify(actual.schema, actual.name, rules)} drop constraint ${quoteIdentifier(actual.primaryKeyName, rules)};")
}

private fun dropColumns(actual: TableModel, required: TableModel, rules: SqlRules): List<String> {
	val kept = required.columns.map { it.name }.toSet()
	return actual.columns.filter { it.name !in kept }
		.map { "alter table ${qualify(actual.schema, actual.name, rules)} drop column ${quoteIdentifier(it.name, rules)};" }
}

private fun createTable(table: TableModel, rules: SqlRules): String {
	val columns = table.columns.joinToString(",\n\t") { column ->
		"${quoteIdentifier(column.name, rules)} ${columnDefinition(column)}${columnChecks(table, column.name, rules)}"
	}
	val primaryKey = if (table.primaryKey.isEmpty()) "" else ",\n\tprimary key (${table.primaryKey.joinToString(", ") { quoteIdentifier(it, rules) }})"
	return "create table ${qualify(table.schema, table.name, rules)} (\n\t$columns$primaryKey\n);"
}

private fun alterColumns(required: TableModel, actual: TableModel, rules: SqlRules): List<String> {
	val existing = actual.columns.associateBy { it.name }
	val statements = mutableListOf<String>()
	for (column in required.columns) {
		val current = existing[column.name]
		val tableName = qualify(required.schema, required.name, rules)
		val columnName = quoteIdentifier(column.name, rules)
		if (current == null) {
			statements += "alter table $tableName add column $columnName ${columnDefinition(column)}${columnChecks(required, column.name, rules)};"
			continue
		}
		if (canonicalType(current.sqlType) != canonicalType(column.sqlType)) {
			statements += "alter table $tableName alter column $columnName set data type ${column.sqlType};"
		}
		if (canonicalDefault(current.defaultSql) != canonicalDefault(column.defaultSql)) {
			statements += if (column.defaultSql == null) {
				"alter table $tableName alter column $columnName drop default;"
			} else {
				"alter table $tableName alter column $columnName set default ${column.defaultSql};"
			}
		}
		if (current.nullable != column.nullable) {
			val nullability = if (column.nullable) "drop not null" else "set not null"
			statements += "alter table $tableName alter column $columnName $nullability;"
		}
	}
	return statements
}

private fun createPrimaryKey(required: TableModel, actual: TableModel?, rules: SqlRules): List<String> {
	if (required.primaryKey.isEmpty() || actual == null || actual.primaryKey == required.primaryKey) return emptyList()
	val columns = required.primaryKey.joinToString(", ") { quoteIdentifier(it, rules) }
	return listOf("alter table ${qualify(required.schema, required.name, rules)} add primary key ($columns);")
}

private fun createUniques(required: TableModel, actual: TableModel?, rules: SqlRules): List<String> {
	val existing = actual?.uniques.orEmpty()
	return required.uniques.filter { desired -> existing.none { sameUnique(desired, it) } }
		.map { unique ->
			val columns = unique.columns.joinToString(", ") { quoteIdentifier(it, rules) }
			"alter table ${qualify(required.schema, required.name, rules)} add constraint ${quoteIdentifier(unique.name, rules)} unique ($columns);"
		}
}

private fun createForeignKeys(required: TableModel, actual: TableModel?, rules: SqlRules): List<String> {
	val existing = actual?.foreignKeys.orEmpty()
	return required.foreignKeys.filter { desired -> existing.none { sameForeignKey(desired, it) } }
		.map { foreignKey ->
			val columns = foreignKey.columns.joinToString(", ") { quoteIdentifier(it, rules) }
			val referencedColumns = foreignKey.referencedColumns.joinToString(", ") { quoteIdentifier(it, rules) }
			"alter table ${qualify(required.schema, required.name, rules)} add constraint ${quoteIdentifier(foreignKey.name, rules)} foreign key ($columns) references ${qualify(foreignKey.referencedSchema, foreignKey.referencedTable, rules)} ($referencedColumns);"
		}
}

private fun columnChecks(table: TableModel, columnName: String, rules: SqlRules): String =
	table.checks.filter { it.columns == listOf(columnName) }.joinToString("") { check ->
		val name = check.name?.let { " constraint ${quoteIdentifier(it, rules)}" }.orEmpty()
		"$name check (${check.expression})"
	}

private fun createChecks(required: TableModel, actual: TableModel?, rules: SqlRules): List<String> {
	val existing = actual?.checks.orEmpty()
	val existingColumns = actual?.columns?.map { it.name }?.toSet().orEmpty()
	return required.checks.filter { desired ->
		val createdWithColumn = desired.columns.size == 1 && (actual == null || desired.columns.single() !in existingColumns)
		!createdWithColumn && existing.none { sameCheck(desired, it) }
	}.map { check ->
		val name = check.name?.let { "constraint ${quoteIdentifier(it, rules)} " }.orEmpty()
		"alter table ${qualify(required.schema, required.name, rules)} add ${name}check (${check.expression});"
	}
}

private fun tablesInDependencyOrder(tables: List<TableModel>): List<TableModel> {
	val byKey = tables.associateBy { it.schema to it.name }
	val ordered = mutableListOf<TableModel>()
	val visiting = mutableSetOf<Pair<String, String>>()
	val visited = mutableSetOf<Pair<String, String>>()
	fun visit(table: TableModel) {
		val key = table.schema to table.name
		if (key in visited) return
		check(visiting.add(key)) { "Schema migration found a foreign key cycle at [${table.name}]" }
		for (foreignKey in table.foreignKeys) byKey[foreignKey.referencedSchema to foreignKey.referencedTable]?.let(::visit)
		visiting.remove(key)
		visited += key
		ordered += table
	}
	tables.sortedWith(compareBy({ it.schema }, { it.name })).forEach(::visit)
	return ordered
}

private fun createIndexes(required: TableModel, actual: TableModel?, rules: SqlRules): List<String> {
	val existing = actual?.indexes.orEmpty()
	return required.indexes.filter { desired -> existing.none { sameIndex(desired, it) } }
		.map { index ->
			val unique = if (index.unique) "unique " else ""
			val columns = index.columns.joinToString(", ") { quoteIdentifier(it, rules) }
			"create ${unique}index ${quoteIdentifier(index.name, rules)} on ${qualify(required.schema, required.name, rules)} ($columns);"
		}
}

private fun columnDefinition(column: ColumnModel): String {
	val defaultSql = column.defaultSql?.let { " default $it" } ?: ""
	val nullSql = if (column.nullable) "" else " not null"
	return "${column.sqlType}$defaultSql$nullSql"
}

private fun sameUnique(required: KeyModel, actual: KeyModel): Boolean =
	required.name == actual.name && required.columns == actual.columns

private fun sameForeignKey(required: ForeignKeyModel, actual: ForeignKeyModel): Boolean =
	required.name == actual.name &&
		required.columns == actual.columns &&
		required.referencedSchema == actual.referencedSchema &&
		required.referencedTable == actual.referencedTable &&
		required.referencedColumns == actual.referencedColumns

private fun sameIndex(required: IndexModel, actual: IndexModel): Boolean =
	!actual.expression &&
		required.name == actual.name &&
		required.unique == actual.unique &&
		required.columns == actual.columns

private fun sameCheck(required: CheckModel, actual: CheckModel): Boolean {
	if (required.name != null && required.name != actual.name) return false
	if (required.columns.isNotEmpty() && actual.columns.isNotEmpty() && required.columns != actual.columns) return false
	val requiredLiterals = literals(required.expression)
	val actualLiterals = literals(actual.expression)
	if (requiredLiterals.isNotEmpty() && actualLiterals.isNotEmpty()) return requiredLiterals == actualLiterals
	return canonicalCheck(required.expression) == canonicalCheck(actual.expression)
}

private fun literals(expression: String): List<String> =
	Regex("'([^']*)'").findAll(expression).map { it.groupValues[1] }.sorted().toList()

fun canonicalType(sqlType: String): String {
	var type = sqlType.lowercase().trim()
	type = type.replace("character varying", "varchar")
	type = type.replace("timestamp without time zone", "timestamp")
	type = type.replace("timestamp with time zone", "timestamptz")
	type = type.replace("double precision", "float8")
	type = type.replace(Regex("""float\((\d+)\)""")) { match ->
		if (match.groupValues[1].toInt() > 24) "float8" else "float4"
	}
	type = type.replace(Regex("""timestamp\(\d+\)"""), "timestamp")
	type = type.replace(Regex("""timestamptz\(\d+\)"""), "timestamptz")
	type = type.replace(Regex("""\breal\b"""), "float4")
	type = type.replace(Regex("""\bbigserial\b"""), "int8")
	type = type.replace(Regex("""\bbigint\b"""), "int8")
	type = type.replace(Regex("""\bsmallserial\b"""), "int2")
	type = type.replace(Regex("""\bsmallint\b"""), "int2")
	type = type.replace(Regex("""\bserial\b"""), "int4")
	type = type.replace(Regex("""\binteger\b"""), "int4")
	type = type.replace(Regex("""\bboolean\b"""), "bool")
	return type.replace(Regex("""\s+"""), " ").trim()
}

fun canonicalDefault(expression: String?): String? {
	if (expression.isNullOrBlank()) return null
	return expression.lowercase()
		.replace("::regclass", "")
		.replace(Regex("""::[a-z_][a-z0-9_ ]*"""), "")
		.replace(Regex("""\s+"""), " ")
		.trim()
}

fun canonicalCheck(expression: String): String {
	var text = expression.lowercase().replace(Regex("""::[a-z_][a-z0-9_ ]*"""), "")
	text = text.removePrefix("check").trim()
	text = text.replace(Regex("""\s+"""), " ")
	while (text.startsWith("(") && text.endsWith(")") && parenBalanced(text.substring(1, text.lastIndex))) {
		text = text.substring(1, text.lastIndex).trim()
	}
	return text
}

private fun parenBalanced(text: String): Boolean {
	var depth = 0
	for (char in text) {
		if (char == '(') depth++
		if (char == ')') depth--
		if (depth < 0) return false
	}
	return depth == 0
}

internal fun systemSchema(name: String): Boolean {
	val schema = name.lowercase()
	return schema == "information_schema" ||
		schema == "pg_catalog" ||
		schema == "pg_toast" ||
		schema == "performance_schema" ||
		schema == "mysql" ||
		schema == "sys" ||
		schema.startsWith("pg_")
}

internal fun quoteIdentifier(identifier: String, rules: SqlRules): String {
	require(identifier.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Refusing to emit an identifier: $identifier" }
	val quote = rules.identifierQuote
	if (quote.isBlank()) return identifier
	return quote + identifier + quote
}

private fun qualify(schema: String, name: String, rules: SqlRules): String =
	"${quoteIdentifier(schema, rules)}.${quoteIdentifier(name, rules)}"

fun migrationFileName(version: Int, stamp: String, branch: String): String {
	val suffix = branch.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')
	check(suffix.isNotEmpty()) { "Schema migration cannot use git branch [$branch] as a migration suffix" }
	return "V${version.toString().padStart(5, '0')}__${stamp}_$suffix.sql"
}
