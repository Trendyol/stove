# How To Write Tests?

There are two ways of testing. If you are using a framework in your application for example, you may attach Stove
to your application, so that you can debug and look with a magnifying glass to your code base. This is especially
helpful
when one wants to see how code works when the physical dependencies are present. Because, unit and integration tests
stay short
for the deeper insights when it comes to the behaviour of the system on production.

## Figuring out the dependencies

Your project might likely have dependencies for Couchbase, Kafka, and external http services. In the application context
we will spin up instances of **Couchbase** and **Kafka** but not the external http services because you don't manage
them, instead we will mock them using **Wiremock**.

### Docker Dependencies

!!! info
    You can skip this step if your registry has already the docker images

Docker dependencies are already hosted on trendyol registry, you need to be logged in to YOUR_REGISTRY in your
local docker to be able to pull the dependencies when the e2e tests run.

```shell
 docker buildx imagetools create confluentinc/cp-kafka:latest --tag YOUR_REGISTRY/confluentinc/cp-kafka:latest  
 docker buildx imagetools create couchbase/server:latest --tag YOUR_REGISTRY/couchbase/server:latest
```

!!! note
    Please make sure that you tag/upload the docker images as cross build to support developers that use ARM and AMD
    microprocessors.
    In the example above, as you can see uses `buildx` to create/tag cross-platform docker images and pushes them directly
    to the registry.


## Choose your testing strategy

- [I will attach my application to Stove E2e testing framework _(recommended)_](./Application-Aware)
- [I will use docker image of my application](./Dockerized)
