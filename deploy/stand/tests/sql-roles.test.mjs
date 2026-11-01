import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, chmod, mkdtemp, symlink, rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

let roles;
test('role reconciler is available', async () => {
    roles = await import('../sql-roles.mjs').catch(() => null);
    assert.equal(typeof roles?.reconcileSqlRole, 'function', 'restricted role reconciliation missing');
});

test('rejects malformed identity and unsafe credential files before invoking a transport', async () => {
    const module = await import('../sql-roles.mjs');
    for (const identity of [null, {}, {schemaVersion:1,installationId:'injection;DROP'}]) {
        await assert.rejects(module.reconcileSqlRole({identity}), /identity/);
    }
    const directory = await mkdtemp(join(tmpdir(),'sql-role-unit-'));
    const identity={schemaVersion:1,installationId:'fixture',database:'stand169',databaseOid:'123',systemIdentifier:'123',migrationRole:'migration169',serviceRole:'service169',loginRole:'us_fixture_sql'};
    try {
        await writeFile(`${directory}/unsafe`, '{}', {mode:0o644});
        await symlink(`${directory}/unsafe`,`${directory}/link`);
        for (const name of ['unsafe','link','missing']) {
            await assert.rejects(module.provisionSqlRole({identity,adminCredentialsPath:`${directory}/${name}`,psqlPath:'/unreachable'}), /credential/);
        }
    } finally { await rm(directory,{recursive:true,force:true}); }
});

const root = process.env.STAND_ROLE_TEST_ROOT;
const psqlPath = process.env.STAND_PSQL_BIN;
function sql(text, { user = 'postgres', database = 'stand169', passfile = `${root}/test.pgpass` } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(psqlPath, ['-X', '-qAt', '-w', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-U', user, '-d', database], {
            env: { ...process.env, PGPASSFILE: passfile }, stdio: ['pipe', 'pipe', 'pipe'],
        });
        let out = '', err = '';
        child.stdout.on('data', data => { out += data; });
        child.stderr.on('data', data => { err += data; });
        child.on('error', reject);
        child.on('exit', code => code === 0 ? resolve(out.trim()) : reject(new Error(err)));
        child.stdin.end(text);
    });
}

