# Anonix

Anonix - временный анонимный чат на Spring Boot с WebSocket/STOMP и встроенным браузерным клиентом. Один сервис отдает UI, REST API и WebSocket endpoint.

Репозиторий уже подготовлен для локальной разработки и первого публичного запуска: здесь есть Gradle-сборка, Docker-образ, GitHub Actions, шаблоны для Render и деплоя на VPS.

## Что умеет приложение

- создает временные комнаты с безопасным идентификатором;
- выдает каждому участнику анонимный alias внутри комнаты;
- доставляет сообщения через WebSocket/STOMP с SockJS fallback;
- шифрует текст сообщений в браузере через Web Crypto (`AES-GCM`);
- хранит ключ приглашения в URL-фрагменте после `#`, поэтому он не уходит на сервер обычным HTTP-запросом;
- позволяет владельцу комнаты:
  - закрывать вход для новых участников;
  - включать автоматическую очистку комнаты;
- ограничивает создание комнат, входы, отправку сообщений и события активности через rate limiting;
- отдает health endpoint на `/actuator/health`.

## Важные особенности

- Сообщения шифруются в браузере до отправки. Сервер пересылает и при необходимости хранит шифротекст, а не открытый текст.
- Ключ приглашения находится в URL-фрагменте, например `#room=...&key=...`. Фрагмент не отправляется серверу в обычных HTTP-запросах.
- Если хочешь, чтобы другой участник видел те же сообщения, отправляй полную ссылку-приглашение, а не только `roomId`.
- По умолчанию используется H2 in-memory. После рестарта процессы, комнаты и сохраненное состояние пропадут, если не включить файловую H2 или внешнюю БД.
- Текущая архитектура рассчитана на один инстанс. Состояние комнат и WebSocket broker держатся в памяти приложения.

## Стек

- Java 17
- Spring Boot 3.2
- Spring WebSocket
- Spring Security
- Spring Data JPA
- H2
- Gradle
- встроенный фронтенд: `index.html`, `app.js`, `style.css`

## Структура репозитория

```text
src/main/java                backend-код
src/main/resources/static    встроенный frontend
src/test/java                тесты
Dockerfile                   сборка контейнера
docker-compose.prod.yml      single-VPS deploy
Caddyfile                    reverse proxy + HTTPS
.github/workflows            CI/CD
DEPLOY.md                    общий обзор деплоя
FIRST_DEPLOY.md              чеклист первого деплоя на VPS
```

## Локальный запуск

### Требования

- Java 17
- отдельный frontend runtime не нужен

### Запуск в dev-режиме

Windows:

```powershell
.\gradlew.bat bootRun
```

Linux/macOS:

```bash
./gradlew bootRun
```

После запуска приложение доступно на:

```text
http://localhost:8080
```

### Сборка jar

Windows:

```powershell
.\gradlew.bat test bootJar --no-daemon
```

Linux/macOS:

```bash
./gradlew test bootJar --no-daemon
```

Готовый артефакт появится в:

```text
build/libs/Anonymous-Chat-0.0.1-SNAPSHOT.jar
```

### Запуск jar

```bash
java -jar build/libs/Anonymous-Chat-0.0.1-SNAPSHOT.jar
```

## Переменные окружения

Приложение читает конфигурацию из environment variables.

| Переменная | Значение по умолчанию | Назначение |
| --- | --- | --- |
| `PORT` | `8080` | HTTP-порт |
| `CHAT_DATASOURCE_URL` | `jdbc:h2:mem:anonymouschat;DB_CLOSE_DELAY=-1` | URL datasource |
| `CHAT_DATASOURCE_USERNAME` | `sa` | пользователь БД |
| `CHAT_DATASOURCE_PASSWORD` | пусто | пароль БД |
| `CHAT_PERSIST_HISTORY` | `false` | сохранять ли зашифрованную историю сообщений |
| `CHAT_SESSION_LIFETIME_MINUTES` | `30` | TTL сессий и окна хранения |
| `CHAT_MAX_MESSAGE_LENGTH` | `4096` | серверный лимит длины шифротекста |
| `CHAT_CLEANUP_MESSAGES_MS` | `300000` | интервал очистки старых сообщений |
| `CHAT_CLEANUP_SESSIONS_MS` | `300000` | интервал очистки неактивных сессий |
| `CHAT_CLEANUP_AUTO_MS` | `300000` | интервал auto-cleanup задач |
| `JAVA_OPTS` | пусто | JVM-опции для рантайма/контейнера |

### Рекомендуемая single-node H2 с сохранением между рестартами

Если хочешь, чтобы данные переживали рестарт одного сервера, используй:

```text
CHAT_DATASOURCE_URL=jdbc:h2:file:/data/anonymouschat;DB_CLOSE_ON_EXIT=FALSE
CHAT_DATASOURCE_USERNAME=sa
CHAT_DATASOURCE_PASSWORD=
```

