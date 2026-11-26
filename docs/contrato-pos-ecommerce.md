# Contrato UvoPOS ↔ uvo-store

| | |
|---|---|
| Versión | **1.0.0-borrador** (20-nov-2026) |
| Estado | Propuesta para aprobar. uvo-store lo implementa por su lado desde el 26-nov-2026 (§12), sin firmar todavía; AlfozApi no (§13). |
| Origen | `GO_LIVE_CHECKLIST.md` §13.9, IMP-08, pasos 1 y 2 |
| Ejemplos | [`contrato-pos-ecommerce/ejemplos/`](contrato-pos-ecommerce/ejemplos/). Son parte del contrato: si el texto y un ejemplo no coinciden, se corrige uno de los dos antes de aprobar. |

## 1. Alcance, versión y aprobación

Este contrato fija cómo se hablan la tienda (uvo-store) y el POS (UvoPOS, el repositorio AlfozApi):

- cómo la tienda **registra una venta web** en el POS, exactamente una vez;
- cómo la tienda **pregunta** si una venta ya entró;
- cómo el POS le **avisa** a la tienda los cambios de catálogo y stock;
- cómo la tienda **lee el catálogo** en bloque para conciliar.

Queda fuera:

- la emisión de la boleta electrónica (§15);
- los pagos, que viven solo en la tienda;
- el despacho.

**Versionado.** Cada petición, en las dos direcciones, lleva `X-Uvo-Contract: 1`. Un cambio compatible (un campo opcional nuevo, un código de error nuevo) sube la versión menor del documento y no cambia la cabecera. Un cambio incompatible sube la versión mayor y la cabecera. Durante la transición, cada lado acepta las dos versiones. Quien recibe una versión que no conoce responde `400 UNSUPPORTED_CONTRACT`.

**Aprobación.** Rige cuando los dos equipos firman esta tabla. Hasta entonces, la implementación no empieza (IMP-08, paso 1).

| Equipo | Responsable | Fecha | Firma |
|---|---|---|---|
| uvo-store | | | |
| UvoPOS | | | |

**Historial.**

| Versión | Fecha | Cambio |
|---|---|---|
| 1.0.0-borrador | 20-nov-2026 | Primera propuesta. Reemplaza las suposiciones de `PosOrderPayloadMapper` y de `docs/pos-ecommerce-communication.md`. |

## 2. Alta de una conexión

Una conexión une **una tienda** con **una empresa del POS**. Una tienda puede tener varias conexiones, una por empresa.

| Lo entrega | Dato | Notas |
|---|---|---|
| POS | `company_id` | El id de la empresa en el POS. |
| POS | URL base | Por ejemplo `https://pos.ejemplo.cl`. La tienda le agrega `/api/v1/…`. Solo HTTPS fuera de desarrollo. |
| POS | `key_id` y secreto **tienda→POS** | Con él firma la tienda. El `key_id` amarra a la empresa. |
| POS | `key_id` y secreto **POS→tienda** | Con él firma el POS los webhooks. Es distinto del anterior, para que una fuga de un lado no permita hacerse pasar por el otro. |
| Tienda | URL de webhooks | `https://<dominio-de-la-tienda>/api/webhooks/pos/` y `…/api/sync/product`. |

- **Secretos:** al menos 32 bytes aleatorios, entregados en base64. Viajan por un canal acordado, nunca por correo ni por chat. Cada lado los guarda cifrados (la tienda, en `PosConnection` con AES-GCM). Ninguno de los dos los escribe en un log.
- **Rotación:** el POS emite un `key_id` nuevo y los dos lados aceptan el viejo y el nuevo durante **7 días**. Después, el viejo se revoca.
- **Revocación:** inmediata. Un `key_id` revocado da `401`.

## 3. Autenticación (las dos direcciones)

Es el mismo esquema que AlfozApi ya usa con uvocms (`com.alfoz.api.security.integracion.FirmaIntegracion`), con una llave por empresa en vez de una de plataforma.

**Cadena canónica.** Siete líneas separadas por `\n`, sin salto final:

