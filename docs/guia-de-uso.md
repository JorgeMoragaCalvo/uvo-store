# Guía de uso de UvoStore

Esta guía describe el panel tal como está hoy en `uvo-store` (actualizada el 8 de noviembre de 2026): las
pantallas de `frontend/src/admin` y lo que hace el backend detrás de ellas. Al final hay una lista de
limitaciones que conviene conocer antes de abrir una tienda.

**Quién hace qué**
- **Operador de UvoPOS:** crea la tienda y la suspende, reactiva o cierra. Hoy esto se hace con la API de plataforma; solo el alta tiene pantalla.
- **Dueño de la tienda:** configura la tienda, carga el catálogo y atiende los pedidos desde el panel (`/admin`).
- **Equipo del dueño:** usa el panel con los permisos de su rol.

---

## 1. Alta de la tienda (operador)

1. Entra a `/plataforma/nueva-tienda`. Solo responde desde la red privada; desde fuera devuelve «no accesible desde esta red».
2. Completa:
   - **API key de plataforma.**
   - **Nick (subdominio interno):** en minúsculas, por ejemplo `tiendadejuan`. Define la dirección `tiendadejuan.<dominio de la plataforma>`.
   - **Dominio propio:** opcional, por ejemplo `tiendadejuan.cl`.
   - **Nombre de la tienda**, **nombre del cliente** y **correo del cliente**.
3. Pulsa **Crear tienda**. La pantalla muestra la dirección de la tienda y la del panel. El cliente recibe un
   correo de invitación para elegir su contraseña; el operador nunca la conoce.
4. **Dominio propio:** mientras el operador no lo marque como verificado, la tienda y los correos usan el
   subdominio de la plataforma. La verificación es una marca manual (`PUT /api/platform/stores/{id}/domain/verified`)
   que se pone después de comprobar el DNS por fuera.

## 2. Primer ingreso al panel

1. Abre el enlace del correo de invitación (`/admin/aceptar-invitacion`), elige la contraseña y confírmala.
2. Desde entonces entras por `https://<tu tienda>/admin/login`.
3. Si olvidas la contraseña, usa **¿Olvidaste tu contraseña?** en el login. Si la invitación venció o no
   llegó, también sirve ese camino, o el operador puede reenviar el acceso del dueño.

El menú lateral muestra solo las secciones que permite tu rol. Si entras por URL a una sección sin permiso, el
panel dice «No tienes permiso para ver esta sección».

**La sesión**
- Dura 8 horas. Al vencer, el panel vuelve al login con el aviso «Tu sesión expiró. Vuelve a iniciar sesión.»
- **Salir** cierra tu sesión en todos los equipos donde hayas entrado, no solo en el actual.

## 3. Orden recomendado para dejar la tienda lista

| Paso | Dónde                                        | Para qué                                              |
|------|----------------------------------------------|-------------------------------------------------------|
| 1    | Config. general → General, Moneda, Checkout  | Datos de contacto, impuesto y reglas de compra        |
| 2    | Config. de tienda                            | Logo, colores, secciones del inicio y SEO             |
| 3    | Categorías                                   | Para poder clasificar los productos                   |
| 4    | Productos                                    | El catálogo                                           |
| 5    | Zonas, métodos y tarifas de envío            | **Sin esto no se puede comprar con despacho**         |
| 6    | Pasarelas de pago y Config. general → Stripe | Cobrar en línea                                       |
| 7    | Banners y cupones                            | Opcional                                              |
| 8    | Compra de prueba                             | Revisar el flujo completo antes de anunciar la tienda |

## 4. Configuración general (`Config. general`)

Todo se guarda con **Guardar cambios**, al pie de la página, sin importar en qué pestaña estés.

- **General:**
  - **Nombre**, **correo** y **teléfono** de la tienda.
  - **Correo de notificaciones:** adonde llegan los avisos de pedidos.
- **Moneda:**
  - Solo CLP.
  - **Tasa de impuesto (%):** 19 por defecto.
  - **Los precios ya incluyen impuesto:** actívalo si los precios que cargas son con IVA, que es lo habitual
    en Chile. Si está apagado, el impuesto se suma en el carrito.
