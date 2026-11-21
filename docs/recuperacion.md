# Respaldo, restauración y vuelta atrás (IMP-09)

Cómo se respalda UvoStore, cómo se restaura sin repetir efectos que el POS o una pasarela ya hicieron, y cómo se decide la vuelta atrás de una versión. Cubre C10, C18 y C19 de `GO_LIVE_CHECKLIST.md` (§11.1, §11.2 y §13.10).

**Estado:** el mecanismo y su ensayo automático están listos (`ensayo-servidor` y `migracion-rollback` en CI). Ningún Cxx queda cerrado: falta repetirlo sobre staging real con este procedimiento, firmar el acta y que C01 apruebe el RPO/RTO.

## Objetivos (propuesta, los aprueba C01)

| | Propuesta | De dónde sale |
|---|---|---|
| **RPO** (cuánto se puede perder) | 5 minutos | WAL continuo con `archive_timeout=60`: con escrituras, se pierde como mucho el último minuto sin archivar. Los 5 minutos dan margen para un archivado lento. |
| **RTO** (cuánto tarda volver) | 2 horas | Restaurar y levantar (lo mide el acta técnica) más conciliar con pasarelas y POS, que depende de cuántos hallazgos haya. |

El ensayo de CI mide los dos (en `c10.txt` del artefacto `ensayo-servidor`), pero con una base de prueba diminuta. Los valores que valen son los del ensayo sobre staging real.

## Qué se respalda

| Qué | Cómo | Retención | Dónde |
|---|---|---|---|
| Clúster PostgreSQL (UvoStore staging y production, **y UvoPOS**: el clúster es compartido, ADR 0001) | pgBackRest: completo el domingo, diferencial de lunes a sábado (03:00), WAL continuo | 4 completos, unas 4 semanas de recuperación a cualquier punto | Bucket de respaldo, `pgbackrest/`, cifrado (aes-256-cbc) |
| Imágenes de cada ambiente | `aws s3 sync` diario (04:00), sin `--delete` | Versionado + 30 días para lo reemplazado o borrado | Bucket de respaldo, `imagenes/<ambiente>/` |
| Llaves y secretos | A mano, en el gestor de contraseñas del equipo | Mientras el ambiente exista | Ver «Custodia» |
| Configuración (`/etc/uvostore/*.env`) | Se reconstruye con `provisionar.sh` más los secretos del gestor | — | — |

El bucket de respaldo está en **otra región** que el de imágenes y usa credenciales propias. Su política le niega a todo el que no sea operaciones el borrado definitivo, el cambio de versionado y el de ciclo de vida, y le niega borrar imágenes (`ops/s3/politica-bucket-respaldo.json`). Ni un error de un script ni unas credenciales robadas del servidor pueden vaciarlo.

## Custodia

Lo que se guarda en el gestor de contraseñas del equipo, con **dos custodios nominales** (los nombra C01):

| Secreto | Por qué es imprescindible | Si se pierde |
|---|---|---|
| `APP_ENCRYPTION_KEY` de cada ambiente | Cifra las credenciales de pasarelas, transportistas, POS y Stripe guardadas en la base | Esas credenciales quedan ilegibles: hay que pedirlas de nuevo a cada tienda. No se puede rotar (`docs/configuracion-ambientes.md`) |
| `RESPALDO_CIPHER_PASS` | Cifra el repositorio de pgBackRest | **Los respaldos no se pueden leer** |
| `RESPALDO_S3_KEY` / `RESPALDO_S3_KEY_SECRET` | Acceso al bucket de respaldo | Se emiten otras desde la cuenta de AWS (operaciones) |
| Acceso de operaciones a la cuenta de AWS | Es el único que puede borrar del bucket de respaldo y cambiar sus políticas | — |

Reglas:
- Los demás secretos del archivo de entorno (`JWT_SECRET`, `PLATFORM_API_KEY`, contraseñas de la base…) se pueden generar de nuevo. Perderlos cierra las sesiones y obliga a reconfigurar UvoPOS, pero no pierde datos.
- **Prueba trimestral:** un custodio recupera `APP_ENCRYPTION_KEY` y `RESPALDO_CIPHER_PASS` del gestor y los usa en una restauración aislada (`restaurar.sh --llave-stdin`). La sonda de descifrado confirma que la llave es la correcta. Se anota en el acta.
- Nunca van en un ticket, un chat, un log, un acta ni un argumento de línea de comandos. `restaurar.sh` lee la llave por la entrada estándar.