```
UVO-HMAC-SHA256
{MÉTODO}                  POST | GET
{RUTA}                    la ruta exacta que viaja, sin dominio y sin query
{TIMESTAMP}               segundos unix
{NONCE}                   base64url, 22 a 64 caracteres, aleatorio
{KEY_ID}
{hex(sha256(cuerpo))}     de los bytes crudos del cuerpo; sin cuerpo = sha256("")
```

`firma = hex(HMAC-SHA256(base64_decode(secreto), canónica))`, en minúsculas.

**Cabeceras.**

| Cabecera | Valor |
|---|---|
| `X-Uvo-Key-Id` | El `key_id` |
| `X-Uvo-Timestamp` | El mismo de la canónica |
| `X-Uvo-Nonce` | El mismo de la canónica |
| `X-Uvo-Signature` | `v1=<hex de 64 caracteres>` |
| `X-Company-ID` | El `company_id` de la conexión |
| `X-Uvo-Contract` | `1` |

**Reglas de quien recibe:**

1. Si falta una cabecera, el nonce no tiene el formato, el `key_id` no se conoce o está revocado, o el timestamp se aleja más de **300 s** del reloj local, responde `401`.
2. Calcula la firma sobre los bytes crudos recibidos, **antes** de deserializar, y la compara en tiempo constante. Solo vale la forma `v1=` más 64 hexadecimales en minúsculas: no se normaliza nada. Si no coincide, responde `401`.
3. Un nonce ya visto con ese `key_id` dentro de la ventana responde `401`. Se guarda al menos el doble de la ventana.
4. Un `X-Company-ID` distinto de la empresa amarrada al `key_id` responde `403`.
5. Una petición con query string responde `400`. Ningún endpoint de este contrato la usa.

**La ruta firmada es la que viaja.** Si lleva caracteres codificados (`%3A` en la consulta del §9), se firma codificada. Quien recibe usa la ruta cruda de la petición, sin decodificar.

**Vector de prueba:** [`firma-vector.json`](contrato-pos-ecommerce/ejemplos/firma-vector.json).

- Usa el secreto, el timestamp y el nonce ficticios de `FirmaIntegracionTest` de AlfozApi.
- El bloque `control_alfozapi` reproduce su firma `ea8fa7aa…`. Una implementación que no lo reproduce está mal antes de mirar lo demás.
- Los casos `venta`, `consulta` y `webhook` traen la canónica y la firma esperada para este contrato.
- Los dos lados lo fijan en sus pruebas.

## 4. Dueño de cada campo

| Campo | Manda | La otra parte… |
|---|---|---|
| SKU, nombre | POS | La tienda no lo edita mientras la conexión esté activa. |
| Precio (con IVA) | POS | La tienda lo aplica tal cual. Los descuentos de la tienda (cupones) son de la tienda (§7). |
| Exento de IVA | POS | La tienda lo usa para calcular el IVA de la línea. |
| `stock_web` | POS | La tienda lo descuenta al cobrar y lo corrige con los avisos (§6). |
| Descripción larga, imágenes, categorías, slug, SEO, visibilidad | Tienda | El POS no los envía. Si los envía, la tienda los ignora. |
| Agrupación en variantes (talla, color) | Tienda | Ver abajo. |

**Variantes.** El POS no tiene variantes: **cada variante es un Producto del POS** con su propio SKU y stock (por ejemplo, «Polera azul M» = producto 777 y «Polera azul L» = producto 778).

- La tienda los agrupa bajo un producto padre con sus atributos, y esa agrupación es solo de la tienda.
- Para el POS, todo `product_id` de este contrato es un Producto suyo. El campo `variant_id` no existe.

## 5. Catálogo

**Avisos del POS a la tienda.** Se firman como en §3 con la llave POS→tienda. Todos los campos son camelCase, que es como los recibe hoy uvo-store.