- **Envío:**
  - **Envío habilitado:** con el interruptor encendido, solo pueden comprar las direcciones que alguna zona
    cubre. Si lo apagas, el checkout deja de exigir cobertura, pero una dirección que sí cae en una zona
    sigue pagando su tarifa (ver la sección 14).
  - **Envío gratis habilitado** y **Monto mínimo para envío gratis:** desde ese monto de productos, el
    despacho no se cobra, sea cual sea la tarifa. La tienda lo anuncia en el encabezado («Envío gratis sobre
    $X»). El monto se compara antes de aplicar cupones. No da cobertura: una dirección que ninguna zona
    cubre sigue sin poder comprar.
  - **Costo de envío por defecto:** hoy no se usa (ver la sección 14).
- **Checkout:**
  - permitir la compra como invitado;
  - teléfono obligatorio;
  - empresa o razón social obligatoria.
- **Stripe:** **Stripe habilitado**, **Public key** y **Secret key**. Para activarlo hacen falta las dos
  claves.
- **POS:** ver la sección 12.
- **SEO** y **Redes:** meta título, descripción y keywords; enlaces a Facebook, Instagram y X.

Las claves secretas nunca se muestran. El campo dice «Configurado» o «No configurado», y si lo dejas en blanco se conserva la clave guardada.

## 5. Apariencia de la tienda (`Config. de tienda` y `Banners`)

**Config. de tienda**
- **Identidad:**
  - nombre y descripción;
  - **logo** y **favicon:** se sube un archivo nuevo para reemplazarlos.
- **Colores de marca:** primario, secundario, acento y oscuro.
- **Secciones del inicio:** cada una se enciende o apaga, con título y límite de productos:
  - **Banner principal:** muestra los banners activos.
  - **Categorías.**
  - **Productos nuevos:** usa además «Días», que es cuántos días se considera nuevo un producto.
  - **Destacados:** muestra los productos marcados como «Destacado».
  - **Ofertas:** ver la sección 14.
  - **Banda de beneficios.**
- **Beneficios (4):**
  - Cada uno tiene ícono, título y descripción.
  - Íconos: `truck`, `shield-check`, `refresh` y `headset`.
  - Ejemplo: `truck` · «Despacho a todo Chile» · «En 2 a 5 días hábiles».
- **Contacto y redes:**
  - correo, teléfono y WhatsApp;
  - Facebook, Instagram, X y TikTok.
- **SEO.**

**Banners** (`Banners → Nuevo`)
- **Imagen de escritorio:** obligatoria. La imagen para móvil es opcional.
- **Contenido:** título, subtítulo y descripción.
- **Botones:** hasta dos, cada uno con texto y enlace. Pueden abrir en una pestaña nueva.
- **Estilo:**
  - posición del texto: izquierda, centro o derecha;
  - color del texto: claro u oscuro;
  - color y opacidad del velo, de 0 a 100: sube la opacidad si el texto no se lee sobre la foto.
- **Orden** y **Activo.**

## 6. Catálogo

### Categorías (`Categorías → Nueva`)
- Nombre, descripción y categoría padre si es una subcategoría.
- Imagen, orden y **Activa**.

### Crear un producto (`Productos → Nuevo`)

1. **Información general:**
   - **Nombre:** obligatorio.
   - **Descripción corta:** el resumen que aparece cerca del precio.
   - **Descripción.**
   - **Categoría.**
2. **Precio e inventario:**
   - **SKU:** tu código interno; también sirve para buscar el producto.
   - **Precio:** en pesos, sin decimales, en la misma base que definiste en «Los precios ya incluyen
     impuesto».
   - **Stock.**
   - **Gestionar stock:** si está encendido, la tienda descuenta las unidades vendidas y no deja comprar más de
     las que hay. Apágalo solo para productos sin inventario, como servicios o productos a pedido.
3. **Visibilidad:**
   - **Activo:** si lo apagas, el producto deja de verse en la tienda.
   - **Destacado:** lo muestra en la sección «Destacados» del inicio.
