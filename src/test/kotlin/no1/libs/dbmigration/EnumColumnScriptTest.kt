package no1.libs.dbmigration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumColumnScriptTest {
	private val state = ColumnModel("state", "varchar(255)", nullable = false)
	private val stateValues = CheckModel(null, listOf("state"), "state in ('PENDING', 'REVIEWED', 'POSTPONED')")

	@Test
	fun `a new table creates an enum column with its allowed values`() {
		val required = schema(table(listOf(state), listOf(stateValues)))

		assertEquals(
			listOf("create table \"public\".\"payamak_report\" (\n\t\"state\" varchar(255) not null check (state in ('PENDING', 'REVIEWED', 'POSTPONED'))\n);"),
			migrationStatements(required, schema()),
		)
	}

	@Test
	fun `a new enum column is created with its allowed values`() {
		val required = schema(table(listOf(ColumnModel("id", "bigint", nullable = false), state), listOf(stateValues)))
		val actual = schema(table(listOf(ColumnModel("id", "bigint", nullable = false))))

		assertEquals(
			listOf("""alter table "public"."payamak_report" add column "state" varchar(255) not null check (state in ('PENDING', 'REVIEWED', 'POSTPONED'));"""),
			migrationStatements(required, actual),
		)
	}

	@Test
	fun `a postgres serial id is already the entity bigint`() {
		val id = ColumnModel("id", "bigint", nullable = false, defaultSql = "nextval('id')")
		val required = schema(table(listOf(id)))
		val actual = schema(table(listOf(id.copy(sqlType = "bigserial"))))

		assertTrue(migrationStatements(required, actual).isEmpty())
	}

	@Test
	fun `a postgres rewritten enum check is the entity check`() {
		val rewritten = CheckModel(
			"payamak_report_state_check",
			listOf("state"),
			"((state)::text = ANY (ARRAY['PENDING'::text, 'REVIEWED'::text, 'POSTPONED'::text]))",
		)
		val required = schema(table(listOf(state), listOf(stateValues)))
		val actual = schema(table(listOf(state), listOf(rewritten)))

		assertTrue(migrationStatements(required, actual).isEmpty())
	}

	@Test
	fun `an existing enum column gains its value check`() {
		val required = schema(table(listOf(state), listOf(stateValues)))
		val actual = schema(table(listOf(state)))

		assertEquals(
			listOf("""alter table "public"."payamak_report" add check (state in ('PENDING', 'REVIEWED', 'POSTPONED'));"""),
			migrationStatements(required, actual),
		)
	}

	private fun schema(vararg tables: TableModel) = SchemaModel(schemas = setOf("public"), sequences = emptySet(), tables = tables.toList())

	private fun table(columns: List<ColumnModel>, checks: List<CheckModel> = emptyList()) =
		TableModel(schema = "public", name = "payamak_report", columns = columns, checks = checks)
}
