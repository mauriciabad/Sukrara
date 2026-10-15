import Foundation

/// Finds Juggluco's web server on the local network without knowing the phone's IP:
/// probes every host of the Mac's IPv4 subnets (≤ /22) on port 17580 and keeps the one whose
/// /api/v1/status.json says `"name": "juggluco"`. Juggluco has no mDNS/Bonjour, hence the sweep.
/// Works on home Wi-Fi and on the phone's own hotspot (no internet needed).
enum Discovery {
    static func find(port: Int = 17580, secret: String? = nil) async -> URL? {
        let candidates = localSubnetHosts()
        log("discovery: probing \(candidates.count) hosts on :\(port)")
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 1.5
        cfg.httpMaximumConnectionsPerHost = 1
        let session = URLSession(configuration: cfg)
        defer { session.invalidateAndCancel() }

        return await withTaskGroup(of: URL?.self) { group in
            for host in candidates {
                group.addTask {
                    let base = URL(string: "http://\(host):\(port)")!
                    var req = URLRequest(url: base.appending(path: "api/v1/status.json"))
                    if let secret, !secret.isEmpty { req.setValue(JugglucoSource.sha1hex(secret), forHTTPHeaderField: "api-secret") }
                    guard let (data, resp) = try? await session.data(for: req),
                          (resp as? HTTPURLResponse)?.statusCode == 200,
                          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                          (obj["name"] as? String)?.lowercased() == "juggluco" else { return nil }
                    return base
                }
            }
            for await found in group where found != nil {
                group.cancelAll()
                return found
            }
            return nil
        }
    }

    /// All host addresses of the Mac's active IPv4 interfaces (excluding the Mac itself).
    static func localSubnetHosts() -> [String] {
        var result: [String] = []
        var ifap: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifap) == 0, let first = ifap else { return [] }
        defer { freeifaddrs(ifap) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), let mask = ifa.ifa_netmask,
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            let ip = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let nm = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let size = ~nm
            guard size >= 2, size <= 1024, ip >> 24 != 169 else { continue }   // skip huge nets and link-local
            let net = ip & nm
            for h in 1..<size where net | h != ip {
                let a = net | h
                result.append("\(a >> 24).\((a >> 16) & 255).\((a >> 8) & 255).\(a & 255)")
            }
        }
        return result
    }
}
