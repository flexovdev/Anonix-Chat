# Deploy Anonix

## What this kit gives you

This repository now includes:

- `Dockerfile` for container builds;
- `docker-compose.prod.yml` for a single VPS deployment;
- `Caddyfile` for automatic HTTPS and reverse proxying;
- `.env.production.example` for production variables;
- GitHub Actions for CI, container publishing, and VPS deployment;
- `render.yaml` if you want a simpler first launch on Render.

## What the current architecture supports

The app is ready for public single-instance deployment.

It is not yet ready for true high availability because:

- room sessions are tracked in application memory;
- the STOMP broker is the in-process Spring simple broker;
- H2 is in memory by default.

If the process restarts, active rooms are lost. If you run more than one app instance, rooms and websocket subscriptions will split across instances.

## Fastest way to go public

Use GitHub + Render if you want the quickest first launch.

Use GitHub + GHCR + a VPS + Docker Compose + Caddy if you want more control, a custom domain, and a setup you can migrate later.

## Files for the VPS route

- `docker-compose.prod.yml`
- `Caddyfile`
- `.env.production.example`
- `.github/workflows/ci.yml`
- `.github/workflows/publish-image.yml`
- `.github/workflows/deploy-vps.yml`
- `scripts/bootstrap-ubuntu.sh`

## Recommended VPS flow

1. Create an Ubuntu VPS.
2. Point your domain to the server IP.
3. Run `scripts/bootstrap-ubuntu.sh` on the server.
4. Push this repository to GitHub.
5. Let `Publish Image` push `ghcr.io/<owner>/anonix:latest`.
6. Make the GHCR package public, or add registry login on the server if you keep it private.
7. Add the GitHub secrets listed below.
8. Run the `Deploy VPS` workflow.

## GitHub secrets for VPS deploy

Required:

- `VPS_HOST`
- `VPS_USER`
- `VPS_SSH_KEY`
- `APP_DOMAIN`
- `ACME_EMAIL`

Optional:

- `CHAT_DATASOURCE_URL`
- `CHAT_DATASOURCE_USERNAME`
- `CHAT_DATASOURCE_PASSWORD`
- `CHAT_PERSIST_HISTORY`
- `CHAT_SESSION_LIFETIME_MINUTES`
- `CHAT_MAX_MESSAGE_LENGTH`
- `CHAT_CLEANUP_MESSAGES_MS`
- `CHAT_CLEANUP_SESSIONS_MS`
- `CHAT_CLEANUP_AUTO_MS`
- `JAVA_OPTS`

## Cloudflare setup

If you put Cloudflare in front of the VPS:

- create an `A` record for your domain pointing to the VPS IP;
- use `Full (strict)` SSL mode;
- do not use `Flexible` SSL;
- keep WebSocket support enabled;
- let Caddy terminate TLS on the origin.

## Notes on storage

By default the deploy keeps the current in-memory H2 behavior.

If you want single-node persistence for H2, set:

- `CHAT_DATASOURCE_URL=jdbc:h2:file:/data/anonymouschat;DB_CLOSE_ON_EXIT=FALSE`

This is acceptable for a single VPS, but it still does not make the system horizontally scalable.

## What to change before true HA

To make the service genuinely fault tolerant and horizontally scalable:

- move room/session/rate-limit state to Redis;
- replace the in-memory STOMP broker with a shared broker;
- stop relying on in-memory H2 for shared state;
- run at least two app instances behind a load balancer;
- add uptime monitoring and alerting.

## Render option

If you want a simpler first launch instead of a VPS:

1. Push the repository to GitHub.
2. Create a Render Web Service from the repo.
3. Use the Docker runtime or sync `render.yaml`.
4. Set the health check path to `/actuator/health`.
5. Attach your domain.