| Endpoint de la tienda | Cuándo | Ejemplo |
|---|---|---|
| `POST /api/sync/product` | Alta o carga completa de un producto | [`sync-product.request.json`](contrato-pos-ecommerce/ejemplos/sync-product.request.json) |
| `POST /api/webhooks/pos/product-created` | Producto nuevo | [`webhook.product-created.json`](contrato-pos-ecommerce/ejemplos/webhook.product-created.json) |
| `POST /api/webhooks/pos/product-updated` | Cambio de nombre, precio o exento | [`webhook.product-updated.json`](contrato-pos-ecommerce/ejemplos/webhook.product-updated.json) |
| `POST /api/webhooks/pos/stock-updated` | Cambio de stock: venta de mostrador, recepción o ajuste | [`webhook.stock-updated.json`](contrato-pos-ecommerce/ejemplos/webhook.stock-updated.json) |
| `POST /api/webhooks/pos/stock-alert` | Stock bajo o agotado (informativo) | [`webhook.stock-alert.json`](contrato-pos-ecommerce/ejemplos/webhook.stock-alert.json) |

**Versión.**

- Cada producto del POS tiene un `version` entero que **sube con cada cambio**, sea de catálogo o de stock.
- Todos los avisos lo llevan, salvo `stock-alert`, que no cambia nada.
- La tienda aplica un aviso solo si su `version` es mayor que la última aplicada para ese producto y esa empresa. Si no, responde `200 {"success": true, "ignored": true}` y no hace nada. Así un aviso atrasado o repetido no pisa uno más nuevo.

**Entrega.**

- `2xx` significa recibido. El POS no reintenta.
- `401`, `403` o `400` significan que la petición está mal: el POS no la reintenta igual y la deja para revisión.
- Ante `5xx`, un timeout o un error de conexión, el POS reintenta con espera creciente durante **48 h**. Cada reintento lleva un nonce y una firma nuevos; la versión evita el doble efecto.

**Lectura masiva:** `POST /api/v1/products/snapshot`, con la llave tienda→POS. Ejemplos: [pedido](contrato-pos-ecommerce/ejemplos/snapshot.request.json) y [respuesta](contrato-pos-ecommerce/ejemplos/snapshot.response.json).

- Hasta **200** `product_ids` por petición. Si son más, el POS responde `422 VALIDATION`.
- `data[]` trae, por producto: `product_id`, `sku`, `version`, `name`, `price`, `tax_exempt` y `stock_web`.
- `missing[]` trae los ids que el POS no tiene o que no son de esa empresa. Un id ausente de los dos no puede ocurrir.
- Es una lectura sin efectos y se puede repetir.

## 6. Stock web y la última unidad

- `stock_web` es lo que el POS ofrece a la web. Sale de la bodega web de la empresa, a definir (§14).
- La tienda **descuenta al confirmar el pago**, y no al armar el carro. Después encola la venta para el POS.
- El POS descuenta el `stock_web` al registrar la venta (§7) y devuelve el saldo en `results[].new_stock_web`. Si el saldo no coincide con el de la tienda, la tienda lo anota como divergencia y la conciliación lo corrige.
- **Si el POS no tiene stock para alguna línea, rechaza la venta entera:** `422 OUT_OF_STOCK`, con las líneas afectadas ([ejemplo](contrato-pos-ecommerce/ejemplos/venta.422-sin-stock.response.json)).
  - **No registra nada**: ni venta, ni documento, ni movimiento de stock, ni clave de idempotencia. Un reintento con la misma clave se evalúa de nuevo.
  - La tienda la deja como **envío rechazado** («Envíos fallidos» del panel) para gestión manual: reponer y reintentar, o reembolsar al comprador.
  - Es el caso de la última unidad vendida a la vez en el mostrador y en la web.
- **Obligación de la tienda:** una corrección de stock que llega del POS (aviso o conciliación) **no debe devolver unidades de ventas web cobradas que todavía no llegaron al POS**. La tienda aplica `stock_web` del POS menos lo pendiente de entregar de ese producto, o salta el producto mientras haya algo pendiente (como hace hoy `PosReconciliationService`).

## 7. Registrar una venta

`POST /api/v1/orders/external`, firmado con la llave tienda→POS, con la cabecera `Idempotency-Key: <clave>`.

Ejemplos:

