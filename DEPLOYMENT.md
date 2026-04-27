# Verotin Deployment Guide

## Pre-Production Checklist

- [ ] All tests passing (`gradle test`)
- [ ] Code review completed
- [ ] Security audit (SQL injection, credentials, etc.)
- [ ] Performance benchmarked (chunking, embedding, search)
- [ ] Documentation up-to-date
- [ ] Error handling covers edge cases
- [ ] Ollama setup documented for production environment

## Environment Setup

### Production Configuration

Add `src/main/resources/application-prod.yml`:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  jpa:
    hibernate:
      ddl-auto: validate  # Never auto-create in prod
  flyway:
    enabled: true
    out-of-order: false

ollama:
  base-url: https://ollama.example.com  # Use HTTPS in prod
  embed-model: mxbai-embed-large
  chat-model: llama3
  embedding-dimensions: 1024

logging:
  level:
    fi.verotin: INFO
    org.springframework: WARN
  file: logs/verotin.log
  file:
    max-size: 100MB
    max-history: 30

server:
  port: 8080
  error:
    include-message: never  # Don't leak stack traces
    include-binding-errors: never
```

### Database Migration

Flyway handles all migrations automatically:

```bash
gradle flywayInfo      # Check migration status
gradle flywayValidate  # Validate migrations
```

**Migrations exist for**:
1. `V1__enable_pgvector.sql` — pgvector extension
2. `V2__source_documents.sql` — Source document table
3. `V3__document_chunks.sql` — Chunks + embedding storage
4. `V4__invoice_extractions.sql` — Invoice extraction storage
5. `V5__deduction_candidates.sql` — Deduction candidates + status tracking
6. `V6__tax_rule_chunks.sql` — Tax rules knowledge base

**Backup before any migration**:
```bash
pg_dump -U verotin -d verotin > backup_$(date +%s).sql
```

## Deployment Options

### 1. Docker Container

**Build JAR**:
```bash
gradle bootJar
```

**Create Dockerfile**:
```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY build/libs/verotin-*.jar verotin.jar

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "verotin.jar"]
```

**Build image**:
```bash
docker build -t verotin:latest .
```

**Run container**:
```bash
docker run -d \
  --name verotin \
  --network verotin-net \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/verotin \
  -e SPRING_DATASOURCE_USERNAME=verotin \
  -e SPRING_DATASOURCE_PASSWORD=<PASSWORD> \
  -e OLLAMA_BASE_URL=http://ollama:11434 \
  -v /var/log/verotin:/app/logs \
  verotin:latest
```

### 2. Kubernetes

**Example Deployment** (`k8s/deployment.yaml`):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: verotin
spec:
  replicas: 2
  selector:
    matchLabels:
      app: verotin
  template:
    metadata:
      labels:
        app: verotin
    spec:
      containers:
      - name: verotin
        image: verotin:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            configMapKeyRef:
              name: verotin-config
              key: db-url
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: verotin-secrets
              key: db-password
        - name: OLLAMA_BASE_URL
          value: "http://ollama-service:11434"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: verotin-service
spec:
  selector:
    app: verotin
  ports:
  - port: 80
    targetPort: 8080
  type: LoadBalancer
```

**Deploy**:
```bash
kubectl apply -f k8s/deployment.yaml
```

### 3. Amazon ECS

**Create task definition** (`ecs-task-def.json`):
```json
{
  "family": "verotin",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [
    {
      "name": "verotin",
      "image": "123456789.dkr.ecr.us-east-1.amazonaws.com/verotin:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/verotin",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

**Deploy**:
```bash
aws ecs register-task-definition --cli-input-json file://ecs-task-def.json
aws ecs create-service --cluster verotin-cluster --service-name verotin --task-definition verotin --desired-count 1
```

## Monitoring & Observability

### Health Checks

```bash
curl http://localhost:8080/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "ollama": {
      "status": "UP"
    }
  }
}
```

### Logging

**View logs**:
```bash
tail -f logs/verotin.log
```

**Log levels (set in application-prod.yml)**:
```yaml
logging:
  level:
    fi.verotin: INFO         # Application logs
    org.springframework: WARN # Framework logs
    org.postgresql: DEBUG    # DB logs (if needed)
```

### Metrics

**Prometheus Integration** (optional):
```gradle
implementation("io.micrometer:micrometer-registry-prometheus")
```

Expose at `/actuator/prometheus`.

### Alerts

Monitor in your alerting system:
- `/actuator/health` → DOWN
- Log errors containing "Ollama"
- Database connection pool exhaustion

## Scaling Considerations

### CPU/Memory

- **Base**: 512MB RAM, 1 CPU (dev)
- **Production**: 1–2GB RAM, 2 CPUs (may handle 100+ requests/sec)
- **Heavy load**: 4GB+ RAM, 4+ CPUs + auto-scaling

### Database

- **Connection pool**: 10–20 connections
- **IVFFlat lists**: Tune for your data volume:
  - <1000 rows: lists=50
  - 1000–100k rows: lists=100
  - >100k rows: lists=200

### Ollama

Run on dedicated GPU machine or scale separately:
```bash
ollama serve --host 0.0.0.0:11434
```

Verotin can be replicated; Ollama should be shared (or load-balanced).

## Security Hardening

### 1. database Connection

Use SSL:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/verotin?ssl=true&sslmode=require
```

