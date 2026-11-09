# Contrato de la API (OpenAPI)

Estos archivos son la especificación OpenAPI vigente de UvoStore, un archivo por grupo de `OpenApiConfig`:

| Archivo | Rutas | Autenticación |
|---|---|---|
| `openapi-public.json` | `/api/v1/**` (storefront) | Ninguna |
| `openapi-admin.json` | `/api/admin/**` (panel de cada tienda) | JWT bearer |
| `openapi-customer.json` | `/api/customer/**` (cuenta del comprador) | JWT bearer |
| `openapi-pos.json` | `/api/webhooks/pos/**`, `/api/sync/**` (UvoPOS) | Firma HMAC o API key |
| `openapi-platform.json` | `/api/platform/**` (operador) | `X-Platform-Key`, solo desde la red privada |

**Por qué están versionados.** Producción y staging exigen `SWAGGER_ENABLED=false`, así que ahí `/swagger-ui.html` y `/v3/api-docs` responden 404. El contrato que queda es este.

**No se editan a mano.** Los genera `OpenApiSpecTest` a partir de los controladores. El mismo test los compara con lo que genera la aplicación y falla en la CI si la API cambió y estos archivos no. Después de cambiar un endpoint o un DTO expuesto, regenéralos y revisa el diff:

```bash
./mvnw test -Dtest=OpenApiSpecTest -Dopenapi.regenerate=true
```

**Notas:**
- Todas las rutas van al mismo origen que sirve la tienda (`servers: /`): el host es el que identifica a la tienda.
- Mientras `CUSTOMER_ACCOUNTS_ENABLED=false`, que es el valor por defecto, los endpoints de `openapi-customer.json` responden 404.
- Los retornos y webhooks de las pasarelas de pago (Stripe, Webpay, MercadoPago) están en `/api/v1/**`, así que van en `openapi-public.json`.
