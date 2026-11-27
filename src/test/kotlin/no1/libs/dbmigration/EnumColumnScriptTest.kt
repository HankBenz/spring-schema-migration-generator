package no1.libs.dbmigration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumColumnScriptTest {
	private val state = ColumnModel("state", "varchar(255)", nullable = false)
	private val stateValues = CheckModel(null, listOf("state"), "state in ('PENDING', 'REVIEWED', 'POSTPONED')")

	@Test
	fun `a new table creates an enum column with its allowed values`() {
		// Arrange
		val required = schema(table(listOf(state), listOf(stateValues)))
		val actual = schema()

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf("create table \"public\".\"payamak_report\" (\n\t\"state\" varchar(255) not null check (state in ('PENDING', 'REVIEWED', 'POSTPONED'))\n);"),
			statements,
		)
	}

	@Test
	fun `a new enum column is created with its allowed values`() {
		// Arrange
		val required = schema(table(listOf(ColumnModel("id", "bigint", nullable = false), state), listOf(stateValues)))
		val actual = schema(table(listOf(ColumnModel("id", "bigint", nullable = false))))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf("""alter table "public"."payamak_report" add column "state" varchar(255) not null check (state in ('PENDING', 'REVIEWED', 'POSTPONED'));"""),
			statements,
		)
	}

	// bigserial and bigint nextval('...') are the same column after type canonicalization.
	@Test
	fun `a postgres serial id is already the entity bigint`() {
		// Arrange
		val id = ColumnModel("id", "bigint", nullable = false, defaultSql = "nextval('id')")
		val required = schema(table(listOf(id)))
		val actual = schema(table(listOf(id.copy(sqlType = "bigserial"))))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertTrue(statements.isEmpty())
	}

	// Postgres stores the entity `in (...)` check as `= ANY (ARRAY[...])`.
	@Test
	fun `a postgres rewritten enum check is the entity check`() {
		// Arrange
		val rewritten = CheckModel(
			"payamak_report_state_check",
			listOf("state"),
			"((state)::text = ANY (ARRAY['PENDING'::text, 'REVIEWED'::text, 'POSTPONED'::text]))",
		)
		val required = schema(table(listOf(state), listOf(stateValues)))
		val actual = schema(table(listOf(state), listOf(rewritten)))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertTrue(statements.isEmpty())
	}

	@Test
	fun `an existing enum column gains its value check`() {
		// Arrange
		val required = schema(table(listOf(state), listOf(stateValues)))
		val actual = schema(table(listOf(state)))

		// Act
		val statements = migrationStatements(required, actual)

		// Assert
		assertEquals(
			listOf("""alter table "public"."payamak_report" add check (state in ('PENDING', 'REVIEWED', 'POSTPONED'));"""),
			statements,
		)
	}

	private fun schema(vararg tables: TableModel) = SchemaModel(schemas = setOf("public"), sequences = emptySet(), tables = tables.toList())

	private fun table(columns: List<ColumnModel>, checks: List<CheckModel> = emptyList()) =
		TableModel(schema = "public", name = "payamak_report", columns = columns, checks = checks)
}
