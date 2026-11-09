# UvoStore: plan de profesionalización y puesta en producción

**Fecha:** 7 de octubre de 2026.  
**Base revisada:** repositorio local, commit `b64ac00`, más el estado de trabajo disponible.  
**Estado del documento:** propuesta técnica y lista de aceptación; las tareas pendientes no se consideran implementadas por aparecer aquí.  
**Destinatarios:** responsable de producto, ingeniería, operaciones y equipo que habilita tiendas.  
**Actualización del 13 de octubre de 2026:** el e-commerce pasa a ser un módulo de UvoPOS, que es el maestro del catálogo, los precios y el stock, y se desplegará en su plataforma. Cambian §2, §3, §4.1, §5.1 (PROD-01 y PROD-12), §7.1, §9.2 y §13, y se añaden §5.4 (tareas `POS-1` a `POS-7`) y §6.5. El orden de ejecución, la dificultad de cada tarea y su división en subtareas (PROD-05a, PROD-13b…) están en `docs/orden-tareas-produccion.md`, que es un documento local: `.gitignore` lo excluye.  
**Actualización del 8 de noviembre de 2026 (GAP-17):** base `b03d275`. Se actualizan los estados de §5 y las afirmaciones de §2, §3, §6, §7, §9, §10, §11, §12 y §14 que el código ya contradecía. Entre el 7 de octubre y esta fecha se cerraron en código las subtareas de PROD y POS indicadas en §5, y las brechas GAP-01 a GAP-09, GAP-11 y GAP-13 de `docs/informe-brechas-produccion-2026-10-30.md`. «Cerrada en código» significa que el repositorio tiene la implementación y sus pruebas; la validación en staging, cuando el criterio la exige, sigue pendiente. Los criterios de aceptación no cambian.

## 1. Dictamen y alcance

UvoStore tiene una base funcional considerable: backend, storefront, panel administrativo, aislamiento por tienda, permisos, integraciones de pago, migraciones y pruebas automatizadas. Puede evolucionar a producción manteniendo su arquitectura de monolito modular con un frontend compartido.

**No se debe declarar listo para un lanzamiento comercial general con la evidencia disponible.** Faltan infraestructura reproducible, validación del recorrido completo por dominio, recuperación demostrada, operación observable y cierre de funciones que hoy ofrecen enlaces o integraciones incompletos. Crear una tienda `ACTIVE` en la base de datos no demuestra que un comprador pueda visitarla y completar una compra por HTTPS.

La preparación profesional se medirá por resultados verificables: despliegues repetibles, datos recuperables, aislamiento comprobado, pagos conciliables, funciones completas y personas responsables de atender incidentes. Este plan no exige microservicios, Kubernetes ni una reescritura para alcanzar esos resultados.

### 1.1 Qué se revisó y qué queda por demostrar

Se revisaron los flujos de alta, resolución de tiendas, configuración, seguridad, CI y releases, almacenamiento, correo, pagos, eventos, invitaciones y rutas del frontend. Se consultó documentación oficial para fundamentar recomendaciones de operación.

En la evaluación inmediatamente anterior se ejecutó `StoreOnboardingTest`: **7 pruebas aprobadas, 0 fallos y 0 errores**. Ese resultado valida el alta y acceso al panel en integración; no certifica DNS, HTTPS, navegador, correo ni pasarelas reales.

Para elaborar este documento no se ejecutó nuevamente la suite completa, una prueba de carga, un pentest, un despliegue ni una restauración. Tampoco se inspeccionó una cuenta de hosting o la configuración remota de GitHub. Por tanto:

- «Implementado» significa que existe evidencia en el código; su operación real debe validarse en staging.
- «Pendiente confirmado» corresponde a una carencia identificada en código o configuración del repositorio.
- «Por verificar» significa que no hay evidencia suficiente para afirmar su cumplimiento o ausencia fuera del repositorio.
- Las cifras de capacidad, SLO, RPO y RTO de este documento son **objetivos propuestos**, no mediciones ni compromisos ya acordados.

Los hallazgos históricos `C1`, `A1`, `F01`–`F24` no se reabren automáticamente. Los identificadores de este plan usan el prefijo `PROD-` y son independientes de las auditorías anteriores. No se utilizarán porcentajes antiguos de cobertura como si describieran el estado actual.

## 2. Estado actual y consecuencias para el lanzamiento

Estado al 8 de noviembre de 2026.

| Área | Evidencia actual | Consecuencia |
|---|---|---|
| Alta de tiendas | Formulario de plataforma y API crean la tienda, el propietario invitado y el rol con permisos; la respuesta entrega `storefrontUrl` y `adminUrl`. UvoPOS puede pedir el alta con la conexión incluida (POS-6). Ciclo de vida por API: suspender, reactivar, recuperar acceso y cerrar (PROD-23) | Falta la verificación pública de la tienda por HTTPS y una consola para el operador (hoy solo la API y la pantalla de alta) |
| Multi-tenancy | Resolución por dominio y slug exacto `<slug>.<dominio-plataforma>` (PROD-05a); JWT ligado a tienda; pruebas de aislamiento, incluido Stripe (GAP-11) | Conservar las garantías y probarlas también detrás del proxy y caché reales |
| Enlaces públicos | `StorePublicUrlResolver` arma recuperación, invitaciones, retornos de pago e imágenes con el dominio de cada tienda (PROD-04, PROD-05b) | Probar en staging con dos tiendas por HTTPS (PROD-14b) |
| Invitación de administradores | Backend, correo, pantalla `/admin/aceptar-invitacion` y reenvío controlado (PROD-06) | Implementada; falta probarla con correo real (PROD-10) |
| Cuenta de comprador | Diferida: `CUSTOMER_ACCOUNTS_ENABLED=false` apaga la API, la invitación y las promesas de la interfaz (PROD-07) | Compra solo como invitado hasta completar el recorrido |
| Seguridad | Permisos, revocación JWT, limitación de intentos, HMAC, validación de imágenes, cifrado, auditoría (PROD-18), validación de configuración de producción (PROD-08a), CSP y cabeceras, y cierre de sesión que revoca (GAP-13) | Faltan controles operativos, cabeceras del proxy y revisión de la exposición final (PROD-17e) |
| Pagos | Validación de importe, control de transiciones, conciliación, intención persistente de reembolso con resolución de inciertos (GAP-01/02), IVA sobre la base descontada (GAP-05), precio vigente en el checkout (GAP-07) y E2E con pasarelas simuladas (GAP-08) | No reemplaza la homologación de cada pasarela, cuenta comercial y callback público |
| Stock y efectos posteriores al commit | Actualizaciones condicionales e idempotencia; outbox transaccional para la venta al POS y los correos (V25) y barrido de stock pendiente (PROD-13); orden cobrada sin stock marcada y con despacho bloqueado (GAP-03); pruebas de interrupción (GAP-04) | La alerta efectiva sobre pendientes es parte de PROD-15c |
| Correo | SMTP con timeouts; sin configuración omite el envío; correos de pedidos por el outbox con reintento durable; los fallidos se ven y reintentan en el panel (GAP-04) | Falta evidencia de entrega real, SPF/DKIM/DMARC y supervisión (PROD-10); la invitación al propietario de POS-6 no pasa por el outbox |
| Envíos | Tarifas y zonas sobre el catálogo oficial de comunas; transportistas excluidos por defecto (`SHIPPING_CARRIERS_ENABLED` vacío, PROD-12); Correos de Chile es un placeholder | Lanzar solo modalidades comprobadas |
| Integración con UvoPOS | Campos del POS bloqueados en el panel (POS-2), entradas ordenadas e idempotentes (POS-3), conciliación periódica (POS-4), variantes (POS-5), alta desde el POS (POS-6) y una empresa POS por orden (GAP-06). Sigue apagada por defecto (`POS_ENABLED=false`) y con contrato sin aprobar (POS-1) | El POS es el maestro del catálogo, los precios y el stock: la integración entra al lanzamiento (§5.4, §6.5) |
| Archivos | Drivers local y S3; validación de formato; con S3 en producción la URL pública debe ser https | Provisionar persistencia, permisos, respaldo y prueba de recuperación |
| Base de datos | PostgreSQL, Flyway hasta V34, validación del esquema; rol de migración separado del de la aplicación (`FLYWAY_USER`, PROD-08a) | Definir operación, migración controlada y restauración |
| CI y release | Backend `clean verify` con umbral JaCoCo del 76 %, frontend lint/test/build, E2E Playwright, dependency-review, Dependabot y Qodana; la release exige el E2E del mismo commit (GAP-09) | Publicar un release de GitHub no despliega ni valida producción |
| Observabilidad | Logs con request ID y tienda, Sentry con ambiente y versión, probes de liveness y readiness, métricas Prometheus de negocio y de pendientes (PROD-15a, PROD-15b) | Falta que las alertas lleguen a una persona y los runbooks ensayados (PROD-15c) |
| Escalamiento | Rate limiting y caché de revocación JWT locales; antirreplay del POS en tabla (POS-3); advisory locks en los jobs salvo `AbandonedOrderJob` | No habilitar varias réplicas sin PROD-26 |
| Documentación comercial | Páginas legales genéricas compartidas (GAP-18) | Ajustar contenido y responsabilidades reales por tienda antes de publicar |
| Contrato de API | Swagger apagado en producción; la spec OpenAPI está versionada en `docs/api/` y `OpenApiSpecTest` falla si la API cambia sin regenerarla (GAP-17) | Mantenerla regenerada con cada cambio de API |

Referencias de código y documentos en la sección 14.

## 3. Alcance recomendado de la primera versión comercial

Propuesta inicial: tiendas chilenas, CLP, compras como invitado, un backend activo, PostgreSQL administrado, almacenamiento persistente, correo transaccional y únicamente medios de pago y envío validados. La infraestructura es la de la plataforma de UvoPOS (§4.1); su dimensionamiento depende del volumen y de los requisitos de disponibilidad.

Debe existir un acuerdo de alcance que indique qué se vende y qué no está disponible. Una función diferida se oculta o deshabilita en sus puntos de entrada, API y correos cuando corresponda. Un texto de «próximamente» no vuelve seguro un flujo de cobro o una integración accesible.