4. **Imágenes:**
   - **Imagen destacada:** la que se ve en el listado y en la ficha.
   - **Galería:** puedes elegir varias a la vez.
   - Al editar, cada foto tiene **Quitar**. Si quitas la destacada, otra toma su lugar.
5. Pulsa **Guardar**.

**Lista de productos**
- Busca por nombre o SKU.
- Activa o desactiva un producto sin entrar a editarlo.
- Elimina productos.

**Si la tienda está conectada a UvoPOS**
- Algunos campos aparecen bloqueados con la nota «Lo gestiona UvoPOS»: suelen ser nombre, precio, stock y
  SKU. Se cambian en el POS y llegan solos a la tienda.
- Las imágenes, la descripción corta, la categoría y la visibilidad se siguen editando en la tienda.

## 7. Envíos

El costo del despacho **sale de las tarifas**, salvo cuando aplica el envío gratis de Config. general. Una
dirección que ninguna zona cubre no puede comprar: el checkout dice «No hay envío disponible para la dirección
seleccionada». Se configura en tres pasos.

1. **Zonas** (`Zonas de envío → Nueva`): qué lugares cubres. Las regiones y comunas se eligen de la lista oficial de Chile, no se escriben.
   - **Regiones:** activa las regiones que cubre la zona.
   - **Comunas:** cada región elegida viene con **Toda la región** activado. Si lo apagas, aparece la lista de
     sus comunas, con un buscador, para marcar solo las que quieras.
   - **Zonas mezcladas:** en una misma región puedes tener una zona de región entera y otras solo con algunas
     comunas. Cada comuna elegida paga la tarifa de su zona, y el resto de la región paga la de la región
     entera. La zona más específica gana siempre, sin importar el orden.
   - **Orden:** solo desempata entre dos zonas del mismo tipo que cubren la misma comuna.
   - **Zonas antiguas:** si una zona se escribió a mano antes de la lista y tiene algo que no se reconoce, la
     lista de zonas la marca con **Revisar**. Lo no reconocido no cubre ni cobra; edita la zona y elige su
     equivalente.
   - En el checkout, el comprador elige siempre región y comuna, entre las que cubren tus zonas.
2. **Métodos** (`Métodos de envío → Nuevo`): cómo entregas.
   - Nombre, **código** (un identificador corto, como `courier-rm`) y descripción.
   - Tipo: Courier, Retiro en tienda o Personalizado.
   - Días mínimo y máximo de entrega: se muestran al comprador.
   - **Cotizar automáticamente con un transportista:** hoy no hay ningún transportista habilitado, así que se trabaja con tarifas propias.
3. **Tarifas** (`Tarifas de envío → Nueva`): cuánto cobra cada método en cada zona.
   - **Método** y **zona.**
   - **Tipo:**
     - **Monto fijo.**
     - **Por peso:** costo base más costo por kg adicional.
     - **Monto fijo (por precio).**
     - **Gratis.**
   - **Condiciones:** compra mínima y máxima, peso mínimo y máximo, y **Envío gratis desde**, que es el monto a partir del cual esa tarifa no cobra.

Si a una dirección le sirven varias tarifas, el comprador ve la **más barata**.

**Envío gratis: dos formas**
- **Para toda la tienda:** Config. general → Envío gratis, con un monto mínimo. Además, se anuncia en el encabezado de la tienda.
- **Solo para una tarifa:** «Envío gratis desde» en esa tarifa. Sirve, por ejemplo, para regalar el despacho en Santiago desde $30.000 y en regiones desde $60.000.

**Ejemplo**
- Zona «RM centro» con las comunas Santiago, Providencia y Ñuñoa.
- Método «Despacho a domicilio», con 1 a 3 días.
- Tarifa de monto fijo de $3.990, con envío gratis desde $40.000.
- Un segundo método, «Retiro en tienda», con una tarifa **Gratis** en la misma zona.

## 8. Pagos

