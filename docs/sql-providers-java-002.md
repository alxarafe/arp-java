# ARP-SQL-JAVA-002 — proveedores SQL

## Preparación y referencias

El árbol Java estaba limpio en `feature/arp-sql-java-001`, commit `2aa6426`.
Se actualizó `main` mediante fast-forward a `825012f7fb747c459f69be48c9a026cdaa1eb3e4`,
que incorpora la PR #9, y se creó `feature/arp-sql-java-002` desde ese punto.
La rama anterior tenía exactamente el mismo árbol que main: no había trabajo pendiente.
La rama remota ya no existía al intentar su eliminación; se eliminó la local y se podó
la referencia remota obsoleta. No se han creado commits, publicado cambios, abierto PR,
hecho merges de entrega, etiquetas ni releases.

Referencia técnica obligatoria: `arp-dotnet/origin/main`,
`3b74085244f9d44f2c949a884d96727f30b33235`, con PR #10 y #11 fusionadas.
Se trasladan sus consultas, restricciones de visibilidad y protecciones físicas;
no se repiten sus investigaciones de tipos o bloqueos.

Autoridad funcional y pins conservados:

- `contract.ref`: `7a62bba0e8f84dbc198e837baf63fc7e0d8722e1`.
- `listget-contract.ref`: `eb38cb96e6ccd218734df2a181908de46067e0c7`.
- Fixtures y esquemas se leen de los checkouts independientes, sin copiar ni modificar el contrato.

## Arquitectura y modificación imprescindible del lector común

```text
SqlRecordReader
├── PostgreSqlRecordReader
├── MySqlRecordReaderBase
│   ├── MySqlRecordReader
│   └── MariaDbRecordReader
└── SqlServerRecordReader
```

Los archivos existentes bajo `src/`, `ListGetHost`, `PhysicalCatalog` y todo el proveedor
PostgreSQL permanecen idénticos a main. No hay otro host, catálogo, algoritmo LIST/GET,
interfaz de dialecto ni sistema de permisos. Las clases nuevas usan las conexiones JDBC
registradas por la aplicación; cada llamada debe proporcionar una conexión nueva, abierta
y en autocommit. No se certifica reutilización de sesiones de un pool.

`SqlRecordReader` tiene dos cambios acotados, 10 líneas añadidas y 2 sustituidas:

1. Sobrecarga de `valueExpression` que recibe `ScalarType`; por defecto delega en la firma
   anterior. La consulta obtiene el tipo de las columnas y de las claves ocultas ya preparadas.
   SQL Server necesita distinguir BIT, DATE, DECIMAL y otras conversiones, como en .NET.
2. `ORDER BY` cualifica las claves con la relación física. JDBC decodifica por etiqueta,
   por lo que las conversiones llevan alias con el nombre original. Sin cualificación,
   el motor podía ordenar el alias convertido a texto: una clave 10 podía preceder a 2.
   Las regresiones reales de paginación detectaron el problema y verifican la corrección.

`prepare` y `open` siguen siendo finales. No cambia la secuencia de preparación,
protección DATA, reobservación física, lectura acotada, decodificación, adquisición GUARD,
commit DATA y publicación protegida. Tampoco cambian parámetros, restricciones de filas,
validación de escalares, tokens, autorizaciones, invalidación ni limpieza obligatoria.
La batería PostgreSQL completa comprueba la modificación de generación SQL compartida.

`SqlMetadata` proporciona únicamente consultas de metadatos parametrizadas y acotadas,
descriptores y límites enteros exactos. Los helpers físicos de cada motor comparten
sesiones/protección entre lector y observador; no ejecutan otro algoritmo de registros.
Las restricciones físicas no soportadas usan el `ReadFailure` existente con
`CAPABILITY_UNAVAILABLE`; los observadores las convierten en `ObservationException`.
Los fallos JDBC de conexión, ejecución o limpieza mantienen `READ_FAILED`, sin diagnósticos privados.

## MySQL y MariaDB

