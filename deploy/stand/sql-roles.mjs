import { open, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join, dirname } from 'node:path';
import { spawn } from 'node:child_process';
import { createHash, createHmac, pbkdf2Sync, randomBytes } from 'node:crypto';

const policyBytes = await open(new URL('./sql-role-policy-v1.json', import.meta.url), 'r').then(async file => {
    try { return await file.readFile(); } finally { await file.close(); }
});
const policy = JSON.parse(policyBytes);
export const rolePolicySha256 = createHash('sha256').update(policyBytes).digest('hex');
const identifier = /^[a-z][a-z0-9_]{0,62}$/;
const quote = value => `'${value.replaceAll("'", "''")}'`;
const ident = value => `"${value}"`;

function validateIdentity(identity) {
    if (!identity || identity.schemaVersion !== 1 || !/^[a-z][a-z0-9_]{0,31}$/.test(identity.installationId)) throw new Error('Invalid SQL installation identity');
    for (const key of ['database','migrationRole','serviceRole','loginRole']) {
        if (typeof identity[key] !== 'string' || !identifier.test(identity[key]) || identity[key].startsWith('pg_')) throw new Error('Invalid SQL identity identifier');
    }
    if (identity.loginRole !== `us_${identity.installationId}_sql` ||
        identity.loginRole === identity.migrationRole || identity.loginRole === identity.serviceRole ||
        identity.migrationRole === identity.serviceRole || ['postgres','template0','template1'].includes(identity.database) ||
        !/^[1-9][0-9]{0,9}$/.test(identity.databaseOid) || !/^[1-9][0-9]{0,19}$/.test(identity.systemIdentifier)) {
        throw new Error('Invalid SQL role/database separation');
    }
    return { ...identity };
}

async function privateJson(path) {
    let file;
    try {
        if (typeof path !== 'string') throw new Error();
        file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
        const stat = await file.stat();
        if (!stat.isFile() || stat.uid !== process.getuid() || (stat.mode & 0o077) !== 0 || stat.size > 16384) throw new Error();
        return JSON.parse(await file.readFile('utf8'));
    } catch { throw new Error('Invalid private SQL credential file'); }
    finally { await file?.close(); }
}

// ASCII-only generated passwords avoid SASLprep ambiguities. Only the verifier
// enters SQL, never the plaintext login password (including PostgreSQL logs).
function verifier(password) {
    if (typeof password !== 'string' || !/^[\x21-\x7e]{20,256}$/.test(password)) throw new Error('Invalid SQL login credential');
    const salt = randomBytes(16);
    const salted = pbkdf2Sync(password, salt, 4096, 32, 'sha256');
    const clientKey = createHmac('sha256', salted).update('Client Key').digest();
    const storedKey = createHash('sha256').update(clientKey).digest('base64');
    const serverKey = createHmac('sha256', salted).update('Server Key').digest('base64');
    return `SCRAM-SHA-256$4096:${salt.toString('base64')}$${storedKey}:${serverKey}`;
}

async function execute(sql, credentials, psqlPath, secretRoot) {
    const directory = await mkdtemp(join(secretRoot, '.sql-secret-'));
    // Private runtime files live beside the caller credential file only when
    // a trusted adapter needs that same private directory mounted in a container.
    const passwordFile = join(directory, 'pgpass');
    const escape = value => String(value).replaceAll('\\','\\\\').replaceAll(':','\\:');
    try {
        await writeFile(passwordFile, [credentials.host,credentials.port,credentials.database,credentials.user,credentials.password].map(escape).join(':')+'\n', {mode:0o600});
        await new Promise((resolve,reject) => {
            const child = spawn(psqlPath, ['-X','-q','-w','-v','ON_ERROR_STOP=1','-h',credentials.host,'-p',String(credentials.port),'-U',credentials.user,'-d',credentials.database], {
                env:{PATH:process.env.PATH,LANG:'C',PGPASSFILE:passwordFile,PGSSLMODE:credentials.sslmode,PGCONNECT_TIMEOUT:'5'},
                stdio:['pipe','ignore','pipe'],
            });
            // Server diagnostics can contain a password verifier or private data.
            // Consume them without forwarding or attaching child-process errors.
            child.stderr.resume();
            child.stdin.on('error',()=>{});
            child.on('error',()=>reject(new Error('SQL role reconciliation transport failed')));
            child.on('close',code=>code===0 ? resolve() : reject(new Error('SQL role reconciliation rejected; inspect private database diagnostics')));
            const timeout = setTimeout(()=>{ child.kill('SIGTERM'); },30000);
            child.once('close',()=>clearTimeout(timeout));
            child.stdin.end(sql);
        });
    } finally { await rm(directory,{recursive:true,force:true}); }
}

