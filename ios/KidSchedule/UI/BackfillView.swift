import SwiftUI

struct BackfillView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: HomeModel

    @State private var typeId: String?
    @State private var startedAt = Date()
    @State private var hasEnded = false
    @State private var endedAt = Date()
    @State private var note = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("行为") {
                    Picker("类型", selection: $typeId) {
                        ForEach(model.visibleTypes) { type in
                            Text("\(type.icon ?? "") \(type.name)").tag(type.id as String?)
                        }
                    }
                }
                Section("时间") {
                    DatePicker("开始", selection: $startedAt)
                    Toggle("有结束时间", isOn: $hasEnded)
                    if hasEnded {
                        DatePicker("结束", selection: $endedAt)
                    }
                }
                Section("备注") {
                    TextField("备注(可选)", text: $note, axis: .vertical)
                        .lineLimit(2...5)
                }
            }
            .navigationTitle("补录记录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(busy || resolvedTypeId == nil)
                }
            }
            .onAppear {
                if typeId == nil { typeId = model.visibleTypes.first?.id }
            }
        }
    }

    private var resolvedTypeId: String? {
        typeId ?? model.visibleTypes.first?.id
    }

    private func save() {
        guard let fid = env.familyId, let baby = model.selectedBaby, let tid = resolvedTypeId else { return }
        busy = true
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            _ = try? await env.recordRepo.backfill(
                familyId: fid, babyId: baby.id, typeId: tid,
                startedAt: TimeFmt.millis(startedAt),
                endedAt: hasEnded ? TimeFmt.millis(endedAt) : nil,
                note: trimmed.isEmpty ? nil : trimmed
            )
            await model.reload(env: env)
            dismiss()
            await model.sync(env: env)
        }
    }
}
