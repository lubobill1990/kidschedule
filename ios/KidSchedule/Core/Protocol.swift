import Foundation

// 协议常量,见 docs/sync-protocol.md §14
enum SyncProtocol {
    static let undoWindowSec = 10
    static let pushBatchSize = 200
    static let pullPageSize = 500
    static let retryBackoffBaseSec = 2
    static let retryBackoffMaxSec = 300
    static let reminderSampleN = 20
    static let reminderMinSamples = 5
    static let attachmentMaxEdge = 2048
    static let attachmentJpegQuality = 80
}
