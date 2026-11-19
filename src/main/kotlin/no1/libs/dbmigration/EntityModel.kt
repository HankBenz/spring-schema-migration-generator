package no1.libs.dbmigration

import org.hibernate.boot.Metadata
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.ImplicitForeignKeyNameSource
import org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl
import org.hibernate.boot.model.relational.ColumnOrderingStrategy
import org.hibernate.boot.spi.MetadataImplementor
import org.hibernate.dialect.temptable.TemporaryTableColumn
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.hibernate.mapping.Column
import org.hibernate.mapping.Constraint
import org.hibernate.mapping.PersistentClass
import org.hibernate.mapping.Table
import org.hibernate.mapping.UserDefinedObjectType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

fun prepareModel(metadata: Metadata, defaultSchema: String = "public"): SchemaModel {
	applyKotlinNullability(metadata)
	(metadata as MetadataImplementor).orderColumns(true)
	return readEntityModel(metadata, defaultSchema)
}

private fun readEntityModel(metadata: Metadata, defaultSchema: String): SchemaModel {
	val tables = metadata.database.namespaces.flatMap { namespace ->
		namespace.tables.filter { it.isPhysicalTable }.map { readTable(metadata, it, defaultSchema) }
	}
	val sequences = tables.flatMap { table ->
		table.columns.mapNotNull { column -> sequenceReference(column.defaultSql, table.schema) }
	}.toSet()
	val schemas = (tables.map { it.schema } + sequences.map { it.schema }).toSet()
	return SchemaModel(schemas, sequences, tables)
}

private fun readTable(metadata: Metadata, table: Table, defaultSchema: String): TableModel {
	val schema = physicalSchema(table.schema, defaultSchema)
	val name = physicalName(table.name)
	val columns = table.columns.map { column ->
		ColumnModel(
			name = physicalName(column.canonicalName),
			sqlType = column.getSqlType(metadata),
			nullable = column.isNullable,
			defaultSql = column.defaultValue,
		)
	}
	val checks = columns.flatMap { column ->
		val mapped = table.columns.first { physicalName(it.canonicalName) == column.name }
		mapped.checkConstraints.map { CheckModel(it.name, listOf(column.name), it.constraint) }
	} + table.checks.map { CheckModel(it.name, emptyList(), it.constraint) }
	val primaryKey = table.primaryKey?.columns?.map { physicalName(it.canonicalName) } ?: emptyList()
	val uniques = table.uniqueKeys.values.mapNotNull { uniqueKey ->
		val keyName = uniqueKey.name?.let(::physicalName) ?: return@mapNotNull null
		KeyModel(keyName, uniqueKey.columns.map { physicalName(it.canonicalName) })
	}
	val foreignKeys = table.foreignKeys.values.mapNotNull { foreignKey ->
		val keyName = foreignKey.name?.let(::physicalName) ?: return@mapNotNull null
		val referenced = foreignKey.referencedTable
		val referencedColumns = foreignKey.referencedColumns.ifEmpty { referenced.primaryKey?.columns ?: emptyList() }
		ForeignKeyModel(
			keyName,
			foreignKey.columns.map { physicalName(it.canonicalName) },
			physicalSchema(referenced.schema, defaultSchema),
			physicalName(referenced.name),
			referencedColumns.map { physicalName(it.canonicalName) },
		)
	}
	val indexes = table.indexes.values.mapNotNull { index ->
		val indexName = index.name?.let(::physicalName) ?: return@mapNotNull null
		IndexModel(indexName, index.isUnique, index.selectables.map { physicalName(it.text) })
	}
	return TableModel(schema, name, columns, primaryKey, null, uniques, foreignKeys, checks, indexes)
}

private fun sequenceReference(defaultSql: String?, tableSchema: String): SequenceName? {
	val match = Regex("""nextval\('(?:([A-Za-z_][A-Za-z0-9_]*)\.)?([A-Za-z_][A-Za-z0-9_]*)'\)""").find(defaultSql ?: return null)
		?: return null
	return SequenceName(match.groupValues[1].ifEmpty { tableSchema }, match.groupValues[2])
}

