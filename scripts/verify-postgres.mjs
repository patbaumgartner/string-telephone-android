#!/usr/bin/env node
/**
 * PostgreSQL migration verification: asserts that the companion API persistence
 * stack matches ADR-0008 — a Spring Boot auto-configured HikariCP DataSource
 * and JdbcClient bean, Flyway migrations applied at startup, `DB_*`
 * configuration with `API_DB` removed, compose `db` service with a dev-only
 * loopback `pgweb`, Testcontainers-backed tests, and guidance docs with no
 * SQLite/API_DB runtime path. Exits nonzero with a reason per failure; prints
 * "postgres verification passed" only after every assertion passes.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
  try {
    return readFileSync(join(root, relative), 'utf8');
  } catch {
    return '';
  }
}

function readDir(relative) {
  try {
    return readdirSync(join(root, relative));
  } catch {
    return [];
  }
}

const dbSourceExists = readDir(
  'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone',
).includes('Database.kt');
const buildFile = read('server/api/build.gradle.kts');
const catalog = read('gradle/libs.versions.toml');
const properties = read('server/api/src/main/resources/application.properties');
const compose = read('server/compose.yaml');
const devCompose = read('server/compose.dev.yaml');
const envExample = read('server/.env.example');

const mainKotlin = readDir(
  'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone',
)
  .filter((name) => name.endsWith('.kt'))
  .map((name) =>
    read(`server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/${name}`),
  )
  .join('\n');

const testKotlin = readDir(
  'server/api/src/test/kotlin/com/patbaumgartner/stringtelephone',
)
  .filter((name) => name.endsWith('.kt'))
  .map((name) =>
    read(`server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/${name}`),
  )
  .join('\n');

const migrations = readDir('server/api/src/main/resources/db/migration').filter(
  (name) => /^V\d+__.*\.sql$/.test(name),
);
const migrationSql = migrations
  .map((name) => read(`server/api/src/main/resources/db/migration/${name}`))
  .join('\n');

const guidanceDocs = [
  'docs/setup.md',
  'docs/operations.md',
  'docs/architecture.md',
  'SECURITY.md',
  'README.md',
  'AGENTS.md',
];
const guidanceText = guidanceDocs.map(read).join('\n');

const pgwebSection = devCompose.slice(devCompose.indexOf('pgweb:'));

const assertions = [
  // Build: Spring Boot JDBC + Flyway starters, PostgreSQL driver, Testcontainers;
  // no SQLite anywhere.
  [
    buildFile.includes('spring-boot-starter-jdbc'),
    'server build uses the JDBC starter (HikariCP DataSource)',
  ],
  [
    buildFile.includes('spring-boot-starter-flyway'),
    'server build uses the Flyway starter (migrations at startup)',
  ],
  [
    buildFile.includes('flyway-database-postgresql'),
    'Flyway PostgreSQL support is declared',
  ],
  [buildFile.includes('org.postgresql'), 'server build depends on the PostgreSQL driver'],
  [buildFile.includes('testcontainers'), 'server tests depend on Testcontainers'],
  [/sqlite/i.test(buildFile) === false, 'server build no longer references SQLite'],
  [/sqlite/i.test(catalog) === false, 'version catalog no longer declares SQLite'],

  // The hand-rolled pool/migration class is gone: Spring Boot owns all of it.
  [dbSourceExists === false, 'the hand-rolled Database class is gone'],

  // Access goes through JdbcClient with bound parameters and Spring
  // transactions — no raw JDBC, no hand-rolled commit/rollback state.
  [mainKotlin.includes('JdbcClient'), 'services read and write through JdbcClient'],
  [/\.(param|params)\(/.test(mainKotlin), 'values are bound with param/params'],
  [
    /prepareStatement|DriverManager/.test(mainKotlin) === false,
    'no raw PreparedStatement or DriverManager code remains',
  ],
  [
    /autoCommit/.test(mainKotlin) === false,
    'no hand-rolled transaction state in the services',
  ],
  [
    mainKotlin.includes('TransactionTemplate'),
    'multi-statement writes run inside TransactionTemplate transactions',
  ],
  [/API_DB/.test(mainKotlin) === false, 'server main sources mention no API_DB'],

  // Configuration: spring.datasource.* from DB_* env vars, mandatory password.
  [
    properties.includes('spring.datasource.url=jdbc:postgresql://${DB_HOST'),
    'spring.datasource.url is built from DB_HOST',
  ],
  [properties.includes('spring.datasource.username=${DB_USER'), 'username comes from DB_USER'],
  [
    /^spring\.datasource\.password=\$\{DB_PASSWORD\}$/m.test(properties),
    'DB_PASSWORD has no blank default',
  ],
  [
    properties.includes('spring.flyway.locations=classpath:db/migration'),
    'Flyway migration location is declared',
  ],
  [
    /API_DB|st\.database/.test(properties) === false,
    'application.properties dropped API_DB and st.database',
  ],

  // Migrations: numbered forward-only files creating every table.
  [migrations.length >= 1, 'at least one V*.sql migration exists'],
  [
    // Parse the numeric prefix rather than trusting directory order.
    migrations
      .map((name) => /^V(\d+)__/.exec(name)?.[1])
      .sort((a, b) => Number(a) - Number(b))
      .every((version, index) => version !== undefined && Number(version) === index + 1),
    'migrations are numbered forward-only from V1',
  ],
  ...['users', 'messages', 'pbx_accounts', 'pbx_settings'].map((table) => [
    new RegExp(`CREATE TABLE[\\s\\S]{0,40}\\b${table}\\b`).test(migrationSql),
    `migration creates ${table}`,
  ]),

  // Compose: db service, healthcheck, named volume, api wired to it.
  [compose.includes('postgres:18-alpine'), 'compose pins postgres:18-alpine'],
  [compose.includes('pg_isready'), 'db service has a pg_isready healthcheck'],
  [
    // PostgreSQL 18 images mount the parent directory, not .../data.
    compose.includes('db-data:/var/lib/postgresql'),
    'db service stores data on a named volume',
  ],
  [compose.includes('DB_HOST: db'), 'api service points at the db host'],
  [
    /condition:\s*service_healthy/.test(compose) && /\bdb\b/.test(compose),
    'api waits for the db healthcheck',
  ],
  [
    /API_DB/.test(compose) === false,
    'compose no longer passes API_DB to the api service',
  ],
  [
    /pgweb/.test(compose) === false,
    'pgweb stays out of the base compose file',
  ],

  // Dev overlay: pgweb bound to loopback only.
  [devCompose.includes('pgweb'), 'dev compose overlay defines pgweb'],
  [devCompose.includes('127.0.0.1'), 'pgweb port binds to loopback'],
  [
    /^ {4}- "(?!127\.0\.0\.1)/m.test(pgwebSection) === false,
    'pgweb section maps no non-loopback port',
  ],

  // Secrets template advertises the database password.
  [envExample.includes('DB_PASSWORD'), '.env.example documents DB_PASSWORD'],

  // Tests run against a real PostgreSQL container, not a file database.
  [
    testKotlin.includes('PostgreSQLContainer'),
    'tests start a Testcontainers PostgreSQL',
  ],
  [testKotlin.includes('JdbcClient'), 'tests drive persistence through JdbcClient'],
  [
    /st\.database|\.db"/.test(testKotlin) === false,
    'tests no longer configure a SQLite file database',
  ],

  // Guidance docs: DB_* documented, no stale SQLite/API_DB instruction.
  [
    /API_DB|sqlite/i.test(guidanceText) === false,
    'guidance docs mention no API_DB or SQLite',
  ],
  [
    guidanceDocs.some((doc) => read(doc).includes('DB_PASSWORD')),
    'setup or operations documents DB_PASSWORD',
  ],
  [
    read('docs/operations.md').includes('pg_dump'),
    'operations documents PostgreSQL backup with pg_dump',
  ],
];

let failed = 0;
for (const [ok, reason] of assertions) {
  if (!ok) {
    failed += 1;
    console.error(`FAIL: ${reason}`);
  }
}
if (failed > 0) {
  console.error(`postgres verification failed: ${failed} assertion(s)`);
  process.exit(1);
}
console.log('postgres verification passed');
