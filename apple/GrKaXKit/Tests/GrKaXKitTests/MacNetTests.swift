import Foundation
import Testing
@testable import GrKaXKit

/// Parsing the output of `route` and `networksetup`, against text captured from
/// a real machine.
///
/// Nothing here runs a command: the network layer changes system settings, and
/// a test that flipped the proxy on a developer's laptop would be worse than no
/// test. What is checked is the half that actually goes wrong — the regexes.
struct MacNetTests {

    private static let routeOutput = """
       route to: default
    destination: default
           mask: default
        gateway: 192.168.1.1
      interface: en0
          flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
     recvpipe  sendpipe  ssthresh  rtt,msec    rttvar  hopcount      mtu     expire
           0         0         0         0         0         0      1500         0
    """

    /// Captured verbatim, VPN clients and all.
    private static let serviceListing = """
    An asterisk (*) denotes that a network service is disabled.
    (1) Thunderbolt Bridge
    (Hardware Port: Thunderbolt Bridge, Device: bridge0)

    (2) Wi-Fi
    (Hardware Port: Wi-Fi, Device: en0)

    (3) iPhone USB
    (Hardware Port: iPhone USB, Device: en7)

    (4) v2RayTun
    (Hardware Port: com.databridges.privacy.v2RayTun, Device: )

    (5) Happ
    (Hardware Port: su.ffg.happ, Device: )

    (6) Karing (system)
    (Hardware Port: com.nebula.karing, Device: )
    """

    @Test func readsInterfaceAndGatewayFromRoute() {
        #expect(MacNet.parseInterface(Self.routeOutput) == "en0")
        #expect(MacNet.parseGateway(Self.routeOutput) == "192.168.1.1")
    }

    @Test func survivesRouteWithNoDefault() {
        let empty = "   route to: default\n"
        #expect(MacNet.parseInterface(empty) == nil)
        #expect(MacNet.parseGateway(empty) == nil)
    }

    @Test func mapsDeviceToServiceName() {
        #expect(MacNet.parseService(forDevice: "en0", in: Self.serviceListing) == "Wi-Fi")
        #expect(MacNet.parseService(forDevice: "bridge0", in: Self.serviceListing) == "Thunderbolt Bridge")
        #expect(MacNet.parseService(forDevice: "en7", in: Self.serviceListing) == "iPhone USB")
        #expect(MacNet.parseService(forDevice: "en9", in: Self.serviceListing) == nil)
    }

    /// Other VPN clients register services with no device. Matching one of them
    /// would point the system proxy at a service that carries no traffic — and
    /// the user would see a connection that goes nowhere.
    @Test func ignoresServicesWithNoDevice() {
        #expect(MacNet.parseService(forDevice: "", in: Self.serviceListing) == nil)
        #expect(MacNet.parseService(forDevice: ")", in: Self.serviceListing) == nil)
    }

    /// A service name containing a bracketed suffix must survive intact —
    /// `networksetup` is given this string back and rejects anything else.
    @Test func keepsFullServiceNames() {
        let listing = """
        (1) Karing (system)
        (Hardware Port: com.nebula.karing, Device: utun9)
        """
        #expect(MacNet.parseService(forDevice: "utun9", in: listing) == "Karing (system)")
    }

    /// The stale-proxy sweep has to be narrow: another client's proxy, or our
    /// own from a second instance on a different port, must be left alone.
    @Test func recognisesOnlyOurOwnProxy() {
        let ours = "Enabled: Yes\nServer: 127.0.0.1\nPort: 10808\nAuthenticated Proxy Enabled: 0"
        #expect(MacNet.describesOurProxy(ours, port: 10808))
        #expect(!MacNet.describesOurProxy(ours, port: 10810), "чужой порт принят за свой")

        let disabled = "Enabled: No\nServer: 127.0.0.1\nPort: 10808"
        #expect(!MacNet.describesOurProxy(disabled, port: 10808))

        let someoneElse = "Enabled: Yes\nServer: 192.168.1.50\nPort: 10808"
        #expect(!MacNet.describesOurProxy(someoneElse, port: 10808), "чужой прокси принят за свой")
    }

    @Test func quotesArgumentsForTheElevatedScript() {
        #expect(Shell.quote("en0") == "en0")
        #expect(Shell.quote("Thunderbolt Bridge") == "'Thunderbolt Bridge'")
        #expect(Shell.quote("") == "''")
        // A service name with an apostrophe still has to survive the shell.
        #expect(Shell.quote("Ivan's Wi-Fi") == "'Ivan'\\''s Wi-Fi'")
    }
}