## Instalación (una vez por servidor)

1. **AWS**, a mano y con la cuenta de operaciones:
   - Crear el bucket de respaldo en otra región, con «Block public access» completo y el versionado activado.
   - Aplicarle `ops/s3/politica-bucket-respaldo.json` y `ops/s3/ciclo-vida-versiones.json`.
   - Activar el versionado en el bucket de imágenes de cada ambiente y aplicarles `ciclo-vida-versiones.json`.
   - Crear un usuario IAM de respaldo con `ops/s3/politica-iam-respaldo.json`. Reemplazar los `@@…@@`: el ARN de operaciones es el del rol o usuario que administra la cuenta.
2. `sudo bash ops/respaldo/instalar-respaldo.sh`. La primera vez:
   - crea `/etc/uvostore/respaldo.env`;
   - genera `RESPALDO_CIPHER_PASS`: **copiarla al gestor en ese momento**;
   - se detiene hasta que se completen los `CAMBIAR_`.
3. Completar `respaldo.env` (bucket, región, credenciales de respaldo) y volver a correrlo. Se detiene otra vez (salida 3), porque activar `archive_mode` exige **reiniciar PostgreSQL**, y eso corta también a UvoPOS. Acordar la ventana con el equipo POS y, en ella:
   ```
   sudo systemctl restart postgresql && sudo bash ops/respaldo/instalar-respaldo.sh
   ```
   El script crea la stanza y corre `pgbackrest check`, que falla ahí mismo si el WAL no llega al bucket. También deja los timers.
4. El primer respaldo completo, a mano y mirándolo terminar:
   ```
   sudo systemctl start uvostore-respaldo-completo
   sudo -u postgres pgbackrest --stanza=uvopos info
   ```
5. **Métricas.** Instalar node_exporter con `--collector.textfile.directory=/var/lib/prometheus/node-exporter` y agregar el job `uvostore-respaldo` (`ops/monitoring/prometheus/scrape.example.yml`). Hasta el primer respaldo completo, la alerta `UvoStoreRespaldoAtrasado` sigue encendida, a propósito.

## Alertas

### respaldo-atrasado

Aplica a `UvoStoreRespaldoAtrasado`, `UvoStoreRepositorioRespaldoIlegible` y `UvoStoreRespaldoImagenesAtrasado`:
1. `systemctl status uvostore-respaldo-diferencial uvostore-respaldo-imagenes` y su `journalctl`.
2. `sudo -u postgres pgbackrest --stanza=uvopos info`. Si no lee el repositorio, revisar las credenciales del bucket, la red y que `RESPALDO_CIPHER_PASS` no haya cambiado.
3. Corregido, lanzar el respaldo a mano (`systemctl start uvostore-respaldo-diferencial`) y comprobar que la alerta baja.

### wal-sin-archivar

Aplica a `UvoStoreWalSinArchivar`. Mientras dure, la recuperación a un punto en el tiempo se detiene en el último segmento archivado, y `pg_wal` crece hasta llenar el disco, y entonces PostgreSQL se detiene, y con él UvoPOS:
1. `SELECT * FROM pg_stat_archiver` y `/var/log/pgbackrest/`.
2. `sudo -u postgres pgbackrest --stanza=uvopos check`.
3. **Nunca** se borran segmentos de `pg_wal` a mano.

### credenciales-sin-descifrar

Aplica a `UvoStoreCredencialesSinDescifrar`. La `APP_ENCRYPTION_KEY` del proceso no abre credenciales que están en la base. La readiness queda en DOWN (`CredentialsHealthIndicator`), así que la instancia no recibe tráfico:
- **Después de restaurar o reinstalar:** la llave del archivo de entorno no es la del gestor. Ponerla y reiniciar.
- **Sin cambio de llave:** una fila dañada o escrita con otra llave. El log del arranque dice de qué tabla (`CredentialDecryptProbe`). La credencial se pide de nuevo a la tienda.

### salir-del-modo-recuperacion

Aplica a `UvoStoreModoRecuperacion`. Una instancia con `APP_RECOVERY_MODE=true`:
- **Si es la restaurada en aislado:** no debería estar en el scrape del ambiente. Quitarla.
- **Si es la del ambiente:** se sale del modo con los criterios de «Volver a admitir tráfico». Se quita la variable del archivo de entorno y se reinicia, y queda en el acta quién lo hizo y a qué hora.

