# Rate limiting

The gateway limits `/api/v1/**` per API key with a token bucket stored in Redis. Each key may send `RATE_LIMIT_BURST_CAPACITY` requests at once (default 20), and the bucket refills at `RATE_LIMIT_REPLENISH_RATE` requests per second (default 10). Requests without an API key share a bucket per client IP. `/admin/**` and the API docs are not limited. Provider webhooks have their own limit (see below).

A second, looser limit applies per client IP across all keys (`RATE_LIMIT_IP_BURST_CAPACITY`, default 40, refilled at `RATE_LIMIT_IP_REPLENISH_RATE`, default 20 per second). Without it, someone guessing keys could send a different key with every request and never be limited, because every unknown key starts with a full bucket. Only requests that pass their key's limit count against the IP limit, so a client retrying a key that is already limited doesn't use up the allowance of other merchants behind the same IP. The client IP is the address of the TCP connection, unless that address is a trusted proxy (see below).

Provider webhooks (`/api/v1/providers/*/webhook`) carry no API key and come from a few provider servers, often in bursts, so they have their own route with only a per-IP limit (`RATE_LIMIT_WEBHOOK_BURST_CAPACITY`, default 100, refilled at `RATE_LIMIT_WEBHOOK_REPLENISH_RATE`, default 50 per second). It is kept apart from the merchant limits in both directions: webhooks never use a merchant's allowance, and merchant traffic from the same IP never blocks webhooks. A limited webhook gets `429` with `Retry-After`; providers retry webhooks that weren't accepted.

The bucket is identified by a SHA-256 hash of the API key, so Redis never holds a merchant's key. Responses include `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity` and `X-RateLimit-Replenish-Rate`. A request over the limit is rejected before it reaches `payment-service`, with `429` in the common error format and a `Retry-After` header giving the seconds until a request is allowed again:

```json
{
  "timestamp": "2026-01-15T10:30:00Z",
  "status": 429,
  "error": "TOO_MANY_REQUESTS",
  "message": "Rate limit exceeded, retry later",
  "path": "/api/v1/payments"
}
```

## Client IP behind a proxy

Behind a reverse proxy or load balancer, every request arrives from the proxy's address, so without more setup all clients would share one per-IP bucket. List the proxies in `TRUSTED_PROXIES` (IPs or CIDRs, comma-separated). The gateway then reads `X-Forwarded-For` for requests whose TCP peer is in that list:

- The header is read from the right, because each proxy appends the address it received the request from. Addresses that are themselves trusted proxies are skipped, and the first address that isn't is the client IP. Entries further left were written by the client and are never used, so a client can't choose its bucket by sending its own `X-Forwarded-For`.
- If every address in the header is a trusted proxy, the left-most one is used. Without the header, the proxy's own address is used.
- If an entry can't be read as an IP address, with or without a port, the proxy's own address is used, because nothing to the left of it can be trusted.
- For a request from an address outside the list, the header is ignored.

This applies to all three limits: per API key for requests without a key, per client IP, and per webhook sender. With the default empty list, the client IP is always the TCP peer. The gateway logs at startup which mode is active.

Only list addresses you control. Trusting a range that clients can connect from would let them pick their own bucket. With Docker Compose and no proxy in front, leave it empty: requests from the host arrive from the Docker network gateway (for example `172.18.0.1`), which isn't a proxy that writes `X-Forwarded-For`.

## When Redis is down

Rate limiting fails open: if Redis can't be reached, requests go through without a limit instead of failing, so a Redis outage never stops payments. The gateway keeps reporting `UP` on `/actuator/health`, because Redis is only needed for rate limiting.

- **Redis stopped or unreachable:** requests are let through immediately. While the connection is down, the Redis client rejects commands at once instead of queuing them.
- **Redis up but not answering:** each limiter gives up after `REDIS_TIMEOUT` (250 ms), so a request waits at most about 0.5 s extra (one per-key and one per-IP check).
- **Redis back:** the client reconnects on its own and limiting resumes without a restart.
- During an outage, responses carry `X-RateLimit-Remaining: -1`. Requests made during the outage are not counted.
- The log shows the outage once per reconnect attempt (`Reconnecting…`, `Cannot reconnect…`, `Reconnected…`) instead of an error for every request. A Redis that is connected but not answering is not logged; it only shows as the extra delay.
- **Wrong Redis username or password:** also fails open, and isn't logged either. It shows only as `X-RateLimit-Remaining: -1` on every response, so check that header after changing credentials.

## Running several gateway instances

The buckets live in Redis, not in the gateway, so several gateway instances enforce one shared limit **only if they all use the same Redis**. An instance pointed at its own Redis gives every client a second full burst on that instance. Nothing else needs to be shared: the gateway keeps no state of its own.

- **Point every instance at one Redis** with the same `REDIS_HOST` and `REDIS_PORT`, plus `REDIS_USERNAME`, `REDIS_PASSWORD` and `REDIS_SSL` if that Redis needs them.
- **Redis Cluster or Sentinel** are configured with Spring Boot's standard properties, for example `SPRING_DATA_REDIS_CLUSTER_NODES=redis-1:7000,redis-2:7001`, or `SPRING_DATA_REDIS_SENTINEL_MASTER` and `SPRING_DATA_REDIS_SENTINEL_NODES`. A bucket is stored as two keys, `request_rate_limiter.{<id>}.tokens` and `.timestamp`. The braces are a Redis Cluster hash tag, so both keys of a bucket are on the same slot and the limiter's script can update them together.
- **Check it at startup.** Each instance logs where its limits are stored, without credentials:

  ```text
  Rate limits stored in Redis at redis:6379 (TLS off)
  ```

  For a Cluster or Sentinel the line names the nodes instead. All instances should print the same line.

To try it locally, start two gateway instances on ports 8080 and 8081, both using the bundled Redis:

```bash
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale api-gateway=2
```

`docker-compose.scale.yml` only turns the gateway's host port into the range `8080-8081`, one port per instance. In production, put a load balancer in front of the instances instead, and list it in `TRUSTED_PROXIES` (see [Client IP behind a proxy](#client-ip-behind-a-proxy)). Run `docker compose up -d` without the extra file to go back to one instance on 8080.