- [simple](contrato-pos-ecommerce/ejemplos/venta-simple.request.json);
- [variante](contrato-pos-ecommerce/ejemplos/venta-variante.request.json);
- [cupón y envío](contrato-pos-ecommerce/ejemplos/venta-cupon-envio.request.json);
- [IVA no incluido](contrato-pos-ecommerce/ejemplos/venta-iva-no-incluido.request.json).

**Campos.** Todo es obligatorio y snake_case.

| Campo | Tipo | Significado |
|---|---|---|
| `source` | texto | Siempre `"uvostore"`. |
| `order_number` | texto | El número de orden de la tienda. |
| `idempotency_key` | texto | `{order_number}:{company_id}`. Igual a la cabecera; si difieren, `422 VALIDATION`. |
| `subtotal` | entero CLP | Suma de `items[].subtotal`. |
| `discount_amount` | entero CLP ≥ 0 | Descuento de la tienda (cupón), en positivo. Suma de `items[].discount_amount`. |
| `shipping_cost` | entero CLP ≥ 0 | El envío. Solo aquí, nunca como línea. Fuera de la base del IVA. |
| `tax_amount` | entero CLP | IVA. Suma de `items[].tax_amount`. |
| `tax_included` | booleano | `true`: `subtotal` y `discount_amount` van con IVA, y `tax_amount` está contenido en ellos. `false`: van netos y el IVA se suma. |
| `total` | entero CLP | Lo cobrado al comprador. |
| `items[].product_id` | entero | Producto del POS. En una variante, el Producto POS de esa variante (§4). |
| `items[].quantity` | entero > 0 | |
| `items[].price` | entero CLP | Precio unitario cobrado. |
| `items[].subtotal` | entero CLP | `quantity × price`. |
| `items[].discount_amount` | entero CLP ≥ 0 | Parte del descuento de la orden que le toca a la línea. |
| `items[].tax_amount` | entero CLP | IVA de la línea, calculado **después** del descuento. Es 0 si el producto está exento. |

**Invariantes.** El POS las verifica, y si alguna falla responde `422 VALIDATION` ([ejemplo](contrato-pos-ecommerce/ejemplos/venta.422-validacion.response.json)):

1. Las sumas de las líneas dan `subtotal`, `discount_amount` y `tax_amount`.
2. `total = subtotal − discount_amount + shipping_cost`, y además `+ tax_amount` cuando `tax_included = false`.
3. Todos los `product_id` son de la empresa de la conexión. **Una orden, una empresa**: la tienda no arma órdenes mixtas (GAP-06, `PosSaleCoverage`).

**El POS no recalcula precios.** No aplica su motor de precios, topes de descuento ni PIN de autorización: el precio y el descuento ya se cobraron en la tienda. Una diferencia con su lista de precios la registra como observación, no como rechazo.

**Efecto.** El POS hace todo esto en **una sola transacción**:

- la venta;
- el documento **Boleta NT 999** (`estado_sii = NO_APLICA`: no va al SII);
- el movimiento de stock en la bodega web;
- la clave de idempotencia.

O queda todo o no queda nada. La venta no necesita una sesión de caja ni un usuario: se atribuye a la conexión.

**El folio del 999** es único por empresa y no se reutiliza.

## 8. Idempotencia

- **Clave:** `{order_number}:{company_id}`. Es única **por empresa**: la restricción es `UNIQUE(company_id, clave)`, no global.
- **Cuerpo:** el POS guarda el `sha256` de los bytes crudos del cuerpo junto con la clave.
- **Concurrencia:** dos peticiones con la misma clave nunca producen dos efectos. La segunda espera a la primera o recibe `409 IN_PROGRESS`; nunca registra otra venta.
- **Reinicio:** la clave se graba en la misma transacción que la venta, así que un reinicio del POS no la pierde.
- **Plazo:** la clave se conserva al menos **90 días**.

