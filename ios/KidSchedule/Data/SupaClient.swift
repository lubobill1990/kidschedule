import Foundation

// 手写 Supabase REST 客户端(Auth + PostgREST)。不依赖 supabase-swift,
// 行为可控:请求头、错误、查询串编码全部显式。

struct SupaConfig {
    static let url = "https://svgjfxmqkkfddwncuyqb.supabase.co"
    static let anonKey = "sb_publishable_PHsrFMNcUL_ehLapE1Wr_w_YqlR4Hvz"
}

struct SupaSession: Codable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Int64 // epoch 秒
    var userId: String
    var phone: String?
}

struct SupaError: Error, LocalizedError {
    let status: Int
    let body: String
    var errorDescription: String? { "HTTP \(status): \(body.prefix(300))" }
}

enum Json {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }()
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()
}

enum UrlEnc {
    private static let allowed: CharacterSet = {
        var s = CharacterSet.alphanumerics
        s.insert(charactersIn: "-._~")
        return s
    }()

    /// 严格百分号编码:'+'、':' 等一律转义,避免服务端把 '+' 解成空格
    static func enc(_ v: String) -> String {
        v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v
    }
}

actor SupaClient {
    private let baseUrl: String
    private let anonKey: String
    private let sessionKey = "supa_session"
    private(set) var session: SupaSession?

    init(baseUrl: String = SupaConfig.url, anonKey: String = SupaConfig.anonKey) {
        self.baseUrl = baseUrl
        self.anonKey = anonKey
        if let data = UserDefaults.standard.data(forKey: sessionKey) {
            session = try? JSONDecoder().decode(SupaSession.self, from: data)
        }
    }

    var userId: String? { session?.userId }
    var isLoggedIn: Bool { session != nil }

    // ---- Auth ----

    func sendOtp(phone: String) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["phone": phone])
        _ = try await request("POST", "/auth/v1/otp", body: body, useAuth: false)
    }

    func verifyOtp(phone: String, code: String) async throws {
        let body = try JSONSerialization.data(
            withJSONObject: ["type": "sms", "phone": phone, "token": code]
        )
        let data = try await request("POST", "/auth/v1/verify", body: body, useAuth: false)
        try applyAuthResponse(data)
    }

    func signOut() {
        session = nil
        UserDefaults.standard.removeObject(forKey: sessionKey)
    }

    func refreshIfNeeded() async throws {
        guard let s = session else { return }
        let now = Int64(Date().timeIntervalSince1970)
        guard s.expiresAt - now < 120 else { return }
        let body = try JSONSerialization.data(withJSONObject: ["refresh_token": s.refreshToken])
        do {
            let data = try await request(
                "POST", "/auth/v1/token?grant_type=refresh_token", body: body, useAuth: false
            )
            try applyAuthResponse(data)
        } catch let e as SupaError where (400...401).contains(e.status) {
            // refresh token 失效,登出走重新登录
            signOut()
            throw e
        }
    }

    private struct AuthResponse: Decodable {
        struct User: Decodable {
            let id: String
            let phone: String?
        }
        let accessToken: String
        let refreshToken: String
        let expiresIn: Int64?
        let expiresAt: Int64?
        let user: User
    }

    private func applyAuthResponse(_ data: Data) throws {
        let r = try Json.decoder.decode(AuthResponse.self, from: data)
        let expiresAt = r.expiresAt
            ?? (Int64(Date().timeIntervalSince1970) + (r.expiresIn ?? 3600))
        session = SupaSession(
            accessToken: r.accessToken, refreshToken: r.refreshToken,
            expiresAt: expiresAt, userId: r.user.id, phone: r.user.phone
        )
        if let data = try? JSONEncoder().encode(session) {
            UserDefaults.standard.set(data, forKey: sessionKey)
        }
    }

    // ---- PostgREST ----

    /// query 为已编码好的查询串(不含 '?')
    func select(_ table: String, query: String) async throws -> Data {
        try await refreshIfNeeded()
        return try await request("GET", "/rest/v1/\(table)?\(query)", body: nil, useAuth: true)
    }

    func rpc(_ fn: String, body: Data) async throws -> Data {
        try await refreshIfNeeded()
        return try await request("POST", "/rest/v1/rpc/\(fn)", body: body, useAuth: true)
    }

    // ---- 底层 ----

    private func request(_ method: String, _ path: String, body: Data?, useAuth: Bool) async throws -> Data {
        guard let url = URL(string: baseUrl + path) else {
            throw SupaError(status: -1, body: "bad url: \(path)")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 30
        req.setValue(anonKey, forHTTPHeaderField: "apikey")
        let bearer = useAuth ? (session?.accessToken ?? anonKey) : anonKey
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        if body != nil {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        req.httpBody = body

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw SupaError(status: -1, body: "no http response")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw SupaError(status: http.statusCode, body: String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }
}
