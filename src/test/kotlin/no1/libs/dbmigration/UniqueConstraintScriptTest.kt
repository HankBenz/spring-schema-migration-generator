package no1.libs.dbmigration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UniqueConstraintScriptTest {
	@Test
	fun `a unique constraint is kept only when its name and columns match the entity`() {
		// Arrange
		val column = ColumnModel("global_id", "uuid", nullable = false)
		val unique = KeyModel("uq__payamak_report__global_id", listOf("global_id"))
		val required = schema(table("payamak_report", listOf(column), uniques = listOf(unique)))
		val actual = schema(table("payamak_report", listOf(column), uniques = listOf(unique)))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertTrue(statements.isEmpty())
	}

	// A name mismatch is a different constraint, even when the columns are the same.
	@Test
	fun `a unique constraint whose name is not declared on the entity is replaced`() {
		// Arrange
		val column = ColumnModel("global_id", "uuid", nullable = false)
		val required = schema(
			table("payamak_report", listOf(column), uniques = listOf(KeyModel("uq__payamak_report__global_id", listOf("global_id")))),
		)
		val actual = schema(
			table("payamak_report", listOf(column), uniques = listOf(KeyModel("uq__payamak_report___global_id", listOf("global_id")))),
		)

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf(
				"""alter table "public"."payamak_report" drop constraint "uq__payamak_report___global_id";""",
				"""alter table "public"."payamak_report" add constraint "uq__payamak_report__global_id" unique ("global_id");""",
			),
			statements,
		)
	}

	@Test
	fun `a missing unique constraint is added without a drop`() {
		// Arrange
		val required = schema(
			table("ranked_origin", listOf(ColumnModel("origin", "varchar(16)", nullable = false)), uniques = listOf(KeyModel("uq__ranked_origin__origin", listOf("origin")))),
		)
		val actual = schema(table("ranked_origin", listOf(ColumnModel("origin", "varchar(16)", nullable = false))))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf("""alter table "public"."ranked_origin" add constraint "uq__ranked_origin__origin" unique ("origin");"""),
			statements,
		)
	}

	// Objects present in the database and absent from the entity model are dropped, in dependency order.
	@Test
	fun `an unmapped table column sequence and schema are dropped`() {
		// Arrange
		val required = SchemaModel(schemas = setOf("public"), sequences = setOf(SequenceName("public", "id")), tables = emptyList())
		val actual = SchemaModel(
			schemas = setOf("public", "audit"),
			sequences = setOf(SequenceName("public", "id"), SequenceName("public", "old_seq")),
			tables = listOf(
				TableModel(
					schema = "public",
					name = "obsolete",
					columns = listOf(ColumnModel("id", "bigint", nullable = false)),
				),
			),
		)

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf(
				"""drop table "public"."obsolete" cascade;""",
				"""drop sequence "public"."old_seq" cascade;""",
				"""drop schema "audit" cascade;""",
			),
			statements,
		)
	}

	private fun schema(vararg tables: TableModel) = SchemaModel(schemas = setOf("public"), sequences = emptySet(), tables = tables.toList())

	private fun table(name: String, columns: List<ColumnModel>, uniques: List<KeyModel> = emptyList()) =
		TableModel(schema = "public", name = name, columns = columns, uniques = uniques)
}
