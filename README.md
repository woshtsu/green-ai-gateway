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

Los query parameters se conservan. No existe un proxy comodín: otros métodos y rutas reciben 404. El Gateway conserva las respuestas de Monitoring, incluido `application/problem+json` y los estados 400, 422, 502, 503 y 504.

La superficie externa está versionada en [gateway-v0.1.yaml](src/main/resources/static/openapi/gateway-v0.1.yaml) y se sirve en `/openapi/gateway-v0.1.yaml`. El esquema completo de las respuestas pertenece al OpenAPI de Monitoring; el Gateway no mantiene una copia divergente.

## Configuración

| Variable | Valor por defecto | Propósito |
| --- | --- | --- |
| `GATEWAY_PORT` | `8081` | Puerto del Gateway |
| `GATEWAY_MONITORING_BASE_URL` | `http://127.0.0.1:8080` | URL interna de Monitoring |
| `GATEWAY_CORS_ALLOWED_ORIGIN` | `http://127.0.0.1:3000` | Origen exacto permitido al Frontend |

En Docker Compose usar `http://monitoring:8080`. En Kubernetes usar el DNS real del Service; nunca `localhost` entre contenedores o pods.

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
