# ARP-CDS-READ-003 — Auditoría de cierre

**Dictamen actualizado:** proveedor físico con persistencia YAML opcional, recuperación y regeneración verificadas.
Se completa el ciclo local para tablas del subconjunto CDS-READ-001; no se amplía el contrato a vistas o filas.
La ausencia de persistencia señalada en la auditoría anterior queda corregida por los cambios de esta revisión.
Referencia intacta: `arp-contract/main`, `7a62bba0e8f84dbc198e837baf63fc7e0d8722e1`.

## Implementado y verificado

**Java — PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)**. Adaptador JDBC en perfil Maven opcional,
sin driver obligatorio en el artefacto puro.

- Introspección real, identificadores físicos/lógicos separados, `arp.physical/v1` validado por el resolver antes
  de publicar. No se copian las observaciones ilustrativas como autoridad de la BD.
- Construcción sin I/O de BD; primera resolución observa solo los proveedores de tablas registrados/activos.
  No se exploran todas las tablas del servidor al arrancar. La observación está limitada por schema/tabla.
- Generación completa e inmutable; invalidar descarta físico, modelo efectivo y proyecciones compiladas de la sesión.
  Siguiente uso regenera; un error o una invalidación durante el cálculo impide publicar parcial/obsoleto.
- Tipos nativos SQL preservados; el diccionario y las restricciones del desarrollador definen los tipos lógicos.
  Enteros exactos sin float; desconocido se omite o queda `not-verified`, no se anuncia una compatibilidad nominal.
- Los tres resultados de compatibilidad están comprobados: intervalo admisible, dimensiones/CHECK no verificados,
  e incompatibilidad real al reducir INT a SMALLINT con un binding lógico INT32 explícito en una copia de prueba.
  Esa regresión comprueba `physical-incompatible`/`CDS_READ_DOMAIN_NOT_REPRESENTABLE`, sin recortar el dominio.
- Columnas nuevas llegan al modelo efectivo y no a la proyección pública. Se conservan las definiciones autoradas;
  además de comparar snapshots, se contrastan SHA-256 de los YAML declarativos originales antes/después de las BD.
  El checkout contractual se monta en solo lectura. No se reescriben YAML del desarrollador.
- El payload físico no contiene contraseñas, DSN, usuario de conexión ni trazas. Las credenciales se toman de la
  fábrica del anfitrión; los tests usan credenciales aleatorias de contenedores propios, no secretos de aplicaciones.

## Persistencia física implementada y verificada

| Capacidad | Estado real |
| --- | --- |
| Descriptor procedente de introspección real | Implementado, proveedor existente reutilizado |
| YAML físico determinista `arp.physical/v1` | Implementado, YAML 1.2 flow aceptado por el parser CDS existente |
| Recuperación semántica exacta y validación de esquema/identidad | Verificada antes de publicar |
| Almacén externo configurable, separado por ámbitos/conexiones/objetos | Implementado, claves opacas sin DSN |
| Escritura temporal, flush y sustitución atómica local | Implementada; no se recurre a una copia no atómica |
| Invalidación durable y bloqueo ante fallo de invalidación | Verificados con errores reales de permisos |
| Recuperación entre procesos y contenedores | Verificada con mismo volumen y cero introspecciones |
| Vistas, vigilancia DDL, coherencia distribuida y ejecución LIST/GET | Fuera del subconjunto implementado |

`PersistedPhysicalProvider` decora al proveedor nativo; no duplica SQL ni reglas escalares. `PhysicalYaml`,
`FilePhysicalStore` y `PhysicalStoreKey` viven en el adaptador opcional. La biblioteca pura no cambia ni requiere
almacenamiento. El anfitrión elige una raíz existente, absoluta y externa, y declara sus raíces de código fuente.
No se impone jerarquía de carpetas. Hashes de ámbito, conexión y objeto impiden colisiones de nombres/literales;
las credenciales nunca son claves ni datos serializados. Los tipos nativos y los enteros exactos se conservan.

La lectura exige estado válido, checksum, identidad y esquema correctos. La invalidación cambia el token local
antes de eliminar el YAML; frena escrituras tardías y elimina los derivados de la generación del catálogo. Si el
almacén falla, no se publica parcialmente ni se recurre a una generación invalidada. Una corrupción falla la
solicitud; una posterior puede regenerar desde la BD. Los YAML lógicos/proyecciones permanecen intactos.

El volumen Docker se crea fuera de las copias de código. El runner de generación se destruye antes de crear el
de recuperación. Ambos usan el mismo volumen; el segundo verifica el checksum y que no invoca al proveedor.
Después de demostrarlo se eliminan exclusivamente volumen, contenedores y red propios, con etiquetas verificadas.
El informe externo conserva la prueba; los logs no se presentan como sustituto del YAML realmente recuperado.

**Límites explícitos:** pruebas en Linux/Docker y filesystem local controlado por el anfitrión. No se promete
resistencia a sustituciones de rutas hostiles, pérdida de alimentación, caída del daemon, ni coherencia distribuida.
Si el filesystem no permite registrar invalidación, se informa del fallo y se bloquea el catálogo actual: hay que
repararlo y repetir la invalidación antes de reiniciar confiando en la caché. No es posible acreditar invalidación
durable cuando el soporte no admite ninguna escritura. Los catálogos en memoria de otros procesos requieren
invalidación explícita por el anfitrión. Detalles de APIs y formato interno en [cds-read-003.md](cds-read-003.md).

## Controles de Git añadidos

