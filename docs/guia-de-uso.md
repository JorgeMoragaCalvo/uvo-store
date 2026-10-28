# Guía de uso de UvoStore

Esta guía describe el panel tal como está hoy en `uvo-store`: las pantallas de `frontend/src/admin` y lo que
hace el backend detrás de ellas. Al final hay una lista de limitaciones que conviene conocer antes de abrir una
tienda.

**Quién hace qué**
- **Operador de UvoPOS:** crea la tienda y la suspende, reactiva o cierra. Hoy esto se hace con la API de
  plataforma; solo el alta tiene pantalla.
- **Dueño de la tienda:** configura la tienda, carga el catálogo y atiende los pedidos desde el panel
  (`/admin`).
- **Equipo del dueño:** usa el panel con los permisos de su rol.

---

## 1. Alta de la tienda (operador)

1. Entra a `/plataforma/nueva-tienda`. Solo responde desde la red privada; desde fuera devuelve «no accesible
   desde esta red».
2. Completa:
   - **API key de plataforma.**
   - **Nick (subdominio interno):** en minúsculas, por ejemplo `tiendadejuan`. Define la dirección
     `tiendadejuan.<dominio de la plataforma>`.
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

## 3. Orden recomendado para dejar la tienda lista

| Paso | Dónde | Para qué |
|---|---|---|
| 1 | Config. general → General, Moneda, Checkout | Datos de contacto, impuesto y reglas de compra |
| 2 | Config. de tienda | Logo, colores, secciones del inicio y SEO |
| 3 | Categorías | Para poder clasificar los productos |
| 4 | Productos | El catálogo |
| 5 | Zonas, métodos y tarifas de envío | **Sin esto no se puede comprar con despacho** |
| 6 | Pasarelas de pago y Config. general → Stripe | Cobrar en línea |
| 7 | Banners y cupones | Opcional |
| 8 | Compra de prueba | Revisar el flujo completo antes de anunciar la tienda |

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
    sigue pagando su tarifa (ver la sección 12).
  - **Envío gratis habilitado** y **Monto mínimo para envío gratis:** desde ese monto de productos, el
    despacho no se cobra, sea cual sea la tarifa. La tienda lo anuncia en el encabezado («Envío gratis sobre
    $X»). El monto se compara antes de aplicar cupones. No da cobertura: una dirección que ninguna zona
    cubre sigue sin poder comprar.
  - **Costo de envío por defecto:** hoy no se usa (ver la sección 12).
- **Checkout:**
  - permitir la compra como invitado;
  - teléfono obligatorio;
  - empresa o razón social obligatoria.
- **Stripe:** **Stripe habilitado**, **Public key** y **Secret key**. Para activarlo hacen falta las dos
  claves.
- **POS:** ver la sección 11.
- **SEO** y **Redes:** meta título, descripción y keywords; enlaces a Facebook, Instagram y X.

Las claves secretas nunca se muestran. El campo dice «Configurado» o «No configurado», y si lo dejas en blanco
se conserva la clave guardada.

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
  - **Ofertas:** ver la sección 12.
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

1. **Zonas** (`Zonas de envío → Nueva`): qué lugares cubres. Las regiones y comunas se eligen de la lista
   oficial de Chile, no se escriben.
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
   - **Cotizar automáticamente con un transportista:** hoy no hay ningún transportista habilitado, así que
     se trabaja con tarifas propias.
3. **Tarifas** (`Tarifas de envío → Nueva`): cuánto cobra cada método en cada zona.
   - **Método** y **zona.**
   - **Tipo:**
     - **Monto fijo.**
     - **Por peso:** costo base más costo por kg adicional.
     - **Monto fijo (por precio).**
     - **Gratis.**
   - **Condiciones:** compra mínima y máxima, peso mínimo y máximo, y **Envío gratis desde**, que es el monto
     a partir del cual esa tarifa no cobra.

Si a una dirección le sirven varias tarifas, el comprador ve la **más barata**.

**Envío gratis: dos formas**
- **Para toda la tienda:** Config. general → Envío gratis, con un monto mínimo. Además, se anuncia en el
  encabezado de la tienda.
- **Solo para una tarifa:** «Envío gratis desde» en esa tarifa. Sirve, por ejemplo, para regalar el despacho en
  Santiago desde $30.000 y en regiones desde $60.000.

**Ejemplo**
- Zona «RM centro» con las comunas Santiago, Providencia y Ñuñoa.
- Método «Despacho a domicilio», con 1 a 3 días.
- Tarifa de monto fijo de $3.990, con envío gratis desde $40.000.
- Un segundo método, «Retiro en tienda», con una tarifa **Gratis** en la misma zona.

## 8. Pagos

| Medio | Dónde se activa | Qué necesita |
|---|---|---|
| **Pago contra entrega** | Siempre aparece en el checkout | Nada. El pedido queda **pendiente de pago** hasta que lo marques como pagado. |
| **Stripe (tarjeta)** | Config. general → Stripe | La clave pública y la secreta de tu cuenta de Stripe |
| **Webpay Plus** | Pasarelas de pago | El **código de comercio hijo** que Transbank te asignó bajo el Mall de la plataforma. El Mall lo configura el operador. |
| **MercadoPago** | Pasarelas de pago | El Access Token, la Public Key y el **secreto de webhook** de tu cuenta. Sin el secreto no se deja activar. |

