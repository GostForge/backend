# GostForge Backend

REST API на Spring Boot для платформы конвертации документов GostForge.

## Технологический стек

| Слой | Технология |
|------|------------|
| Runtime | Java 21, Spring Boot 3.4 |
| База данных | PostgreSQL 16 + миграции Flyway (Используются UUID v7 для внешних ID, INT для внутренних) |
| Аутентификация | JWT RS256 (30 дней, без Refresh) + Personal Access Tokens (PAT) |
| Хранилище | Локальная файловая система |
| Конвертация | md2gost (Markdown → DOCX) + Gotenberg (DOCX → PDF) |
| Документация API | SpringDoc OpenAPI 3 (Swagger UI) |

## Инструкция: Локальный дебаг и разработка (Local Debug)

Проект был значительно упрощен, чтобы минимизировать нагрузку на ресурсы (убраны Redis, MinIO и т.д.). Для запуска backend-утилиты локально (например, в IntelliJ IDEA или VS Code) следуйте этим шагам:

### 1. Поднятие базы данных и воркеров (dev-инфраструктура)
Перейдите в папку `infra` и запустите **только** необходимое окружение (БД PostgreSQL и сервисы конвертации: `md2gost`, `gotenberg`, `docx2md`).

```bash
cd ../infra
# Поднимаем dev-инфраструктуру
docker compose -f docker-compose.dev.yml up -d
```
*Примечание: Если вы меняете структуру БД, можно легко сбросить данные, удалив docker volume `gostforge-postgres` (поскольку проект в разработке).*

### 2. Подготовка ключей JWT
Для генерации JWT необходимы RSA-ключи (RS256). Сгенерировать их можно в директории `infra` с помощью скрипта или вручную:
```bash
# Приватный ключ
openssl genrsa -out private.pem 2048
# Публичный ключ
openssl rsa -in private.pem -outform PEM -pubout -out public.pem
```

В вашем IDE (Конфигурация запуска) или в `application-dev.yml` укажите пути к этим ключам:
`jwt.private-key=file:/path/to/private.pem`
`jwt.public-key=file:/path/to/public.pem`

### 3. Запуск Backend
В корне `backend` запустите сервис через Gradle (или запустите метод `main()` в `backend/src/main/java/org/gostforge/backend/BackendApplication.java` в вашей IDE):

```bash
./gradlew bootRun
```
Backend будет доступен по адресу `http://localhost:8080`.
Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## Переменные окружения (application.yml)

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `POSTGRES_HOST` | `localhost` | Хост PostgreSQL |
| `POSTGRES_PORT` | `5432` | Порт PostgreSQL |
| `POSTGRES_DB` | `gostforge` | Имя БД |
| `POSTGRES_USER` | `gostforge` | Пользователь БД |
| `POSTGRES_PASSWORD` | `gostforge_secret` | Пароль БД |
| `JWT_PRIVATE_KEY` | _(обязательно)_ | Приватный RSA ключ (путь или строка) |
| `JWT_PUBLIC_KEY` | _(обязательно)_ | Публичный RSA ключ (путь или строка) |
| `MD2GOST_SERVICE_URL` | `http://localhost:8081` | URL сервиса md2gost |
| `GOTENBERG_SERVICE_URL` | `http://localhost:3000` | URL сервиса Gotenberg |
| `DOCX2MD_SERVICE_URL` | `http://localhost:8082` | URL сервиса docx2md |

## Безопасность

- **JWT RS256**: Access-токен, выдающийся на длительный срок (30 дней). Refresh-концепция удалена ради простоты архитектуры.
- **PAT**: Токены с префиксом `gstf_`, в БД хранятся в виде SHA-256 (созданы для CLI/плагинов).

## Тестирование

```bash
./gradlew test
```
Для интеграционных тестов используются Testcontainers (потребуется работающий Docker Desktop/Daemon).
