# ARP-SQL-JAVA-001 — Refactorización SQL genérica y PostgreSQL

## Estado y referencias

Rama normal `feature/arp-sql-java-001`, creada desde `origin/main` actualizado:
`03452cd75ce5ee79215881686b3601edcffbdc7a` (PR #8, incluida la corrección GET).
El estado inicial estaba limpio. Se conserva la rama anterior y no se descarta trabajo.
No se han creado commits, pushes, PR, merges, etiquetas ni versiones.

Referencia .NET: PR #10 y PR #11 fusionadas, main en
`3b74085244f9d44f2c949a884d96727f30b33235`. Se consultaron la implementación y el
informe de concurrencia; el lector SQL también se obtuvo de ese commit fusionado.
No se ha modificado el repositorio .NET ni Java/PHP ajenos a esta tarea.

Las referencias contractuales y el contrato se conservan:

- `contract.ref`: `7a62bba0e8f84dbc198e837baf63fc7e0d8722e1`.
- `listget-contract.ref`: `eb38cb96e6ccd218734df2a181908de46067e0c7`.
- El oráculo sigue siendo arp-contract; .NET es referencia arquitectónica.

## Árbol de clases y capas

```text
src/main/java/org/alxarafe/arp/
  dictionary/
    ListGetHost                        registro, autorización, tokens y publicación
    ReadCatalog                        puerto neutral del catálogo
    RecordReader                       prepare / open neutrales
    ReadAttempt                        rows / complete / close neutrales
    ReadPlan                           posición, operación y límite lógicos
      Prepared, Column, Row            valores inmutables sin JDBC
    ReadFailure                        diagnóstico sanitizado del almacenamiento
    ScalarValidator                    códec existente, sin cambios
  cds/physical/
    PhysicalCatalog                    catálogo neutral existente
    PhysicalObservationProvider        puerto de observación existente
    ObservationException               excepción neutral existente
    InvalidatablePhysicalProvider      invalidación existente
    FilePhysicalStore                  almacenamiento existente
    PhysicalStoreKey                    identidad existente
    PhysicalYaml                       serialización existente, sin vínculo PostgreSQL
    PersistedPhysicalProvider          decorador existente
providers/sql/src/main/java/org/alxarafe/arp/dictionary/
  abstract SqlRecordReader              una implementación común JDBC
    Connections                        fábrica JDBC, solo en esta capa
    SqlSource, SqlPrepared              vinculación privada con el lector creador
    private final Attempt              ciclo transaccional fijo
providers/postgresql/src/main/java/org/alxarafe/arp/
  dictionary/PostgreSqlRecordReader extends SqlRecordReader
  cds/physical/PostgreSqlObservationProvider
providers/postgresql/src/main/resources/postgresql.sql
```

Se mantiene el empaquetado Maven por perfiles existente, sin convertir todo el
repositorio a un reactor multimódulo ni introducir JPMS:

- Sin perfil: núcleo, puertos, catálogo y almacenamiento genérico. Sin JDBC/proveedor.
- `-Psql-reader`: añade solo la infraestructura JDBC común; salida en providers/sql/target.
- `-Ppostgresql-provider`: añade infraestructura SQL, especialización PostgreSQL,
  observación y pruebas reales; el driver continúa siendo una dependencia de test.

Son capas y conjuntos de fuentes opcionales del artefacto existente, no tres
artefactos Maven publicados independientemente. Las clases mantienen el paquete
Java existente para reutilizar ScalarValidator sin ampliar su API de escritura.
El JAR ordinario y el JAR SQL independiente se inspeccionaron: el primero no contiene
SqlRecordReader/PostgreSql; el segundo contiene SqlRecordReader y ninguna clase PostgreSql.
Jdeps no encuentra dependencia java.sql en el núcleo.

## Traslados, reutilización y cambios concretos

| Archivo/componente | Cambio |
| --- | --- |
| ListGetHost.java | Se traslada al núcleo; recibe ReadCatalog y RecordReader; conserva validación, autorización, proyecciones, AES-GCM, paginación lógica y respuestas. Corrige commit/finalización y fence final. |
| PhysicalCatalog.java | Se traslada al núcleo e implementa ReadCatalog; conserva resolveGate, publicación, invalidación, estado bloqueado y comparación de snapshot por identidad. |
| Otros siete componentes físicos genéricos | Se trasladan al núcleo sin cambiar sus contratos. PhysicalYaml crea directamente el mismo ObjectMapper exacto, eliminando su llamada a la fábrica PostgreSQL. |
| PostgreSqlReadExecutor.java | Se sustituye por SqlRecordReader + PostgreSqlRecordReader; desaparece su callback inseguro read(..., publish). |
| SqlRecordReader.java | Extrae preparación y compatibilidad física que estaban en el host, además del algoritmo SQL, parámetros, límites, códecs y recursos del ejecutor. Añade guarda superpuesta y cierre obligatorio. |
| PostgreSqlRecordReader.java | Conserva sintaxis y protección PostgreSQL; reutiliza PostgreSqlObservationProvider mediante observePhysical. |
| PostgreSqlObservationProvider y postgresql.sql | Sin cambios. Misma observación, datos exactos, metadatos y autoridad comprobada. |
| ScalarValidator/CdsReadResolver/CdsReadPublication | Sin cambios. Sin códecs, contador de generaciones ni cachés paralelos. |
| pom.xml | Nuevo perfil SQL independiente; el perfil PostgreSQL añade también las fuentes comunes. |
| ReadHostTest.java | Cuatro regresiones ordinarias mediante puertos neutrales, sin JDBC. |
| ListGetIntegrationTest.java | Conserva las 24 pruebas existentes y añade seis regresiones de ciclo/publicación/herencia. |

El registro de aplicación debe construir ahora PostgreSqlRecordReader en lugar de
PostgreSqlReadExecutor. ListGetHost conserva su paquete y forma de uso, pero sus
parámetros son interfaces neutrales. Esto requiere recompilar integraciones Java;
no se promete compatibilidad binaria con la firma anterior. No se conserva una fachada
con el callback antiguo porque permitiría aceptar respuestas antes del commit.

## Puertos mínimos

```java
interface ReadCatalog {
    CdsReadResult resolve();
    Optional<CdsReadResult> current();
    long generation();
    <T> T publishRead(CdsReadResult expected, Supplier<T> publication);
}
interface RecordReader {
    ReadPlan.Prepared prepare(CdsReadResult snapshot, String projection,
                              Map<String, Object> equalities);
    ReadAttempt open(ReadPlan plan, BooleanSupplier current);
}
interface ReadAttempt extends AutoCloseable {
    List<ReadPlan.Row> rows();
    void complete();
    void close();
}
```

Prepared contiene columnas, PK tipada y restricciones normalizadas; su derivado SQL
retiene en privado la observación y el lector propietario. No se expone una conexión,
transacción o sintaxis SQL por los puertos. Las filas y sus valores están congelados,
incluidos mapas con nulos. La generación se obtiene dentro de publishRead, sin depender
de synchronized sobre la instancia de una interfaz suministrada por otra aplicación.

## Comportamiento común y especialización

En SqlRecordReader son final `prepare` y `open`. La construcción/ejecución de SELECT
está en readRows privado: columnas públicas y claves internas explícitas, identificadores
escapados, valores/PK/continuación/límite parametrizados, restricciones anteriores a
ORDER BY y al límite, orden ascendente por PK completa. Decode es privado y exige un
lote acotado incluso si un proveedor ignorara el límite SQL. Las referencias nunca
entran en JDBC: el host las valida y convierte a posiciones lógicas.

| Punto protegido | Implementación común / PostgreSQL |
| --- | --- |
| identifierStart, identifierEnd, fromModifier | Delimitadores por defecto de identificador SQL; PostgreSQL configura ONLY en el constructor. |
| supportsColumn | Compatibilidad de códecs observados; PostgreSQL rechaza CHAR de padding no demostrado. |
| beginTransaction | JDBC READ_COMMITTED, readOnly, autoCommit=false; PostgreSQL hereda. |
| initializeSession | Punto común sin configuración obligatoria de motor; PostgreSQL fija lock_timeout y DateStyle ISO,YMD. |
| limitClause | Punto de sintaxis; PostgreSQL conserva LIMIT ?. El valor por defecto FETCH FIRST no implica soporte de motores no probados. |
| keyComparison | Tupla completa para igualdad GET o posición LIST; PostgreSQL hereda lo ya probado. |
| valueExpression / portableValue | JDBC exacto: BigDecimal a texto plano, BOOLEAN tipado y texto/DATE; PostgreSQL hereda. |
| acquireReadProtection | Abstracto, sin fingir una protección portable; PostgreSQL obtiene ACCESS SHARE y comprueba tabla ordinaria sin herencia/partición. |
| observePhysical | Abstracto; PostgreSQL invoca el observador existente con una conexión nueva. |

Attempt es privado/final: una especialización no puede reemplazar complete/close ni
la secuencia de apertura. Sus métodos no autorizan ni publican. Identificadores solo
desde metadatos confiables; valores siempre mediante PreparedStatement. No se añade
QuoteString, ORM, sistema de permisos, dialecto por composición ni otro motor.

## Problema real y ciclo de concurrencia

Antes, PostgreSqlReadExecutor.read llamaba publish.apply y después connection.commit.
La publicación salía de los monitores del host/catálogo antes del commit. Una revocación
o invalidación reentrante en el commit no tenía comprobación posterior. La única
transacción física terminaba con ese commit, sin guarda independiente de publicación.

La corrección sigue el orden demostrado en .NET, adaptado a JDBC:

1. Monitor del host: registro, operación y concesión inicial de campos; captura de época.
2. Resolve/prepare sin retener el monitor del host durante E/S. El catálogo conserva
   sus mecanismos de generación y publicación existentes.
3. current comprueba brevemente host/catálogo. JDBC abre una conexión nueva ya conectada,
   con autoCommit=true. Se exige esa precondición y comienza READ_COMMITTED/readOnly.
4. La transacción DATA obtiene ACCESS SHARE y prueba almacenamiento soportado. Un
   observador fresco confirma exactamente la observación, mientras DATA mantiene el bloqueo.
5. SELECT parametrizado y decodificación completa/acotada, sin monitores de publicación
   retenidos durante consultas o esperas de base de datos.
6. Antes de liberar DATA, otra conexión/transacción GUARD obtiene la misma protección.
   La guarda queda en el Attempt privado. Si DDL en espera impide adquirirla, se aborta.
7. Monitor del host y después fence del catálogo: registro, operación, campos,
   restricciones, contexto y época deben seguir vigentes. Se materializa la respuesta
   privadamente, sin devolverla al llamante.
8. complete hace commit obligatorio de DATA dentro de esos fences. GUARD sigue activa.
9. Se repiten autorización/época/registro e identidad del snapshot. La comprobación
   final satisfactoria es el punto de aceptación de la lectura.
10. Se liberan los monitores y try-with-resources ejecuta close obligatorio: rollback
    de DATA si no se completó, cierre DATA, rollback GUARD explícito y cierre GUARD.
    Se intentan todos los recursos aunque falle otro. Solo después puede invoke devolver éxito.

Un fallo de commit o cierre/rollback obligatorio devuelve READ_FAILED sin output.
Una invalidación o revocación reentrante durante commit se detecta en el paso 9.
Una invalidación de otro hilo anterior a la aceptación impide publicar; una posterior
está ordenada después de esta lectura. GET de referencia desconocida sigue usando
el fence local sin ejecutar un SELECT de filas ni adquirir una guarda innecesaria.

No hay intervalo sin protección entre el commit DATA y la aceptación. DDL puede
completarse legítimamente después de liberar GUARD: la aceptación ya ha ocurrido.
No se promete atomicidad de entrega de red ni vigencia perpetua de una respuesta.
ACCESS SHARE protege los DDL incompatibles de tabla demostrados (ACCESS EXCLUSIVE),
no congela todos los catálogos, estadísticas o índices concurrentes de PostgreSQL.

## Evidencia de regresión y resultados

Java 25.0.4.1 Temurin, PostgreSQL 18.6 y JDBC 42.7.8, con la imagen PostgreSQL que ya
estaba instalada, fijada por digest. No se descargó ningún otro motor.

- **455 pruebas ordinarias correctas**, 0 fallos/errores/omitidas, también con Maven
  offline y --network none, sin variables DB ni socket Docker en el proceso de pruebas.
  Incluyen las 451 anteriores y cuatro nuevas de los puertos/finalización neutrales.
- Build ordinario y build `-Psql-reader` correctos sin PostgreSQL.
- **488 pruebas correctas** en la integración completa: 455 ordinarias, dos tests
  PostgreSQL de metadatos, uno de persistencia y 30 casos dinámicos LIST/GET.
  Se conserva toda la batería anterior; se añaden seis casos de publicación/ciclo/herencia.
- Recuperación en otro contenedor: un test de persistencia correcto, mismo volumen/YAML
  y cero introspección. Contenedores/volúmenes previos y YAML originales preservados.
- Los casos nuevos ejecutan seis ALTER TABLE después de commit DATA (simple/compuesta
  x LIST/GET/vacía): todos reciben SQLSTATE 55P03. El mismo ALTER funciona tras invoke.
- Commit fallido, cierre DATA, rollback GUARD y cierre GUARD fallidos descartan la salida
  y dejan cero conexiones monitorizadas abiertas; invalidación y revocación en commit
  no devuelven éxito. Se demuestra recuperación posterior.
- DDL realmente en espera, confirmado por pg_stat_activity: la entrega de guarda con
  timeout de un segundo devuelve READ_FAILED, libera DATA, permite acabar DDL y reconstruir.
- Descendiente PostgreSQL sin overrides ejecuta el lector común; prepare/open son final
  y un plan preparado por otra instancia se rechaza.
- **193 respuestas** validan contra el esquema LIST/GET fijado. Las 147 respuestas
  anteriores están contenidas en los resultados actuales, normalizando exclusivamente
  referencias/continuaciones aleatorias; ningún otro campo cambia en esa comparación.

Prueba negativa reproducible: en una copia auxiliar se elimina solo la adquisición
GUARD y se ejecuta publicationLifecycle (cuatro casos en ese punto). Los casos de
DDL fallan porque ALTER TABLE atraviesa el intervalo tras commit; las comprobaciones
de rollback obligatorio también detectan que la guarda falta. No es una medición
sobre código de otro lenguaje ni una modificación permanente de la rama.

La integración completa se ejecutó antes del ajuste final de saltos de línea; se
comprobó igualdad de todos los tokens Java, preservando literales, y se recompilaron
núcleo/SQL y ejecutaron pruebas ordinarias después. No se repitió la batería de base
de datos por un cambio exclusivamente de espacios. No hay formateador Maven configurado;
se comprueban indentación, LF/UTF-8, longitud y whitespace de los archivos nuevos.

## Matriz de 88 vectores y excepciones

Se conserva la matriz previa: **N=24 directos, E=58 equivalentes, U=6 pendientes**.
Los alias P/R/O/A/M/D/X/F1..F6 corresponden a las mismas pruebas PostgreSQL conservadas.
Las nuevas regresiones refuerzan la secuencia de publicación sin convertir pendientes
en aprobados. Los 24 directos son replays negativos de petición/respuesta y comprueban
cero SELECT de filas. Los equivalentes ejecutan PostgreSQL real y tokens emitidos por
el host; no se exige reproducir los tokens fijos del oráculo. No se declara conformidad completa.

1. **stale-catalog-generation**: el host resuelve su catálogo actual; snapshots externos
   obsoletos no son una entrada soportada. Sí se comprueba invalidación concurrente real.
2. **unexpected-null-nonnullable**: NOT NULL impide la contradicción física; la inyección
   concreta de un proveedor que entregue ese nulo sigue pendiente.
3. **duplicate-record-identity**: la PK impide duplicados; no se ha ejecutado su inyección
   mediante un proveedor defectuoso. Las pruebas de fallos de commit no cubren ese caso.
4. **invalid-server-limit-registration**: Target rechaza límites inválidos al construirse,
   no mediante el envelope de invocación del vector; no se declara esa equivalencia.
5. **modules-reports-without-catalog**: validación de dependencias de la composición
   a cargo de la aplicación, fuera del perfil de composición válida recibido por el host.
6. **modules-undeclared-projection-dependency**: validación general de manifiestos y
   dependencias requiere esa integración de aplicación; no se añade un framework modular.

Estas obligaciones pendientes no demuestran un incumplimiento del perfil soportado,
pero tampoco se cuentan como aprobadas. No se amplían tipos, motores ni capacidades.

| Vector | Clasificación conservada | Evidencia |
| --- | --- | --- |
| get-authorized-hidden-key | E | P |
| get-null-preserved | E | P |
| get-historical-rating | E | P |
| get-exact-decimal | E | P |
| get-unicode-and-empty-values | E | P |
| get-unknown-reference | E | O |
| get-existing-but-row-denied | E | R |
| get-scope-binding-denied | E | F4 |
| get-principal-binding-denied | E | F4 |
| get-policy-binding-denied | E | F4 |
| get-projection-binding-denied | E | F4 |
| get-simple-typed-key | E | P |
| get-composite-typed-key | E | P |
| get-missing-typed-key | E | R |
| get-raw-hidden-key-rejected | N | F1 |
| key-null | N | F1 |
| key-incomplete | N | F1 |
| key-reordered | N | F1 |
| key-extra | N | F1 |
| key-number-not-string | N | F1 |
| key-wrong-type | N | F1 |
| key-out-of-range | N | F1 |
| list-first-page | E | P |
| list-second-page | E | P |
| list-last-page | E | P |
| list-server-clamps-limit | E | P |
| list-empty-authorized-set | E | F3 |
| list-numeric-order-simple-keys | E | P |
| list-derived-projection | E | F3 |
| list-limit-zero | N | F1 |
| list-limit-negative | N | F1 |
| list-limit-bool | N | F1 |
| list-limit-fraction | N | F1 |
| list-limit-too-large | N | F1 |
| list-limit-null | N | F1 |
| list-arbitrary-filter | N | F1 |
| list-arbitrary-sort | N | F1 |
| list-fields-injection | N | F1 |
| list-offset-rejected | N | F1 |
| cursor-unknown | N | F1 |
| cursor-null | N | F1 |
| cursor-limit-changed | E | O |
| cursor-context-changed | E | F4 |
| operation-not-authorized | E | A |
| target-not-registered | E | A |
| module-disabled | E | A |
| operation-not-enabled | E | A |
| derived-operation-not-exposed | E | R |
| selected-field-not-readable | E | A |
| client-context-injection | N | F1 |
| client-context-cannot-authorize | N | F1 |
| input-null | N | F1 |
| read-capability-not-proven | E | A |
| table-without-key | E | F6 |
| invalid-definition | E | D |
| unauthorized-definition-error-concealed | E | F2 |
| physical-incompatibility | E | F6 |
| stale-catalog-generation | U | - |
| stale-record-reference | E | O |
| stale-continuation | E | O |
| invalidation-during-read | E | F5 |
| authorization-lost-before-publication | E | F4 |
| provider-failure | E | D |
| last-row-decode-failure-no-partial-page | E | X |
| unexpected-null-nonnullable | U | - |
| duplicate-record-identity | U | - |
| new-column-not-published-after-rebuild | E | D |
| removed-published-column-rebuild-rejected | E | D |
| public-application-without-principal | E | P |
| empty-get-still-checks-publication-generation | E | F3 |
| context-changed-before-publication | E | F4 |
| list-limit-one | E | F4 |
| list-after-last-empty | E | F3 |
| invalid-server-limit-registration | U | - |
| modules-catalog-list | E | P |
| modules-catalog-get-denied-independently | E | A |
| modules-catalog-get-authorized | E | P |
| modules-reports-derived-list | E | F3 |
| modules-reports-get-unavailable | E | R |
| modules-reports-inactive | E | A |
| modules-all-inactive | E | A |
| modules-reports-without-catalog | U | - |
| modules-undeclared-projection-dependency | U | - |
| modules-deactivation-during-read | E | M |
| modules-row-restriction-still-authoritative | E | F3 |
| modules-client-cannot-activate | N | F1 |
| modules-field-access-still-authoritative | E | A |
| modules-permission-revoked-before-publication | E | M |

## Comparación con .NET y límites restantes

La separación neutral y el algoritmo fijo por herencia son equivalentes. Ambas
implementaciones mantienen una guarda física superpuesta durante commit y aceptación,
con comprobación reentrante posterior y finalización obligatoria antes de devolver éxito.
Java utiliza try-with-resources y SQLException; JDBC close puede hacer rollback implícito,
por lo que GUARD se revierte explícitamente y se conserva su error. La fábrica JDBC
entrega conexiones nuevas ya abiertas, a diferencia de DbConnection cerrado en .NET.
No se traduce el mecanismo de Dispose ni se introduce una segunda transacción sobre
la misma conexión JDBC, que no proporcionaría una guarda independiente.

Costes/limitaciones conocidos:

- Cada lectura SQL añade una adquisición/transacción GUARD. Se necesitan dos conexiones
  simultáneas, como ya ocurría durante la observación; el coste de la guarda es adicional.
- DDL exclusivo ya en cola puede causar READ_FAILED por timeout en la entrega de guarda.
  Es un coste de disponibilidad probado, sin publicación parcial ni espera ilimitada
  en la configuración demostrada.
- Solo se demuestra PostgreSQL 18.6, ese driver y conexiones nuevas; no otros motores,
  configuraciones de pooling, acciones administrativas globales ni coordinación distribuida.
- Paginación determinista con datos estables; no se añade snapshot compartido entre páginas.
- El almacenamiento y su serialización se trasladan, no se crea otro caché ni formato.
- El empaquetado sigue basado en perfiles Maven; no hay artefactos/JPMS nuevos publicados.
- Las seis excepciones contractuales anteriores permanecen documentadas.

## Evidencias y reproducción

Todo el trabajo auxiliar está en `~/Desarrollo/tmp/arp-sql-java-001/`:

- `initial-state.json`, `tested-source-sha256.json`, `final-source-sha256.json`.
- `integration-final/java.log`, `java-isolation.json`, `listget-responses.json`.
- `ordinary-and-sql-build.log`, `ordinary-offline.log`, JAR y surefire-reports bajo ordinary/.
- `red-no-publication-guard/`: copia controlada, runner.log y evidence/java.log negativos.
- `response-validation.json`, `vector-coverage.json`, `format-token-equivalence.json`.
- `final-checks.json`, `final.diff`: comprobaciones y parche completo, incluidos archivos nuevos.

```sh
mvn -B -Darp.contract.path=/ruta/al/contrato-historico test
mvn -B -Psql-reader -Darp.contract.path=/ruta/al/contrato-historico package
python3 providers/integration-docker.py \
  --contract "$HOME/Desarrollo/tmp/arp-contract-php-config-001-validation" \
  --listget-contract "$HOME/Desarrollo/tmp/arp-java-listget-001-contract" \
  --output "$HOME/Desarrollo/tmp/arp-sql-java-001/otra-integracion"
```

La integración es opcional. Las pruebas ordinarias no activan el perfil PostgreSQL,
no arrancan Docker, no descargan motores y no usan conexiones externas. Para probar
Maven offline se requiere haber restaurado sus dependencias previamente. El runner
existente crea recursos aislados propios, comprueba nonce de propiedad y conserva los
recursos ajenos. No se copian fixtures al repositorio ni se versionan resultados Docker.