| Situación | Respuesta | Ejemplo |
|---|---|---|
| Clave nueva, venta registrada | `201`: `sale_id`, `duplicate: false`, `document` y `results[]` | [201](contrato-pos-ecommerce/ejemplos/venta.201-registrada.response.json) |
| Misma clave y mismo hash | `200` con **la misma** venta y `duplicate: true`. No hay segundo efecto. | [200](contrato-pos-ecommerce/ejemplos/venta.200-repetida.response.json) |
| Misma clave y hash distinto | `409 IDEMPOTENCY_CONFLICT`, con el `sale_id` existente | [409](contrato-pos-ecommerce/ejemplos/venta.409-conflicto.response.json) |
| La misma clave se está procesando ahora | `409 IN_PROGRESS` y la cabecera `Retry-After` en segundos | [409](contrato-pos-ecommerce/ejemplos/venta.409-en-curso.response.json) |
| Sin stock | `422 OUT_OF_STOCK`; no se graba la clave | [422](contrato-pos-ecommerce/ejemplos/venta.422-sin-stock.response.json) |
| Datos inválidos | `422 VALIDATION`; no se graba la clave | [422](contrato-pos-ecommerce/ejemplos/venta.422-validacion.response.json) |
| Firma, llave o empresa | `401` / `403` | |
| Fallo interno | `5xx`. Si la transacción no se confirmó, no queda nada. | |

## 9. Consulta de una venta

`GET /api/v1/orders/external/{clave}`, firmado con la llave tienda→POS y sin cuerpo. La clave va codificada en la ruta: `UVO-20261120-0001%3A12`.

| Situación | Respuesta | Ejemplo |
|---|---|---|
| La venta está registrada | `200`: `status: "REGISTERED"`, `sale_id`, `registered_at`, `body_sha256` y `document` | [200](contrato-pos-ecommerce/ejemplos/consulta.200-registrada.response.json) |
| El POS **garantiza** que no la procesó: no está registrada ni en curso | `404`: `error.code = "NOT_PROCESSED"` | [404](contrato-pos-ecommerce/ejemplos/consulta.404-no-procesada.response.json) |
| Se está procesando | `409 IN_PROGRESS` | |

- Un `404` **sin** `error.code = NOT_PROCESSED` (un proxy, una ruta que no existe) no significa nada y se trata como sin respuesta.
- `NOT_PROCESSED` es una promesa: después de responderlo, el POS no puede registrar esa clave salvo por una petición nueva.

## 10. Cómo clasifica la tienda cada resultado

| Resultado de `POST /orders/external` | Para la tienda | Qué hace |
|---|---|---|
| `201` o `200` | Entregada | Cierra la fila del outbox y compara `new_stock_web`. |
| `409 IN_PROGRESS`, `408`, `429` | No procesada | Reintenta después de `Retry-After` o con espera creciente. |
| No pudo conectar | No salió | Reintenta con espera creciente, hasta 48 h. |
| `409 IDEMPOTENCY_CONFLICT`, `422`, `400`, `401`, `403` | Rechazada | «Envíos fallidos», con el código y el mensaje, para que la vea una persona. |
| `5xx`, timeout de lectura, `409` sin código, proceso interrumpido a mitad del envío | **Incierta** | **Consulta (§9) antes de decidir.** |

| Consulta de una incierta | Qué hace |
|---|---|
| `REGISTERED` | Entregada. No se reenvía. |
| `NOT_PROCESSED` | Se puede reenviar con la misma clave. |
| Sin respuesta o cualquier otra cosa | Sigue incierta: «Envíos fallidos», y la revisa una persona en el POS. |

**Reintentos automáticos de inciertas.** La tienda los tiene **apagados** (`app.pos.retry.enabled=false`). Se encienden por ambiente solo cuando el POS real haya demostrado, con las pruebas compartidas, que una clave repetida no produce un segundo efecto, ni siquiera después de reiniciarse (IMP-08, paso 6). Hasta entonces, una incierta que la consulta no resuelve la revisa una persona.

## 11. Errores y trazabilidad

**Formato de error**, en las dos direcciones:

```json
{ "success": false, "error": { "code": "OUT_OF_STOCK", "message": "texto para personas", "details": { } } }
```

| Código | HTTP | Dónde |
|---|---|---|
| `IDEMPOTENCY_CONFLICT` | 409 | venta |
| `IN_PROGRESS` | 409 | venta, consulta |
| `OUT_OF_STOCK` | 422 | venta |
| `VALIDATION` | 422 | venta, snapshot |
| `NOT_PROCESSED` | 404 | consulta |
| `UNSUPPORTED_CONTRACT` | 400 | todas |
| `UNAUTHORIZED` | 401 | todas |
| `FORBIDDEN_COMPANY` | 403 | todas |

