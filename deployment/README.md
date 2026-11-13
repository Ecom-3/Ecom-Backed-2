# Native Windows deployment

This deploy path builds Spring Boot executable jars and a static Vite frontend. It does not use Docker. The application services bind to loopback; put IIS or Nginx in front of the static frontend and gateway. Expose only the web server's HTTP/HTTPS ports publicly.

## Requirements

- Windows Server with Java 21, Maven, Node.js/npm, and MySQL installed.
- A MySQL application user that can connect to the provisioned Ecom schemas.
- A hostname and TLS certificate configured on IIS or Nginx.
- The reviewed SQL schema dumps from `../Ecom_DB` applied before starting the services. Runtime Flyway is disabled and Hibernate validates schemas.

## Build a release

From the repository root, run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\Ecom-Backend\Ecom-Backed\deployment\Build-Release.ps1
```

The script builds `http-sync`, packages each backend service, runs the frontend production build, and stages a timestamped release under `deployment/releases`. It excludes `.env` and other credentials. Maven needs access to its dependency repository on the first build.

For a separate API hostname, pass it at frontend build time, for example `-FrontendApiBaseUrl https://api.shop.example.com`. For the included same-origin reverse-proxy setup, leave it empty.

## Configure and start

1. Copy the release folder to the target server.
2. Copy `deployment/.env.example` to `.env` next to `Start-Release.ps1`; fill in unique production secrets, the database connection, and any SMTP settings. Do not commit or put this file in the web root.
3. Apply database schemas and required reviewed migrations. Use a least-privilege MySQL account; do not use the local development root account.
4. Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\Start-Release.ps1` from the release folder. The launcher waits for each service health endpoint and records logs/PIDs in the release folder.
5. Configure IIS URL Rewrite + ARR, or Nginx using `nginx.conf.example`, to serve the `frontend` directory and proxy the API prefixes to `http://127.0.0.1:58082`.
6. Configure HTTPS on the web server, then verify sign-in, customer OTP delivery, catalog, checkout, and order flows.

`VITE_API_BASE_URL` should normally remain blank because the frontend uses relative service paths and the reverse proxy sends those paths to the gateway. The frontend's Google Maps key, if used, is a public browser key; restrict it to the production hostname and required APIs.

## Stop and operations

Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\Stop-Release.ps1`. For unattended operation, configure Windows Task Scheduler to start `Start-Release.ps1` at boot under a dedicated service account, with a restart-on-failure policy. Keep the private `.env`, logs, database backups, and releases outside the public web root. The `Ecom-Alerts` legacy service is not included in this core release.

Before public traffic, validate that all schemas match the current service model, production secrets have replaced placeholders, SMTP is configured if OTP email is required, TLS is active, and only the reverse proxy is reachable from the internet.