Toda la implementación está en `providers/mysql`: los dos lectores y observadores concretos
solo seleccionan la familia. `VERSION()` distingue MariaDB; no se infiere una familia por
el nombre registrado. Se usan backticks escapados, LIMIT parametrizado y comparación de
claves completas por tuplas. Solo se admiten tablas InnoDB ordinarias no particionadas.

Las consultas de `information_schema` trasladan columnas, PK, tipos nativos, nulabilidad,
identidad, generación, geometría decimal y longitudes. No se inventa integridad de índices
ni FKs no observados. CHECK impide afirmar un dominio escalar no demostrado; los tipos
no reconocidos no reciben una compatibilidad inventada. CHAR fijo no se declara portable.

Los valores se convierten a CHAR UTF-8 y JDBC los lee como texto. INT64 y DECIMAL nunca
pasan por coma flotante; la validación común normaliza sus cadenas exactas. La fixture
completa usa DECIMAL(65,18), sin cambiar los valores del contrato.
`TINYINT(1)` se observa como INT8, nunca como BOOLEAN físico. Una vinculación lógica
BOOLEAN explícita permite solo 0/1; el valor almacenado 2 provoca `ROW_DECODE_FAILED`
y descarta toda la página, incluso con la conversión automática del driver habilitada.

JDBC `setReadOnly` puede ser solo una indicación: se solicita realmente
`SET TRANSACTION READ ONLY` antes de desactivar autocommit, con READ_COMMITTED.
Una prueba real intenta UPDATE y exige rechazo. Las sesiones configuran `lock_wait_timeout`,
`innodb_lock_wait_timeout` y `max_execution_time` en MySQL o `max_statement_time` en MariaDB.
Los tiempos JDBC de consulta también están acotados.

SELECT LIMIT 0 retiene MDL durante la transacción. SHOW CREATE y las consultas de permisos
exigen SELECT directo global, de esquema o de tabla para demostrar autoridad completa.
SELECT de todas las columnas visibles no basta: la prueba añade una columna INVISIBLE,
rechaza al usuario con permisos por columna y acepta el permiso de tabla, observando
la columna invisible. La autoridad concedida exclusivamente por roles no está certificada.

## SQL Server

Se usan identificadores entre corchetes escapados y OFFSET 0 / FETCH NEXT parametrizado.
El cero es sintaxis fija; la paginación sigue siendo keyset, sin offset del cliente.
LIST genera una comparación lexicográfica OR de prefijos AND; GET exige igualdad de
cada componente. JDBC repite parámetros posicionales en el orden de cada predicado.
Las pruebas recorren PK simples y compuestas y verifican los valores y el acceso restringido.

BIT conserva su representación booleana; DATE usa estilo 23. Las demás conversiones
usan NVARCHAR, preservando Unicode, INT64 y los decimales exactos. Las longitudes físicas
se expresan en BYTES, como en .NET, sin atribuir capacidad de caracteres a bytes Unicode.
CHAR/NCHAR fijo no se declara portable. Los alias y tipos desconocidos no reciben pruebas
escalares inventadas.

SQL Server limita DECIMAL a 38 dígitos. Los distintos valores contractuales no caben todos
en una única geometría DECIMAL(38,s). Como en .NET, la fixture completa usa `sql_variant`
con subtipos reales DECIMAL/NUMERIC y vinculación lógica explícita, sin inventar una
prueba física DECIMAL para sql_variant. SQL_VARIANT_PROPERTY comprueba el subtipo:
texto y flotantes no se aceptan por parecer números. Se ejecuta además LIST y GET
sobre una columna nativa DECIMAL(38,18), con fracción exacta de 18 dígitos.

La observación usa sys.tables, sys.schemas, sys.columns, sys.types, restricciones e índices
PK habilitados. Exige SELECT del objeto y VIEW DEFINITION de la base de datos. Se rechazan
tablas con versionado temporal, de memoria, particionadas o con predicados RLS. Las pruebas crean
un usuario con SELECT sin VIEW DEFINITION y exigen rechazo; con visibilidad completa
se acepta. Una política RLS real se rechaza para el administrador y para ese usuario.

