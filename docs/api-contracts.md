# API Contracts — ShopSphere

All endpoints via gateway `:8080` (prefix `/api`). Auth = `Authorization: Bearer <jwt>`.
Gateway injects `X-User-Id` / `X-User-Roles` — clients never send them.
Standard error: `{timestamp,status,error,message,path}`.

## Auth (public) — user-service
| Method | Path | Body → Response |
|---|---|---|
| POST | /v1/auth/register | {email,password,firstName,lastName,phone?} → 201 {accessToken,refreshToken,tokenType,expiresInSeconds} |
| POST | /v1/auth/login | {email,password} → 200 same token shape; 401 generic |
| POST | /v1/auth/refresh | {refreshToken} → 200 rotated pair; 401 if revoked/expired |
| POST | /v1/auth/logout | {refreshToken} → 204 |

Password policy: 8–100 chars, letter+digit. Refresh rotates every use;
reuse of revoked token revokes ALL user tokens.

## Users (JWT) — user-service
| Method | Path | Notes |
|---|---|---|
| GET | /v1/users/me | own profile |
| PUT | /v1/users/me | {firstName?,lastName?,phone?} |
| GET/POST | /v1/users/me/addresses | list / add (one default enforced) |
| PUT/DELETE | /v1/users/me/addresses/{id} | owner-only |

## Products (browse public, admin JWT+ADMIN) — product-service
| Method | Path | Notes |
|---|---|---|
| GET | /v1/products?q=&categoryId=&minPrice=&maxPrice=&page=&size=&sort= | public; sort ∈ name,price_asc,price_desc,newest |
| GET | /v1/products/{id} | public detail incl. images |
| GET | /v1/categories | public top-level categories |
| POST | /v1/admin/products | ADMIN; ProductRequest (sku,name,categoryId,price,…) |
| PUT | /v1/admin/products/{id} | ADMIN partial update |
| PATCH | /v1/admin/products/{id}/status | ADMIN {status}; DISCONTINUED = soft delete |
| GET | /v1/admin/products | ADMIN all statuses |

## Cart (JWT) — cart-service
| Method | Path | Notes |
|---|---|---|
| GET | /v1/cart | own cart (auto-created), items+subtotal |
| POST | /v1/cart/items | {productId,quantity} — validates ACTIVE product |
| PUT | /v1/cart/items/{productId} | {quantity} — 0 removes |
| DELETE | /v1/cart/items/{productId} | remove |
| POST | /v1/cart/internal/clear?customerId= | service-to-service (order confirm) |

## Orders (JWT, owner-only) — order-service
| Method | Path | Notes |
|---|---|---|
| POST | /v1/orders | {shippingAddressJson} → 201 PENDING; saga starts async |
| GET | /v1/orders?page=&size= | own history, newest first |
| GET | /v1/orders/{id} | detail (items, totals, sagaState) |
| GET | /v1/orders/track/{orderNumber} | tracking |
| POST | /v1/orders/{id}/cancel | {reason?} — PENDING/CONFIRMED only; triggers compensation |
| PATCH | /v1/admin/orders/{id}/status | ADMIN {status: SHIPPED\|DELIVERED} |

Status lifecycle: PENDING → CONFIRMED → SHIPPED → DELIVERED;
↘ CANCELLED / FAILED via saga events.

## Payments (JWT reads; mutations ADMIN) — payment-service
| Method | Path | Notes |
|---|---|---|
| GET | /v1/payments/{id} | status + gatewayRef |
| GET | /v1/payments/order/{orderId} | latest payment for order |
| GET | /v1/payments/{id}/transactions | append-only ledger |
| POST | /v1/payments/{id}/retry | ADMIN — FAILED only, new idem key |
| POST | /v1/payments/{id}/refund?reason= | ADMIN — CAPTURED only |

## Inventory (internal/admin) — inventory-service
| Method | Path | Notes |
|---|---|---|
| GET | /v1/inventory/stock/{productId} | onHand/reserved/available per warehouse |
| GET | /v1/inventory/orders/{orderId}/reservations | reservation lines |
| POST | /v1/inventory/internal/release/{orderId}?reason= | force-release (compensation) |

## Saga event flow (Kafka)
```
POST /orders ──► order.created ──► inventory-service: reserve
            ──► inventory.reserved ──► payment-service: charge
            ──► payment.completed ──► order CONFIRMED (+order.confirmed,
                                        cart cleared, notifications)
Failures: inventory.failed→order FAILED; payment.failed→order CANCELLED
          +order.cancelled→stock release (+auto-refund if captured)
```