| Medio                   | Dónde se activa                | Qué necesita                                                                                                            |
|-------------------------|--------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| **Pago contra entrega** | Siempre aparece en el checkout | Nada. El pedido queda **pendiente de pago** hasta que lo marques como pagado.                                           |
| **Stripe (tarjeta)**    | Config. general → Stripe       | La clave pública y la secreta de tu cuenta de Stripe                                                                    |
| **Webpay Plus**         | Pasarelas de pago              | El **código de comercio hijo** que Transbank te asignó bajo el Mall de la plataforma. El Mall lo configura el operador. |
| **MercadoPago**         | Pasarelas de pago              | El Access Token, la Public Key y el **secreto de webhook** de tu cuenta. Sin el secreto no se deja activar.             |

Cada medio en línea aparece en el checkout solo cuando está **Habilitado** y guardado.

Los reembolsos se hacen desde cada orden. Un reembolso por pasarela puede quedar «Pendiente» si la pasarela no
responde; en contra entrega el dinero se devuelve por fuera. Ver «Reembolsos» en la sección 10.

## 9. Cupones (`Cupones → Nuevo`)

- **Código:** se guarda en mayúsculas, por ejemplo `BIENVENIDA10`.
- Nombre y descripción, para uso interno.
- **Tipo:**
  - **Porcentaje,** con un **descuento máximo** opcional que pone tope a lo que descuenta. Se calcula sobre el
    precio **sin IVA**, aunque tus precios lo incluyan: el 10 % de un producto de $11.900 con IVA incluido descuenta $1.000, no $1.190.
  - **Monto fijo.**
- El IVA se calcula después del cupón, sobre lo que el comprador de verdad paga.
- **Compra mínima.**
- **Vigencia:** desde y hasta.
- **Límites:** usos totales y usos por cliente.
- **Activo.**

## 10. Operación diaria

### Órdenes (`Órdenes`)

En la lista puedes buscar por número de orden o por cliente, y filtrar con las pestañas **Todas**, **Pendientes**,
**Pagadas**, **En proceso**, **Enviadas**, **Canceladas** y **Sin stock**. En el detalle de una orden:

- **Productos y totales:** subtotal, descuento, envío y total. Si tus precios incluyen IVA, el impuesto no se
  suma: bajo el total aparece **«IVA incluido: $X»**. Si no lo incluyen, aparece la línea «Impuesto».
- **Dirección de envío** y **datos del cliente.**
- **Botones rápidos:**
  - **Marcar en proceso**, **Marcar enviada** y **Marcar entregada**;
  - **Cancelar orden.**
- **Estado de la orden:** PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED o REFUNDED.
  - Cuando se confirma el pago, la orden pasa sola a PROCESSING («En proceso»).
  - PAID aparece en la lista, pero no se usa: elegirlo da error, porque lo pagado se cambia en el estado de pago.
  - REFUNDED no se elige a mano: se llega reembolsando todo el saldo.
- **Estado de pago:** PENDING, PAID, FAILED o REFUNDED.
  - Marcar el pago como PAID es lo que dispara el correo de compra confirmada y, si hay integración con
    UvoPOS, el envío de la venta al POS.
  - Un pago no vuelve a PENDING a mano.
- **Seguimiento:** escribe el número del courier y pulsa **Guardar**.
- **Para enviar hace falta el pago:** «Marcar enviada», «Marcar entregada» y «Guardar» del seguimiento solo
  funcionan con el pago en PAID y sin productos faltantes (ver «Cobrada sin stock» más abajo).

**Flujo típico**
- **Pago en línea:** pago confirmado (la orden queda En proceso) → Enviada, con el número de seguimiento →
  Entregada.
- **Contra entrega:** PENDING → **Marcar en proceso** → entregas y cobras → pago PAID → Enviada → Entregada.

**Contra entrega: márcala en proceso el mismo día.** Cada hora, la tienda cancela sola las órdenes que llevan
más de 24 horas en PENDING con el pago pendiente, y deja el pago en FAILED. Lo hace también con las de contra
entrega. «Marcar en proceso» la saca de ese estado.

### Cancelar una orden

**Cancelar orden** se ejecuta con un clic, sin pedir confirmación. La orden pasa a CANCELLED, y el stock y el cupón
se devuelven.
- **Orden ya pagada:** cancelar **no devuelve el dinero**. El pago sigue en PAID; reembolsa desde la tarjeta
  «Reembolsos».
