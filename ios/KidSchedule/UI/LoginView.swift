import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var env: AppEnv
    @State private var phone = "+86"
    @State private var code = ""
    @State private var codeSent = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "figure.and.child.holdinghands")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
            Text("KidSchedule")
                .font(.largeTitle.bold())
            Text("多用户家庭共享的婴儿看护记录")
                .foregroundStyle(.secondary)

            VStack(spacing: 12) {
                TextField("手机号(+86…)", text: $phone)
                    .keyboardType(.phonePad)
                    .textFieldStyle(.roundedBorder)

                if codeSent {
                    TextField("短信验证码", text: $code)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                }

                if let error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                if !codeSent {
                    Button(action: sendCode) {
                        if busy { ProgressView() } else { Text("发送验证码") }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || phone.count < 8)
                } else {
                    Button(action: verify) {
                        if busy { ProgressView() } else { Text("登录") }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || code.count < 4)

                    Button("重新发送") { sendCode() }
                        .font(.footnote)
                        .disabled(busy)
                }
            }
            .padding(.horizontal, 32)
            Spacer()
            Spacer()
        }
    }

    private func sendCode() {
        busy = true
        error = nil
        let p = phone.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                try await env.supa.sendOtp(phone: p)
                codeSent = true
            } catch {
                self.error = "发送失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func verify() {
        busy = true
        error = nil
        let p = phone.trimmingCharacters(in: .whitespaces)
        let c = code.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                try await env.supa.verifyOtp(phone: p, code: c)
                env.onLoggedIn()
            } catch {
                self.error = "验证失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }
}