function statements(i, passwordVerifier, revoked) {
    const db=ident(i.database), role=ident(i.loginRole), migration=ident(i.migrationRole), service=ident(i.serviceRole);
    const marker=`ultistats-stand:${i.installationId}:${i.systemIdentifier}:${i.databaseOid}`;
    const literalRole=quote(i.loginRole);
    return `BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '20s';
SELECT pg_advisory_xact_lock(169, hashtext(${quote(i.installationId)}));
DO $guard$
DECLARE existing oid;
BEGIN
    IF current_database() <> ${quote(i.database)} OR
       (SELECT system_identifier::text FROM pg_control_system()) <> ${quote(i.systemIdentifier)} OR
       NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND oid::text=${quote(i.databaseOid)}
           AND datdba=(SELECT oid FROM pg_roles WHERE rolname=${quote(i.migrationRole)})
           AND shobj_description(oid,'pg_database')=${quote(`ultistats-stand:${i.installationId}`)})
    THEN RAISE EXCEPTION 'Installation identity mismatch'; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=${quote(i.serviceRole)}) THEN RAISE EXCEPTION 'Service owner missing'; END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg\\_%' ESCAPE '\\')
    THEN RAISE EXCEPTION 'Unsupported application schema'; END IF;
    SELECT oid INTO existing FROM pg_roles WHERE rolname=${literalRole};
    IF existing IS NOT NULL THEN
        IF shobj_description(existing,'pg_authid') IS DISTINCT FROM ${quote(marker)} OR
           EXISTS (SELECT 1 FROM pg_auth_members WHERE member=existing OR roleid=existing) OR
           EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=existing AND deptype='o')
        THEN RAISE EXCEPTION 'Unsafe or foreign SQL role'; END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database d WHERE d.datname<>current_database() AND d.datallowconn AND
        (EXISTS (SELECT 1 FROM aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) a
                 WHERE a.grantee=0 AND a.privilege_type IN ('CONNECT','CREATE','TEMPORARY')) OR
         (existing IS NOT NULL AND (has_database_privilege(existing,d.oid,'CONNECT') OR has_database_privilege(existing,d.oid,'CREATE') OR has_database_privilege(existing,d.oid,'TEMP')))))
    THEN RAISE EXCEPTION 'Foreign database access is not isolated'; END IF;
    IF EXISTS (SELECT 1 FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a
        WHERE a.grantee IN (0,COALESCE(existing,0)) AND
        d.defaclrole NOT IN (SELECT oid FROM pg_roles WHERE rolname IN (${quote(i.migrationRole)},${quote(i.serviceRole)})) AND
        d.defaclobjtype IN ('r','S','f'))
    THEN RAISE EXCEPTION 'Unmanaged default privilege grant'; END IF;
    IF existing IS NULL THEN
        CREATE ROLE ${role} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        COMMENT ON ROLE ${role} IS ${quote(marker)};
    END IF;
END $guard$;
ALTER ROLE ${role} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE ${role} RESET ALL;
REVOKE ALL ON DATABASE ${db} FROM PUBLIC, ${role};
GRANT CONNECT,TEMP ON DATABASE ${db} TO ${migration},${service};
REVOKE ALL ON SCHEMA public FROM PUBLIC,${role};
GRANT USAGE ON SCHEMA public TO ${migration},${service};
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC,${role};
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC,${role};
-- Preserve access already held by migration/service accounts before removing
-- PUBLIC function execution. Do not broaden a function previously denied to them.
DO $service_functions$
DECLARE item record; account text;
BEGIN
    FOR item IN SELECT p.oid,p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public'
    LOOP
        FOREACH account IN ARRAY ARRAY[${quote(i.migrationRole)},${quote(i.serviceRole)}]
        LOOP
            IF has_function_privilege(account,item.oid,'EXECUTE') THEN
                EXECUTE format('GRANT EXECUTE ON ROUTINE %s TO %I',item.signature,account);
            END IF;
        END LOOP;
    END LOOP;
END $service_functions$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC,${role};
REVOKE ALL ON ALL PROCEDURES IN SCHEMA public FROM PUBLIC,${role};
DO $columns$
DECLARE item record;
BEGIN
    FOR item IN SELECT c.relname,a.attname FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped
        AND c.relkind IN ('r','p','v','m','f') AND a.attacl IS NOT NULL
    LOOP EXECUTE format('REVOKE ALL (%I) ON TABLE public.%I FROM PUBLIC, %I',item.attname,item.relname,${literalRole}); END LOOP;
END $columns$;
${[migration,service].flatMap(owner=>['',' IN SCHEMA public'].flatMap(scope=>[
    `ALTER DEFAULT PRIVILEGES FOR ROLE ${owner}${scope} REVOKE ALL ON TABLES FROM PUBLIC,${role};`,
    `ALTER DEFAULT PRIVILEGES FOR ROLE ${owner}${scope} REVOKE ALL ON SEQUENCES FROM PUBLIC,${role};`,
    `ALTER DEFAULT PRIVILEGES FOR ROLE ${owner}${scope} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC,${role};`,
])).join('\n')}
${revoked ? '' : `GRANT CONNECT ON DATABASE ${db} TO ${role};
GRANT USAGE ON SCHEMA public TO ${role};
${policy.tables.map(table=>`GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE public.${ident(table)} TO ${role};`).join('\n')}
${policy.sequences.map(sequence=>`GRANT USAGE,SELECT ON SEQUENCE public.${ident(sequence)} TO ${role};`).join('\n')}
ALTER ROLE ${role} LOGIN PASSWORD ${quote(passwordVerifier)};`}
COMMIT;`;
}

