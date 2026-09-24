# Green AI — API Gateway

Punto de entrada HTTP del Frontend para los microservicios Green AI. El primer incremento publica únicamente el contrato confirmado de Monitoring; Data Processing se incorporará cuando su OpenAPI sea aprobado.

Java 21 · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 · Gateway Server WebFlux.

## Responsabilidad

El Gateway enruta, reescribe prefijos, aplica CORS/timeouts y mantiene correlación mediante `X-Request-Id`. No consulta Prometheus o Supabase y no ejecuta PromQL, SQL, ETL, predicción o simulación.

```text
Frontend → Gateway → Monitoring
                   → Data Processing (cuando exista contrato)

Data Processing → Monitoring / Supabase / Prediction
```

## Ejecutar

Monitoring debe estar accesible y su URL se configura explícitamente:

```powershell
$env:GATEWAY_MONITORING_BASE_URL = 'http://127.0.0.1:8080'
$env:GATEWAY_AUTH_ISSUER_URI = 'https://<project-ref>.supabase.co/auth/v1'
$env:GATEWAY_AUTH_JWK_SET_URI = 'https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json'
$env:GATEWAY_CORS_ALLOWED_ORIGIN = 'http://127.0.0.1:3000'
.\mvnw.cmd spring-boot:run
```

El Gateway escucha en `8081` por defecto. Prueba las rutas externas:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8081/api/monitoring/v1/metrics/catalog'
Invoke-RestMethod 'http://127.0.0.1:8081/api/monitoring/v1/metrics/current?metric=node.cpu.utilization'
```

| Ruta externa | Ruta interna de Monitoring |
| --- | --- |
| `GET /api/monitoring/v1/metrics/catalog` | `/api/v1/metrics/catalog` |
| `GET /api/monitoring/v1/metrics/current` | `/api/v1/metrics/current` |
| `GET /api/monitoring/v1/metrics/history` | `/api/v1/metrics/history` |

Los query parameters se conservan. No existe un proxy comodín. Las rutas de métricas exigen JWT: 401 sin token válido, 403 sin rol autorizado o para rutas/métodos no permitidos con sesión válida. El Gateway conserva las respuestas de Monitoring.

## JWT de Supabase Auth

Validación obligatoria: firma ES256/RS256 por JWKS, issuer exacto, audience authenticated, expiración, subject y usuario no anónimo. El claim user_role debe ser OPERATOR o ADMIN; no existe rol por defecto ni bypass. Configurar el Custom Access Token Hook antes de usar el dashboard.

Las consultas PowerShell de este README requieren la cabecera Authorization con Bearer y un access token. No pegar tokens en documentación ni Git. Health y OpenAPI son públicos; el resto se deniega salvo las tres rutas GET confirmadas. Actuator prometheus queda sin exposición pública autorizada.

El Dockerfile ejecuta las pruebas durante la compilación con Java 21. Incluyen firma ES256 real con JWKS de prueba, expiración, issuer/audience incorrectos, roles, rutas y preflight CORS. No llaman al proyecto Supabase real.

La superficie externa está versionada en [gateway-v0.1.yaml](src/main/resources/static/openapi/gateway-v0.1.yaml) y se sirve en `/openapi/gateway-v0.1.yaml`. El esquema completo de las respuestas pertenece al OpenAPI de Monitoring; el Gateway no mantiene una copia divergente.

## Configuración

| Variable | Valor por defecto | Propósito |
| --- | --- | --- |
| `GATEWAY_PORT` | `8081` | Puerto del Gateway |
| `GATEWAY_MONITORING_BASE_URL` | `http://127.0.0.1:8080` | URL interna de Monitoring |
| `GATEWAY_CORS_ALLOWED_ORIGIN` | `http://127.0.0.1:3000` | Origen exacto permitido al Frontend |

En Docker Compose usar `http://monitoring:8080`. En Kubernetes usar el DNS real del Service; nunca `localhost` entre contenedores o pods.

## Conectar el Frontend

El navegador debe usar como base pública del API `http://127.0.0.1:8081` y llamar únicamente a:

```text
GET /api/monitoring/v1/metrics/catalog
GET /api/monitoring/v1/metrics/current
GET /api/monitoring/v1/metrics/history
```

Ejemplo mínimo para el adaptador HTTP del Frontend:

```javascript
const gatewayBaseUrl = window.GREEN_AI_GATEWAY_URL ?? "http://127.0.0.1:8081";

export async function getCurrentMetric(metric, accessToken, filters = {}) {
  const query = new URLSearchParams({ metric, resourceType: "node", ...filters });
  const response = await fetch(
    `${gatewayBaseUrl}/api/monitoring/v1/metrics/current?${query}`,
    { headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` } },
  );

  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail ?? `HTTP ${response.status}`);
  }
  return response.json();
}
```

El Frontend experimental debe reemplazar su base `/api` y dejar de consumir `/api/kpis`, `/api/logs`, `/api/hardware` y `/api/usuarios`. Primero obtiene las métricas disponibles desde `catalog`; después usa `current` para tarjetas y `history` para gráficos. No debe codificar nombres o unidades que ya entrega el contrato.

Para desarrollo local, `GATEWAY_CORS_ALLOWED_ORIGIN` debe coincidir exactamente con el origen donde se abrió la página, incluido hostname y puerto. Por ejemplo, si el Frontend se abre en `http://localhost:3001`:

```powershell
$env:GATEWAY_CORS_ALLOWED_ORIGIN = 'http://localhost:3001'
docker compose up --build
```

El compose del workspace usa `http://127.0.0.1:3001` por defecto y permite sobrescribirlo con esa variable. No se requiere Data Processing, Prediction ni Supabase para visualizar las métricas técnicas de Monitoring.

## Salud y observabilidad

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

La salud del Gateway no garantiza que Monitoring tenga una fuente de métricas disponible. Esa condición se comunica en la consulta funcional mediante el error de Monitoring.

## Data Processing

Data Processing consulta directamente la API interna de Monitoring para pipelines y lee históricos reales de Supabase con sus propias credenciales mínimas. El Gateway lo invocará solo para operaciones solicitadas por el Frontend. No se publican rutas de Data Processing hasta disponer de su OpenAPI.

El diseño y contrato propuesto están en [arquitectura-integracion-data-processing.md](arquitectura-integracion-data-processing.md).

## Verificación

```powershell
.\mvnw.cmd -B -ntp verify
```

Las pruebas comprueban que solo existe la ruta confirmada y que las rutas no registradas no se reenvían. La integración end-to-end requiere Monitoring en ejecución y se documentará separadamente.
