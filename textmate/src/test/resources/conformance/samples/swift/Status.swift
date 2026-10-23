import Foundation

/// The state of a file in the working tree.
enum FileStatus: String, CaseIterable {
    case added, modified, deleted
    case conflicted = "both modified"
}

@MainActor
final class StatusModel: ObservableObject {
    @Published private(set) var files: [String: FileStatus] = [:]
    private let limit = 10_000

    func load(from porcelain: String) async throws {
        for line in porcelain.split(separator: "\n") where line.count > 3 {
            let code = line.prefix(2)
            let path = String(line.dropFirst(3))
            files[path] = code == "UU" ? .conflicted : .modified
        }
        guard files.count <= limit else { throw CocoaError(.fileReadTooLarge) }
        print("Loaded \(files.count) files") // interpolation
    }
}