| Capacidad                                     | Condición para la primera salida                                                                                             |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| Crear tienda y entregar acceso al propietario | Obligatoria y verificada por HTTPS                                                                                           |
| Catálogo, carrito, checkout y seguimiento     | Obligatorios para la modalidad de venta ofrecida                                                                             |
| Pago manual                                   | Puede ser la única modalidad inicial si existe un proceso real de conciliación, vencimiento y confirmación                   |
| Stripe, Webpay o MercadoPago                  | Obligatorio validar cada uno que se habilite; no es necesario lanzar los tres juntos                                         |
| Tarifas de envío propias o retiro             | Alternativa inicial si coincide con la operación del comerciante y funciona de extremo a extremo                             |
| Chilexpress y Correos de Chile                | Diferidos (PROD-12): Correos no es configurable y Chilexpress exige homologación para activarse                              |
| Invitación de administradores                 | Implementada (PROD-06): pantalla, reenvío e invalidación del token anterior. Falta probarla con correo real                  |
| Cuentas de compradores                        | Diferidas con `CUSTOMER_ACCOUNTS_ENABLED=false` (PROD-07); se reactivan solo al cerrar todo el flujo                         |
| Integración con UvoPOS                        | Obligatoria: el POS es el maestro del catálogo, los precios y el stock, y cada venta web debe quedar registrada en él (§6.5) |
| Emisión de documentos por el POS              | Opcional por tienda; la que no la use necesita un proceso externo acordado. No prometer emisión automática sin validación    |
| Varias réplicas y alta disponibilidad         | Diferibles para un piloto con disponibilidad y recuperación explícitamente acordadas                                         |

La creación de la tienda no carga productos, no configura una cuenta comercial de pagos y no demuestra cobertura de despacho. La habilitación técnica y la preparación comercial de cada tienda deben registrarse por separado.

## 4. Arquitectura de producción inicial

```mermaid
flowchart LR
    U[Compradores y administradores] --> D[DNS y dominios verificados]
    D --> E[Entrada HTTPS / proxy]
    E --> F[Frontend estático compartido]
    E --> B[Backend Spring Boot]
    B --> P[(PostgreSQL privado)]
    B --> S[Almacenamiento de imágenes]
    B --> M[Correo transaccional]
    B --> G[Pasarelas habilitadas]
    G --> E
    B --> O[Logs, métricas y alertas]
    P --> R[Respaldos y recuperación]
    S --> R
```

### 4.1 Decisiones que se deben registrar antes de contratar y desplegar

Crear registros breves de decisión arquitectónica —ADR— con contexto, opciones, elección, consecuencias y responsable:

| Decisión                                                   | Entregable                                                                           |
|------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Proveedor, región, presupuesto y soporte                   | Presupuesto mensual desglosado y responsables de las cuentas                         |
| Disponibilidad, pérdida de datos y recuperación tolerables | SLO, RPO y RTO aceptados por negocio                                                 |
| Forma de ejecutar el backend                               | Contenedor o servicio de sistema reproducible, supervisado y con límites de recursos |
| Dominio de plataforma y dominios propios                   | Procedimiento de DNS, prueba de propiedad, TLS y renovación                          |
| Almacenamiento de imágenes                                 | Volumen durable respaldado o bucket S3 compatible con política de acceso documentada |
| Correo y pasarelas iniciales                               | Cuentas verificadas, responsables y separación de credenciales por ambiente          |
| Alcance de cuentas de comprador, POS y transportistas      | Funciones habilitadas y condiciones para activar las diferidas                       |

Los costos deben incluir cómputo, base de datos, respaldos, transferencia, imágenes, correo, observabilidad, DNS, contingencia y atención operativa. No estimar rentabilidad usando solo el costo del servidor. Dimensionar con mediciones de staging antes de cerrar capacidad.

**Decidido el 13 de octubre de 2026:** el e-commerce se despliega en la plataforma de UvoPOS, un SaaS en la nube con infraestructura en producción que mantiene el mismo equipo. Se comparte la infraestructura —proxy, servidor PostgreSQL, respaldos, gestión de secretos, correo, observabilidad y pipeline—, pero el e-commerce sigue siendo **un servicio y una base de datos propios**: base y rol separados en el mismo servidor, e integración con el POS por contrato HTTP sobre red privada (§6.5). Falta registrar el ADR y resolver lo que la plataforma no tenía: subdominio por tienda, dominios propios, entrada pública para compradores y webhooks de pasarelas. Si la plataforma despliega por defecto con varias réplicas o con rolling, el e-commerce va con una sola réplica y sin solapamiento hasta cerrar PROD-26.

### 4.2 Ambientes y red

Mantener desarrollo, CI, staging y producción separados. Staging debe reproducir proxy, HTTPS, rutas, almacenamiento y comportamiento de jobs de producción, con datos sintéticos o anonimizados y credenciales de prueba.

La base de datos y el puerto del backend no deben quedar accesibles directamente desde Internet. Publicar solo la entrada HTTPS necesaria. Administrar infraestructura mediante identidades individuales, MFA y permisos mínimos; disponer de un acceso de emergencia controlado.

Servir `/api/**` desde el backend y las rutas de la SPA desde su `index.html`. Configurar explícitamente `/uploads/**` cuando se use almacenamiento local. No enviar una llamada API inexistente al HTML de la SPA. Probar recarga directa en `/shop`, productos, `/admin/login` y enlaces de recuperación.

El proxy debe conservar el host validado que identifica a la tienda: el valor predeterminado de una configuración de proxy no debe sustituirlo por el host interno del backend. La documentación de [NGINX sobre cabeceras hacia el upstream](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header) fundamenta esta configuración; debe adaptarse al proxy elegido.

### 4.3 DNS, TLS y cachés

- Configurar dominio de plataforma y subdominios; si se usa wildcard, comprobar DNS y certificado para esos nombres. El dominio raíz requiere su tratamiento correspondiente.
- Verificar propiedad de dominios propios antes de asignarlos y emitir certificados. Registrar estados como pendiente, verificado, certificado y operativo en el procedimiento o implementación elegida.
- Automatizar renovación, alertar por vencimiento y definir retirada o cambio de dominio sin dejar asociaciones ajenas activas.
- Rechazar hosts desconocidos en la entrada. El resolver solo acepta como fallback `<slug>.<dominio-plataforma>` exacto (PROD-05a; antes tomaba el primer segmento de cualquier hostname); el proxy y la política de dominios deben limitar los nombres realmente admitidos.
- Redirigir HTTP a HTTPS. Aplicar HSTS cuando todos los nombres afectados estén preparados; no activar `includeSubDomains` o preload sin revisar ese alcance.
- Separar caché por hostname y URL. Nunca compartir respuestas autenticadas, pedidos o configuración de tiendas bajo una clave de caché común.
- Dar larga duración a assets con hash; permitir actualización o revalidación de `index.html`. Comprobar que un despliegue no deja HTML apuntando a chunks eliminados.

**Aceptación:** dos tiendas con contenido diferente se visitan simultáneamente, con caché habilitada, sin mezclar catálogo, branding, imágenes, sesiones ni respuestas de API.

## 5. Backlog priorizado y criterios de cierre

**P0:** bloqueo del lanzamiento dentro del alcance habilitado.  
**P1:** requisito antes de ampliar el piloto a operación general; durante el piloto necesita mitigación, responsable y fecha.  
**P2:** mejora posterior o requisito previo a una ampliación concreta.

Los responsables son roles sugeridos, pendientes de asignarse a personas. Una sola persona puede cubrir varios roles, pero cada tarea debe tener un único responsable de cierre. Marcar algo como verificado requiere adjuntar evidencia de la versión y ambiente evaluados.

### 5.1 Bloqueos de lanzamiento