test('real PostgreSQL restricts DML, repairs drift, rejects collisions and revokes an existing session', { skip: !root }, async t => {
    const adminPassword = (await readFile(`${root}/password`, 'utf8')).trim();
    const password = randomBytes(24).toString('hex');
    await writeFile(`${root}/test.pgpass`, `127.0.0.1:5432:*:postgres:${adminPassword}\n127.0.0.1:5432:*:us_fixture_sql:${password}\n`, { mode: 0o600 });
    await sql(`CREATE ROLE migration169 LOGIN; CREATE ROLE service169 LOGIN;
        CREATE DATABASE stand169 OWNER migration169;
        CREATE DATABASE foreign169;
        REVOKE CONNECT,TEMP ON DATABASE postgres,template1,foreign169 FROM PUBLIC;
        COMMENT ON DATABASE stand169 IS 'ultistats-stand:fixture';`, { database: 'postgres' });
    const migrationDir = fileURLToPath(new URL('../../../src/main/resources/db/migration/', import.meta.url));
    const migrations = ['V1__create_tables.sql', 'V1_1__migrate_match_players_to_participants.sql',
        'V2__rename_event_types.sql','V3__add_match_display_snapshots.sql','V4__add_players_per_team_to_matches.sql',
        'V5__add_event_creation_fingerprint.sql','V6__event_log_revisions_and_batch_receipts.sql',
        'V7__obs_config.sql','V8__obs_scoreboard_profiles.sql','V9__undo_command_receipts.sql'];
    for (const migration of migrations) await sql(`SET ROLE migration169; ${await readFile(`${migrationDir}/${migration}`, 'utf8')}`);
    await sql(`SET ROLE migration169;
        CREATE TABLE credentials169(secret text); INSERT INTO credentials169 VALUES ('synthetic');
        CREATE SEQUENCE forbidden169;
        CREATE FUNCTION leak169() RETURNS text LANGUAGE sql SECURITY DEFINER AS $$SELECT secret FROM credentials169 LIMIT 1$$;
        CREATE TABLE flyway_schema_history(version text);
        GRANT ALL ON ALL TABLES IN SCHEMA public TO service169;
        GRANT USAGE ON SCHEMA public TO service169;`);
    const identity = {
        schemaVersion: 1, installationId: 'fixture', database: 'stand169',
        databaseOid: await sql("SELECT oid FROM pg_database WHERE datname=current_database()"),
        systemIdentifier: await sql('SELECT system_identifier FROM pg_control_system()'),
        migrationRole: 'migration169', serviceRole: 'service169', loginRole: 'us_fixture_sql',
    };
    await writeFile(`${root}/admin.json`, JSON.stringify({ host:'127.0.0.1',port:5432,database:'stand169',user:'postgres',password:adminPassword,sslmode:'disable' }), { mode:0o600 });
    await writeFile(`${root}/login.json`, JSON.stringify({ password }), { mode:0o600 });
    const options = { identity,adminCredentialsPath:`${root}/admin.json`,loginCredentialsPath:`${root}/login.json`,psqlPath };
    // The RED state is a real database without the restricted login.
    assert.equal(await sql("SELECT count(*) FROM pg_roles WHERE rolname='us_fixture_sql'"), '0');
    assert.equal(typeof roles?.provisionSqlRole, 'function', 'real PG provisioning missing');
    const proof = await roles.provisionSqlRole(options);
    assert.equal(proof.state, 'enabled');
    assert.match(proof.rolePolicySha256, /^[0-9a-f]{64}$/);
    assert.equal(JSON.stringify(proof).includes(password), false);
    const client = { user:'us_fixture_sql' };
    await t.test('CRUD allowed only on explicit baseline data tables', async () => {
        assert.equal(await sql(`INSERT INTO teams(id,name) VALUES ('00000000-0000-0000-0000-000000000169','fixture');
            UPDATE teams SET city='green' WHERE name='fixture'; SELECT city FROM teams WHERE name='fixture';
            DELETE FROM teams WHERE name='fixture';`, client), 'green');
        assert.equal(await sql('SELECT payload FROM obs_config WHERE id=1', client), '{}');
        await sql('SELECT * FROM undo_command_receipts; SELECT * FROM event_batch_receipts;', client);
    });
    await t.test('rejects direct, PUBLIC and column catalog secret grants and unsafe system functions', async () => {
        for (const grantee of ['us_fixture_sql','PUBLIC']) {
            for (const access of ['SELECT','SELECT(rolpassword)']) {
                await sql(`GRANT ${access} ON pg_catalog.pg_authid TO ${grantee}`);
                try { await assert.rejects(roles.reconcileSqlRole(options), /rejected/); }
                finally { await sql(`REVOKE ${access} ON pg_catalog.pg_authid FROM ${grantee}`); }
            }
            await sql(`GRANT CREATE ON SCHEMA pg_catalog TO ${grantee}`);
            try { await assert.rejects(roles.reconcileSqlRole(options), /rejected/); }
            finally { await sql(`REVOKE CREATE ON SCHEMA pg_catalog FROM ${grantee}`); }
            await sql(`GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) TO ${grantee}`);
            try { await assert.rejects(roles.reconcileSqlRole(options), /rejected/); }
            finally { await sql(`REVOKE EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) FROM ${grantee}`); }
        }
        await sql('CREATE TABLE information_schema.unsafe_system169(secret text)');
        try { await assert.rejects(roles.reconcileSqlRole(options), /rejected/); }
        finally { await sql('DROP TABLE information_schema.unsafe_system169'); }
        await roles.reconcileSqlRole(options);
        // Ordinary metadata and safe builtins must still work for real SQL clients.
        assert.equal(await sql("SELECT count(*)>0 FROM pg_catalog.pg_class",client),'t');
        assert.equal(await sql("SELECT count(*)>0 FROM information_schema.tables",client),'t');
        assert.equal(await sql('SELECT length(current_database())>0',client),'t');
    });
    await t.test('preserves existing service table and column PUBLIC rights without broadening denied columns', async () => {
        await sql('REVOKE SELECT ON public.teams FROM service169; GRANT SELECT ON public.teams TO PUBLIC');
        assert.equal(await sql("SELECT has_table_privilege('service169','public.teams','SELECT')"),'t');
        await roles.reconcileSqlRole(options);
        assert.equal(await sql("SELECT has_table_privilege('service169','public.teams','SELECT')"),'t');
        await sql('REVOKE SELECT ON public.teams FROM service169; GRANT SELECT(name) ON public.teams TO PUBLIC');
        assert.equal(await sql("SELECT has_column_privilege('service169','public.teams','name','SELECT')"),'t');
        assert.equal(await sql("SELECT has_column_privilege('service169','public.teams','id','SELECT')"),'f');
        await roles.reconcileSqlRole(options);
        assert.equal(await sql("SELECT has_column_privilege('service169','public.teams','name','SELECT')"),'t');
        assert.equal(await sql("SELECT has_column_privilege('service169','public.teams','id','SELECT')"),'f');
        await sql('GRANT SELECT ON public.teams TO service169');
    });
    const forbidden = [
        'CREATE TABLE illegal169(id integer)', 'CREATE TEMP TABLE illegal169(id integer)',
        'CREATE SCHEMA illegal169', 'ALTER TABLE teams ADD COLUMN bad integer', 'TRUNCATE teams',
        'CREATE ROLE illegal169', 'CREATE DATABASE illegal169', 'SET ROLE migration169', 'SET ROLE service169',
        'SELECT * FROM credentials169', "INSERT INTO credentials169 VALUES ('bad')",
        'SELECT * FROM flyway_schema_history', "SELECT nextval('forbidden169')", "SELECT leak169()",
        "SELECT pg_read_file('/etc/passwd')", 'ALTER ROLE us_fixture_sql SUPERUSER',
    ];
    await t.test('DDL, admin, security, sequence and privilege escape denied', async () => {
        for (const statement of forbidden) await assert.rejects(sql(statement, client), /permission denied|must be|not permitted/, statement);
        await sql('GRANT SELECT ON teams TO PUBLIC',client);
        assert.equal(await sql("SELECT count(*) FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a WHERE c.oid='public.teams'::regclass AND a.grantee=0"),'0');
        for (const database of ['foreign169','postgres','template1']) await assert.rejects(sql('SELECT 1', { ...client,database }), /permission denied/);
        // Service/migration owners retain their own application rights.
        assert.equal(await sql('SET ROLE service169; SELECT count(*) FROM teams'), '0');
        assert.equal(await sql('SET ROLE service169; SELECT leak169()'), 'synthetic');
        await sql('SET ROLE migration169; CREATE TABLE owner_still_works169(id integer)');
    });
    await t.test('idempotent reconcile removes table/column/default/function drift without future auth grants', async () => {
        await sql(`SET ROLE migration169;
            GRANT SELECT(secret) ON credentials169 TO us_fixture_sql;
            GRANT SELECT ON credentials169 TO PUBLIC;
            ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO us_fixture_sql;
            CREATE TABLE sessions169(secret text);
            GRANT EXECUTE ON FUNCTION leak169() TO us_fixture_sql;`);
        await roles.reconcileSqlRole(options);
        await roles.reconcileSqlRole(options);
        for (const q of ['SELECT secret FROM credentials169','SELECT * FROM sessions169','SELECT leak169()']) {
            await assert.rejects(sql(q,client), /permission denied/);
        }
        await sql('SET ROLE migration169; CREATE TABLE future_security169(secret text)');
        await assert.rejects(sql('SELECT * FROM future_security169',client), /permission denied/);
    });
    await t.test('wrong identity, insecure secrets, role memberships and unknown schemas fail closed', async () => {
        for (const delta of [{databaseOid:'1'},{systemIdentifier:'1'},{installationId:'foreign'},{database:'postgres'}, {loginRole:'migration169'}]) {
            await assert.rejects(roles.reconcileSqlRole({ ...options,identity:{...identity,...delta} }));
        }
        await chmod(`${root}/login.json`,0o644);
        await assert.rejects(roles.reconcileSqlRole(options), /credential/);
        await chmod(`${root}/login.json`,0o600);
        await sql('GRANT service169 TO us_fixture_sql');
        await assert.rejects(roles.reconcileSqlRole(options));
        await sql('REVOKE service169 FROM us_fixture_sql; CREATE SCHEMA extra169');
        await assert.rejects(roles.reconcileSqlRole(options));
        await sql('DROP SCHEMA extra169');
    });
    await t.test('revoke blocks fresh DML on an already connected idle client and new logins', async () => {
        // Keep the same backend session through revocation.
        const child = spawn(psqlPath,['-X','-qAt','-w','-h','127.0.0.1','-U','us_fixture_sql','-d','stand169'], {
            env:{...process.env,PGPASSFILE:`${root}/test.pgpass`},stdio:['pipe','pipe','pipe'],
        });
        let output='',error='';
        child.stdout.on('data',data=>{ output+=data; });
        child.stderr.on('data',data=>{ error+=data; });
        child.stdin.write("SELECT 'ready169';\n");
        const deadline=Date.now()+10000;
        while(!output.includes('ready169') && Date.now()<deadline) await new Promise(r=>setTimeout(r,20));
        assert.match(output,/ready169/);
        const revoked = await roles.revokeSqlRole({ ...options,loginCredentialsPath:undefined });
        assert.equal(revoked.state,'revoked');
        await roles.revokeSqlRole(options);
        const ended = new Promise(resolve=>child.on('exit',resolve));
        child.stdin.end('INSERT INTO public.teams(id,name) VALUES (gen_random_uuid(),\'revoked\');\n');
        await ended;
        assert.match(error,/permission denied/);
        await assert.rejects(sql('SELECT 1',client),/not permitted to log in/);
        assert.equal(await sql("SELECT count(*) FROM teams WHERE name='revoked'"),'0');
        await roles.reconcileSqlRole(options);
        assert.equal(await sql('SELECT count(*) FROM teams',client),'0');
    });
});
