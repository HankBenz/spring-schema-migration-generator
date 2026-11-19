# spring-schema-migration-generator

Compares your JPA entities with the live database and writes the next Flyway migration.

Add the jar to a Spring Boot application. On startup it opens a JDBC connection, reads the database product, and builds the entity model with Hibernate. It then reads the live catalog through JDBC. Missing tables, columns, sequences, indexes, and constraints become `CREATE` statements. Database objects that are not mapped on an entity are dropped, including objects that contain data. When the two sides already match, startup continues. When they differ, the statements are written to `src/main/resources/db/migration` and startup stops with those statements in the exception. Commit the new file and start again. Flyway applies it before the next comparison.

The generated file is named:

```text
V00012__261119_1044_my_branch.sql
```

`V` plus the next number, then the local date and time (`yyMMdd_HHmm`), then the current git branch. `/` and other characters outside letters, digits, `_`, and `-` become `_`.

`flyway_schema_history` is kept. So are the default schema, `public`, `dbo`, and system schemas such as `information_schema`, `pg_catalog`, and `sys`.

## Add it to a Spring Boot application

Releases are published to GitHub. The jar can be downloaded without a token. Maven coordinates:

```text
hankbenz:spring-schema-migration-generator:init-261119
```

The version on GitHub is `<branch>-<yyMMdd>`, for example `init-261119`. Use the newest release from [Releases](https://github.com/HankBenz/spring-schema-migration-generator/releases). A local `mvn install` publishes the version `local` from this repository's POM. The release workflow replaces that with the branch-and-date version before it publishes.

Gradle:

```kotlin
repositories {
    ivy {
        name = "GitHubReleases"
        url = uri("https://github.com/HankBenz/spring-schema-migration-generator/releases/download")
        patternLayout {
            artifact("[revision]/[artifact]-[revision].[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeGroup("hankbenz")
        }
    }
    mavenCentral()
}

dependencies {
    implementation("hankbenz:spring-schema-migration-generator:init-261119")
}
```

That download has no POM, so it does not pull transitive libraries. The application already needs the pieces this library calls: Spring Boot autoconfigure, Hibernate ORM, Jakarta Persistence, and a JDBC driver for the target database.

GitHub Packages also receives each release, at `https://maven.pkg.github.com/HankBenz/spring-schema-migration-generator`. That registry requires a token with `read:packages` even when this repository is public. An anonymous request returns 401.

The JDBC driver stays in the application (`runtimeOnly` or `runtime`). This library does not ship one.

A JDBC `DataSource` bean is used when the application has one. Otherwise the connection comes from `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password`. If those are absent, `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password` are used.

Entity classes are scanned from `db.migration.entity-package`. When that property is empty, the Spring Boot application package is scanned.

## What a run expects

The process needs `git` on `PATH`, because the migration file name includes the current branch. The migration directory must already exist. The default is `src/main/resources/db/migration`.

The check runs after Flyway has migrated. It stays off when either of these is set:

```properties
db.migration.enabled=false
```

or both of these are `none`:

```properties
spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none
spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=none
```

Use the second pair in production so a deployed process does not write a migration or refuse to start.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `db.migration.enabled` | `true` | Turn the check on or off |
| `db.migration.entity-package` | Spring Boot application package | Package scanned for `@Entity` |
| `db.migration.migration-directory` | `src/main/resources/db/migration` | Where the next `V*.sql` file is written |
| `db.migration.history-table` | `flyway_schema_history` | Table left untouched |
| `db.migration.sequence-starts.<name>` | empty | `START WITH` for a sequence created because an entity default references it |

Example for a shared id sequence:

```properties
db.migration.sequence-starts.id=1000
```

`Instant` columns follow `spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type` (`TIMESTAMP` when unset). Enum columns follow `spring.jpa.properties.hibernate.type.prefer_native_enum_types` (`false` when unset), so an enum is a string column with a value check such as `state in ('PENDING', 'REVIEWED')`.

## Names

Beans already present in the application are used for physical names, foreign-key names, and column order. When those beans are absent, the library uses:

- `EntitySuffixPhysicalNamingStrategy` — snake case, and a table whose logical name ends in `_entity` drops that suffix
- `ReadableForeignKeyNamingStrategy` — `fk__<table>__<columns>`
- `EntityFieldColumnOrderingStrategy` — column order follows the entity's primary-constructor parameter order

A Kotlin property that is not marked nullable is `NOT NULL`. `id` is the exception: it may be `Long?` in code and is still `NOT NULL`, because the database fills it.

## Build this repository

Java 25 and Maven:

```bash
mvn verify
```

A push to any branch runs the tests and publishes `hankbenz:spring-schema-migration-generator:<branch>-<yyMMdd>`. A second push of the same branch on the same UTC day replaces that release.
