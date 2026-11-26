# Plan de implementación de la integración con AlfozApi en UvoStore

La entrega se completa cuando una tienda puede importar su catálogo desde AlfozApi, cobrar una orden y acreditar que el POS registró **una sola venta por el importe cobrado**, incluso si la respuesta se perdió. El saldo de una variante debe seguir siendo correcto mientras esa venta espera confirmación.

Este plan desarrolla IMP-08 desde UvoStore. Toma como base el código local `fb6b2b7` y el contrato `1.0.0-borrador` del 20 de noviembre de 2026. UvoStore ya dispone de alta privada, mapeos, control de empresa en checkout, despacho durable de ventas, conciliación y panel de envíos fallidos. Las etapas siguientes adaptan esas piezas. [Contrato y ejemplos](contrato-pos-ecommerce.md) · [Criterios de lanzamiento](../GO_LIVE_CHECKLIST.md) · [Hoja de ruta](../PLAN_PRODUCCION.md)

## Secuencia de entrega

| Etapa | Responsable propuesto | Depende de | Evidencia de salida |
|---|---|---|---|
| 1. Cerrar el contrato | Backend UvoStore, equipo POS y producto | Decisiones de ambos equipos | Contrato aprobado y ejemplos con respuestas esperadas |
| 2. Conexiones y firma | Backend UvoStore | 1 | Alta repetible, firma interoperable, rotación y aislamiento entre dos tiendas |
| 3. Catálogo y variantes | Backend UvoStore y panel admin | 2 | Carga inicial y conciliación de simples y variantes con eventos fuera de orden |
| 4. Venta y outbox | Backend UvoStore | 2 y definición de mapeos de 3 | Un pago produce una clave y un cuerpo inmutable por empresa |
| 5. Recuperación y operación | Backend UvoStore y panel admin | 4 | Una respuesta perdida se resuelve por consulta y el operador ve el resultado |
| 6. Aceptación y activación | QA, operaciones y ambos equipos backend | 3–5 y AlfozApi disponible en staging | Dos tiendas por HTTPS, evidencia de un solo efecto y ensayo de pausa |

### 1. Cerrar las decisiones que cambian código

- Acordar la procedencia de `stock_web`, los campos obligatorios en la carga inicial y quién inicia esa carga después del alta. El snapshot consulta IDs conocidos. Una tienda vacía necesita que el POS le entregue los productos iniciales.
- Fijar la identidad de cada variante como producto POS, el procedimiento para asociarla a su padre en la tienda y la recuperación de eventos parciales perdidos. Por ejemplo, si llega primero el stock v16 y después el precio v15, descartar v15 exige poder recuperar el precio mediante un snapshot completo v16.
- Acordar con AlfozApi las claves por dirección, tolerancia de reloj, límites de lectura, semántica de `NOT_PROCESSED`, retención de la idempotencia y ejemplos de error. Precisar qué hacer con ventas históricas cuando la retención del POS ya venció.
- Producto y el equipo POS deben resolver en C01 el documento tributario de la venta web. El borrador registra en el POS una Boleta NT 999 interna. También quedan por decidir la identificación del comprador en ese registro y el procedimiento de anulación tras un reembolso. Registrar el responsable operativo si la anulación queda manual.

**Aceptación.** Ambos equipos aprueban la misma versión del contrato y sus ejemplos. El equipo POS se compromete a suministrar credenciales de prueba por empresa, alta/carga inicial, avisos firmados y los tres endpoints de máquina de venta, consulta y snapshot. Son dependencias para la aceptación conjunta. [Decisiones y alcance del contrato](contrato-pos-ecommerce.md)

### 2. Adaptar el alta privada y la autenticación

1. Ampliar `PosConnection` mediante Flyway para guardar identificador, secreto cifrado, dirección, vigencia y revocación de las claves. Conservar las relaciones tienda–empresa existentes. Inventariar conexiones antiguas y aprovisionar claves v1 antes de activarlas. Un token Bearer existente no acredita una conexión v1.
2. Adaptar `StoreOnboardingRequest`, `StoreOnboardingServiceImpl`, la configuración administrativa y el diagnóstico de descifrado. La repetición de un alta debe devolver la misma tienda y conexión sin duplicar invitaciones. Tratar la rotación como una operación explícita. Conservar el acceso privado a plataforma y construir los destinos de callbacks con `StorePublicUrlResolver`.
3. Unificar la verificación en `PosApiKeyAuthFilter` y `PosWebhookAuthFilter` para carga y avisos. Usar el mismo formato en `PosClient` para venta, consulta y snapshot. Firmar método, ruta cruda y bytes exactos según el vector, con `X-Uvo-Contract: 1`. Vincular clave, empresa del encabezado y del cuerpo, y tienda resuelta por host. Consumir el nonce válido de forma atómica y durable por clave.
4. Propagar `X-Request-Id`, devolver el formato de error del contrato y conservar la protección de las rutas cuando la integración está apagada. Ajustar OpenAPI de POS y plataforma, ejemplos de alta y cualquier formulario que aún solicite las credenciales antiguas.