### 2. API Authentication

Add Spring Security + API Keys:
```gradle
implementation("org.springframework.boot:spring-boot-starter-security")
```

Example filter:
```kotlin
@Component
class ApiKeyFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val apiKey = request.getHeader("X-API-Key")
        if (apiKey != System.getenv("API_KEY")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        chain.doFilter(request, response)
    }
}
```

### 3. Rate Limiting

Add Spring Cloud Gateway or similar.

### 4. HTTPS

Use reverse proxy (Nginx, HAProxy) or Spring Cloud Load Balancer:
```yaml
server:
  ssl:
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
```

### 5. Input Validation

Validate all user inputs:
```kotlin
@PostMapping("/ingest")
fun ingest(@RequestParam @Max(1000) query: String) { ... }
```

## Backup & Recovery

### Database Backup Strategy

**Daily backup** (cron job):
```bash
#!/bin/bash
pg_dump -U verotin verotin | gzip > /backups/verotin_$(date +%Y%m%d).sql.gz
```

**Retain** 30 days of daily backups.

**Test restore** monthly:
```bash
gunzip < /backups/verotin_20240101.sql.gz | psql -U verotin verotin_test
```

### Recovery Procedure

1. **Stop application**:
   ```bash
   docker stop verotin
   ```

2. **Restore database**:
   ```bash
   gunzip < /backups/verotin_YYYYMMDD.sql.gz | psql -U verotin verotin
   ```

3. **Restart application**:
   ```bash
   docker start verotin
   ```

4. **Verify health**:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

## Troubleshooting

### Application Won't Start

**Check logs**:
```bash
docker logs verotin
```

**Common issues**:
- Database not reachable: verify `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_PASSWORD`
- Ollama not reachable: verify `OLLAMA_BASE_URL`
- Port 8080 already in use: change `SERVER_PORT`

### Database Connection Pool Exhausted

Increase in `application-prod.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 40
```

Monitor with actuator:
```bash
curl http://localhost:8080/actuator/metrics/hikaricp.connections
```

### Ollama Timeouts

Increase timeout in `WebClientConfig.kt`:
```kotlin
@Bean
fun ollamaWebClient(props: OllamaProperties): WebClient =
    WebClient.builder()
        .baseUrl(props.baseUrl)
        .clientConnector(ReactorClientHttpConnector(
            HttpClient.create().responseTimeout(Duration.ofSeconds(60))
        ))
        .build()
```

### Search Queries Slow

Rebuild IVFFlat index:
```sql
REINDEX INDEX document_chunks_embedding_idx;
REINDEX INDEX tax_rule_chunks_embedding_idx;
```

Tune `lists`:
```sql
DROP INDEX IF EXISTS document_chunks_embedding_idx;
CREATE INDEX document_chunks_embedding_idx
  ON document_chunks USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 200);

ANALYZE document_chunks;
```

## Version Updates

### Update Ollama Models

```bash
ollama pull mxbai-embed-large  # Latest version
ollama pull llama3             # Latest version
```

No code changes needed (pulled on demand).

### Update Dependencies

```bash
gradle dependencyUpdates
```

Test before prod deployment.

### Update Java Runtime

Ensure Java 21+ available on deployment target.

## Rollback Procedure

If deployment fails:

1. **Revert container image**:
   ```bash
   docker pull verotin:previous-tag
   docker stop verotin
   docker run -d --name verotin verotin:previous-tag
   ```

2. **Revert database** (if migrations failed):
   ```bash
   # Rollback disallowed by Flyway
   # Manual recovery from backup required
   ```

3. **Verify health**:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

## Runbooks

### Emergency: High Error Rate

1. Check logs: `docker logs verotin | tail -100`
2. Check Ollama: `curl http://ollama:11434/api/tags`
3. Check DB: `curl http://localhost:8080/actuator/health`
4. Scale horizontally (add replicas)
5. If cascading: circuit breaker + graceful degradation

### Emergency: Database Full

1. Check usage: `SELECT pg_size_pretty(pg_database_size('verotin'));`
2. Identify large tables: `SELECT * FROM pg_stat_user_tables ORDER BY n_live_tup DESC;`
3. Archive old documents (optional)
4. Expand disk/storage

### Regular Maintenance

- **Weekly**: Monitor metrics, check logs for errors
- **Monthly**: Test backup/restore, run security scans
- **Quarterly**: Update dependencies, capacity planning

## Next Steps

1. Set up CI/CD pipeline (GitHub Actions, GitLab CI, Jenkins)
2. Add monitoring/alerting (Prometheus + Grafana, DataDog, etc.)
3. Implement authentication (API keys, OAuth, OIDC)
4. Add rate limiting
5. Set up log aggregation (ELK, Loki, CloudWatch)
6. Document runbooks for your organization

