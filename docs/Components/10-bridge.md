# Bridge

Bridge component is used for accessing the DI container of the application. When the application is started, the bridge
is
created and the DI container is accessed in the tests.

If you want to access to the beans of the application, you can simply do:

```kotlin
// setup
TestSystem()
  .with {
    //other deps...
    bridge()
  }

// while writing tests
validate {
  using<YourBean> {
    this.doSomething()
  }

  using<Bean1, Bean2> { bean1, bean2 ->
    bean1.doSomething()
    bean2.doSomething()
  }
}
```

Both Spring-Boot and Ktor have `bridge` function built-in, so you don't have to add any extra dependency than
`com-trendyol:stove-ktor-testing-e2e` or `com-trendyol-stove-spring-testing-e2e`. If you are using Spring-Boot, the
bridge will be referring to the`ApplicationContext` and if you are using Ktor, it will be referring to the
`Application`.
