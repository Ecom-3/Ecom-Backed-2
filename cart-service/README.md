# Cart Service

Cart Service owns customer shopping carts and cart items. It stores `customerId`, `productId`, and `variantId` as identifiers only; Customer Service and Catalog Service remain the owners of their respective entities.

Run with MySQL database `cart_service` on port `3310` by default and the environment variables documented in `src/main/resources/application.yml`. Flyway creates the local cart schema on startup.

Endpoints are rooted at `/api/v1/carts`: create/get carts, add/update/remove items, clear a cart, get an active cart, and recalculate totals. All money values use `BigDecimal` and two decimal places.

## Local Run

Create the database in MySQL Workbench:

```sql
CREATE DATABASE IF NOT EXISTS cart_service;
```

Start the service:

```powershell
cd "C:\Users\vinee\OneDrive\Documents\Ecom v1\Ecom\service\cart-service"
mvn spring-boot:run
```

Default local URLs use:

```text
Base URL: ${CART_SERVICE_URL:http://localhost:58091}
MySQL:    localhost:53310
Database: cart_service
Username: root
Password: root123
```

If your MySQL Workbench server uses another port, override it while starting:

```powershell
$env:MYSQL_PORT="3306"
mvn spring-boot:run
```

## Postman Samples

Use header `Content-Type: application/json` for request bodies.

Create cart:

```http
POST ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts
```

```json
{
  "customerId": "11111111-1111-1111-1111-111111111111",
  "storeId": "main-store"
}
```

Get cart:

```http
GET ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}
```

Get active cart:

```http
GET ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/active?customerId=11111111-1111-1111-1111-111111111111&storeId=main-store
```

Add item:

```http
POST ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}/items
```

```json
{
  "productId": "22222222-2222-2222-2222-222222222222",
  "variantId": "33333333-3333-3333-3333-333333333333",
  "productName": "Avakaya Pickle 500g",
  "quantity": 2,
  "unitPrice": 199.00
}
```

Update item quantity:

```http
PATCH ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}/items/{{itemId}}
```

```json
{
  "quantity": 3
}
```

Remove item:

```http
DELETE ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}/items/{{itemId}}
```

Clear cart:

```http
DELETE ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}/items
```

Calculate cart total:

```http
POST ${CART_SERVICE_URL:http://localhost:58091}/api/v1/carts/{{cartId}}/calculate-total
```