JDBC no proporciona transacciones SQL Server realmente read-only mediante setReadOnly.
El proveedor establece READ_COMMITTED y autocommit false, sin presentar esa indicación
como una garantía. Las consultas son SQL fijo de lectura. SET XACT_ABORT ON y
LOCK_TIMEOUT acotan la sesión, junto con el timeout JDBC.

Sch-S dura una sentencia y no protege todo el intento. Se usa SELECT TOP(1) con
TABLOCK,HOLDLOCK para retener un bloqueo S de tabla hasta terminar la transacción.
La guarda independiente adquiere la misma protección antes del commit DATA.
**Estos bloqueos compartidos pueden bloquear escrituras**, además de DDL que necesite
Sch-M, durante la lectura y hasta cerrar la guarda de publicación. Es un coste operativo
del perfil demostrado, sin reducir las garantías del núcleo. No se certifican RCSI ni SNAPSHOT.

## Pruebas y resultados

Los tests ordinarios no incorporan fuentes de integración, drivers ni conexiones:
`mvn test` no necesita Docker ni una base de datos. Se han ejecutado 455 tests sin fallos
en un contenedor sin red, sin socket Docker y sin variables de base de datos. Maven no
está instalado en el host; el contenedor es el entorno usado para verificarlos, no una
exigencia del proyecto. También pasa la compilación `-Psql-reader` offline.

Los perfiles `mysql-provider`, `mariadb-provider` y `sqlserver-provider` añaden por separado
las fuentes opcionales y las pruebas compartidas. Los drivers solo tienen scope test:
MySQL Connector/J 9.4.0, MariaDB Connector/J 3.5.6 y Microsoft JDBC 13.2.1.jre11.
PostgreSQL conserva su perfil y el driver 42.7.8. Entorno: Maven 3.9 / Temurin Java 25, Linux.

| Motor / imagen comprobada | Integración | Ordinarios en el perfil | Envelopes validados |
| --- | ---: | ---: | ---: |
| MySQL 8.4 | 29, sin fallos | 455 | 184 |
| MariaDB 12.3 | 29, sin fallos | 455 | 184 |
| SQL Server 2022 Developer | 29, sin fallos | 455 | 185 |
| PostgreSQL 18.6 | 30 LIST/GET + 2 observación + 1 persistencia | 455 | 193 |

PostgreSQL ejecuta además la fase de recuperación de persistencia en otro contenedor,
con la misma información física recuperada y sin introspección. Los 746 envelopes
registrados validan contra el esquema LIST/GET del pin independiente.
Los 29 escenarios de cada motor nuevo incluyen:

- Fixtures originales completas: valores exactos, Unicode, nulos, fechas, PK simples/compuestas,
  lookahead, límites, GET público y opaco, filas ausentes y restringidas, proyecciones derivadas.
- Denegación previa a SQL, restricciones de filas, bindings de contexto/proyección/política,
  tampering, generación expirada y abortos sin salida ante invalidación o revocación.
- DDL externo bloqueado durante DATA y tras su commit, para LIST, GET y página vacía;
  el mismo DDL se permite al cerrar la guarda. DDL no invalidado provoca GENERATION_CHANGED.
- Fallos obligatorios de commit, cierre y rollback GUARD que descartan resultados completos,
  con comprobación de que todas las conexiones monitorizadas se cierran.
- Valores almacenados inválidos, metadatos incompletos y particularidades de cada motor
  indicadas arriba. Los vectores negativos nativos exigen cero consultas de filas.

El runner opcional reutiliza las imágenes locales fijadas por digest en
`providers/integration-sql-engines.py`. Crea redes y contenedores efímeros con etiqueta
y nonce propios, sin puertos publicados; comprueba el nonce antes del DDL de fixtures.
Solo elimina recursos propios y verifica que permanecen los contenedores y volúmenes
preexistentes. La espera MySQL/MariaDB exige TCP para no confundir el servidor temporal
de inicialización con el servidor listo para JDBC. Contratos montados en solo lectura.
Las copias, cachés Maven, resultados y evidencias quedan bajo `~/Desarrollo/tmp/`.