## Modo recuperación

`APP_RECOVERY_MODE=true` (`RecoveryModePolicy`) es para levantar una base restaurada sin que salga nada hacia afuera. Un respaldo es una foto del pasado: lo que pasó después (un cobro que Transbank confirmó, una venta que el POS registró, un correo que salió) no está en la foto, y la instalación no lo sabe.

| Con el modo | Detalle |
|---|---|
| Trabajos programados | No corre ninguno: el planificador no se enciende (`SchedulingConfig`). Ni el outbox, ni la conciliación de pagos o del POS, ni las órdenes abandonadas, ni el barrido de stock |
| POS y correo | `PosClient` y `EmailServiceImpl` lanzan `RecoveryModeException` en vez de enviar |
| HTTP | 503 `RECOVERY_MODE` (`RecoveryModeFilter`) para toda escritura (checkout, pagos, webhooks de pasarelas y del POS, panel, alta de tiendas) y para el retorno de Webpay también por GET. El 503 hace que el proveedor reintente después |
| Lo que sí pasa | Lecturas, login del panel, resolución de outbox (`/api/admin/outbox/{id}/retry\|resolve`; el reintento solo vuelve a la cola), resolución de reembolsos inciertos y registro de un reembolso externo, correos de acceso de la plataforma, y la API de recuperación |
| Arranque | WARN en el log, gauge `uvostore_recovery_mode`, y la sonda de descifrado no saca a la instancia de la readiness |

### API de recuperación (solo en modo recuperación; fuera de él responde 409)

- `GET /api/platform/recovery/report?from=<ISO>`: la base al lado de lo que dicen las pasarelas, por tienda.
  - **Solo lee:** Webpay `status()`, la búsqueda de MercadoPago y la sesión de Stripe con su cargo. Nunca confirma, cobra ni devuelve.
  - **Qué compara:** las órdenes y reembolsos que se movieron desde `from`, y los pendientes de cualquier fecha.
  - **Sin datos del comprador.**
- `POST /api/platform/recovery/outbox/hold {"restoredAt": "<ISO>"}`: pasa a FAILED, con el motivo «Restaurado desde respaldo», lo que el respaldo traía en cola o en vuelo. Desde ahí se ve en «Envíos fallidos» y se reintenta o se cierra a mano. Lo trata como ya entregado hasta demostrar lo contrario.

## Restaurar

Este procedimiento es el mismo para el ensayo y para un incidente. La restauración siempre es **aislada**, en otro clúster y otra instancia, y nunca sobre lo vivo.

0. **Anotar** la hora del incidente (`--referencia`) y el punto al que se restaura (`--hasta`, o `--ultimo`). Avisar al equipo POS: lo restaurado trae su base al mismo punto, aunque aquí no se usa.
1. **Restaurar y levantar en modo recuperación:**
   ```
   sudo -u postgres pgbackrest --stanza=uvopos info        # qué puntos hay
   <llave del gestor> | sudo bash ops/respaldo/restaurar.sh production --hasta '2026-11-20 14:30:00-03' \
       --referencia '2026-11-20 14:42:00-03' --bucket-aislado <bucket-aislado> --llave-stdin
   ```
   - Deja el clúster restaurado en `127.0.0.1:5433` (sin archivar WAL) y la instancia en `127.0.0.1:8092` (unidad `uvostore-production-restauracion`).
   - Deja `acta-restauracion.md` con los tiempos, la última transacción restaurada y el RPO.
   - **Se detiene** si el jar activo trae migraciones que la base restaurada no tiene: hay que usar el jar de esa época (`--jar`).
2. **Comprobar y sacar el informe:**
   ```
   sudo bash ops/respaldo/verificar-restauracion.sh production --desde '2026-11-13T00:00:00Z' --conteos-origen <conteos>
   ```
   - Comprueba la readiness, el 503 del checkout y del retorno de Webpay, el modo, la sonda de descifrado y los conteos por tienda.
   - Deja `informe-recuperacion.json`.
   - `--conteos` sin restauración imprime los del clúster vivo, para comparar.