- **Orden con pago pendiente:** el pago pasa a FAILED. Si después la pasarela confirma el cobro, la orden no
  revive: hay que devolver ese dinero desde el panel de la pasarela (ver la sección 14, punto 12).

### Reembolsos

La tarjeta **Reembolsos** del detalle de la orden aparece solo para roles con el permiso de reembolsar
(`orders.refund`), y solo se puede usar en órdenes pagadas.

**Lo que muestra**
- **Devuelto** y **Por devolver.** Si hay reembolsos sin confirmar, agrega «Incluye $X sin confirmar.»
- Cada reembolso, con su tipo, motivo, referencia y una insignia de estado:
  - **Hecho:** la pasarela lo confirmó.
  - **Rechazado:** la pasarela lo rechazó. Aparece tachado y no cuenta en lo devuelto.
  - **Pendiente:** no se sabe si la pasarela lo hizo (ver abajo).

**Cómo reembolsar**
- **Monto (vacío = todo el saldo)** y **Motivo**, y luego:
  - **Reembolsar:** devuelve el dinero por la pasarela de la orden. Se ejecuta sin pedir confirmación.
  - **Registrar reembolso hecho en la pasarela:** si devolviste el dinero por fuera del panel. El motivo es obligatorio.
- **Contra entrega o transferencia:** no hay pasarela que devuelva el dinero. Devuélvelo por fuera y anótalo con
  «Registrar reembolso hecho en la pasarela».
- Si el reembolso cubre todo el saldo, la orden y el pago pasan a REFUNDED, y se devuelven el stock y el cupón.

**Lo que pasa según la pasarela**
- **Aprueba:** el reembolso queda «Hecho».
- **Rechaza:** queda «Rechazado» y el monto vuelve a estar disponible para otro intento.
- **No responde o se corta la conexión:** queda **«Pendiente»**, porque el dinero pudo haber salido o no. El
  monto queda reservado, y mientras haya un pendiente la orden no acepta otro reembolso.

**Cómo cerrar un reembolso pendiente**
1. **Recarga la página de la orden.** El aviso de error aparece al momento, pero la fila «Pendiente» con sus
   botones aparece recién al recargar.
2. Revisa en el panel de la pasarela si la devolución se hizo.
3. En la fila del reembolso:
   - **Sí se hizo:** escribe la **Referencia de la pasarela** y una **Nota** con lo que viste. El reembolso queda
     «Hecho» y, si no queda saldo, la orden pasa a REFUNDED.
   - **No se hizo:** escribe solo la **Nota**. El monto vuelve a estar disponible.
4. Ninguna de las dos mueve dinero: solo registra lo que pasó en la pasarela. La nota queda en el historial y en la auditoría.

**No uses «Registrar reembolso hecho en la pasarela» para cerrar un pendiente:** contaría el mismo dinero dos
veces.

### Cobrada sin stock

Puede pasar que dos compradores paguen la última unidad, o que el stock baje en el POS mientras alguien paga. La tienda conserva el pago y marca la orden.

**Dónde se ve**
- En el Dashboard, la tarjeta **«Órdenes sin stock»**.
- En Órdenes, la pestaña **Sin stock** y una insignia «Sin stock» en la orden y en cada producto que faltó.
- En el detalle, la tarjeta **«Cobrada sin stock»**, que dice qué productos faltaron.

**Qué queda bloqueado:** enviar, entregar y guardar el seguimiento. «Marcar en proceso» sigue disponible.

**Las tres salidas**
- **Reintentar descuento:** cuando repongas el stock. Si alcanza, la orden se puede despachar; si no, dice
  «Todavía falta stock para algunas líneas».
- **Liberar bloqueo:** si lo resolviste de otra forma, por ejemplo consiguiendo el producto por fuera. Exige una
  **Nota**, no descuenta stock y queda registrado.
- **Reembolsar:** si no vas a conseguir el producto, desde la tarjeta «Reembolsos».

Las dos primeras piden el permiso de gestionar órdenes.

### Clientes (`Clientes`)
- La lista de clientes.
- En el detalle: el historial de compras y las direcciones. Puedes marcar una dirección como predeterminada o eliminarla.