## REST API и WebSocket

### REST

- `POST /api/chat/room` - создать новый `roomId`
- `POST /api/chat/room/{roomId}/session` - войти в комнату и создать сессию участника
- `DELETE /api/chat/session/{sessionId}` - выйти из комнаты
- `GET /api/chat/session/{sessionId}/validate?roomId=...` - проверить валидность сессии
- `GET /api/chat/room/{roomId}/settings?sessionId=...` - получить настройки комнаты
- `PUT /api/chat/room/{roomId}/settings` - изменить настройки комнаты как владелец
- `POST /api/chat/room/{roomId}/activity` - отправить события вроде копирования/шаринга инвайта
- `GET /api/chat/room/{roomId}/history?sessionId=...&limit=50` - получить сохраненную зашифрованную историю, если persistence включен

### WebSocket

- endpoint: `/ws`
- application destination prefix: `/app`
- topic комнаты: `/topic/room/{roomId}`
- отправка сообщений: `/app/chat/{roomId}`

## Модель безопасности

- Статические файлы, API, WebSocket и health endpoint публичны.
- Приложение выставляет базовые защитные заголовки, включая CSP и `Referrer-Policy: no-referrer`.
- CSRF cookie включена, но `/api/**` и `/ws/**` исключены из CSRF enforcement.
- Сообщения шифруются на клиенте через `AES-GCM`.
- Backend валидирует `roomId`, принадлежность сессии комнате, идентификаторы сообщений и ограничивает злоупотребления через rate limiting.

## Деплой

В репозитории уже подготовлены два практических варианта.

### Вариант 1: Render

Это самый быстрый путь для первого публичного запуска.

1. Запушь репозиторий в GitHub.
2. Создай в Render новый Web Service из репозитория.
3. Используй `render.yaml` или выбери Docker runtime вручную.
4. Укажи health check path: `/actuator/health`.
5. При необходимости подключи домен.

См. также: [`render.yaml`](render.yaml)

### Вариант 2: VPS + Docker Compose + Caddy

Это основной вариант, если нужен свой сервер и домен.

1. Подними Ubuntu VPS.
2. Направь домен или поддомен на IP сервера.
3. Выполни `scripts/bootstrap-ubuntu.sh` на сервере.
4. Запушь проект в GitHub.
5. Публикуй образ в GHCR.
6. Деплой через `docker-compose.prod.yml` и `Caddyfile`.

Основные файлы:

- [`Dockerfile`](Dockerfile)
- [`docker-compose.prod.yml`](docker-compose.prod.yml)
- [`Caddyfile`](Caddyfile)
- [`.env.production.example`](.env.production.example)
- [`DEPLOY.md`](DEPLOY.md)
- [`FIRST_DEPLOY.md`](FIRST_DEPLOY.md)

## GitHub Actions

В репозитории уже есть три workflow:

- `CI` - гоняет тесты и собирает jar на push и pull request;
- `Publish Image` - собирает и публикует `ghcr.io/<owner>/anonix` при push в `main`;
- `Deploy VPS` - загружает файлы деплоя на VPS и перезапускает сервисы.

Файлы workflow:

- [`.github/workflows/ci.yml`](.github/workflows/ci.yml)
- [`.github/workflows/publish-image.yml`](.github/workflows/publish-image.yml)
- [`.github/workflows/deploy-vps.yml`](.github/workflows/deploy-vps.yml)

## Ограничения текущей архитектуры

Проект специально сделан простым и пока не готов к настоящему HA.

- активные участники комнат хранятся в памяти приложения;
- WebSocket broker - это встроенный Spring simple broker;
- H2 по умолчанию in-memory;
- при горизонтальном масштабировании состояние комнат разъедется между инстансами;
- рестарт процесса сбрасывает активные комнаты и сессии в памяти.

Чтобы перейти к HA, следующим шагом нужны:

- вынос общего room/session state во внешний storage, например Redis;
- общий broker вместо in-process решения;
- внешняя БД вместо локальной H2;
- несколько инстансов приложения за load balancer.

## Рекомендуемый процесс работы

Простой рабочий процесс для одного разработчика или маленькой команды:

1. Держать `main` в деплояемом состоянии.
2. Делать задачи в короткоживущих ветках `feature/...` или `fix/...`.
3. Открывать pull request.
4. Давать CI проверить сборку.
5. Мержить в `main`.
6. Публиковать новый контейнерный образ.
7. Деплоить на VPS вручную workflow-ом или дать Render сделать auto-deploy.

## Примечания

- Frontend встроен в Spring Boot приложение, отдельный frontend-деплой не нужен.
- В backend есть API истории, но основной сценарий UX сейчас - это live chat.
- `EncryptionService` в backend-коде является заглушкой и не участвует в реальном шифровании сообщений. Рабочий путь шифрования реализован в браузерном клиенте.
