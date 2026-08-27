import SwiftUI

struct EventDetailView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: HomeModel

    private let original: EventRow
    @State private var startedAt: Date
    @State private var endedAt: Date
    @State private var hasEnded: Bool
    @State private var note: String
    @State private var confirmDelete = false

    init(model: HomeModel, event: EventRow) {
        self.model = model
        self.original = event
        _startedAt = State(initialValue: TimeFmt.date(event.startedAt))
        _endedAt = State(initialValue: TimeFmt.date(event.endedAt ?? event.startedAt))
        _hasEnded = State(initialValue: event.status == "done" && event.endedAt != nil)
        _note = State(initialValue: event.note ?? "")
    }

    private var type: ActivityTypeRow? {
        model.typesById[original.activityTypeId]
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text(type?.icon ?? "•")
                        Text(type?.name ?? "记录")
                        if original.autoEnded {
                            Text("自动结束")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                        Spacer()
                        if original.status == "ongoing" {
                            Text("进行中").foregroundStyle(.tint)
                        }
                    }
                }
                Section("时间") {
                    DatePicker("开始", selection: $startedAt)
                    if original.status == "done" {
                        Toggle("有结束时间", isOn: $hasEnded)
                        if hasEnded {
                            DatePicker("结束", selection: $endedAt)
                        }
                    }
                }
                Section("备注") {
                    TextField("备注", text: $note, axis: .vertical)
                        .lineLimit(2...5)
                }
                if original.status == "ongoing" {
                    Button("现在结束") {
                        Task {
                            await model.endOngoing(env: env, eventId: original.id)
                            dismiss()
                        }
                    }
                }
                Button("删除记录", role: .destructive) { confirmDelete = true }
            }
            .navigationTitle("记录详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                }
            }
            .confirmationDialog("确认删除这条记录?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    Task {
                        try? await env.recordRepo.softDelete(eventId: original.id)
                        await model.sync(env: env)
                        dismiss()
                    }
                }
            }
        }
    }

    private func save() {
        var e = original
        e.startedAt = TimeFmt.millis(startedAt)
        if original.status == "done" {
            e.endedAt = hasEnded ? TimeFmt.millis(endedAt) : e.startedAt
            // 手动修正后不再是自动结束
            if original.autoEnded { e.autoEnded = false }
        }
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        e.note = trimmed.isEmpty ? nil : trimmed
        Task {
            try? await env.recordRepo.update(event: e)
            await model.sync(env: env)
            dismiss()
        }
    }
}