**Aceptación.** Reproducir los cuatro casos de [firma](contrato-pos-ecommerce/ejemplos/firma-vector.json), incluida la ruta de consulta con `%3A`. Probar dos tiendas, repetición tras reinicio, versión no admitida y clave revocada. Según los plazos del borrador, admitir ambas claves durante siete días de rotación, validar timestamp a 300 s y retener el nonce al menos 600 s. Extender `PosStoreOnboardingTest`, `PosWebhookAuthTest` y `PosCrossTenantWriteTest`.

### 3. Adaptar catálogo, variantes y conciliación

- Migrar los mapeos para resolver cada par **empresa + producto POS** a un único destino vendible, simple o variante. Conservar pedidos históricos. Separar las asociaciones antiguas cuya equivalencia requiera confirmación del POS y bloquear la venta de esas referencias hasta verificarlas. Dar al operador una forma de asociar productos POS al padre y los atributos locales, conservando imágenes, textos y agrupación.
- Adaptar los DTO de `/api/sync/product` y los cuatro avisos de `/api/webhooks/pos/`, junto con `PosSyncServiceImpl`, `PosWebhookServiceImpl` y `PosCatalogWriter`. El POS gobierna nombre, SKU, precio, exención y stock web. Exigir versión en cada entrada que modifica datos. Aplicar el cambio y registrar la versión en la misma transacción. Responder con el resultado contractual a una repetición. Mantener el orden de bloqueo de variante y padre y recalcular el stock agregado.
- Adaptar `PosReconciliationService` para pedir hasta 200 IDs de productos POS por lote, incluidas las variantes. Validar que cada ID solicitado aparezca en `data` o `missing`. Un lote incompleto es un error recuperable. Un ID confirmado en `missing` requiere revisión del mapeo.
- Proteger el stock en carga, aviso y snapshot. Si hay una venta cobrada pendiente, diferir la corrección absoluta de ese producto y dejarla pendiente de conciliación. Distinguir los campos efectivamente aplicados para que un snapshot de la misma versión pueda completar el stock diferido o un campo perdido. Mantener la escritura condicional frente a ventas concurrentes.

**Aceptación.** Importar dos tallas con stock independiente, vender una y comprobar la suma del padre. Inyectar un cambio de precio v15 y un stock v16 en orden inverso, una repetición, un lote de 201 IDs y una corrección durante una venta web pendiente. Tras conciliar, los campos del POS coinciden, el contenido comercial local se conserva y ninguna unidad vuelve a ofrecerse antes de tiempo. Extender `PosVariantsTest`, `PosOrderedInputsTest`, `PosStockRaceTest` y `PosReconciliationTest`.

### 4. Fijar el contenido de cada venta en el outbox

1. Adaptar `PosOrderPayloadMapper`, `PosOrderNotifier` y `PosSaleCoverage`. Enviar el `product_id` vendible de cada línea, incluida la variante. Tomar precios, descuentos e IVA de la orden cobrada. Validar enteros CLP, distribución del cupón, exentos y envío según las cuatro muestras de venta. Mantener una empresa por orden.
2. Hoy `PosSaleOutboxHandler` prepara la venta en cada intento. Persistir con la fila `POS_ORDER` la versión del contrato, empresa, clave, bytes serializados y SHA-256 antes del primer envío, preferentemente al encolar con la confirmación del pago. Conservar el identificador de petición. Un reintento reutiliza esos bytes y genera una firma y un nonce nuevos. Mantener el HTTP fuera de la transacción de base de datos.
3. Clasificar las filas anteriores a la migración. Para una venta demostrablemente sin enviar, fijar el cuerpo con los datos históricos verificables. Para una que pudo llegar, conciliar primero con el POS. Una fila sin hash original verificable, con mapeo ambiguo o con una clave fuera de retención necesita revisión antes de un reenvío.
4. Adaptar `PosClient` a `POST /api/v1/orders/external` v1. Validar la respuesta, guardar `sale_id` y la referencia al documento 999, y comparar `results[].new_stock_web` como señal de divergencia. Una respuesta de éxito incompleta requiere consulta.

**Aceptación.** Las muestras de venta simple, variante, cupón con envío e IVA no incluido pasan por el serializador real. La clave del cuerpo coincide con `Idempotency-Key`. Tras reinicio, modificación de catálogo o rotación de clave, la orden conserva su importe, sus IDs externos y el hash original. Extender `PosOrderPayloadMapperTest` y `OutboxPosEnqueueTest`. [Ejemplos de contrato](contrato-pos-ecommerce/ejemplos/)

