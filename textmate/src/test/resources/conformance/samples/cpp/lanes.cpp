#include <algorithm>
#include <map>
#include <string>
#include <vector>

#ifndef MAX_LANES
#define MAX_LANES 64 \
    /* continued */
#endif

namespace graph {

struct Commit {
    std::string hash;
    std::vector<std::string> parents;
};

/// Gives each commit a lane.
template <class Range>
auto assignLanes(const Range& commits) -> std::map<std::string, int> {
    std::map<std::string, int> lanes;
    int next = 0;
    for (const auto& commit : commits) {
        if (auto it = lanes.find(commit.hash); it == lanes.end()) {
            lanes.emplace(commit.hash, std::min(next++, MAX_LANES - 1));
        }
    }
    return lanes;
}

}  // namespace graph

int main() {
    std::vector<graph::Commit> commits{{"a1b2c3d", {}}, {"e4f5a6b", {"a1b2c3d"}}};
    auto lanes = graph::assignLanes(commits);
    return static_cast<int>(lanes.size()) == 2 ? 0 : 1;
}
