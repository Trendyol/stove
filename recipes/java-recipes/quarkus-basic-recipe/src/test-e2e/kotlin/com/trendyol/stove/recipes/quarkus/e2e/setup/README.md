# Quarkus Bridge Setup

This package provides Stove integration for Quarkus applications, enabling the `using<T>` DSL
to access beans from the Quarkus Arc CDI container.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Test Classloader                         │
│  ┌─────────────┐    ┌───────────────────┐    ┌───────────────┐ │
│  │ IndexTests  │───▶│ QuarkusBridgeSystem│───▶│  JDK Proxy    │ │
│  │ using<T>    │    │ get()/getByType() │    │ (Interface)   │ │
│  └─────────────┘    └───────────────────┘    └───────┬───────┘ │
└──────────────────────────────────────────────────────┼─────────┘
                                                       │ reflection
┌──────────────────────────────────────────────────────┼─────────┐
│                     Quarkus Classloader              ▼         │
│  ┌─────────────────┐    ┌─────────────┐    ┌───────────────┐   │
│  │ ArcContainer    │◀───│ Accessor    │◀───│  Real Bean    │   │
│  │ select/listAll  │    │ (reflection)│    │ (impl class)  │   │
│  └─────────────────┘    └─────────────┘    └───────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Components

| File | Purpose |
|------|---------|
| `Stove.kt` | Kotest project config, TestSystem setup |
| `QuarkusSystem.kt` | `quarkus()` DSL, starts application |
| `QuarkusBridgeSystem.kt` | `bridge()` DSL, `using<T>` support |
| `QuarkusContext.kt` | Classloader management |
| `ArcContainerAccessor.kt` | Reflection-based Arc access |

## Usage

```kotlin
// Single bean
using<HelloService> {
  hello() shouldBe "Hello"
}

// Multiple implementations
using<List<GreetingService>> {
  this shouldHaveSize 3
  forEach { println(it.greet("World")) }
}
```

## Limitations

### 1. Interface-Only Resolution

JDK Dynamic Proxies only work with interfaces. Concrete classes without interfaces will fail.

```kotlin
// ❌ Fails - no interface
using<HelloServiceImpl> { ... }

// ✅ Works - interface
using<HelloService> { ... }
```

**Workaround:** Always define interfaces for your beans (CDI best practice anyway).

### 2. Return Types from Methods

Method return values come from the Quarkus classloader. Primitives and Strings work fine,
but complex objects remain in the Quarkus classloader and aren't automatically proxied.

```kotlin
using<OrderService> {
  val id = getOrderId()      // ✅ String - works
  val order = getOrder(id)   // ⚠️ Order object from Quarkus CL
  order.id                   // ✅ String property - works
  order.customer             // ⚠️ Customer not proxied
}
```

**Workaround:** Use primitives/Strings for assertions, or serialize to JSON for complex comparisons.

### 3. Dead Code Elimination

Quarkus removes beans at build time if they're never injected anywhere.
Your beans must have at least one injection point in the application.

```java
// If no injection point exists, beans are removed!
@Inject Instance<GreetingService> greetings;  // Keeps all implementations
```

**Workaround:** Add an `Instance<T>` injection point in a resource or service.

### 4. Method Arguments Across Classloaders

Passing complex objects TO Quarkus beans may fail due to classloader mismatch.

```kotlin
using<OrderService> {
  val order = Order(...)  // Created in test CL
  save(order)             // ❌ Quarkus expects its Order class
}
```

**Workaround:** Use primitive values, IDs, or serialize to JSON/Map.

### 5. No Field Access

Only method invocations are proxied. Direct field access won't work.

```kotlin
using<ConfigService> {
  config.timeout  // ❌ Field access not proxied
  getTimeout()    // ✅ Method call works
}
```

**Workaround:** Use getter methods instead of field access.

### 6. Static Methods

Static methods cannot be proxied through the bridge.

**Workaround:** Wrap static calls in instance methods.

## Best Practices

1. **Design with interfaces** - Every CDI bean should implement an interface
2. **Use Instance<T>** - For multi-implementation scenarios
3. **Return simple types** - Prefer primitives/Strings from service methods
4. **Pass IDs, not objects** - Use identifiers instead of complex DTOs
5. **Test behavior, not state** - Focus on method results, not internal state