Cada medio en línea aparece en el checkout solo cuando está **Habilitado** y guardado.

## 9. Cupones (`Cupones → Nuevo`)

- **Código:** se guarda en mayúsculas, por ejemplo `BIENVENIDA10`.
- Nombre y descripción, para uso interno.
- **Tipo:**
  - **Porcentaje,** con un **descuento máximo** opcional que pone tope a lo que descuenta.
  - **Monto fijo.**
- **Compra mínima.**
- **Vigencia:** desde y hasta.
- **Límites:** usos totales y usos por cliente.
- **Activo.**

## 10. Operación diaria

### Órdenes (`Órdenes`)

En la lista puedes filtrar por pestañas y buscar por número de orden o por cliente. En el detalle de una orden:

- **Productos y totales:** subtotal, descuento, envío, impuesto y total.
- **Dirección de envío** y **datos del cliente.**
- **Botones rápidos:**
  - **Marcar en proceso**, **Marcar enviada** y **Marcar entregada**;
  - **Cancelar orden.**
- **Estado de la orden:** PENDING, PAID, PROCESSING, SHIPPED, DELIVERED o CANCELLED.
- **Estado de pago:** PENDING, PAID o FAILED.
  - **Pago contra entrega:** cuando recibas el dinero, cambia el estado de pago a **PAID**.
  - Marcar una orden como pagada es lo que dispara el correo de compra confirmada y, si hay integración con
    UvoPOS, el envío de la venta al POS.
- **Seguimiento:** escribe el número del courier y pulsa **Guardar**.
- **Reembolsos:** solo para roles con el permiso de reembolsar, y solo en órdenes pagadas.
  - **Reembolsar:** devuelve el dinero por la pasarela. Si dejas el monto vacío, se devuelve todo el saldo.
  - **Registrar reembolso hecho en la pasarela:** si devolviste el dinero por fuera del panel. El motivo es
    obligatorio.
  - Una orden no se puede marcar como «devuelta» a mano: se llega a ese estado reembolsando.

**Flujo típico**
- **Pago en línea:** PAID → En proceso → Enviada, con el número de seguimiento → Entregada.
- **Contra entrega:** PENDING → (entregas y cobras) → pago PAID → Entregada.

### Clientes (`Clientes`)
- La lista de clientes.
- En el detalle: el historial de compras y las direcciones. Puedes marcar una dirección como predeterminada o
  eliminarla.

### Reportes (`Reportes`)
- Elige el rango de fechas **Desde** y **Hasta**.
- Hay pestañas de ventas, productos y medios de pago.

### Dashboard
- Es la portada del panel, con un resumen de la actividad. Lo ven todos los roles.

## 11. Equipo y UvoPOS

### Usuarios (`Usuarios → Nuevo`)
- Datos: nombre, correo, teléfono, **rol**, notas y avatar.
- **Enviar invitación:** viene activado. La persona recibe un correo y elige su propia contraseña. Si lo
  apagas, tienes que escribir tú la contraseña.
- **Activo:** si lo apagas, la persona ya no puede entrar al panel.

### Roles (`Roles → Nuevo`)
- Se le da un nombre y se marcan los permisos, agrupados por sección.
- En cada sección, **ver** permite consultar y **gestionar** permite crear y editar.
- Hay permisos aparte para **reembolsar** órdenes y para la configuración.
- Ejemplo de rol «Bodega»: ver órdenes y productos, sin pagos ni configuración.

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

## 12. Limitaciones actuales que conviene conocer

Son cosas que el panel muestra pero que hoy no hacen lo que parece, o que todavía no existen. Salen de la
revisión del código, no de pruebas en producción.

1. **«Envío habilitado» apagado no deja el despacho en cero.** Si una zona cubre la dirección, se cobra su
   tarifa. Para no cobrar despacho, usa tarifas «Gratis» o desactiva las zonas.
2. **«Costo de envío por defecto» de Config. general no se usa.** El costo sale siempre de las tarifas.
3. **El producto no tiene campo de peso.** Las tarifas «Por peso» y los límites de peso no tienen con qué
   calcular; usa tarifas de monto fijo.
4. **No hay precio de oferta en el formulario.** La sección «Ofertas» del inicio queda vacía; apágala en
   Config. de tienda.
5. **No hay variantes desde el panel,** como talla o color. Cada variante se carga como un producto aparte,
   igual que en UvoPOS, donde cada talla es un SKU.
6. **Texto contradictorio sobre Stripe.** La página de Pasarelas de pago dice que Stripe se configura en la
   plataforma, pero el checkout usa las claves de Config. general → Stripe de cada tienda.
7. **No hay consola para el operador.** Listar y buscar tiendas, suspenderlas, reactivarlas, cerrarlas o
   verificar su dominio solo se puede hacer llamando a la API de plataforma (`/api/platform/stores`). La
   única pantalla es la de alta.
8. **Contra entrega siempre visible:** no se puede apagar desde el panel.