3. **Conciliar.** Cada hallazgo del informe tiene su acción, y cada acción queda en el acta:

   | Hallazgo | Acción |
   |---|---|
   | `pago.PROVEEDOR_PAGADO_LOCAL_NO` | El cliente pagó después del punto. Al salir del modo, la conciliación de pagos lo confirma sola si la orden sigue PENDING. Si no, confirmar a mano |
   | `pago.LOCAL_PAGADO_PROVEEDOR_NO` | Revisar en el portal de la pasarela. Si no hay cobro, la orden no se despacha |
   | `pago.MONTO_DISTINTO` | Revisar en el portal. No despachar hasta aclararlo |
   | `pago.REEMBOLSO_DISTINTO` | La pasarela devolvió algo que la base no tiene: registrarlo como reembolso externo (`/refund/external`) |
   | `reembolso.PROVEEDOR_DEVOLVIO` / `PROVEEDOR_NO_DEVOLVIO` | Resolver el reembolso incierto como completado o fallido (`/refunds/{id}/resolve`) |
   | `reembolso.NO_CONCLUYENTE` / `NO_CONSULTABLE`, `pago.NO_CONSULTABLE` | Revisar en el portal de la pasarela |
   | `outbox.INCIERTO_POS` | Buscar la venta en el POS por el número de orden. Hasta que exista la consulta por clave (contrato IMP-08, §9), es a mano |
   | `outbox.INCIERTO_CORREO` | Si el comprador lo recibió, cerrar. Si no, reintentar |
   | `credenciales.sin_descifrar` | La llave no es la correcta: no seguir |

4. **Retener el outbox** (`POST /api/platform/recovery/outbox/hold`, con el punto de restauración) y resolver cada fila desde «Envíos fallidos»: reintentar solo lo que se comprobó que no llegó.
5. **Decidir.**
   - **Ensayo:** se termina con `sudo bash ops/respaldo/restaurar.sh <ambiente> --detener`. Los datos quedan en `/var/lib/uvostore-restauracion/<ambiente>`, y borrarlos es aparte.
   - **Incidente con el clúster vivo perdido:** se restaura el mismo punto en el clúster principal, con PostgreSQL detenido y en coordinación con el equipo POS:
     ```
     sudo -u postgres pgbackrest --stanza=uvopos --delta --type=time --target='<punto>' --target-action=promote restore
     ```
     Se levanta el ambiente **primero con `APP_RECOVERY_MODE=true`** (`fijar_env`), se repiten los pasos 2 a 4 sobre él, y después se sale del modo.

### Volver a admitir tráfico

Solo con todo esto en el acta:
- [ ] La sonda de descifrado da 0 fallas.
- [ ] Los conteos son coherentes con lo esperado al punto restaurado.
- [ ] Cada hallazgo del informe tiene una acción ejecutada o una decisión firmada.
- [ ] El outbox restaurado está retenido y cada fila se reintentó o se cerró.
- [ ] El equipo POS confirmó su lado.
- [ ] Se quitó `APP_RECOVERY_MODE` y se reinició, con hora y responsable.
- [ ] Se observan el backlog y las alertas durante 30 minutos.

## Migraciones y vuelta atrás

**Nada de esto se deshace solo.** Flyway no revierte, Hibernate valida el esquema al arrancar (`ddl-auto=validate`), y Flyway ignora las migraciones «del futuro» (`*:future`). Que el jar anterior arranque sobre un esquema más nuevo depende de qué cambió.

### Clasificación por migración

Para volver del jar de la migración N al de N-1:

| Migración | Cambio | Volver al jar anterior |
|---|---|---|
| V1–V21 | Esquema inicial y cambios previos a la primera etiqueta con despliegue | No aplica |
| V22 | `users.password` admite NULL (invitaciones) | **Riesgo semántico:** el jar anterior no espera usuarios sin contraseña |
| V23, V24, V27, V28, V30 | Columnas nulables o tablas nuevas | Rollback binario. V24: las órdenes que cobre el jar anterior quedan sin `paid_at`, y el barrido de stock no las ve |
| **V25** | Crea el outbox y **borra** `orders.pos_synced`, `sync_attempts`, `last_sync_error` | **No.** El jar anterior falla la validación: restaurar y conciliar, o corregir hacia adelante |
| V26, V29, V31, V34, V37 | CHECK que solo amplían valores, o tabla nueva | Rollback binario, **salvo que ya haya filas con los valores nuevos**: el enum del jar anterior no los conoce y falla al leerlas (una tienda `CLOSED` de V29, un log `DISCARDED` de V26, acciones de auditoría de V31, V34 y V37) |
| V32, V35 | Columna con DEFAULT, o CHECK sobre una columna nueva | Rollback binario |
| V33 | `tax_included` y `order_items.discount_amount` con DEFAULT | **Riesgo semántico:** las órdenes que escriba el jar anterior quedan con IVA «no incluido» y sin descuento por línea |
| **V36** | `outbox_messages.store_id` **NOT NULL** sin DEFAULT, y tipos nuevos | **No.** El jar anterior no escribe `store_id`: falla todo insert al outbox (checkout incluido) |