### 5. Resolver envíos inciertos y exponer el resultado

Implementar la consulta firmada `GET /api/v1/orders/external/{clave}` y usarla desde `PosSaleOutboxHandler`, la recuperación de filas huérfanas y las acciones de «Envíos fallidos». Persistir la decisión en el outbox con actualización condicional, para que un operador y el despachador concurrentes no vuelvan a publicar la misma fila.

| Observación | Acción de UvoStore |
|---|---|
| POST 201 o 200 con venta confirmada y clave coincidente | Cerrar como entregada y guardar la referencia POS |
| Sin conexión, 408, 429 o 409 `IN_PROGRESS` | Reprogramar con `Retry-After` cuando corresponda o espera creciente dentro de la ventana de 48 h |
| 409 `IDEMPOTENCY_CONFLICT`, 422, 400, 401 o 403 | Mostrar el rechazo y su código. Para `OUT_OF_STOCK`, permitir resolver mediante reposición y reintento o reembolso gestionado |
| 5xx, timeout de lectura, 409 desconocido o caída tras tomar la fila | Marcar incertidumbre y consultar antes de otra publicación |
| Consulta 200 `REGISTERED` y hash coincidente | Registrar la entrega, incluso si se perdió la respuesta del POST |
| Consulta 404 con `NOT_PROCESSED` y clave dentro de retención | Habilitar el reenvío de los mismos bytes según la política del ambiente |
| Consulta en curso, 404 genérico, hash distinto o respuesta inconclusa | Conservar la incertidumbre. Volver a consultar si está en curso y derivar a revisión si persiste la duda |

Actualizar `OutboxFailures.tsx` para distinguir **consultar**, **reintentar** y **revisar en POS**, con código, empresa y referencia de seguimiento. Reutilizar las métricas de pendientes y divergencias. Ajustar `RecoveryReportService` para consultar en modo recuperación respetando su carácter de solo lectura. Registrar duración y resultado con `X-Request-Id` y filtrar secretos y datos del comprador.

**Aceptación.** Simular que el POS guardó la venta y perdió la respuesta, reiniciar el despachador y resolver por consulta. Verificar una sola venta y una sola afectación de stock. Repetir con un 404 de proxy y comprobar que la fila sigue abierta a revisión. Cubrir recepción duplicada, hash distinto y carrera entre panel y job en `OutboxCrashRecoveryTest` y las pruebas del panel.

### 6. Probar y activar por tienda

1. **CI de UvoStore.** Extender el doble en `frontend/e2e/doubles/server.ts` con verificación de firma, deduplicación por empresa y clave, consulta, snapshot y fallos configurables. Ejecutar `mvnw.cmd clean verify` y en frontend lint, pruebas, build y Playwright con el JAR recién generado. Conservar el escenario con POS desactivado.
2. **Staging con AlfozApi.** Usar dos tiendas por HTTPS y empresas con claves diferentes, base descartable y documentos internos 999. Habilitar `POS_ENABLED` en staging y `pos_sync_enabled` solo en las tiendas de prueba. Repetir alta, carga, compra y venta de mostrador. Probar carrera por la última unidad, interrupción tras registrar, dos envíos concurrentes y repetición tras reinicio del POS. Registrar por caso los commits, clave de prueba, hash, request ID, venta, folio y stock antes/después. Verificar aislamiento y que una divergencia llegue al operador.
3. **Piloto.** Mantener `POS_RETRY_ENABLED=false` hasta demostrar idempotencia con el receptor real. Como el interruptor es de ambiente, comprobar todas las conexiones activas antes de cambiarlo. El criterio de ampliación es que cuadren pagos, ventas, documentos 999 y movimientos, y que se hayan resuelto los pendientes del piloto. Adjuntar la evidencia a IMP-08, C13/C14 y los casos POS de C18.
4. **Pausa y reversión.** Detener nuevas compras de la tienda piloto y pausar el despacho de sus filas antes de desactivar su conexión. Implementar y probar esa pausa si el mecanismo disponible convierte filas en `SKIPPED`. Conservar datos, claves, cuerpos y estados durante la reversión. Antes de reanudar, consultar las ventas en vuelo y conciliar stock. Ensayar el procedimiento con una orden pagada pendiente.

**Listo del lado UvoStore** significa que las etapas 2–5 y la CI de la etapa 6 pasan contra la versión acordada. **Listo para operar con AlfozApi** exige además la evidencia bilateral de staging. El responsable de la activación debe poder rastrear una orden desde su pago hasta el registro POS y explicar cualquier diferencia de stock antes de ampliar el piloto.