- `message` no lleva datos personales del comprador ni secretos.
- **`X-Request-Id`:** cada petición lleva uno (`[A-Za-z0-9-]{8,64}`). Quien recibe lo registra en sus logs y lo devuelve en la respuesta, así una venta se sigue entre los dos sistemas con un solo id. La tienda manda el de la petición que originó la venta.
- **Tiempos de la tienda:** 5 s para conectar y 20 s para leer. El POS debe responder la venta en menos de 20 s. Si no puede, es preferible un `409 IN_PROGRESS` rápido a un timeout.
- **Registro:** cada lado guarda, por petición, la clave, el `X-Request-Id`, el resultado y la duración. Nunca guarda secretos, firmas completas ni datos de pago.

## 12. Impacto en uvo-store

Lo que había que cambiar para cumplir este contrato. Implementado el 26-nov-2026 (IMP-08, migración V38); el esquema
anterior (Bearer `uvp_` y `X-Signature`) desapareció.

| Pieza | Antes | Con el contrato | Estado |
|---|---|---|---|
| `PosClient` | Bearer `uvp_<empresa>_<secreto>` | Firma UVO-HMAC (§3) y `X-Uvo-Contract`, sobre los bytes exactos | Hecho |
| `PosClient.classify` | `409` = incierto | `409` por código; `422 OUT_OF_STOCK` como rechazada; `Retry-After` | Hecho |
| Consulta | No existía | `PosClient.lookup` y resolución de inciertas en `PosSaleOutboxHandler` (también de las huérfanas en vuelo y antes de cada reenvío) | Hecho |
| Cuerpo de la venta | `variant_id` y decimales | Enteros CLP, `product_id` del Producto POS de la variante, invariantes del §7 antes de enviar; el cuerpo se fija en la fila del outbox y los reintentos mandan los mismos bytes | Hecho |
| Mapeo de variantes (`ProductVariationSyncMapping`) | Variante del POS | Producto del POS, único por empresa y con su versión; se vincula a mano desde el panel | Hecho |
| Avisos y `/api/sync/product` | `X-Signature` y API key | `PosSignatureAuthFilter`: UVO-HMAC (§3), antirreplay por nonce | Hecho |
| `stock-updated` | Sin `version`, ordenado por `oldStock` | Con `version`; el stock se salta mientras haya ventas web sin registrar (§6) | Hecho |
| `PosConnection` | `apiKey` y `webhookSecret` | `pos_connection_keys`: `key_id` y secreto por dirección, rotación de 7 días y revocación | Hecho |
| Exento de IVA | No existía | `products.tax_exempt` y `product_variations.tax_exempt`; IVA 0 en la línea | Hecho |
| Doble del POS del E2E | Siempre 200, sin deduplicar | Verifica la firma, deduplica por empresa y clave, consulta y fallos inyectables | Hecho (sin escenarios E2E nuevos de fallo todavía) |

## 13. Impacto en UvoPOS (AlfozApi): brechas actuales

Esto se observó en el código de AlfozApi (rama `main`, commit `1977abe0`), al 20-nov-2026.

