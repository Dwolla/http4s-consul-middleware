$version: "2.0"
namespace com.dwolla.test

use alloy#simpleRestJson
use com.dwolla.consul.smithy#discoverable

@http(method: "POST", uri: "/greet", code: 200)
operation Greet {
    input: GreetInput
    output: GreetOutput
}

structure GreetInput {
    name: String
}

structure GreetOutput {
    message: String
}

@simpleRestJson
@discoverable(serviceName: "hello-world")
service HelloService {
    operations: [Greet]
}

service UnannotatedService {
    operations: [Greet]
}
