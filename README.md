# GostForge Backend

REST API на Spring Boot для платформы конвертации документов GostForge.

## Технологический стек

| Слой | Технология |
|------|------------|
| Runtime | Java 21 (virtual threads), Spring Boot 3.4 |
| База данных | PostgreSQL 16 + миграции Flyway |
| Кэш | Redis 7 (refresh-токены, rate limiting) |
| Хранилище | MinIO (S3-совместимое объектное хранилище) |
| Аутентификация | JWT RS256 + Personal Access Tokens (PAT) |
| Конвертация | md2gost (Markdown → DOCX) + Gotenberg (DOCX → PDF) |
| Документация API | SpringDoc OpenAPI 3 (Swagger UI) |

## Структура проекта

```text
src/main/java/org/gostforge/backend/
├── Main.java                          # Точка входа
├── auth/                              # Регистрация, вход, refresh JWT
├── common/                            # Общие сущности, исключения, DTO
├── config/                            # Конфигурационные бины приложения
├── conversion/                        # Пайплайн конвертации
│   ├── ConversionJob.java             # Сущность задачи
│   ├── ConversionJobRepository.java
│   ├── ConversionService.java         # Основная оркестрация (md2gost + Gotenberg)
│   ├── ConversionWorker.java          # Фоновый обработчик задач
│   ├── QuickConvertController.java    # Публичные и внутренние endpoint'ы
│   ├── StaleCacheException.java       # Ошибка рассинхронизации CAS
│   └── dto/
├── pat/                               # Управление Personal Access Token
├── security/                          # JWT/PAT фильтры, SecurityConfig
├── storage/
│   ├── CasService.java                # SHA-256 Content-Addressable Storage (MinIO)
│   ├── MinioStorageService.java       # Низкоуровневые операции MinIO
│   ├── UserCasFile.java               # Связь пользователя и CAS-файлов
│   └── UserCasFileRepository.java
├── telegram/                          # Telegram auth + внутренние API endpoint'ы
└── user/                              # CRUD пользователя, профиль
```

## Быстрый старт

### Требования

- Java 21+
- Запущенные PostgreSQL, Redis, MinIO, md2gost, Gotenberg
  (рекомендуется через `../infra/docker-compose.yml`)

### Запуск через Docker Compose (рекомендуется)

```bash
cd ../infra
cp .env.example .env   # заполните обязательные секреты
docker compose up -d
```

Backend будет доступен по адресу `http://localhost:8080`.

### Локальный запуск (development)

```bash
# Поднять только инфраструктурные сервисы
cd ../infra && docker compose up -d postgres redis minio minio-init md2gost gotenberg

# Запустить backend (требуется Java 21)
cd ../backend
./gradlew bootRun
```

### Сборка JAR

```bash
./gradlew bootJar
java -jar build/libs/gostforge-backend-*.jar
```

## Переменные окружения

Все настройки заданы в `src/main/resources/application.yml`, при необходимости переопределяются через environment variables.

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `POSTGRES_HOST` | `localhost` | Хост PostgreSQL |
| `POSTGRES_PORT` | `5432` | Порт PostgreSQL |
| `POSTGRES_DB` | `gostforge` | Имя БД |
| `POSTGRES_USER` | `gostforge` | Пользователь БД |
| `POSTGRES_PASSWORD` | `gostforge` | Пароль БД |
| `REDIS_HOST` | `localhost` | Хост Redis |
| `REDIS_PORT` | `6379` | Порт Redis |
| `REDIS_PASSWORD` | _(пусто)_ | Пароль Redis |
| `MINIO_ENDPOINT` | `http://localhost:9000` | Endpoint MinIO |
| `MINIO_ACCESS_KEY` | `minioadmin` | Access key MinIO |
| `MINIO_SECRET_KEY` | `minioadmin` | Secret key MinIO |
| `MINIO_BUCKET` | `gostforge` | Имя bucket в MinIO |
| `JWT_PRIVATE_KEY` | _(обязательно)_ | Приватный RSA ключ (PEM) |
| `JWT_PUBLIC_KEY` | _(обязательно)_ | Публичный RSA ключ (PEM) |
| `INTERNAL_API_KEY` | `gostforge_internal_dev` | Ключ для `X-Internal-Api-Key` |
| `TELEGRAM_BOT_TOKEN` | _(опционально)_ | Токен Telegram бота (для `/auth/telegram`) |
| `MD2GOST_SERVICE_URL` | `http://md2gost:5000` | URL сервиса md2gost |
| `GOTENBERG_SERVICE_URL` | `http://gotenberg:3000` | URL сервиса Gotenberg |

## Обзор API

Базовый URL: `http://localhost:8080/api/v1`

| Endpoint | Метод | Auth | Описание |
|----------|-------|------|----------|
| `/auth/register` | POST | — | Регистрация пользователя |
| `/auth/login` | POST | — | Логин, выдача JWT + refresh token |
| `/auth/refresh` | POST | — | Обновление access token |
| `/auth/logout` | POST | JWT | Инвалидация refresh token |
| `/auth/telegram` | POST | — | Логин/линковка через Telegram WebApp initData |
| `/users/me` | GET | JWT/PAT | Профиль текущего пользователя |
| `/pats` | GET/POST | JWT | Просмотр/создание Personal Access Token |
| `/pats/{id}` | DELETE | JWT | Отзыв PAT |
| `/convert/quick/check-hashes` | POST | JWT/PAT | Проверка отсутствующих хэшей в CAS |
| `/convert/quick` | POST | JWT/PAT | Создание задачи конвертации (multipart: files[] + manifest) |
| `/convert/quick/jobs/{id}` | GET | JWT/PAT | Получение статуса задачи |
| `/convert/quick/jobs/{id}/stream` | GET (SSE) | JWT/PAT | Статус задачи в реальном времени |
| `/convert/quick/jobs/{id}/download/{format}` | GET | JWT/PAT | Скачивание результата (DOCX/PDF) |
| `/internal/convert/quick` | POST | Internal | Создание задачи от имени Telegram пользователя |
| `/internal/jobs/{id}` | GET | Internal | Статус задачи (для Telegram бота) |

Для Internal API используются заголовки `X-Internal-Api-Key` + `X-Telegram-Chat-Id`.

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Хэш-кэширование (CAS)

Backend использует Content-Addressable Storage, чтобы не загружать неизменённые файлы повторно:

1. Клиент отправляет `POST /convert/quick/check-hashes` с `{filePath → sha256hex}`
2. Сервер возвращает пути файлов, которых нет в MinIO CAS (`cas/<hash>`)
3. Клиент загружает только отсутствующие файлы + полный manifest
4. Сервер валидирует хэши, сохраняет их как `cas/<hash>`, затем собирает полное рабочее пространство
5. Если кэш был очищен между check и submit, сервер отвечает `409 STALE_CACHE` — клиент повторяет процесс

## Безопасность

- **JWT RS256**: access токен (15 минут) + refresh токен (7 дней в Redis)
- **PAT**: токены с префиксом `gstf_`, в БД хранятся в виде SHA-256
- **BCrypt(12)**: хеширование паролей
- **Internal API**: отдельная цепочка фильтров, обязателен `X-Internal-Api-Key`

## Тестирование

```bash
./gradlew test
```

Для интеграционных тестов используются Testcontainers (реальные PostgreSQL/Redis).

## Лицензия

Часть проекта GostForge. См. LICENSE в корне соответствующего репозитория.
