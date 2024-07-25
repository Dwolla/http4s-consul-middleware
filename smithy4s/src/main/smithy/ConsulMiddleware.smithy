$version: "2.0"

namespace com.dwolla.consul.smithy

use smithy4s.meta#typeclass

string ServiceName

@trait(selector: ":is(service)")
structure discoverable {
    @required serviceName: ServiceName
}
