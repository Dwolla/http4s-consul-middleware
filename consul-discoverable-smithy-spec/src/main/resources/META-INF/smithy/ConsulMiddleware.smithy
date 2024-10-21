$version: "2.0"

namespace com.dwolla.consul.smithy

string ServiceName

@trait(selector: ":is(service)")
structure discoverable {
    @required serviceName: ServiceName
}
