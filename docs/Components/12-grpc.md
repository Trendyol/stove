# gRPC Client

Call your app's gRPC services from tests. Supports Wire, grpc-kotlin, and custom client factories. Unary, server-stream, client-stream, bidi.

<a class="open-in-wizard" data-sys="grpc">Open in setup wizard</a>

<!--{wizard:snippet id=sys.grpc parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>grpc { GrpcSystemOptions(host = "localhost", port = 9090) }</code>. Use <code>wireClient&lt;T&gt;()</code> (Wire) or <code>channel&lt;T&gt;()</code> (gRPC-kotlin). For raw access, <code>rawChannel { channel -&gt; }</code>. Streaming via coroutines: <code>serverStream</code>, <code>clientStream</code>, <code>bidiStream</code>.
</div>

## Configure

```kotlin
Stove().with {
  grpc {
    GrpcSystemOptions(
      host = "localhost",
      port = 9090,
      usePlaintext = true,
      timeout = 30.seconds,
      interceptors = listOf(/* ClientInterceptor */),
      metadata = Metadata().apply {
        put(Metadata.Key.of("x-source", Metadata.ASCII_STRING_MARSHALLER), "stove")
      }
    )
  }
}.run()
```

| Field | Use |
|---|---|
| `host`, `port` | Where your gRPC server listens |
| `usePlaintext` | `true` for local tests; flip when testing TLS |
| `timeout` | Per-call deadline |
| `interceptors` | `ClientInterceptor` chain |
| `metadata` | Default per-call metadata (auth, tracing, ...) |
| `createChannel` | Custom `ManagedChannel` factory |
| `createWireClient` | Custom Wire client factory |

## DSL

### Wire client

```kotlin
stove {
  grpc {
    wireClient<OrderServiceClient>().createOrder(
      OrderRequest(userId = "u1", amount = 99.99)
    ).status shouldBe OrderStatus.CREATED
  }
}
```

### grpc-kotlin channel

```kotlin
stove {
  grpc {
    val stub = channel<OrderServiceGrpcKt.OrderServiceCoroutineStub>()
    stub.createOrder(orderRequest).status shouldBe "CREATED"
  }
}
```

### Per-call metadata override

```kotlin
stove {
  grpc {
    withEndpoint(::OrderServiceClient) {
      metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer x")
      createOrder(orderRequest)
    }
  }
}
```

### Raw channel access

```kotlin
stove {
  grpc {
    rawChannel { channel ->
      val stub = MyServiceGrpc.newBlockingStub(channel)
      stub.something()
    }
  }
}
```

### Streaming

```kotlin
stove {
  grpc {
    // server stream
    serverStream(::OrderServiceClient) {
      val flow = streamOrders(StreamRequest(userId = "u1"))
      flow.toList() shouldHaveSize 10
    }

    // client stream
    clientStream(::OrderServiceClient) {
      val reply = bulkCreate(flowOf(o1, o2, o3))
      reply.created shouldBe 3
    }

    // bidi
    bidiStream(::OrderServiceClient) {
      val out = chat(flowOf("hi", "hello"))
      out.toList().size shouldBe 2
    }
  }
}
```

## Error handling

```kotlin
stove {
  grpc {
    shouldThrow<StatusException> {
      wireClient<OrderServiceClient>().getOrder(OrderRequest(id = "missing"))
    }.status.code shouldBe Status.Code.NOT_FOUND
  }
}
```

## Multiple gRPC services (keyed)

```kotlin
object Inventory : SystemKey
object Payments  : SystemKey

Stove().with {
  grpc(Inventory) { GrpcSystemOptions(host = "localhost", port = 9090) }
  grpc(Payments)  { GrpcSystemOptions(host = "localhost", port = 9091) }
}

stove {
  grpc(Inventory) { wireClient<InventoryClient>().reserve(/* ... */) }
  grpc(Payments)  { wireClient<PaymentClient>().charge(/* ... */) }
}
```

See [Multiple Systems](20-multiple-systems.md).

## Pairs well with

- [gRPC Mock](14-grpc-mock.md) for mocking upstream gRPC services
- [Tracing](15-tracing.md) for full call chain with gRPC spans
- [Recipes](../recipes/index.md) for multi-system flows
