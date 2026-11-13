# Gateway verification in Postman

1. Start the backend with `.\run-all-services.ps1 start` from the backend directory.
2. Import `Ecom-Gateway.postman_collection.json` and `Ecom-Local.postman_environment.json` into Postman.
3. Select the **Ecom Local Gateway** environment.
4. Run folders **01**, **02**, and **03** with the Collection Runner. They require no credentials.
5. Set `username` and `password` to an existing account's credentials in the selected environment, then run folder **04** in order. Login saves `access_token` automatically. Keep credentials and captured tokens local; do not commit exported populated environments.

The default gateway URL is `http://localhost:58082`. Change `gateway_url` if needed, without a trailing slash. Set `frontend_origin` to your frontend's origin for the CORS test.

The collection contains 22 requests covering gateway health, all core service route prefixes (including cart, order and fulfillment aliases), an org-access ping, payment capabilities, missing routes, rejected authentication, CORS preflight, login and token validation. It does not create business records. Login can update authentication audit/rate-limit state.

Expected results:

- Health checks: HTTP 200 and `status: UP`.
- Ping: HTTP 200 and `service: org-access`.
- Payment capabilities: HTTP 200 with a boolean `processingEnabled`. The current implementation disables payment processing; this is not a gateway failure.
- Unknown route: HTTP 404.
- Profile requests without a token or with an invalid token: HTTP 401.
- CORS: HTTP 200 or 204 with matching origin and allowed method/header.
- Valid login and authenticated identity checks: HTTP 200.

Troubleshooting:

- Connection refused: check that the gateway is running on port 58082.
- HTTP 503 on a service route: check the service and its Eureka registration at `http://localhost:58761`.
- HTTP 404 on a configured route: check the config server and gateway route configuration; the gateway removes the first path segment before forwarding.
- HTTP 401/403 on login or identity checks: check the account credentials and token. These are downstream authentication checks through the gateway, not evidence of gateway-level authentication.
- Failed health status: inspect the relevant service's dependencies and logs in `D:\ecom\Ecom\.local\logs`.
- Failed CORS assertions: inspect the response headers and the configured frontend origin. Postman itself does not enforce browser CORS.

Gateway health alone does not prove downstream routing. Passing these checks verifies basic connectivity, prefix stripping and authentication forwarding; it does not validate complete checkout or payment workflows. Config-server/Eureka health and unrelated legacy routes are outside this collection.