Reproducción ordinaria y por motor, desde el repositorio:

```bash
mvn -B -Darp.contract.path=/ruta/al/contrato-historico test
mvn -B -Psql-reader -Darp.contract.path=/ruta/al/contrato-historico package
python3 providers/integration-sql-engines.py \
    --engine mysql \
    --contract "$HOME/Desarrollo/tmp/arp-contract-php-config-001-validation" \
    --listget-contract "$HOME/Desarrollo/tmp/arp-java-listget-001-contract" \
    --output "$HOME/Desarrollo/tmp/arp-sql-java-002-nueva-ejecucion"
```

Cambiar `mysql` por `mariadb` o `sqlserver`, o usar `all` para ejecución secuencial.
`--maven-cache` permite copiar una caché existente sin alterar la original.
La regresión PostgreSQL se mantiene en `providers/integration-docker.py`, con los mismos
argumentos contract/output, sin ejecutarla simultáneamente con el otro runner.

## Matriz de 88 vectores y límites

La matriz completa y sus evidencias PostgreSQL permanecen en
[sql-refactor-java-001.md](sql-refactor-java-001.md#matriz-de-88-vectores-y-excepciones):
**24 directos, 58 equivalentes y 6 pendientes**. No se reclasifica ningún vector ni se
atribuye automáticamente esa cobertura íntegra a los tres motores nuevos. Estos tienen
las regresiones representativas reales descritas arriba; PostgreSQL conserva toda su matriz.
El hash de las fixtures y las 88 entradas de la evidencia previa se han comprobado.

Se conservan los seis pendientes:

1. `stale-catalog-generation`: no se aceptan snapshots externos obsoletos como entrada.
2. `unexpected-null-nonnullable`: pendiente la inyección de contradicción a NOT NULL.
3. `duplicate-record-identity`: pendiente la inyección de duplicados contra una PK física.
4. `invalid-server-limit-registration`: el rechazo al construir Target no se cuenta como el envelope del vector.
5. `modules-reports-without-catalog`: composición general de dependencias a cargo de la aplicación.
6. `modules-undeclared-projection-dependency`: no se incorpora un parser general de manifiestos.

No se certifican otros drivers/versiones/SO, sesiones recicladas de pools, autoridad MySQL
solo por roles, SQL Server RCSI/SNAPSHOT, vistas, formas avanzadas de almacenamiento,
snapshots estables entre páginas con datos cambiantes ni invalidación distribuida.
No se introduce vigilancia externa de DDL. La lectura protegida reobserva los hechos físicos.
La persistencia de los motores nuevos no se declara comprobada: solo se reejecuta la
implementación PostgreSQL existente. No se añaden contratos o capacidades fuera de alcance.

## Evidencias de entrega

Directorio: `~/Desarrollo/tmp/arp-sql-java-002/`.

- `acceptance/{mysql,mariadb,sqlserver}.log` y `summary.json`: aceptación final por motor y aislamiento.
- `final-postgresql/java.log`, `java-isolation.json`: batería y recuperación PostgreSQL.
- `ordinary/{ordinary,sql-reader}.log`: ejecución offline y compilación común.
- `response-validation.json` y `*-responses.json`: resultados contra el esquema contractual.
- `vector-coverage.json`: matriz conservada, sin alterar sus seis pendientes.
- `final-checks.json`, `source-sha256.json`, `format-equivalence.json`: estado y verificaciones de entrega.
- `full.diff`: diff completo frente a main, incluidos todos los archivos nuevos sin necesidad de staging.

Los intentos iniciales conservan sus logs; no se cuentan sus fallos como aceptación.
No hay formateador configurado en Maven. Se han comprobado UTF-8, LF, sangría,
longitudes y whitespace de los archivos nuevos, preservando los tokens y literales al
ajustar el formato. El único cambio posterior a las pruebas finales es documentación.