# Reglas de green-ai-gateway

- Este directorio es un repositorio independiente. No modificar proyectos hermanos desde una tarea del Gateway.
- Conservar Java 21, Spring Boot 4.1.1 y el release train compatible de Spring Cloud fijado en el POM.
- El Gateway es la única entrada del Frontend y solo publica rutas y métodos incluidos explícitamente.
- No consultar Prometheus o Supabase; no ejecutar SQL, PromQL, ETL, predicción ni simulación.
- Data Processing consulta Monitoring directamente para pipelines internos. El Gateway solo lo invoca por solicitudes externas cuando exista un OpenAPI aprobado.
- No habilitar rutas comodín hacia la red interna ni aceptar una URL de destino aportada por el cliente.
- Propagar correlación y errores contractuales sin filtrar DNS internos, trazas o secretos.
- Sin reintentos sobre métodos mutables. Timeouts y límites se configuran en el Gateway y siguen vigentes en cada servicio interno.
- Añadir un servicio solamente después de acordar propietario, OpenAPI, health, timeout y variables de configuración.
- No incorporar autenticación ficticia. JWT/RBAC se añadirá como incremento explícito con emisor, audiencia y roles confirmados.