| ID | Estado y trabajo necesario (al 8-nov-2026) | Responsable sugerido | Criterio de aceptación |
|---|---|---|---|
| PROD-01 | **Decidido en parte:** despliegue en la plataforma de UvoPOS (§4.1). Falta el ADR, los objetivos y la lista de funciones habilitadas | Producto + líder técnico | ADR y lista de funciones habilitadas aprobados; SLO/RPO/RTO y costos registrados |
| PROD-02 | **Pendiente, fuera del módulo:** el repositorio no tiene empaquetado ni instrucciones de staging; corresponde a la plataforma | Operaciones | Crear staging desde instrucciones versionadas y desplegar artefactos identificados, sin depender del equipo local |
| PROD-03 | **Pendiente, fuera del módulo:** DNS, TLS, rutas y dominios. En el código solo existe la verificación manual del dominio propio (`domain_verified_at`, V23) | Operaciones + backend | Dos tiendas operativas por HTTPS; dominio propio verificado; renovación ensayada; host desconocido rechazado |
| PROD-04 | **Cerrada en código** (PR #55): `StorePublicUrlResolver` sustituye a `APP_FRONTEND_URL`. Falta la prueba por HTTPS con dos tiendas, dentro de PROD-14b | Backend | Recuperación, invitaciones, retornos y webhooks usan el dominio HTTPS correcto en dos tiendas; no usan localhost ni host arbitrario |
| PROD-05 | **Parcial:** 05a limita el fallback al slug exacto de la plataforma (PR #59); 05b arma las URLs de imágenes con el resolver (PR #61). Falta 05c, la frontera del proxy: proxies de confianza, `Host` original y backend sin puerto público | Operaciones + seguridad | Conservar tenant; spoofing de cabeceras no elude límites; clientes distintos no comparten un único contador; backend sin acceso público directo |
| PROD-06 | **Cerrada** (PR #54 y #56): pantalla `/admin/aceptar-invitacion`, reenvío con límite e invalidación del token anterior | Frontend + backend | Abrir email, definir contraseña, entrar con permisos; token inválido, expirado o usado muestra respuesta útil; reenvío controlado e invalidación del anterior |
| PROD-07 | **Resuelto por la segunda vía: diferido con interruptor** (`CUSTOMER_ACCOUNTS_ENABLED=false`) | Producto + frontend/backend | Cumplido deshabilitando: `/api/customer/**` → 404, el checkout no genera invitación, no sale el correo, y el 👤 del header y el §3 de los términos ya no prometen cuenta. Comprobado por `CustomerAccountsDisabledTest` y `Header.test.tsx` |
| PROD-08 | **Parcial:** 08a (PR #83) valida la configuración al arrancar con `APP_ENVIRONMENT=production` o `staging` (`ProductionConfigurationRules`) y separa el rol de Flyway (`FLYWAY_USER`). Falta 08b: secretos nuevos, base y rol propios en la plataforma, rotación documentada | Operaciones + seguridad | Secretos nuevos fuera del repo; DB sin superusuario; rotación y restauración de claves documentadas; ningún fallback de desarrollo activo |
| PROD-09 | **Parcial, mínimo:** 09a (igual a 05b). Faltan respaldos con PITR, bucket S3, custodia de la clave de cifrado y un simulacro de restauración junto con el POS | Operaciones + backend | Reinicio y redeploy conservan imágenes; restauración aislada de DB, archivos y claves cumple RPO/RTO medidos |
| PROD-10 | **Parcial:** los correos de pedidos van por el outbox con reintento durable (PR #78), y los fallidos se ven y reintentan en el panel (GAP-04, PR #108). Falta la entrega real y SPF/DKIM/DMARC; la invitación al propietario de POS-6 no es durable | Operaciones + backend | Entrega real de recuperación, invitación y mensajes de pedidos; SPF/DKIM/DMARC revisados; fallos visibles y recuperables |
| PROD-11 | **Parcial; lo del módulo está cerrado:** clasificación del timeout de reembolso (GAP-01, PR #100), resolución de intenciones inciertas (GAP-02, PR #102), última unidad (GAP-03, PR #103), IVA con cupón (GAP-05, PR #105), cambio de precio (GAP-07, PR #107), E2E de fallos de pasarela (GAP-08, PR #109) y Stripe entre tiendas (GAP-11, PR #115). Falta la homologación con credenciales y callbacks reales | Backend + QA + negocio | Matriz de escenarios de sección 9 superada; credenciales correctas por tienda; conciliación y resolución de reembolsos inciertos demostradas |
| PROD-12 | **Transportistas: resuelto por exclusión** (`SHIPPING_CARRIERS_ENABLED` vacío). **POS: reabierto el 13-10-2026** y trasladado a §5.4; `POS_ENABLED=false` se mantiene hasta encenderlo en PROD-14b | Integraciones + producto | Cumplido excluyendo: Correos de Chile no es configurable (no implementado), Chilexpress exige homologación y rechaza el arranque sobre el sandbox, el cotizador omite filas previas, `/api/sync/**` y `/api/webhooks/pos/**` → 404, no se notifica ninguna orden al POS y conectarlo exige secreto de webhook. Comprobado por `AdminShippingCarrierGateTest`, `CarrierQuoteGateTest`, `PosSurfaceDisabledTest`, `PosNotifierDisabledTest`, `PosConnectionActivationTest`, `MethodForm.test.tsx` y `GeneralSettings.test.tsx`. Para el POS, la exclusión ya no es el cierre: rige el criterio de POS-1 a POS-4 |
| PROD-13 | **Cerrada en código:** 13a, reclamo condicional de stock y barrido (`StockRecoveryJob`); 13b, outbox durable con la venta al POS antes que los correos (V25). Ambas en PR #78. GAP-04 (PR #108) agrega pruebas de interrupción y la revisión manual de fallos. Falta la alerta efectiva (PROD-15c); `POS_RETRY_ENABLED` espera la idempotencia del receptor (POS-1) | Backend | Caída entre confirmación y efecto no deja stock o notificaciones críticos perdidos; reanudación idempotente y alerta sobre pendientes |
| PROD-14 | **Parcial:** 14a, E2E local con Playwright, dos tiendas y dobles de POS y SMTP (PR #94); fallos de pasarela simulados y móvil (GAP-08, PR #109). Falta 14b: aceptación en staging por HTTPS con el POS real | QA + frontend/backend | Navegador real, dos tenants y proxy real: alta, login, configuración, compra, pago, stock, seguimiento, correo y devolución |
| PROD-15 | **Parcial:** 15a, probes y Sentry con ambiente y versión (PR #61); 15b, logs correlacionables, métricas Prometheus y pendientes en el panel (PR #89). Falta 15c: alertas que llegan a una persona y runbooks ensayados | Operaciones + soporte | Alerta inyectada llega a una persona; panel muestra salud y pendientes; procedimientos de sección 11 ensayados |
| PROD-16 | **Parcial, mínimo:** la release exige el E2E del mismo commit (GAP-09, PR #110). Falta promover el artefacto, migrar con su rol, drenar, smoke tests y rollback | Operaciones + backend | Promover el mismo artefacto probado, validar migración, drenar solicitudes, ejecutar smoke tests y volver a versión compatible |
| PROD-17 | **Parcial:** 17a, sin `token_ws` en los logs; 17b, consultas con tienda (`RepositoryTenantScopeTest`); 17c, interruptor de Swagger (las tres en PR #60). Stripe entre tiendas (GAP-11, PR #115). 17d en parte: CSP, cabeceras y cierre de sesión que revoca (GAP-13, PR #116). Faltan las cabeceras del proxy y 17e, la revisión del sistema desplegado | Seguridad + producto | Sin hallazgos críticos/altos explotables sin resolver; accesos, aislamiento, políticas y datos publicados revisados |

### 5.2 Antes de ampliar a operación general

| ID | Trabajo | Estado (al 8-nov-2026) | Responsable sugerido | Criterio de aceptación |
|---|---|---|---|---|
| PROD-18 | Auditoría de operaciones administrativas y de plataforma | **Cerrada** (PR #86): `audit_events` (V28) y `AuditTrail`. Falta decidir la retención | Backend + seguridad | Actor, tienda, acción, fecha, resultado y correlación para cambios sensibles; sin secretos ni datos excesivos |
| PROD-19 | Identidad de operadores y MFA para accesos privilegiados | **Parcial:** `X-Platform-Operator` opcional (PROD-18) y `/api/platform/**` solo desde la red privada (POS-6). Falta hacer obligatorio el operador, la identidad y el MFA del POS, y rotar la clave | Seguridad + backend | Operadores individualizados y revocables; MFA/SSO o control equivalente implementado; acciones atribuibles |
| PROD-20 | Revisar sesiones del panel y protección frente a XSS | **Decisión tomada** (GAP-13, PR #116): el token sigue en el navegador, con CSP, cierre de sesión que revoca en el servidor, vencimiento de 8 h y lint contra inserción de HTML. Se revisa con POS-7 | Frontend + seguridad | Decisión documentada sobre token persistido, expiración, CSP y cierre de sesión; pruebas de ataques y revocación |
| PROD-21 | Capacidad, cuotas y aislamiento de consumo entre tiendas | **Pendiente, fuera del módulo** (GAP-10): exige el entorno real | Backend + operaciones | Carga objetivo sostenida; una tienda no agota conexiones, archivos o reportes de las demás; límites medidos |
| PROD-22 | Reforzar cadena de suministro y reglas de repositorio | **Parcial:** Dependabot, dependency-review y `CODEOWNERS` (PR #62); la release exige el E2E (GAP-09). Falta en GitHub: protección de rama, checks obligatorios y secret scanning | Líder técnico | CI obligatorio, revisión de cambios sensibles, análisis de dependencias/secretos, artefactos trazables y actualización periódica |
| PROD-23 | Completar operación de plataforma | **Cerrada por API** (PR #87, V29): `PlatformStoreController` lista, cambia dominio, suspende, reactiva, recupera acceso y cierra, con auditoría. No hay consola: el cliente previsto es el backoffice del POS | Backend + soporte | Listar tiendas, cambiar dominio, suspender/reactivar, recuperar acceso y cerrar una tienda mediante procedimientos auditados |
| PROD-24 | Experiencia, accesibilidad y contenido por tienda | **Parcial:** 24a, nombre real de la tienda (PR #62); 24b, accesibilidad, mensajes y errores del comprador (PR #99). Faltan las páginas legales por tienda (GAP-18) y las pruebas manuales (GAP-19) | Frontend + producto | Flujo usable en móvil y teclado; mensajes claros; branding inicial correcto; contenido comercial y legal coherente |
| PROD-25 | Medir y mantener calidad del código | **Parcial:** 25a, cobertura medida con umbral del 76 % (PR #62); contrato de API versionado en `docs/api/` y vigilado por `OpenApiSpecTest` (GAP-17). Falta priorizar la deuda | Ingeniería | Cobertura actual medida, pruebas de invariantes críticas, deuda priorizada y contrato de API documentado |

### 5.3 Mejoras posteriores o condicionadas

| ID | Trabajo | Estado (al 8-nov-2026) | Cuándo pasa a ser obligatorio |
|---|---|---|---|
| PROD-26 | Estado compartido y coordinación de jobs entre réplicas | **Pendiente.** Avances indirectos: el antirreplay del POS está en tabla (POS-3) y los jobs de conciliación, stock, outbox y POS usan advisory locks. Faltan el rate limiting y la caché de revocación compartidos, y el lock de `AbandonedOrderJob` | Antes de ejecutar más de un backend activo, incluso durante despliegues solapados |
| PROD-27 | Alta disponibilidad y recuperación regional | Pendiente | Cuando el SLO contratado y el impacto de caída lo requieran |
| PROD-28 | Optimizar reportes, consultas y tareas pesadas | Pendiente | Si la carga medida incumple límites; no cambiar cifras contables al optimizar |
| PROD-29 | SEO avanzado, sitemap, datos estructurados y posible prerenderizado | Pendiente | Según canales de adquisición y objetivos comerciales |
| PROD-30 | Más monedas, países, transportistas o autoservicio de alta | Pendiente | Antes de comercializar esas capacidades; CLP y alta por operador siguen siendo el alcance actual |

Dependencias principales: `01 → 02/03/08 → 04/05/09/10 → 11/12/14 → 16 → lanzamiento`. Integración con UvoPOS (§5.4): `POS-1 → POS-2/POS-3/POS-5 → POS-4`, y `POS-1 + PROD-13 → PROD-14`. Los trabajos de UI y recuperación durable pueden avanzar en paralelo a la infraestructura, manteniendo integración y pruebas al final de cada cambio.

### 5.4 Integración con UvoPOS

El e-commerce es un módulo de UvoPOS, y el POS es el maestro del catálogo, los precios y el stock. Estas tareas no estaban en la versión original del plan: salen de esa decisión (§4.1, §6.5) y usan los mismos niveles P0 y P1.

Estado al 8 de noviembre de 2026:

| ID | Estado |
|---|---|
| POS-1 | **Pendiente.** El código sigue un contrato propuesto (venta, lectura masiva, variantes y alta), ajustado por GAP-05 y GAP-06. Falta el documento aprobado por ambos equipos y separar el registro de la venta de la emisión del documento |
| POS-2 | **Cerrada con una desviación** (PR #79): el backend y el formulario bloquean los campos del POS, pero el panel todavía permite crear productos en una tienda con el POS como maestro. GAP-06 lo mitiga: con el POS activo, un producto sin mapeo no se vende |
| POS-3 | **Cerrada en código** (PR #79, V26): mismas reglas de propiedad en sincronización y webhook, eventos viejos descartados y antirreplay durable. Falta que el POS envíe `version` |
| POS-4 | **Cerrada en código** (PR #81): `PosReconciliationService` detecta y corrige divergencias, con resincronización por tienda. Falta el endpoint de lectura masiva en el POS |
| POS-5 | **Cerrada en código** (PR #82, V27): variantes y mapeo por variante. Una empresa POS por orden: GAP-06, PR #106 |
| POS-6 | **Cerrada en código** (PR #84): alta idempotente desde el POS, con la conexión y la invitación al propietario. Falta implementarla en UvoPOS |
| POS-7 | **Pendiente** |

| ID    | Prioridad                     | Trabajo                                   | Responsable sugerido            | Criterio de aceptación                                                                                                                                                                                                                                                                                                                                                                                        |
|-------|-------------------------------|-------------------------------------------|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| POS-1 | P0                            | Contrato e-commerce ↔ UvoPOS              | Backend + equipo POS            | Documento versionado y aprobado por ambos equipos que fija: dueño de cada campo; significado de `stock_web` y política cuando web y mostrador venden la última unidad; registro de la venta separado de la emisión del documento; idempotencia; versión u orden de los eventos; variantes; empresas por tienda; lectura masiva para conciliar. `PosOrderPayloadMapper` y su test de contrato alineados con él |
| POS-2 | P0                            | El panel respeta los campos del POS       | Backend + frontend              | En un producto con mapeo activo, el backend rechaza cambios a los campos del POS con un mensaje claro y el formulario los muestra bloqueados; en tiendas con el POS como maestro no se crean productos desde el panel; imágenes, descripción comercial y destacados siguen editables                                                                                                                          |
| POS-3 | P0                            | Entradas del POS ordenadas e idempotentes | Backend + equipo POS            | Sincronización y webhooks aplican las mismas reglas de propiedad; ninguna entrada escribe stock absoluto sin condición; un evento más viejo que el aplicado se descarta y queda registrado; un replay se rechaza también después de un reinicio                                                                                                                                                               |
| POS-4 | P0                            | Conciliación periódica POS ↔ e-commerce   | Backend                         | Una divergencia inyectada de stock o precio se detecta y alerta dentro del intervalo acordado, y se corrige hacia el POS sin deshacer ventas web en vuelo; existe una resincronización completa por tienda                                                                                                                                                                                                    |
| POS-5 | P0 si el POS maneja variantes | Variantes en la integración               | Backend + equipo POS            | Un producto con variantes en el POS se vende por variante en la web, con stock y registro de venta por variante                                                                                                                                                                                                                                                                                               |
| POS-6 | P1                            | Alta de la tienda desde UvoPOS            | Backend + equipo POS            | Activar el módulo en el POS crea, en un paso idempotente, la tienda, la conexión con secretos generados, el catálogo inicial y la invitación al propietario; un reintento no duplica nada; `/api/platform/**` no es accesible desde Internet                                                                                                                                                                  |
| POS-7 | P1                            | Entrar al panel desde UvoPOS (SSO)        | Backend + frontend + equipo POS | Un usuario del POS con permiso entra al panel de su tienda sin una segunda contraseña y con sus roles mapeados; revocarlo en el POS le quita el acceso al panel dentro del plazo acordado                                                                                                                                                                                                                     |

Relación con las tareas `PROD-*`:

- **PROD-13** incluye registrar la venta en el POS de forma durable, **antes** que los correos: es el efecto que mueve el stock del maestro.
- **PROD-14** incluye el POS en el recorrido: venta web registrada en el POS, venta en mostrador reflejada en la web y POS caído durante una venta web.
- **PROD-19 y PROD-23** pasan a tener como cliente el backoffice del POS. Los operadores usan la identidad y el MFA del POS, y `X-Platform-Key` queda como credencial entre servicios, restringida a la red privada.
- **PROD-21** incluye el aislamiento frente al POS: el servidor de base es compartido, y un reporte pesado del e-commerce no debe degradar las ventas del mostrador.

## 6. Diseño de los cierres funcionales más importantes

### 6.1 URLs públicas por tienda

Crear un componente común que resuelva el origen público de una tienda: dominio propio verificado cuando esté operativo, o subdominio de plataforma. Usarlo en emails, recuperaciones, invitaciones, callbacks y retornos. El nombre exacto de clase y las propiedades nuevas deberán definirse al implementar; no existen todavía por estar descritos aquí.

La resolución debe funcionar también en jobs y listeners sin petición HTTP, tomando la tienda desde la orden o usuario. No construir enlaces sensibles a partir de `Host`, `Origin` o `X-Forwarded-*` arbitrarios. Si el cliente puede enviar URLs de retorno, validarlas contra orígenes autorizados para esa tienda.

Revisar especialmente `AuthController`, `UserServiceImpl`, `CustomerInvitationEmailListener`, `WebpayController`, `MercadoPagoController` y los fallbacks de las pasarelas. Webpay redirige hoy al `frontendUrl` global después del retorno; MercadoPago arma la URL de webhook con esquema, nombre y puerto de la petición, que pueden describir el upstream HTTP al terminar TLS en el proxy.

**Estado al 8-nov-2026:** implementado en `service/url/StorePublicUrlResolver` (PROD-04), con `PUBLIC_STOREFRONT_URL`, `PUBLIC_API_URL` y `PUBLIC_PLATFORM_DOMAIN`. Lo usan los correos, las invitaciones, los retornos y webhooks de Webpay y MercadoPago, y las URLs de imágenes (PROD-05b). Falta la prueba obligatoria en staging.

**Prueba obligatoria:** alta de tiendas A y B, una por subdominio y otra por dominio propio; activar invitación, resetear contraseña y completar pago en ambas. Todos los enlaces permanecen en su tienda y usan HTTPS. Incluir cambio de dominio y un host manipulado.

### 6.2 Alta y habilitación comercial

La historia «recibir credenciales y nick para habilitar un e-commerce» se cierra con este recorrido:

1. Un operador autorizado registra nick, datos del propietario y nombre de tienda.
2. Se validan disponibilidad del nick y dominio, correo y credenciales; los errores no dejan una tienda parcialmente creada.
3. Se crea propietario con permisos y se entrega una URL concreta del storefront y otra del panel.
4. El propietario obtiene acceso, configura identidad, contacto, catálogo, pago y despacho o retiro.
5. Se completa una compra de prueba y se registra la evidencia.
6. Se comunica al cliente qué está habilitado y quién presta soporte.

Como evolución profesional, el propietario debería establecer su contraseña mediante invitación de un solo uso. El alta actual recibe una contraseña del operador; migrar ese flujo exige integrar la invitación, no basta con cambiar un texto. Mientras exista entrega manual, definir un canal controlado y un procedimiento de cambio de contraseña.

El nombre de `Store` y el branding deben quedar coherentes: `StoreSettingsServiceImpl` crea ajustes por defecto y `StoreSettings.storeName` parte de «UvoStore». Inicializar con el nombre elegido o exigir el paso de configuración antes de publicar.

**Estado al 8-nov-2026:** el alta entrega las URLs del storefront y del panel. Desde POS-6, el operador ya no fija la contraseña: el propietario nace invitado y la elige él. PROD-24a inicializa los ajustes con el nombre del alta. Faltan la compra de prueba con evidencia (paso 5) y la comunicación al cliente (paso 6), que son procedimientos.

### 6.3 Invitaciones y cuentas

**Estado al 8-nov-2026:** la pantalla de invitación y el reenvío están implementados (PROD-06), y las cuentas de comprador están diferidas (PROD-07).

La pantalla de invitación administrativa debe gestionar éxito, caducidad, token consumido y fallo de red sin perder el estado de forma confusa. El reenvío requiere permiso, límite de frecuencia, expiración e invalidación del token anterior. Revisar que desactivación y cambios de rol no permitan recuperar acceso indebido mediante una invitación pendiente.

Si se habilitan cuentas de compradores, completar activación, login/logout, recuperación y cambio de contraseña, perfil, direcciones, expiración de sesión y tratamiento del correo ya usado por un invitado. La UI debe respetar la revocación actual: cambiar la contraseña invalida la sesión, salvo que se implemente una emisión segura de token nuevo.

Si se difieren las cuentas, la compra invitada debe seguir funcionando y sus correos limitarse a capacidades disponibles. No resolver la ausencia de UI permitiendo que cualquier visitante reclame una fila de cliente solo por conocer su correo.

### 6.4 Consistencia y recuperación después de una caída

Los listeners `AFTER_COMMIT` y las colas de ejecutores viven en el proceso. Un apagado abrupto puede ocurrir después de guardar un pago y antes de completar el descuento de stock o la notificación. Es un riesgo de durabilidad inferido del mecanismo actual; no se ha ejecutado aquí una prueba de caída que cuantifique su incidencia.

Definir una solución proporcional: una tabla outbox transaccional y consumidores idempotentes, o un registro durable equivalente con reconciliación de efectos faltantes. No hace falta introducir un broker para un volumen inicial pequeño. Registrar estado, intentos, próximo reintento, último error y clave de deduplicación; permitir revisión manual de fallos definitivos.

**No mover simplemente `StockDecrementListener` a `@Async`.** Conservar sus invariantes y su protección contra descuentos duplicados. La recuperación debe cubrir tanto el pedido como cada línea y sus marcas de stock. El control de `stock >= 0` no garantiza por sí solo que dos compradores nunca paguen la última unidad: documentar la política de reserva, disponibilidad y compensación de pagos sin stock.

Distinguir la conciliación existente de pagos `PENDING` de la recuperación de un pedido ya `PAID` cuyo efecto local quedó incompleto. Son selecciones y problemas distintos. Un reembolso `PENDING` incierto tampoco se resuelve creando una nueva devolución a ciegas.

**Estado al 8-nov-2026:** implementado.
- **Outbox transaccional** (V25, `OutboxService` y `OutboxDispatcher`): la venta al POS va antes que los correos, con reintento, último error y clave de deduplicación (PROD-13b).
- **Barrido de stock:** `StockRecoveryJob` completa el descuento de un pedido ya `PAID` con líneas sin aplicar (PROD-13a).
- **Revisión manual:** los fallos definitivos se ven en el panel, en «Envíos fallidos», y se reintentan o se dan por resueltos con nota y auditoría (GAP-04, V34). GAP-04 agrega pruebas que interrumpen antes y después de cada commit.
- **Última unidad:** la política mínima marca la orden cobrada sin stock, bloquea su despacho y deja el reembolso disponible (GAP-03, V32). La reserva coordinada con el POS sigue fuera.
- **Reembolsos inciertos:** un timeout deja la intención en `PENDING`, y se resuelve desde el panel sin mover dinero (GAP-01 y GAP-02, V31).

### 6.5 Integración con UvoPOS

**Arquitectura.** Se comparte la infraestructura de la plataforma de UvoPOS, pero no el proceso ni el esquema. El POS vende en el mostrador: un pico de tráfico o un fallo del storefront no debe dejar sin caja a los locales, y cada sistema debe poder desplegarse sin detener al otro. Cada uno es dueño de su esquema (aquí, Flyway con `ddl-auto=validate`), y los modelos de tenant son distintos —`Store` frente a `empresa_id`—, unidos por `PosConnection` y `ProductSyncMapping`. La integración usa el contrato HTTP existente sobre red privada: salida a `orders/external`, entrada por `/api/sync/**` y `/api/webhooks/pos/**` con firma HMAC.

**Stock.** El POS fija el stock. El e-commerce guarda una réplica y, al confirmar un pago, la descuenta con la actualización condicional existente. Ese descuento es una **reserva**, no una segunda fuente de verdad: impide que dos compradores web se lleven la última unidad mientras la venta viaja al POS. Después registra la venta en el POS de forma durable e idempotente, y el POS devuelve los cambios condicionados al valor anterior o a una versión. El código ya trata el descuento propio como autoridad sobre el stock web (`PosOrderNotifier.checkStockDivergence`); POS-1 debe convertirlo en acuerdo, incluido el significado de `stock_web`, que el POS ya envía aparte de `new_stock`.

**Documento tributario.** Hoy notificar una orden al POS emite un documento. Como no todas las ventas web deben emitirlo por el POS, el contrato separa registrar la venta (siempre) de emitir el documento (opcional por tienda).

**Carencias comprobadas en el código** (commit `c1efba8`), con su estado al 8-nov-2026:

- **Cerrada por POS-2.** El panel no consulta `ProductSyncMapping`: un administrador puede editar el precio o el stock de un producto sincronizado, y el cambio ni llega al POS ni sobrevive a la siguiente sincronización.
- **Cerrada por POS-3.** `PosSyncServiceImpl` ignora los indicadores de sincronización al actualizar un producto existente y escribe stock absoluto; el webhook `product.updated` sí los respeta.
- **Cerrada por POS-3.** `product.updated` aplica el último evento que llega aunque sea más viejo que el ya aplicado, y la protección antirreplay vive en memoria. El antirreplay está ahora en `pos_webhook_receipts`.
- **Cerrada por PROD-13b.** El reintento de notificaciones solo considera órdenes con al menos un intento (`OrderRepository`, `syncAttempts >= 1`). Si el proceso cae antes del primero, la venta nunca llega al POS. La venta va ahora por el outbox, y V25 eliminó `sync_attempts`.
- **Cerrada por POS-5.** La sincronización solo crea productos simples, y el mapeo es por producto, no por variante.
- **Abierta (POS-1).** La notificación de órdenes sigue un contrato propuesto y no confirmado (`PosOrderPayloadMapper`).

**Restauración.** Con el POS como maestro, restaurar solo uno de los dos sistemas deja ventas y stock desalineados. Los ensayos de §8.3 deben restaurar ambos a un punto coherente, o terminar con la conciliación de POS-4.

## 7. Configuración, secretos y seguridad

### 7.1 Inventario de configuración

Esta tabla enumera nombres ya existentes. No debe copiarse como un `.env` listo para producción ni incluir valores secretos reales.

| Configuración                                                         | Condición de producción                                                                                                   |
|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`                                | Base privada, cifrado de transporte según proveedor y rol de aplicación con privilegios mínimos                           |
| `DB_POOL_MAX`                                                         | Ajustado a capacidad de PostgreSQL, jobs y número de procesos; comprobar saturación                                       |
| `JWT_SECRET`, `JWT_EXPIRATION_MS`                                     | Clave nueva y duración decidida por riesgo; procedimiento de revocación y rotación                                        |
| `PLATFORM_API_KEY`                                                    | Nueva, acceso muy limitado; herramienta de alta protegida en red y con trazabilidad                                       |
| `APP_ENCRYPTION_KEY`                                                  | Nueva para una instalación vacía; custodia separada y recuperación probada                                                |
| `PUBLIC_STOREFRONT_URL`, `PUBLIC_API_URL`, `PUBLIC_PLATFORM_DOMAIN`   | Origen HTTPS de la plataforma y dominio de los subdominios de tienda; reemplazan a `APP_FRONTEND_URL` (PROD-04)            |
| `APP_ENVIRONMENT`                                                     | `production` o `staging` activan las comprobaciones de arranque de `ProductionConfigurationRules` (PROD-08a)              |
| `FLYWAY_USER`, `FLYWAY_PASSWORD`                                      | Rol que aplica las migraciones, distinto del de la aplicación; producción lo exige                                        |
| `SWAGGER_ENABLED`                                                     | `false` en producción y staging (lo exige el arranque); el contrato está en `docs/api/`                                   |
| `METRICS_TOKEN`                                                       | Protege `/actuator/prometheus`; nuevo por ambiente                                                                        |
| `PLATFORM_ALLOWED_NETWORKS`                                           | Redes desde las que responde `/api/platform/**`; solo la red privada de la plataforma                                     |
| `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`                                | Ambiente y versión desplegada, para separar y atribuir los errores                                                       |
| `CUSTOMER_ACCOUNTS_ENABLED`, `SHIPPING_CARRIERS_ENABLED`              | Apagados mientras esas capacidades sigan diferidas (PROD-07, PROD-12)                                                    |
| `OUTBOX_*`, `STOCK_RECOVERY_*`, `POS_RECONCILIATION_*`                | Intervalos y plazos de los jobs de recuperación; no apagarlos en producción                                               |
| `APP_STORAGE_DRIVER`, `APP_UPLOAD_DIR`                                | Persistencia explícita si se usa local; ninguna imagen depende del filesystem efímero del contenedor                      |
| `APP_STORAGE_S3_*`                                                    | Bucket, región, endpoint y URL pública correctos; permisos mínimos; preferir identidad de ejecución cuando sea compatible |
| `MAIL_*`                                                              | Relay funcional, autenticación y TLS acordes al proveedor, remitente verificado y timeouts                                |
| `SENTRY_DSN`                                                          | Variable real de entorno del proceso: `main()` no la obtiene del `.env` mediante Spring                                   |
| `VITE_SENTRY_DSN`                                                     | Configuración del build del frontend; asociar errores a versión y ambiente                                                |
| `RATE_LIMIT_TRUSTED_PROXIES`                                          | Solo IP/CIDR reales de proxies autorizados; probar cadena y direcciones falsificadas                                      |
| `RATE_LIMIT_*`                                                        | Valores de producción; no copiar los límites elevados de Surefire                                                         |
| `CORS_ADDITIONAL_ORIGINS`                                             | Vacío si frontend/API comparten origen; excepciones justificadas y acotadas                                               |
| `WEBPAY_PARENT_COMMERCE_CODE`, `WEBPAY_API_KEY`, `WEBPAY_ENVIRONMENT` | Valores comerciales correctos cuando se habilita; impedir sandbox involuntario                                            |
| `STRIPE_*` y credenciales guardadas por tienda                        | Revisar precedencia y evitar que un fallback cobre en la cuenta equivocada                                                |
| `CHILEXPRESS_QUOTE_URL` y credenciales de envío                       | Activar únicamente tras homologación y mapeo territorial                                                                  |
| `POS_ENABLED` y `pos_sync_enabled` por tienda                         | Encender al cerrar POS-1 a POS-4 y validar el recorrido completo en PROD-14                                               |
| `POS_RETRY_ENABLED`                                                   | Mantener desactivado hasta demostrar idempotencia del receptor                                                            |
| `RECONCILIATION_*`, `ABANDONED_ORDERS_*`                              | Tiempos acordes a pagos manuales, expiración de pasarelas y soporte                                                       |
| `SCHEDULER_POOL_SIZE`, timeouts                                       | Medidos bajo carga; monitorear colas, conexiones y tareas pendientes                                                      |
| `REPORTS_TIMEZONE`                                                    | Alineado con operación chilena y pruebas de límites de fecha                                                              |

`VITE_DEV_PROXY_TARGET` es exclusivo del desarrollo: fija el host que recibe la API y no es el mecanismo de producción para seleccionar tiendas. No usar `vite dev` ni `vite preview` como servidor comercial.

Agregar una validación explícita de configuración de producción que detecte secretos ausentes, orígenes localhost, pasarelas en sandbox no autorizadas y almacenamiento efímero. Activar un perfil llamado `prod` no implementa por sí solo esas validaciones. **Hecho en PROD-08a:** `ProductionConfigurationRules` revisa al arrancar con `APP_ENVIRONMENT=production` o `staging` y lista todos los problemas juntos.

### 7.2 Custodia y rotación

Separar secretos por ambiente y por tienda cuando el modelo lo requiera. No guardarlos en logs, artefactos, tickets, capturas ni variables públicas `VITE_*`. Las claves previamente publicadas en el historial deben tratarse como conocidas.

Rotar `APP_ENCRYPTION_KEY` exige migrar o recifrar los valores existentes con un procedimiento probado: cambiar la variable sin más vuelve ilegibles las credenciales cifradas. Conservar acceso controlado a las versiones necesarias para restaurar respaldos y definir cuándo se destruyen. Diferenciar esa operación de rotar JWT, que afecta la validez de las sesiones.

Separar el rol que aplica DDL/Flyway del rol cotidiano de aplicación cuando se implemente el pipeline. Probar que la aplicación funciona sin permisos de superusuario o creación de esquema. El código ya lo admite (`FLYWAY_USER`, PROD-08a); falta crear los roles en la plataforma.

### 7.3 Revisión de seguridad del sistema desplegado

Usar una matriz seleccionada de [OWASP ASVS](https://owasp.org/projects/asvs) para convertir autenticación, autorización, validación y protección de datos en comprobaciones verificables. Registrar versión y requisitos aplicados; este documento no afirma certificación ni conformidad completa.

- Probar acceso entre tiendas en lectura, escritura, exportación, archivos, pedidos, pagos, refunds, clientes y roles, con IDs válidos de la otra tienda.
- Confirmar 401 para sesión inexistente, revocada o de otra tienda; 403 para un usuario autenticado sin permiso. El frontend no debe cerrar sesión por todo 403.
- Conservar validación de firmas y montos, defensa contra replay, límites de intentos y validación de archivos por contenido.
- Revisar especialmente endpoints públicos que reciben identificadores de órdenes y crean operaciones de pago: pertenencia, autorización aplicable, enumeración y abuso económico.
- Definir límites de cuerpo, tamaño de página, exportación, consumo de pasarelas y almacenamiento por tienda. El rate limiter actual no cubre automáticamente todas las operaciones costosas.
- Aplicar CSP, política de referrer y protección de framing compatibles con el frontend y pagos. Empezar CSP con observación si es necesario y cerrar antes de generalizar. **GAP-13:** la SPA lleva su CSP en el `index.html` del build (`frontend/csp.ts`), y el backend fija `Referrer-Policy`, `X-Frame-Options` y una CSP cerrada para `/api/**` y `/uploads/**`. El proxy debe agregar `frame-ancestors 'none'` al servir la SPA.
- El token del panel se persiste en almacenamiento del navegador. Evaluar una sesión basada en cookie `HttpOnly/Secure` o una mitigación explícita para el diseño actual. Si se cambia a cookies, revisar CSRF y `SameSite`; no conservar ciegamente la política stateless actual. **GAP-13:** se eligió la mitigación. El token se queda en el navegador, el cierre de sesión lo revoca en el servidor, vence a las 8 h y el lint prohíbe insertar HTML crudo.
- Evaluar hash de tokens de invitación/reset en reposo y evitar su exposición en logs, analítica o cabeceras Referer.
- Limitar Swagger y herramientas de plataforma a la exposición acordada. Mantener los detalles de salud fuera de respuestas públicas. PROD-17c: Swagger tiene interruptor (`SWAGGER_ENABLED`, `SwaggerDisabledTest`); apagado, `/swagger-ui.html` y `/v3/api-docs/**` responden 404. **El default es encendido (el de desarrollo): producción debe poner `SWAGGER_ENABLED=false`**, y desde PROD-08a el arranque en producción o staging lo exige. Como ahí no hay spec en línea, el contrato vigente está versionado en `docs/api/` (GAP-17).
- Revisar logs y Sentry: correos, tokens de pago, URLs con tokens y cuerpos sensibles deben redactarse. PROD-17a: el retorno de Webpay ya no registra `token_ws` ante errores, solo un prefijo de 8 caracteres saneado (`WebpayReturnLoggingTest`); y `EmailServiceImpl` no registra el cuerpo de los correos (`EmailServiceImplLoggingTest`). En **Sentry**, las páginas de aceptar invitación y de restablecer contraseña llevan el token en `?token=`, y el SDK de navegador adjunta por defecto la URL de la página a los eventos. Desde PROD-15a, `frontend/src/lib/sentry.ts` define `beforeSend` para limpiar los eventos; queda comprobarlo con un error provocado en esas páginas.
- No configurar `server.forward-headers-strategy=framework` como atajo para resolver HTTPS: las instrucciones del proyecto advierten que altera la confianza en la IP del cliente. Diseñar y probar la solución de esquema/origen preservando `ClientIpResolver`.

## 8. Datos, migraciones y recuperación

### 8.1 Migraciones y permisos

Mantener Flyway como fuente del esquema y `ddl-auto=validate`. No modificar migraciones ya aplicadas. Cada cambio debe probarse contra una base vacía y contra una copia anonimizada del esquema/datos de la versión anterior.

Separar cambios compatibles de cambios destructivos mediante expansión, transición y retirada posterior. Revisar duración, locks y tamaño de tablas antes de migrar. Definir si migra un job de release o el arranque; no dejar que esa responsabilidad sea accidental ni duplicar ejecuciones sin control.

Una reversión del binario requiere compatibilidad con el esquema vigente. Restaurar toda la base para deshacer un despliegue puede borrar ventas posteriores; debe reservarse al procedimiento de desastre y acompañarse de conciliación con las pasarelas.

### 8.2 Objetivos iniciales propuestos

| Objetivo                                 | Propuesta para acordar                       | Evidencia requerida                                                   |
|------------------------------------------|----------------------------------------------|-----------------------------------------------------------------------|
| RPO: datos máximos que se podrían perder | Hasta 15 minutos para pedidos y pagos        | Restauración medida y contraste de transacciones recientes            |
| RTO: tiempo hasta recuperar servicio     | Hasta 4 horas para desastre de base/servidor | Simulacro desde incidente hasta compra de verificación                |
| Retención operativa de respaldos         | 30 días como punto de partida                | Política aplicada y compatible con necesidades comerciales y de datos |
| Restauración de prueba                   | Antes del lanzamiento y luego mensualmente   | Registro de ejecución, duración, errores y acciones                   |

Un backup nocturno por sí solo no satisface un RPO de 15 minutos. PostgreSQL permite recuperación a un instante mediante backup base y archivado WAL; los dumps lógicos no sustituyen esa cadena. Contratar o implementar PITR y verificar su funcionamiento. [Documentación oficial de recuperación de PostgreSQL 18](https://www.postgresql.org/docs/18/continuous-archiving.html).

### 8.3 Qué debe recuperarse conjuntamente

- Base de datos y metadatos de versión/migraciones.
- Imágenes y archivos, con versionado o copias independientes de los borrados accidentales.
- Claves necesarias para descifrar credenciales, custodiadas fuera del respaldo de datos con acceso separado.
- Configuración de aplicación, DNS, proxy y recursos, sin publicar secretos en Git.
- Inventario de integraciones y procedimiento de conciliación de operaciones externas.

Ensayar la restauración en una red aislada con jobs y salidas de correo/pago/POS deshabilitados. Verificar conteos, relaciones, imágenes, lectura de credenciales, pedidos y acceso de dos tiendas antes de habilitar tráfico. Un ambiente restaurado no debe reenviar notificaciones ni emitir documentos reales durante el ensayo.

El rollback automático de muchos tests no sustituye una base de pruebas dedicada: Flyway, tests con commits, jobs y pruebas de concurrencia pueden escribir fuera de ese mecanismo. **Nunca apuntar la suite a producción.**

## 9. Pruebas, CI/CD y aceptación técnica

### 9.1 Puertas de calidad

La CI existente es una base que se conserva. Antes de cada release ejecutar en ambientes desechables o dedicados:

```text
Backend:  ./mvnw --batch-mode --no-transfer-progress clean verify
Windows:  .\mvnw.cmd --batch-mode --no-transfer-progress clean verify
Frontend, desde frontend/: npm ci
                           npm run lint
                           npm test
                           npm run build
```

`npm run build` ya ejecuta `tsc -b`. `tsc --noEmit` por sí solo no valida este proyecto con referencias de TypeScript. JaCoCo impone desde PROD-25a un umbral del 76 % de líneas (`pom.xml`): se midió primero y se fijó por debajo de lo medido. Conviene proteger las rutas críticas y subir el umbral de forma sostenible, sobre todo en código nuevo. Cobertura alta no demuestra ausencia de fallos de dinero o aislamiento.

Agregar pruebas de navegador contra frontend y backend reales, contratos de integraciones y pruebas de caída. Las pruebas transaccionales que revierten al final no ejecutan por sí solas los efectos `AFTER_COMMIT`; las comprobaciones críticas deben confirmar la transacción y observar esos efectos. **Estado al 8-nov-2026:**
- **Navegador:** `frontend/e2e` corre el circuito completo con dos tiendas y dobles de POS, SMTP y pasarelas, también en móvil (PROD-14a, GAP-08). La CI lo ejecuta en cada PR.
- **Caída:** hay pruebas que confirman la transacción e interrumpen antes y después de cada commit (GAP-04, `support/InterruptionTestSupport`).
- **Contrato de API:** está versionado en `docs/api/` y lo vigila `OpenApiSpecTest`.
- **Contrato con el POS:** falta, porque depende de POS-1.

Verificar en GitHub las protecciones de rama y checks obligatorios: su configuración remota no se deduce de que exista un YAML. Incorporar análisis de secretos, dependencias y licencias, actualizaciones planificadas y reglas para excepciones con responsable y caducidad. Dependabot y dependency-review ya están (PROD-22); el análisis de secretos y la protección de rama se configuran en GitHub. Qodana ya tiene un umbral de críticos en su configuración; comprobar que el check realmente bloquea cuando corresponde.

### 9.2 Matriz mínima de aceptación

| Escenario                                                                     | Resultado esperado                                                                                         |
|-------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| Alta con nick nuevo y datos válidos                                           | Tienda accesible por URL HTTPS, propietario autenticado y permisos utilizables                             |
| Nick/dominio duplicado, clave de plataforma incorrecta                        | Rechazo claro, sin alta parcial                                                                            |
| Petición con token de A al dominio B                                          | Sin autenticación ni acceso a datos de A                                                                   |
| Dominio propio, subdominio y host desconocido                                 | Los dos primeros resuelven la tienda correcta; el tercero no sirve una tienda accidental                   |
| Invitación/reset desde correo real                                            | Link correcto, token de un uso, expiración y reenvío controlados                                           |
| Compra simple, variante, oferta, cupón y envío                                | Mismo total CLP en UI, orden y pasarela; variante y método de envío registrados                            |
| Doble clic y reintento de checkout                                            | Comportamiento acordado sin duplicar cobros ni usos de cupón                                               |
| Dos compradores por la última unidad                                          | Stock no negativo y resultado comercial explícito para cualquier pago sin disponibilidad                   |
| Venta web con el POS caído; última unidad vendida en web y mostrador a la vez | La venta llega al POS una sola vez al recuperarse; la última unidad se resuelve según la política de POS-1 |
| Pago aprobado, rechazado, cancelado y retorno perdido                         | Estado correcto y recuperación por mecanismos comprobados                                                  |
| Webhook repetido, tardío, inválido o de otra tienda                           | Sin doble efecto ni contaminación entre tiendas; rechazo/registro útil                                     |
| Importe incorrecto o pago de estado incierto                                  | No se marca una venta pagada sin validar; caso visible para conciliación                                   |
| Reembolso parcial, total y timeout después de éxito remoto                    | Saldo correcto y ausencia de segundo movimiento por un reintento ciego                                     |
| Pago manual y orden abandonada                                                | Vencimiento coherente con el proceso bancario; cupón y stock consistentes                                  |
| Caída después del commit y antes del efecto                                   | Recuperación de stock/notificaciones conforme a PROD-13                                                    |
| SMTP, transportista, pasarela o POS indisponibles                             | Timeouts acotados, mensaje útil, alerta y recuperación sin duplicaciones                                   |
| Reinicio, despliegue y restauración                                           | Datos/archivos persistentes y operaciones pendientes recuperables                                          |
| Sesión expirada y permisos insuficientes                                      | Reingreso en 401; explicación de 403 sin bucle de login                                                    |
| Móvil, teclado y recarga de ruta                                              | Compra y administración esenciales completables                                                            |
| Reporte tras devolución y cambio de mes en Chile                              | Totales netos y fechas consistentes con el comportamiento contable implementado                            |

Ejecutar casos de pasarela en sandbox y, una vez autorizado operativamente, una transacción controlada de bajo importe y su devolución en el ambiente comercial. Guardar evidencia sin números de tarjeta, contraseñas, tokens ni datos personales innecesarios.

### 9.3 Entrega de software

Construir artefactos inmutables identificados por commit, versión y checksum; conservar los anteriores. Promover el mismo artefacto aprobado en staging, con la configuración propia del ambiente. Si el frontend contiene configuración de build distinta, registrar esa variante como artefacto separado y probarla.

Preferir credenciales de despliegue temporales mediante federación de identidad si el proveedor lo permite. [GitHub Actions documenta OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-cloud-providers) para evitar secretos cloud duraderos en los workflows. Acotar repositorio, rama/tag y ambiente que pueden asumir esa identidad.

El pipeline de producción debe validar backup reciente y recuperable, compatibilidad de migración, health/readiness, smoke tests y observabilidad. Incorporar control de concurrencia de deployments y promoción aprobada por el responsable del release. El workflow actual de release genera JAR y ZIP y, desde GAP-09, solo publica si el E2E pasa sobre el mismo commit. Falta implementar la entrega al ambiente.

No suponer que un rolling deployment es inocuo con una sola instancia nominal: durante el solapamiento puede haber dos procesos con cachés y jobs propios. Resolver PROD-26 o usar una estrategia inicial que evite ese solapamiento, con la ventana de indisponibilidad acordada.

## 10. Observabilidad, rendimiento y escalamiento

### 10.1 Señales y objetivos

| Señal                                         | Objetivo o umbral inicial propuesto                                   | Acción                                                                                           |
|-----------------------------------------------|-----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| Disponibilidad externa del storefront/API     | SLO inicial 99,5 % mensual, sujeto a acuerdo                          | Investigar tendencia y consumo de margen de error; no vender un SLA superior sin infraestructura |
| Catálogo y consultas normales                 | p95 de API menor a 800 ms con dataset y carga declarados              | Perfilar consultas, pool y CPU antes de escalar                                                  |
| Procesamiento local del checkout              | p95 menor a 3 s, midiendo aparte esperas de terceros                  | Revisar bloqueos, SQL, colas y saturación                                                        |
| Errores HTTP 5xx                              | Alerta inicial >1 % durante 5 min con volumen mínimo definido         | Revisar release e integraciones; ajustar para tráfico bajo                                       |
| Pagos/reembolsos inciertos y stock incompleto | Sin casos invisibles; alertar al superar el tiempo operativo acordado | Cola de conciliación con responsable                                                             |
| Colas de mail/POS, pool DB y disco            | Umbrales según capacidad medida                                       | Evitar agotamiento y aplicar contención                                                          |
| Backups y archivado                           | Alertar cuando ya no permitan cumplir RPO                             | Reparar cadena y valorar suspensión de cambios                                                   |
| Certificados y dominio                        | Alerta anticipada, por ejemplo a 30/15/7 días                         | Renovar y verificar desde fuera                                                                  |

Definir la medición exacta: ventana, origen de datos, tráfico incluido y comportamiento ante mantenimiento. Las latencias anteriores son metas internas propuestas; medir por separado la experiencia completa del comprador, que sí incluye terceros.

Recoger logs centralizados con timestamp UTC, versión, ambiente, request ID y tienda cuando proceda. Usar identificadores internos en trazas y logs; evitar cardinalidad ilimitada en etiquetas de métricas. Redactar PII y secretos antes de enviarlos a terceros. Configurar Sentry de backend/frontend con ambiente, versión y alertas; manejar source maps con acceso controlado. El ambiente y la versión ya se envían (PROD-15a), y los logs llevan request ID y tienda (PROD-15b); faltan las alertas.

Distinguir liveness de readiness: una caída de base no debe provocar reinicios continuos que empeoren el incidente. Spring Boot ofrece grupos y probes de salud; su exposición y seguridad deben configurarse explícitamente. Desde PROD-15a, `/actuator/health` tiene grupos de liveness y readiness. PROD-15b agrega `/actuator/prometheus`, que solo responde desde la red privada y con `METRICS_TOKEN`. [Referencia de endpoints y probes de Spring Boot](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).

### 10.2 Carga y límites

Preparar un dataset con número de tiendas, productos, variantes, imágenes y pedidos comparable al lanzamiento. Medir navegación, búsqueda, checkout, uploads y reportes en una mezcla realista, incluyendo una tienda de alto consumo. Registrar p50/p95/p99, tasa de errores, memoria, GC, pool DB, queries y colas. Probar picos y degradación de dependencias sin atacar cuentas comerciales.

Los reportes agregan datos en memoria. Acotar rangos y exportaciones antes de que una tienda grande comprometa el proceso. Si las mediciones lo exigen, migrar agregados a SQL o procesamiento en segundo plano conservando distribución de descuentos, devoluciones y zona horaria.

### 10.3 Condiciones para más de una réplica

Antes de escalar horizontalmente, resolver y probar:

- Rate limiting y throttling por cuenta compartidos o coordinados.
- Revocación JWT: hoy la caché por proceso puede retrasar hasta su TTL la observación de cambios hechos en otro proceso.
- Antirreplay POS: memoria compartida o registro durable con unicidad. **Resuelto en POS-3:** el registro es durable (`pos_webhook_receipts`, V26).
- Coordinación de todos los jobs. Usan advisory lock PostgreSQL la conciliación de pagos, `StockRecoveryJob`, `OutboxDispatcher` y la conciliación con el POS; **`AbandonedOrderJob` todavía no**.
- Persistencia compartida de imágenes y efectos; consumidores idempotentes frente a entregas repetidas.
- Capacidad del pool multiplicada por instancias, migraciones y tareas operativas.
- Pruebas de reinicio de una réplica, pérdida de conexión y doble ejecución de tareas.

## 11. Procedimientos operativos mínimos

Cada procedimiento debe indicar quién decide, quién ejecuta, dónde observa el resultado y cómo registra el incidente. Versionar los pasos específicos del proveedor al implementar la infraestructura.

### 11.1 Desplegar una versión

1. Identificar versión, cambios, migraciones, compatibilidad y evidencia de CI/staging.
2. Verificar recuperación disponible y estado de operaciones pendientes; anunciar ventana si existe.
3. Aplicar migraciones con el rol y orden acordados; detener ante errores o locks imprevistos.
4. Drenar solicitudes y permitir el cierre ordenado de aplicación y ejecutores. Alinear el timeout del hosting con los 20 s de shutdown y 15 s de espera de ejecutores actuales, más el margen validado.
5. Desplegar backend/frontend compatibles y comprobar salud, tenant, login, catálogo e imágenes.
6. Ejecutar smoke tests de checkout sin cobros involuntarios; observar métricas y logs durante la ventana acordada.
7. Registrar éxito o revertir a una versión compatible. No eliminar artefactos ni evidencia del fallo.

### 11.2 Incidente de pago o reembolso

1. Identificar tienda, orden, referencia externa e intención de reembolso; restringir datos sensibles.
2. Consultar el estado real en la pasarela y compararlo con base e historial.
3. No repetir el cargo/devolución si la operación anterior puede haber tenido éxito.
4. Usar la acción idempotente y auditada correspondiente, o escalar a revisión manual documentada.
5. Confirmar dinero, estado de orden, stock, cupón, correo y efectos POS. Informar al comerciante cuando corresponda.

### 11.3 Caída o restauración

1. Declarar incidente y responsable; decidir si se pausa checkout mientras se desconoce el estado de datos.
2. Seleccionar punto de recuperación y restaurar en entorno aislado; conservar evidencia de origen.
3. Verificar esquema, archivos, claves, aislamiento y acceso antes de publicar.
4. Conciliar pagos y reembolsos ocurridos externamente después del punto restaurado.
5. Rehabilitar jobs y tráfico en orden controlado; registrar RPO/RTO reales y pérdidas si las hubo.

### 11.4 Credencial comprometida

Identificar alcance y accesos; revocar identidades o sesiones, rotar la credencial adecuada, revisar auditoría y vigilar usos posteriores. Para la clave de cifrado, ejecutar el plan de recifrado y recuperación, no un reemplazo improvisado. Mantener una vía de acceso de emergencia probada.

### 11.5 Alta, suspensión y salida de una tienda

Usar checklist de dominio, propietario, permisos, branding, catálogo, pagos, despacho, políticas y compra de prueba. Al suspender, confirmar el comportamiento actual: se bloquea también el panel de la tienda. Definir el canal de soporte para el comerciante suspendido.

PROD-23 implementó el ciclo de vida por API (`PlatformStoreController`): listar, cambiar dominio, suspender, reactivar, recuperar el acceso del dueño y cerrar, todo auditado. La exportación y la purga son procedimientos SQL documentados. No hay consola: hasta que el backoffice del POS consuma esa API, cualquier intervención debe ser controlada, documentada y revisable. La salida debe contemplar exportación, retención acordada, revocación de accesos, retirada de dominios y credenciales, y tratamiento de respaldos.

## 12. Producto, datos personales y mantenibilidad

### 12.1 Experiencia y accesibilidad

Validar móvil y escritorio, navegación por teclado, foco, etiquetas de formularios, mensajes de error y contraste. Probar carrito vacío, productos eliminados, stock cambiado, red lenta, reintento de pago y sesión caducada. Evitar mensajes internos de SDK o errores sin salida para el usuario.

Cada tienda debe mostrar su identidad, contacto, condiciones de envío, devoluciones y precios de forma coherente con lo que realmente ofrece. Las páginas legales actuales son textos compartidos y no acreditan esa revisión. Revisar también promesas de cookies, cuentas y comunicaciones que no correspondan a la implementación efectiva.

Inventariar datos recogidos y terceros que los reciben; acordar finalidades, acceso, retención, exportación, rectificación y eliminación con los responsables correspondientes. Someter los textos y procesos comerciales a revisión jurídica para el país y fecha de lanzamiento. Este plan prescribe una revisión; no certifica cumplimiento normativo ni sustituye ese análisis.

La documentación de pagos debe indicar quién cobra, quién concilia y cómo se atienden disputas. Confirmar con cada proveedor el alcance aplicable al comercio sin asumir que usar un checkout externo elimina todas sus responsabilidades.

### 12.2 Ingeniería mantenible

- Conservar separación entre controlador, servicio, repositorio y DTO, con transacciones y límites de tenant explícitos.
- Mantener pruebas de invariantes de dinero, stock, permisos y aislamiento al refactorizar; no simplificar protecciones históricas sin entender su motivo.
- Revisar consultas globales reutilizables que carecen de `storeId`; eliminar o restringir las que no tienen un uso seguro justificado. PROD-17b: eliminadas las 45 que no tenían llamadores (15 repositorios) y fijado con `RepositoryTenantScopeTest`, que obliga a que toda consulta sobre una entidad de tienda nombre la tienda o figure en una lista de globales con su razón. Quedan 10 globales justificadas; en tres el llamador no compara la tienda y siguen pendientes: el reset de contraseña de administrador (`AuthController`, por token) y los dos lookups de Stripe por id de sesión/intent. En Stripe el hueco es más ancho: `PaymentServiceImpl.handleWebhook` verifica la firma con el secreto de la tienda de la petición pero no compara la tienda de la orden encontrada, y `orderFromIntent` la resuelve con `findById(order_id)` tomado de la metadata del evento. **Cerrado por GAP-11:** `PaymentServiceImpl` compara la tienda de la orden en `verifyPayment`, en el webhook y en `orderFromIntent`, y `StripeCrossTenantTest` lo prueba con eventos firmados de verdad. Queda el reset de contraseña por token (Webpay, MercadoPago y el seguimiento ya comparaban la tienda).
- Registrar decisiones y contratos, incluyendo CLP sin fracciones, atribución temporal de devoluciones y comportamiento del POS ante cambios fuera de orden.
- Introducir idempotencia y versionado en sincronización POS cuando se habilite; resolver la sobrescritura de stock absoluto y actualizaciones tardías antes de prometer sincronización fiable. **Hecho en POS-3**; falta que el POS envíe `version`.
- Mantener README, OpenAPI, configuración de ejemplo, runbooks y registro de cambios actualizados; corregir comentarios desfasados al tocar el área. Desde GAP-17, la spec OpenAPI está versionada y un test falla si queda vieja.
- Definir revisión de PR, responsables de módulos, estrategia de releases y proceso de actualización de dependencias/runtime con pruebas.
- Generar inventario de componentes y licencias; conservar trazabilidad desde una versión desplegada hasta commit y resultados de CI.

## 13. Ejecución por fases y autorización de salida

### Fase A: decisiones y cierre de alcance

Entregar ADR, presupuesto, responsables, objetivos operativos y lista de capacidades del lanzamiento. Registrar las funciones diferidas y cómo se deshabilitarán. Dividir los `PROD-*` en tickets estimables con evidencia exigida. No fijar una fecha comercial sin estimar los bloqueos y dependencias externas.

### Fase B: entorno reproducible y cierres de producto

Construir staging con dominios/TLS, datos persistentes, secretos, correo y observabilidad. Resolver URLs por tienda, invitaciones y la decisión de cuentas de compradores. Incorporar recuperación durable y completar o excluir integraciones pendientes.

### Fase C: demostración técnica

Ejecutar CI completa, matriz E2E, homologación, revisión de seguridad, pruebas de carga, caída, restauración y rollback. Medir objetivos. Corregir fallos y repetir las pruebas afectadas; guardar evidencia de la versión candidata.

### Fase D: piloto con comercios seleccionados

Habilitar un grupo acotado de tiendas que conozcan el alcance y los tiempos de soporte. Supervisar primeras ventas, correos, despachos y devoluciones; revisar diariamente pendientes y discrepancias. Una propuesta inicial es observar dos semanas, extendiéndolas si el volumen no permite validar la operación.

### Fase E: operación general

Cerrar P1 o convertirlos en restricciones explícitas del servicio con mitigación y fecha. Ampliar tiendas según capacidad medida y soporte disponible. Adoptar P2 cuando los objetivos comerciales o métricas lo requieran.

### Lista de salida obligatoria

- [ ] Alcance comercial y responsable de cada componente registrados.
- [ ] Todos los P0 aplicables cerrados; capacidades diferidas realmente deshabilitadas.
- [ ] Dos tiendas aisladas por HTTPS con alta, acceso y primera venta verificadas.
- [ ] Recuperación e invitaciones apuntan al dominio correcto y tienen pantalla funcional.
- [ ] Secretos, roles DB, almacenamiento y configuración de producción revisados.
- [ ] Pasarelas, despacho y POS habilitados cuentan con evidencia de validación.
- [ ] Ventas web registradas en UvoPOS sin pérdidas ni duplicados, y divergencias de stock conciliadas.
- [ ] No hay efectos críticos perdidos al reiniciar ni reintentos que dupliquen dinero.
- [ ] CI y E2E aprobados sobre la versión candidata; seguridad y capacidad evaluadas.
- [ ] Restauración completa y rollback ensayados, con RPO/RTO registrados.
- [ ] Alertas entregadas a responsables y runbooks practicados.
- [ ] Identidad, políticas y soporte de cada tienda revisados.
- [ ] Versión, migraciones, artefactos, resultados y riesgos residuales identificados.
- [ ] Responsable técnico y responsable de negocio registran decisión de salida.

**Al 8-nov-2026 ningún punto está marcado,** porque cada uno exige evidencia de un ambiente. Algunos ya tienen evidencia en el código:
- recuperación e invitaciones con el dominio de cada tienda y su pantalla (PROD-04, PROD-06);
- outbox y conciliación con el POS (PROD-13, POS-4);
- recuperación sin pérdidas ni reintentos que dupliquen dinero (GAP-01, GAP-02, GAP-04);
- CI y E2E sobre el mismo commit (GAP-09).

No dar salida con pérdida o cruce de datos, cobros/reembolsos duplicables sin control, secretos conocidos activos, restauración imposible o una compra principal que no completa. Un release publicado, un health check verde o una tienda `ACTIVE` no sustituyen estos criterios.

**Paquete final de evidencia:** versión/commit, artefactos y checksums, resultados de CI/E2E, configuración sin secretos, homologaciones, reporte de seguridad, carga medida, acta de restauración, ensayo de rollback, enlaces a dashboards y responsables. Toda excepción debe indicar impacto, mitigación, alcance, dueño y vencimiento.

## 14. Referencias verificables del repositorio

Los enlaces son relativos a este documento para que funcionen en el repositorio. La lista identifica las fuentes principales de los hechos descritos; las recomendaciones y diseños propuestos se distinguen en las secciones anteriores.

| Evidencia                                   | Archivos                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Contexto y límites conocidos                | [CLAUDE.md](CLAUDE.md), [README](README.md). `AGENTS.md` es una copia local y no está versionada                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Alta de tienda y propietario                | [StoreOnboardingServiceImpl](src/main/java/org/uvo/uvostore/service/platform/StoreOnboardingServiceImpl.java), [NewStore](frontend/src/platform/pages/NewStore.tsx), [StoreOnboardingTest](src/test/java/org/uvo/uvostore/platform/StoreOnboardingTest.java)                                                                                                                                                                                                                                                                                               |
| Rutas, incluida la pantalla de invitación   | [router.tsx](frontend/src/router.tsx)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| Ciclo de vida de tiendas y auditoría        | [PlatformStoreController](src/main/java/org/uvo/uvostore/controller/platform/PlatformStoreController.java), [AuditTrail](src/main/java/org/uvo/uvostore/service/audit/AuditTrail.java)                                                                                                                                                                                                                                                                                                                                                                     |
| Configuración de producción                 | [ProductionConfigurationRules](src/main/java/org/uvo/uvostore/config/ProductionConfigurationRules.java)                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| Contrato de API                             | [docs/api](docs/api/README.md), [OpenApiSpecTest](src/test/java/org/uvo/uvostore/config/OpenApiSpecTest.java), [OpenApiConfig](src/main/java/org/uvo/uvostore/config/OpenApiConfig.java)                                                                                                                                                                                                                                                                                                                                                                   |
| Resolución y estado de tienda               | [StoreHostResolver](src/main/java/org/uvo/uvostore/security/StoreHostResolver.java), [TenantResolutionFilter](src/main/java/org/uvo/uvostore/security/TenantResolutionFilter.java)                                                                                                                                                                                                                                                                                                                                                                         |
| Seguridad y sesiones                        | [SecurityConfig](src/main/java/org/uvo/uvostore/config/SecurityConfig.java), [ClientIpResolver](src/main/java/org/uvo/uvostore/security/ClientIpResolver.java), [useAdminAuthStore](frontend/src/admin/stores/useAdminAuthStore.ts), [csp.ts](frontend/csp.ts)                                                                                                                                                                                                                                                                                                                        |
| Configuración y observabilidad              | [application.properties](src/main/resources/application.properties), [logback-spring.xml](src/main/resources/logback-spring.xml), [UvoStoreApplication](src/main/java/org/uvo/uvostore/UvoStoreApplication.java), [main.tsx](frontend/src/main.tsx)                                                                                                                                                                                                                                                                                                        |
| URLs de recuperación e invitación           | [AuthController](src/main/java/org/uvo/uvostore/controller/auth/AuthController.java), [UserServiceImpl](src/main/java/org/uvo/uvostore/service/security/UserServiceImpl.java), [CustomerInvitationEmailHandler](src/main/java/org/uvo/uvostore/service/outbox/CustomerInvitationEmailHandler.java)                                                                                                                                                                                                                                                        |
| Retornos y notificaciones de pago           | [WebpayController](src/main/java/org/uvo/uvostore/controller/order/WebpayController.java), [MercadoPagoController](src/main/java/org/uvo/uvostore/controller/order/MercadoPagoController.java)                                                                                                                                                                                                                                                                                                                                                             |
| Consistencia y recuperación                 | [OrderStatusServiceImpl](src/main/java/org/uvo/uvostore/service/order/OrderStatusServiceImpl.java), [StockDecrementListener](src/main/java/org/uvo/uvostore/service/order/event/StockDecrementListener.java), [OutboxService](src/main/java/org/uvo/uvostore/service/outbox/OutboxService.java), [OutboxDispatcher](src/main/java/org/uvo/uvostore/service/outbox/OutboxDispatcher.java), [StockRecoveryJob](src/main/java/org/uvo/uvostore/service/order/StockRecoveryJob.java), [RefundService](src/main/java/org/uvo/uvostore/service/payment/RefundService.java), [RefundIntentStore](src/main/java/org/uvo/uvostore/service/payment/RefundIntentStore.java), [PaymentReconciliationService](src/main/java/org/uvo/uvostore/service/payment/PaymentReconciliationService.java)                                                          |
| Correo e imágenes                           | [EmailServiceImpl](src/main/java/org/uvo/uvostore/service/notification/EmailServiceImpl.java), [S3FileStorageServiceImpl](src/main/java/org/uvo/uvostore/service/catalog/S3FileStorageServiceImpl.java)                                                                                                                                                                                                                                                                                                                                                    |
| Transportistas incompletos                  | [ChilexpressQuoteClient](src/main/java/org/uvo/uvostore/service/shipping/carrier/ChilexpressQuoteClient.java), [CorreosChileQuoteClient](src/main/java/org/uvo/uvostore/service/shipping/carrier/CorreosChileQuoteClient.java)                                                                                                                                                                                                                                                                                                                             |
| Integración con UvoPOS                      | [PosOrderNotifier](src/main/java/org/uvo/uvostore/service/pos/PosOrderNotifier.java), [PosOrderPayloadMapper](src/main/java/org/uvo/uvostore/service/pos/PosOrderPayloadMapper.java), [PosSyncServiceImpl](src/main/java/org/uvo/uvostore/service/pos/PosSyncServiceImpl.java), [PosWebhookServiceImpl](src/main/java/org/uvo/uvostore/service/pos/PosWebhookServiceImpl.java), [ProductSyncMapping](src/main/java/org/uvo/uvostore/entity/pos/ProductSyncMapping.java), [PosCatalogWriter](src/main/java/org/uvo/uvostore/service/pos/PosCatalogWriter.java), [PosReconciliationService](src/main/java/org/uvo/uvostore/service/pos/PosReconciliationService.java), [PosSaleCoverage](src/main/java/org/uvo/uvostore/service/pos/PosSaleCoverage.java) |
| Branding inicial                            | [StoreSettingsServiceImpl](src/main/java/org/uvo/uvostore/service/settings/StoreSettingsServiceImpl.java), [StoreSettings](src/main/java/org/uvo/uvostore/entity/settings/StoreSettings.java)                                                                                                                                                                                                                                                                                                                                                              |
| CI, release y calidad                       | [ci.yml](.github/workflows/ci.yml), [e2e.yml](.github/workflows/e2e.yml), [release.yml](.github/workflows/release.yml), [dependabot.yml](.github/dependabot.yml), [qodana.yaml](qodana.yaml), [pom.xml](pom.xml), [package.json](frontend/package.json), [frontend/e2e](frontend/e2e)                                                                                                                                                                                                                                                                                                                                                                                    |
| Comportamiento del proxy de desarrollo      | [vite.config.ts](frontend/vite.config.ts)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Textos legales actuales                     | [Terms](frontend/src/pages/legal/Terms.tsx), [Privacy](frontend/src/pages/legal/Privacy.tsx)                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
