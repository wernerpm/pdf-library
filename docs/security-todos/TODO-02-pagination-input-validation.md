# TODO-02: No Input Validation on Pagination Parameters

**Severity:** HIGH
**Component:** API Layer
**File:** `src/main/kotlin/com/example/Main.kt:159-160`

## Description

The `page` and `size` query parameters on `/api/pdfs` have no bounds checking:

```kotlin
val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
val startIndex = page * size
val totalPages = (allPdfs.size + size - 1) / size
```

## Impact

- **Negative values:** Cause `IndexOutOfBoundsException`
- **size=0:** Division by zero when calculating `totalPages`
- **Integer overflow:** `page * size` overflows with large values
- **Memory exhaustion:** `size=2147483647` attempts to return entire dataset

## Suggested Fix

```kotlin
val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
```

## Affected Tests

- `MainTest` — add test cases for boundary values