async function apply(options, revoked) {
    const identity=validateIdentity(options?.identity);
    const credentials=await privateJson(options.adminCredentialsPath);
    if (credentials.database !== identity.database || !identifier.test(credentials.user) || credentials.user===identity.loginRole ||
        typeof credentials.host!=='string' || !/^[a-zA-Z0-9_.:/-]{1,255}$/.test(credentials.host) ||
        !Number.isInteger(credentials.port) || credentials.port<1 || credentials.port>65535 ||
        typeof credentials.password!=='string' || !/^[\x21-\x7e]{1,1024}$/.test(credentials.password) ||
        !['disable','require','verify-ca','verify-full'].includes(credentials.sslmode)) throw new Error('Invalid private SQL admin credential');
    const passwordVerifier=revoked ? null : verifier((await privateJson(options.loginCredentialsPath)).password);
    await execute(statements(identity,passwordVerifier,revoked),credentials,options.psqlPath ?? 'psql',dirname(options.adminCredentialsPath));
    return {schemaVersion:1,installationId:identity.installationId,database:identity.database,databaseOid:identity.databaseOid,
        systemIdentifier:identity.systemIdentifier,loginRole:identity.loginRole,rolePolicySha256,state:revoked?'revoked':'enabled'};
}
export const provisionSqlRole = options => apply(options,false);
export const reconcileSqlRole = options => apply(options,false);
export const revokeSqlRole = options => apply(options,true);