- **Rollback binario:** `sudo bash ops/servidor/desplegar-backend.sh --activar <ambiente> <versión anterior>`. Desde IMP-09 avisa si el esquema es más nuevo que ese jar.
- **No:** se corrige hacia adelante, con una versión nueva que arregla el problema sobre el esquema nuevo, o se restaura el respaldo de antes de migrar y se concilia (este documento).
- `desplegar-backend.sh` **exige un respaldo de menos de una hora** antes de activar un jar con migraciones pendientes (`SIN_RESPALDO=1` para desplegar igual, a sabiendas), y lista las que va a aplicar.

### Ensayo automático

El job `migracion-rollback` de CI (`ops/servidor/ensayo/migracion-rollback.sh`) hace esto:
1. Arma el jar de la etiqueta anterior (`git describe --tags --abbrev=0 HEAD^`) y lo levanta con datos.
2. Migra con la candidata.
3. Vuelve a levantar el jar anterior sobre el esquema migrado.
4. Restaura el respaldo de antes de migrar.

Deja la clasificación en el resumen del job y en `migracion-rollback.json`. De v1.3.1 (V23) a la candidata (V37) tiene que dar `RESTAURAR_Y_CONCILIAR`, por V25 y V36. Si da otra cosa, esta tabla está mal.

## Evidencia por punto del checklist

| Punto | Qué la produce hoy | Qué falta en el servidor real |
|---|---|---|
| **C10:** backup de DB y archivos, custodia de la llave, restauración aislada con RPO/RTO medidos | `ensayo-servidor` (paso C10): instala el respaldo, hace un completo y la copia de imágenes, y restaura al punto en aislado con la llave por la entrada estándar. Comprueba conteos, imagen, que no se reenvió el correo, la retención del outbox y la llave equivocada detectada. Deja `c10.txt`, `acta-restauracion.md` e `informe-recuperacion.json`. También `CredentialDecryptProbeTest`, `RecoveryModeTest` y las alertas (promtool) | El bucket real en otra región con sus políticas, el primer respaldo completo, una restauración aislada en staging con la llave traída del gestor por un custodio, el acta firmada, y RPO/RTO aprobados en C01 |
| **C18:** caídas durante pago, inventario, outbox y devolución; recuperación sin duplicar dinero | Las pruebas de interrupción (`PaymentInterruptionTest`, `RefundInterruptionTest`, `OutboxInterruptionTest`, `WebpayUncertainCommitTest`); el reinicio con trabajo en vuelo de `ensayo-servidor` (C11); el informe de recuperación, que compara con las pasarelas | Repetirlo con pasarelas reales (C12) y POS (C14), que siguen abiertos |
| **C19:** migración y rollback ensayados, incompatibilidades documentadas, recuperación coherente con el POS | `migracion-rollback` (CI), la tabla de arriba, las comprobaciones de `desplegar-backend.sh` | El ensayo sobre una copia representativa de staging, y la coordinación con el POS al restaurar el clúster compartido (C14 abierto) |

## Plantilla del acta

El acta técnica (`acta-restauracion.md`) la genera `restaurar.sh`. Esta es la parte que completan las personas, sin secretos ni datos de compradores:

```
Acta de restauración — <ambiente> — <fecha>
Motivo:                 ensayo trimestral | incidente <id>
Responsable / custodio: <nombre> / <nombre>
Punto pedido / ref.:    <--hasta> / <--referencia>
RTO técnico / RPO:      <del acta técnica>
Llave:                  recuperada del gestor por <custodio>; sonda: <0 fallas | N fallas>
Conteos:                coinciden | diferencias: <...>
Hallazgos del informe:  <clasificación: cantidad, acción, responsable>
Outbox retenido:        <N filas>; reintentadas <n>, cerradas <n>
Equipo POS:             <confirmación y hora>
Decisión:               fin del ensayo (--detener) | promoción a producción
Salida del modo:        <hora, responsable>  ·  RTO total (con conciliación): <...>
```