| Brecha | Dónde | Lo que pide el contrato |
|---|---|---|
| La única venta exige un usuario con JWT y una sesión de caja abierta | `CajaController` `POST /caja/sesion/{id}/vender` (línea 353); `Venta.sesion` NOT NULL | Endpoint de máquina por conexión, sin sesión de caja (§7) |
| El documento se emite dentro de la venta, con el tipo que pida la caja | `VentaService.registrarVenta` → `DteEmissionService.emitirDte` | La venta web siempre como 999 y en una sola transacción (§7) |
| Idempotencia con PK global, grabada **después** de emitir y sin comparar el cuerpo. Dos peticiones simultáneas emiten dos veces | `VentaIdempotencia` (PK `clave`); `VentaService.anotarClave` | `UNIQUE(company_id, clave)` con hash, en la misma transacción, y `IN_PROGRESS` (§8) |
| El folio del 999 es `System.currentTimeMillis()` y puede repetirse | `DteEmissionService` línea 796 | Folio único por empresa (§7) |
| Stock sin bloqueo ni versión; admite negativo | `InventarioService.registrarMovimiento` (línea 640); `Inventario` sin `@Version` | Descuento con bloqueo y `422 OUT_OF_STOCK` (§6) |
| `Producto` sin `version` ni stock web | `entity/Producto.java` | `version` monótono y `stock_web` (§5) |
| No envía webhooks | Solo hay un outbox de correo (`IntercambioOutboxService`) | Outbox de avisos firmados con reintento de 48 h (§5); el patrón de `ReferidoEventoProcesador` sirve de base |
| La llave de máquina es una sola, de plataforma | `IntegracionUvocmsProperties` (`key-id`, `secret`) | Llaves por empresa y por dirección (§2) |
| No hay endpoint de consulta ni de snapshot | | §5 y §9 |

**Advertencia.** Ninguna prueba de esta integración corre contra una base con el SII real. La base de desarrollo de AlfozApi es una copia de producción, y en ese repositorio un push a `main` despliega. Las pruebas compartidas usan una base descartable y documentos 999.

## 14. Puntos abiertos (para la reunión con el equipo POS)

1. **De qué bodega sale `stock_web`:** una bodega «web» por empresa o un porcentaje de la bodega principal. Quién la configura.
2. **Ventana de tiempo y retención de nonces:** aquí son 300 s y el doble de la ventana. Hay que confirmar que el reloj de los servidores se sincroniza (NTP).
3. **Límites de tasa** del snapshot y de los webhooks, y cuántos reintentos simultáneos acepta cada lado.
4. **Cliente de la venta 999:** consumidor final genérico, o los datos del comprador (nombre, RUT si lo dio). Hoy la tienda no los envía.
5. **Anulación:** cómo se anula en el POS una venta web reembolsada en la tienda. No está en esta versión.
6. **Datos de la empresa (propuesta de uvo-store).** Hoy la tienda solo conoce el `company_id` numérico. Nada confirma que esa empresa sea la de la tienda, y el POS no devuelve sus datos.
   - **Propuesta:** `GET /api/v1/company`, firmado con la llave tienda→POS (§3), sin cuerpo y sin parámetros. La empresa sale del `key_id` y de `X-Company-ID`, así que una conexión solo puede leer su propia empresa.
   - **Respuesta `200`:** `company_id`, `rut`, `razon_social`, `giro`, `direccion` y `comuna`. Son campos que la entidad `Empresa` de AlfozApi ya tiene. Es una lectura sin efectos y se puede repetir.
   - **Uso en la tienda:**
     - Al crear la conexión, comparar el `rut` con el RUT legal de la tienda (`store_settings.legal_tax_id`). Si no coinciden, la conexión no se activa.
     - Mostrar los datos al operador para que confirme la empresa y, si quiere, completar con ellos los datos legales de la tienda.
     - El POS sigue siendo el dueño de esos datos (§4). La tienda no los envía de vuelta.
   - **Por decidir con el equipo POS:**
     - Si se aprueba, qué campos son obligatorios.
     - Si un cambio de razón social o de dirección se avisa por webhook o solo se lee bajo demanda.
     - Si este endpoint entra en la versión 1 o en una versión menor posterior (es un cambio compatible).

## 15. Exclusión que vuelve a C01

Por decisión de este contrato, **la venta web se registra solo como Boleta NT 999**, un documento interno que no va al SII. **La boleta electrónica (39) queda fuera de esta integración.**

Eso separa «registrar la venta» de «emitir el documento tributario», como pide IMP-08 paso 2: el registro nunca emite al SII, y un reintento no puede duplicar una boleta ante el SII.

El checklist exige que toda exclusión vuelva a **C01**. Antes de aprobar este contrato hay que decidir y anotar en C01 cómo cumple la tienda su obligación de emitir el documento tributario de una venta web: desde el POS, aparte, o por otro medio.