private fun applyKotlinNullability(metadata: Metadata) {
	for (binding in metadata.entityBindings) {
		val entity = binding.mappedClass ?: continue
		val identifier = binding.identifierProperty
			?: error("Schema migration found no id property on [${binding.entityName}]")
		for (column in identifier.columns) column.isNullable = databaseNullable(entity, identifier.name)
		for (property in binding.properties) {
			val nullable = databaseNullable(entity, property.name)
			for (column in property.columns) column.isNullable = nullable
		}
	}
}

private fun physicalSchema(schema: String?, defaultSchema: String): String =
	schema?.trim('"')?.takeIf { it.isNotEmpty() } ?: defaultSchema

private fun physicalName(name: String): String = name.trim('"').lowercase()

fun databaseNullable(entity: Class<*>, propertyName: String): Boolean {
	if (propertyName == "id") return false
	val marked = entity.kotlin.memberProperties
		.filter { it.name == propertyName }
		.map { it.returnType.isMarkedNullable }
		.distinct()
	check(marked.size == 1) {
		"Schema migration found ${marked.size} nullability declarations for [${entity.simpleName}.$propertyName]"
	}
	return marked.single()
}

class EntityFieldColumnOrderingStrategy : ColumnOrderingStrategy {
	override fun orderTableColumns(table: Table, metadata: Metadata): List<Column>? {
		val binding = metadata.entityBindings.firstOrNull { it.table.exportIdentifier == table.exportIdentifier }
			?: return null
		val entity = binding.mappedClass ?: return null
		val names = entity.kotlin.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
		if (names.isEmpty()) return null
		val remaining = ArrayList(table.columns)
		val ordered = ArrayList<Column>(remaining.size)
		for (name in names) {
			val property = persistentProperty(binding, name) ?: continue
			for (column in property.columns) {
				if (column.value.table.exportIdentifier != table.exportIdentifier) continue
				val index = remaining.indexOf(column)
				if (index >= 0) ordered += remaining.removeAt(index)
			}
		}
		ordered += remaining
		return ordered
	}

	override fun orderConstraintColumns(constraint: Constraint, metadata: Metadata): List<Column>? {
		val ordered = orderTableColumns(constraint.table, metadata) ?: return null
		val rank = HashMap<Column, Int>(ordered.size)
		ordered.forEachIndexed { index, column -> rank.putIfAbsent(column, index) }
		return constraint.columns.sortedBy { rank[it] ?: Int.MAX_VALUE }
	}

	override fun orderUserDefinedTypeColumns(
		userDefinedType: UserDefinedObjectType,
		metadata: Metadata,
	): List<Column>? = null

	override fun orderTemporaryTableColumns(temporaryTableColumns: MutableList<TemporaryTableColumn>, metadata: Metadata) =
		Unit

	private fun persistentProperty(binding: PersistentClass, name: String) =
		if (binding.identifierProperty?.name == name) binding.identifierProperty
		else binding.properties.find { it.name == name }
}

class ReadableForeignKeyNamingStrategy : ImplicitNamingStrategyJpaCompliantImpl() {
	override fun determineForeignKeyName(source: ImplicitForeignKeyNameSource): Identifier {
		source.userProvidedIdentifier?.let { return it }
		val table = readable(source.tableName)
		val columns = source.columnNames.joinToString("__") { readable(it) }
		return Identifier.toIdentifier("fk__${table}__${columns}")
	}

	private fun readable(name: Identifier): String {
		if (name.isQuoted) return name.text
		val builder = StringBuilder(name.text.replace('.', '_'))
		var index = 1
		while (index < builder.length - 1) {
			val before = builder[index - 1]
			val current = builder[index]
			val after = builder[index + 1]
			if ((before.isLowerCase() || before.isDigit()) && current.isUpperCase() && (after.isLowerCase() || after.isDigit())) {
				builder.insert(index, '_')
				index++
			}
			index++
		}
		return builder.toString().lowercase().removeSuffix("_entity")
	}
}

class EntitySuffixPhysicalNamingStrategy : PhysicalNamingStrategySnakeCaseImpl() {
	override fun toPhysicalTableName(logicalName: Identifier, jdbcEnvironment: JdbcEnvironment): Identifier {
		val snake = super.toPhysicalTableName(logicalName, jdbcEnvironment)
		return Identifier.toIdentifier(snake.text.removeSuffix("_entity"), snake.isQuoted)
	}
}