### Reportes (`Reportes`)
- Elige el rango de fechas **Desde** y **Hasta**.
- Hay pestañas de ventas, productos y medios de pago.

### Dashboard
- Es la portada del panel, con un resumen de la actividad. Lo ven todos los roles.
- Las tarjetas **«Órdenes sin stock»** y **«Envíos fallidos»** muestran lo que necesita que alguien lo revise.

## 11. Envíos fallidos (`Envíos fallidos`)

Algunas cosas salen de la tienda después de cada venta y se reintentan solas si fallan:
- **Venta al POS**, si la tienda está conectada a UvoPOS;
- **Correo de pedido recibido**;
- **Correo de confirmación de pago**;
- **Correo de invitación a crear cuenta**.

Cuando un envío se rechaza de forma definitiva, o sigue fallando después de 48 horas de reintentos, aparece en
esta página. Si no hay ninguno, dice «No hay envíos fallidos.»

**Permisos:** ver la página pide ver órdenes. Reintentar o marcar resuelto pide gestionar órdenes.

**Lo que muestra cada fila:** el tipo de envío, la orden (con enlace), el error, los intentos y el último intento. Puede llevar una de estas insignias:
- **Pudo haber llegado:** una venta al POS cuyo resultado no se sabe. El POS pudo haberla registrado.
- **Anterior al outbox:** una venta de antes del sistema actual de envíos. No se puede reintentar.

**Cómo resolverlo:** pulsa **Revisar**, escribe una **Nota** con lo que revisaste y elige:
- **Reintentar:** el envío vuelve a la cola y sale en la próxima pasada.
- **Marcar resuelta:** se cierra sin reenviar. Úsalo si lo resolviste por fuera, por ejemplo si la venta ya está
  en el POS o le avisaste al comprador por otro medio.

Las dos quedan anotadas en el historial de la orden y en la auditoría.

**Antes de reintentar una venta «Pudo haber llegado», búscala en el POS.** Si ya está, reenviarla puede emitir un
segundo documento tributario: márcala resuelta. Para reintentarla, el panel pide marcar la casilla «Revisé en el
POS que la venta no está».

**Lo que no aparece aquí:** los envíos que la tienda omite a propósito. Por ejemplo, el correo cuando la plataforma
no tiene configurado el servidor de correo, o la venta al POS cuando la integración está apagada.

## 12. Equipo y UvoPOS

### Usuarios (`Usuarios → Nuevo`)
- Datos: nombre, correo, teléfono, **rol**, notas y avatar.
- **Enviar invitación:** viene activado. La persona recibe un correo y elige su propia contraseña. Si lo apagas, tienes que escribir tú la contraseña.
- **Activo:** si lo apagas, la persona ya no puede entrar al panel.

### Roles (`Roles → Nuevo`)
- Se le da un nombre y se marcan los permisos, agrupados por sección. El formulario muestra el nombre técnico
  de cada permiso, por ejemplo `orders.view` o `orders.refund`.
- En cada sección, `.view` permite consultar y `.manage` permite crear y editar.
- Reembolsar tiene su propio permiso: `orders.refund`.
- En órdenes, conviene saber qué incluye cada permiso:
  - `orders.view`: ver órdenes y la página «Envíos fallidos».
  - `orders.manage`: cambiar estados, despachar, cancelar órdenes, resolver «Cobrada sin stock» y reintentar o
    resolver envíos fallidos.
  - `orders.refund`: reembolsar y cerrar reembolsos pendientes.
- Ejemplo de rol «Bodega»: `orders.view`, `orders.manage` y `products.view`, sin pagos, reembolsos ni
  configuración. Ten en cuenta que, para despachar, `orders.manage` también le permite cancelar órdenes.

### UvoPOS (`Config. general → POS`)
- Solo funciona si el operador habilitó la integración en el servidor. Si no, la pestaña lo dice y los
  campos están desactivados.
- **Campos:**
  - **Sincronización con POS habilitada**;
  - **URL de la API del POS**;
  - **Token** (`uvp_<empresa>_<clave>`);
  - **Secreto de webhook.**