- `.gitignore` ignora nombres reservados `*.physical.yaml`, `*.physical.yml`, `*.physical.json`.
- `providers/check-generated-physical.py --index` examina **blobs del índice**, no el working tree: detecta
  `apiVersion: arp.physical/...` incluso en JSON, archivos renombrados, claves escapadas, aliases o mapas anidados.
  Componer YAML no ejecuta tags ni convierte números. No imprime contenido ni errores con fragmentos sensibles.
- `.githooks/pre-commit` rechaza el índice si el control falla. Se activa con
  `git config --local core.hooksPath .githooks`; se conserva cualquier hook anterior en lugar de sobrescribirlo.
  `ARP_AUDIT_PYTHON` permite seleccionar el Python/venv de desarrollo. Requisito dev: `PyYAML==6.0.3`, ya empleado
  por el tooling contractual. No se añade ninguna dependencia al runtime ni al contrato.
- `.github/workflows/physical-artifacts.yml` prepara el mismo control sobre **todo HEAD**, no solo el diff.
  La ejecución remota y el estado de la CI se documentan en la PR de integración. Para impedir integración
  evitando hooks, el repositorio debe exigir el check `physical-artifact-policy` en su protección de rama.
  Esta auditoría no modifica las reglas remotas de GitHub ni afirma que ya sean obligatorias.
- 12 pruebas por stack crean repositorios Git desechables: verifican `git add -f`, working tree distinto del índice,
  renombrado, escapes, aliases, contenido anidado, nombres especiales, árbol completo y rechazo por pre-commit.
  La prueba de hook verifica que no llega a crearse un commit. No se crean commits en los runtimes.
- El control se instala exclusivamente en los tres runtimes. No se modifica ni se escanea como parte de su árbol
  el repositorio contractual externo; sus fixtures físicos diseñados para conformidad se conservan.

**Límite comprobable:** Git no ofrece un hook que impida ejecutar `git add -f` en sí. Ese comando sí puede añadir
un blob al índice, pero el control lo rechaza después. Un hook local puede omitirse con `--no-verify` o desactivarse;
la garantía de no integrar requiere CI obligatoria y revisión de cambios al propio control. No se promete una
protección absoluta basada solo en .gitignore/hooks. Referencias: [Git hooks][hooks], [protección de ramas][branches].

[hooks]: https://git-scm.com/docs/githooks
[branches]: https://docs.github.com/articles/about-protected-branches

## Repetición y evidencias

```sh
python3 -m venv /tmp/arp-physical-audit
/tmp/arp-physical-audit/bin/pip install -r providers/requirements-audit.txt
/tmp/arp-physical-audit/bin/python providers/tests/test_generated_physical_guard.py
/tmp/arp-physical-audit/bin/python providers/check-generated-physical.py --index
python3 providers/integration-docker.py --contract /path/to/clean/pinned/arp-contract --output /tmp/arp-audit-results
git diff --cached --check
```

Para PHP, construir primero la imagen de tests indicada en `cds-read-003.md`. Los cinco escenarios contractuales
se ejecutan sin alterar sus expectativas. Hay nueve grupos adicionales, separados de los fixtures: claves/índices,
índices no representables, tipos conservadores/exactos, CHECK, nombres literales, permisos/visibilidad incompleta,
objetos ausentes/vistas/sentinel, fallo del segundo proveedor y estrechamiento incompatible real.

454 pruebas JUnit correctas con el perfil (451 previas, dos métodos de integración y un método de persistencia),
más una prueba de recuperación en otro contenedor. Ejecutan los cinco escenarios contractuales, nueve grupos
adicionales y los 15 grupos de persistencia. 12 pruebas del control Git correctas.

Los informes registran `preexistingContainersPreserved`, `logicalYamlUnchanged` y
`testLogPersistsAfterContainerRemoval`. La eliminación usa solo IDs creados por la prueba y su etiqueta única,
rechaza contenedores preexistentes, no publica puertos ni utiliza bases de datos de aplicaciones ajenas.
La comparación antes/después conserva IDs, estado, inicio y contador de reinicios de los contenedores ajenos.
No quedan contenedores/redes de los runs. La imagen de tests PHP es una caché local reutilizable.
Además se verifican 15 grupos de persistencia y una recuperación real en un segundo proceso/contenedor: mismo
volumen y checksum, cero introspecciones. Incluyen corrupción, permisos de lectura/escritura, escritura fallida,
fallo nativo tras invalidar, separación de identidades, invalidación fallida/durante generación, rutas y secretos.
Los informes añaden `physicalYamlRecoveredAfterContainerRecreation`, `recoveryWithoutIntrospection`,
`noGeneratedPhysicalYamlInSource` y `preexistingVolumesPreserved`; todos deben ser verdaderos.
No se extrapola a recuperación tras caída del daemon/host.

## Cierre y pendientes

Preparado para revisión en `feature/arp-cds-read-003`; commits y resultados remotos constan en la PR. Persistencia física opcional completada
para tablas verificables: generación, recuperación entre procesos/contenedores e invalidación/regeneración.
Pendiente operativo: publicar/revisar la CI y verificar/configurar `physical-artifact-policy` como obligatorio en
GitHub; no se afirma protección remota efectiva. Vistas quedan fuera del subconjunto aprobado y LIST/GET sigue
sin ejecutor por las cuatro decisiones mínimas documentadas. Sin conformidad normativa general, cambios
contractuales, releases, etiquetas ni publicaciones. Los YAML físicos de prueba permanecen fuera del árbol Git.

Comprobación adicional de la referencia, solo lectura: 106 pruebas Python y los seis verificadores contractuales
correctos. Evidencia estática/pura separada de las pruebas nativas con BD real.