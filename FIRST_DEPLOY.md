# First Deploy Checklist

Этот файл нужен для первого публичного запуска `Anonix` на VPS.

Ниже используются плейсхолдеры:

- `YOUR_DOMAIN` -> например `chat.example.com`
- `YOUR_EMAIL` -> почта для Let's Encrypt, например `admin@example.com`
- `YOUR_SERVER_IP` -> IP VPS
- `YOUR_GITHUB_OWNER` -> GitHub username или организация
- `YOUR_VPS_USER` -> пользователь на сервере, например `ubuntu`

## 1. Что купить и подготовить

Нужно:

- 1 VPS с Ubuntu 22.04 или 24.04
- 1 домен или поддомен
- GitHub-репозиторий с этим проектом
- Cloudflare, если хочешь проксирование и DNS через него

Минимально нормальная конфигурация VPS:

- 2 vCPU
- 2 GB RAM
- 20+ GB SSD

## 2. Подготовка DNS

Создай `A` запись:

- `chat` -> `YOUR_SERVER_IP`

Если домен целиком, а не поддомен:

- `@` -> `YOUR_SERVER_IP`

Для Cloudflare:

- SSL/TLS mode: `Full (strict)`
- WebSockets: `On`

Не используй `Flexible`.

## 3. Первый вход на сервер

Подключение:

```bash
ssh root@YOUR_SERVER_IP
```

Создай пользователя:

```bash
adduser YOUR_VPS_USER
usermod -aG sudo YOUR_VPS_USER
mkdir -p /home/YOUR_VPS_USER/.ssh
cp ~/.ssh/authorized_keys /home/YOUR_VPS_USER/.ssh/authorized_keys
chown -R YOUR_VPS_USER:YOUR_VPS_USER /home/YOUR_VPS_USER/.ssh
chmod 700 /home/YOUR_VPS_USER/.ssh
chmod 600 /home/YOUR_VPS_USER/.ssh/authorized_keys
```

Дальше зайди уже под этим пользователем:

```bash
ssh YOUR_VPS_USER@YOUR_SERVER_IP
```

## 4. Установка Docker и базовой подготовки

На локальной машине из корня проекта:

```bash
scp scripts/bootstrap-ubuntu.sh YOUR_VPS_USER@YOUR_SERVER_IP:~/
ssh YOUR_VPS_USER@YOUR_SERVER_IP "bash ~/bootstrap-ubuntu.sh"
```

После этого переподключись:

```bash
ssh YOUR_VPS_USER@YOUR_SERVER_IP
```

## 5. GitHub Container Registry

Образ будет публиковаться в:

```text
ghcr.io/YOUR_GITHUB_OWNER/anonix:latest
```

После первого успешного workflow `Publish Image` открой пакет в GitHub Packages и сделай его `Public`.

## 6. GitHub Secrets

Добавь в GitHub repository secrets:

### Обязательные

`VPS_HOST`

```text
YOUR_SERVER_IP
```

`VPS_USER`

```text
YOUR_VPS_USER
```

`VPS_SSH_KEY`

Это приватный SSH ключ, которым GitHub Actions будет входить на VPS.

Если у тебя ещё нет отдельного deploy-ключа, создай его локально:

```bash
ssh-keygen -t ed25519 -C "anonix-deploy" -f ~/.ssh/anonix_deploy
```

Добавь публичную часть на сервер:

```bash
ssh-copy-id -i ~/.ssh/anonix_deploy.pub YOUR_VPS_USER@YOUR_SERVER_IP
```

Потом положи содержимое файла `~/.ssh/anonix_deploy` в secret `VPS_SSH_KEY`.

`APP_DOMAIN`

```text
YOUR_DOMAIN
```

`ACME_EMAIL`

```text
YOUR_EMAIL
```

### Рекомендуемые

`CHAT_DATASOURCE_URL`

Для single-node с файловой H2, чтобы переживать рестарты:

```text
jdbc:h2:file:/data/anonymouschat;DB_CLOSE_ON_EXIT=FALSE
```

`CHAT_DATASOURCE_USERNAME`

```text
sa
```

`CHAT_DATASOURCE_PASSWORD`

Можно оставить пустым, если не усложняешь локальную H2.

`CHAT_PERSIST_HISTORY`

```text
false
```

`CHAT_SESSION_LIFETIME_MINUTES`

```text
30
```

`CHAT_MAX_MESSAGE_LENGTH`

```text
4096
```

`CHAT_CLEANUP_MESSAGES_MS`

```text
300000
```

`CHAT_CLEANUP_SESSIONS_MS`

```text
300000
```

`CHAT_CLEANUP_AUTO_MS`

```text
300000
```

`JAVA_OPTS`

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

## 7. Первый ручной деплой без GitHub Actions

Если хочешь сначала руками убедиться, что всё стартует:

Скопируй пример env:

```bash
cp .env.production.example .env.production
```

Заполни значения:

```text
APP_DOMAIN=YOUR_DOMAIN
ACME_EMAIL=YOUR_EMAIL
APP_IMAGE=ghcr.io/YOUR_GITHUB_OWNER/anonix:latest
CHAT_DATASOURCE_URL=jdbc:h2:file:/data/anonymouschat;DB_CLOSE_ON_EXIT=FALSE
CHAT_DATASOURCE_USERNAME=sa
CHAT_DATASOURCE_PASSWORD=
CHAT_PERSIST_HISTORY=false
CHAT_SESSION_LIFETIME_MINUTES=30
CHAT_MAX_MESSAGE_LENGTH=4096
CHAT_CLEANUP_MESSAGES_MS=300000
CHAT_CLEANUP_SESSIONS_MS=300000
CHAT_CLEANUP_AUTO_MS=300000
JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

Скопируй файлы на сервер:

```bash
scp docker-compose.prod.yml Caddyfile .env.production YOUR_VPS_USER@YOUR_SERVER_IP:~/anonix/
```

Зайди на сервер:

```bash
ssh YOUR_VPS_USER@YOUR_SERVER_IP
```

Если пакет GHCR публичный, запускай сразу:

```bash
cd ~/anonix
docker compose --env-file .env.production -f docker-compose.prod.yml pull
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

Проверка:

```bash
curl https://YOUR_DOMAIN/actuator/health
```

## 8. Первый деплой через GitHub Actions

Порядок:

1. Запусти workflow `Publish Image`.
2. Проверь, что образ появился в `GHCR`.
3. Сделай пакет публичным.
4. Запусти workflow `Deploy VPS`.
5. В поле `image_tag` оставь `latest`.

## 9. Обновление сервиса

Обычный сценарий:

1. Пушишь изменения в `main`
2. Срабатывает `Publish Image`
3. Запускаешь `Deploy VPS`

## 10. Полезные команды на сервере

Статус контейнеров:

```bash
cd ~/anonix
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

Логи приложения:

```bash
cd ~/anonix
docker compose --env-file .env.production -f docker-compose.prod.yml logs -f app
```

Логи Caddy:

```bash
cd ~/anonix
docker compose --env-file .env.production -f docker-compose.prod.yml logs -f caddy
```

Перезапуск:

```bash
cd ~/anonix
docker compose --env-file .env.production -f docker-compose.prod.yml restart
```

## 11. Что важно понимать

Этот набор подходит для публичного запуска на одном сервере.

Это ещё не настоящая отказоустойчивость, потому что:

- комнаты и websocket state живут в памяти приложения;
- брокер сообщений локальный;
- горизонтальное масштабирование пока не готово.

Для нормального HA следующим этапом нужен Redis и вынос общего state из памяти приложения.