- **Resincronizar ahora:** compara el stock y el precio con el POS y corrige las diferencias. Hace lo mismo
  que la pasada automática, pero en el momento.

**Qué se vende con el POS conectado**
- Solo los productos que vienen del POS. Un producto creado solo en el panel, sin la nota «Lo gestiona UvoPOS»,
  aparece en la tienda pero no se puede comprar, y el panel no avisa. Créalo en el POS.
- Un pedido lleva productos de una sola empresa del POS. Si el carrito mezcla empresas, el comprador ve en cada producto:
  - «Este producto no está disponible para la venta en línea»: el producto no está asociado a ninguna empresa del
    POS, o está asociado a más de una.
  - «Este producto se compra en un pedido aparte»: es de otra empresa.
- Las variantes, como talla o color, llegan desde el POS como variantes del mismo producto.

## 13. Lo que ve el comprador

- **Si un precio cambia mientras compra:** el checkout no crea la orden y dice «El total de tu pedido cambió de
  $X a $Y. Revisa el carrito y confirma de nuevo.» El carrito marca el producto con su precio anterior y el
  nuevo. Siempre se cobra el precio vigente.
- **Si la pasarela todavía no confirma el pago:** ve «Tu pago está en proceso», con el aviso de que recibirá un
  correo cuando se confirme y no tiene que pagar de nuevo.
- **Si Webpay rechaza el pago:** vuelve al checkout con su carrito intacto.

## 14. Limitaciones actuales que conviene conocer

Son cosas que el panel muestra pero que hoy no hacen lo que parece, o que todavía no existen. Salen de la
revisión del código, no de pruebas en producción.

1. **«Envío habilitado» apagado no deja el despacho en cero.** Si una zona cubre la dirección, se cobra su
   tarifa, y tampoco se aplica el envío gratis de toda la tienda. Para no cobrar despacho, usa tarifas «Gratis»
   o desactiva las zonas.
2. **«Costo de envío por defecto» de Config. general no se usa.** El costo sale siempre de las tarifas.
3. **El producto no tiene campo de peso.** Las tarifas «Por peso» y los límites de peso no tienen con qué
   calcular; usa tarifas de monto fijo.
4. **No hay precio de oferta en el formulario.** La sección «Ofertas» del inicio queda vacía; apágala en
   Config. de tienda.
5. **No hay variantes desde el panel,** como talla o color. Sin el POS, cada variante se carga como un
   producto aparte. Con el POS conectado, las variantes llegan desde él (sección 12).
6. **Texto contradictorio sobre Stripe.** La página de Pasarelas de pago dice que Stripe se configura en la
   plataforma, pero el checkout usa las claves de Config. general → Stripe de cada tienda.
7. **No hay consola para el operador.** Listar y buscar tiendas, suspenderlas, reactivarlas, cerrarlas o
   verificar su dominio solo se puede hacer llamando a la API de plataforma (`/api/platform/stores`). La
   única pantalla es la de alta.
8. **Contra entrega siempre visible:** no se puede apagar desde el panel.
9. **Las órdenes contra entrega se cancelan solas a las 24 horas** si siguen en PENDING con el pago pendiente,
   igual que un pago en línea abandonado. Márcalas en proceso antes (sección 10).
10. **Un reembolso pendiente aparece recién al recargar la página.** Cuando la pasarela no responde, el panel
    muestra el aviso pero no actualiza la tarjeta de Reembolsos hasta que recargas.
11. **El historial de la orden no se ve en el panel.** Las notas que la tienda agrega (cambios de estado,
    reembolsos resueltos, envíos reintentados, stock faltante) se guardan, pero el detalle de la orden no las muestra.
12. **Un cobro que llega sobre una orden ya cancelada no se ve ni se puede reembolsar desde el panel.** Pasa
    cuando cancelas una orden con el pago pendiente y la pasarela confirma el cobro después. La orden sigue
    cancelada, el aviso queda en el historial (que no se ve) y lo recibe el operador. El pago queda en FAILED,
    así que el panel no deja reembolsarlo: la devolución se hace directamente en el panel de la pasarela.
