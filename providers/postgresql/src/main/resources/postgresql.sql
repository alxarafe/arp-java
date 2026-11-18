WITH config AS (
    SELECT CAST(@provider AS text) AS provider, CAST(@id AS text) AS id,
           CAST(@schema AS text) AS schema, CAST(@table AS text) AS name
), target AS (
    SELECT c.oid, c.relkind, x.* FROM config x
    JOIN pg_catalog.pg_namespace n ON n.nspname = x.schema
    JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid AND c.relname = x.name
    WHERE c.relkind IN ('r', 'p')
), columns AS (
    SELECT a.attnum, a.attname, jsonb_strip_nulls(jsonb_build_object(
        'name', a.attname, 'nativeType', pg_catalog.format_type(a.atttypid, a.atttypmod),
        'ordinal', a.attnum, 'identity', a.attidentity <> '', 'generated', a.attgenerated <> '',
        'nullable', CASE WHEN a.attnotnull THEN false WHEN ty.typtype <> 'd' AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_constraint ck WHERE ck.conrelid = t.oid AND ck.contype = 'c'
            AND (ck.conkey IS NULL OR cardinality(ck.conkey) = 0 OR a.attnum = ANY(ck.conkey))) THEN true END,
        'scalarType', CASE WHEN ns.nspname = 'pg_catalog' AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_constraint ck WHERE ck.conrelid = t.oid AND ck.contype = 'c'
            AND (ck.conkey IS NULL OR cardinality(ck.conkey) = 0 OR a.attnum = ANY(ck.conkey))) THEN CASE ty.typname
            WHEN 'int2' THEN 'INT16' WHEN 'int4' THEN 'INT32' WHEN 'int8' THEN 'INT64'
            WHEN 'varchar' THEN 'STRING' WHEN 'bpchar' THEN 'STRING' WHEN 'text' THEN 'TEXT'
            WHEN 'bool' THEN 'BOOLEAN' WHEN 'numeric' THEN 'DECIMAL' WHEN 'date' THEN 'DATE' END END,
        'integerRange', CASE WHEN ns.nspname = 'pg_catalog' AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_constraint ck WHERE ck.conrelid = t.oid AND ck.contype = 'c'
            AND (ck.conkey IS NULL OR cardinality(ck.conkey) = 0 OR a.attnum = ANY(ck.conkey))) THEN CASE ty.typname
            WHEN 'int2' THEN jsonb_build_object('minimum', '-32768', 'maximum', '32767')
            WHEN 'int4' THEN jsonb_build_object('minimum', '-2147483648', 'maximum', '2147483647')
            WHEN 'int8' THEN jsonb_build_object('minimum', '-9223372036854775808', 'maximum', '9223372036854775807')
            END END,
        'characterLength', CASE WHEN ns.nspname = 'pg_catalog' AND ty.typname IN ('varchar','bpchar')
            AND a.atttypmod > 4 THEN jsonb_build_object('value', a.atttypmod - 4, 'unit', 'CHARACTERS') END
    )) AS descriptor
    FROM target t JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid
    JOIN pg_catalog.pg_type ty ON ty.oid = a.atttypid
    JOIN pg_catalog.pg_namespace ns ON ns.oid = ty.typnamespace
    WHERE a.attnum > 0 AND NOT a.attisdropped
), keys AS (
    SELECT COALESCE(jsonb_agg(a.attname ORDER BY k.ord), '[]'::jsonb) AS primary_key
    FROM target t JOIN pg_catalog.pg_constraint c ON c.conrelid = t.oid AND c.contype = 'p'
    CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
    JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
), indexes AS (
    SELECT i.indexrelid, ic.relname, i.indisunique, i.indpred IS NULL AND i.indisvalid AND i.indisready
        AND NOT (0 = ANY(i.indkey)) AS representable,
        (SELECT jsonb_agg(a.attname ORDER BY k.ord)
         FROM unnest(i.indkey) WITH ORDINALITY k(attnum, ord)
         JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
         WHERE k.ord <= i.indnkeyatts) AS names
    FROM target t JOIN pg_catalog.pg_index i ON i.indrelid = t.oid
    JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
), foreign_keys AS (
    SELECT c.conname, jsonb_build_object('name', c.conname,
        'columns', (SELECT jsonb_agg(a.attname ORDER BY k.ord)
            FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
            JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum),
        'target', jsonb_build_object('catalog', current_database(), 'schema', ns.nspname, 'table', tc.relname,
            'columns', (SELECT jsonb_agg(a.attname ORDER BY k.ord)
                FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum))) AS descriptor
    FROM target t JOIN pg_catalog.pg_constraint c ON c.conrelid = t.oid AND c.contype = 'f'
    JOIN pg_catalog.pg_class tc ON tc.oid = c.confrelid
    JOIN pg_catalog.pg_namespace ns ON ns.oid = tc.relnamespace
)
SELECT jsonb_strip_nulls(jsonb_build_object(
    'apiVersion', 'arp.physical/v1', 'id', t.id, 'kind', 'table',
    'source', jsonb_build_object('provider', t.provider, 'dialect', 'postgresql', 'catalog', current_database(),
        'schema', t.schema, 'name', t.name, 'engineVersion', current_setting('server_version')),
    'columns', (SELECT jsonb_agg(descriptor ORDER BY attnum) FROM columns),
    'primaryKey', (SELECT primary_key FROM keys),
    'indexes', CASE WHEN NOT EXISTS (SELECT 1 FROM indexes WHERE NOT representable) THEN
        (SELECT COALESCE(jsonb_agg(jsonb_build_object('name', relname, 'columns', names, 'unique', indisunique)
            ORDER BY relname COLLATE "C"), '[]'::jsonb) FROM indexes) END,
    'foreignKeys', (SELECT COALESCE(jsonb_agg(descriptor ORDER BY conname COLLATE "C"), '[]'::jsonb) FROM foreign_keys)
))::text FROM target t