#import <Foundation/Foundation.h>
#include <string>
#include <vector>

namespace leaf {
template <typename T>
std::vector<T> keepFirst(const std::vector<T>& items, size_t count) {
    return std::vector<T>(items.begin(), items.begin() + std::min(count, items.size()));
}
} // namespace leaf

@interface Bridge : NSObject
- (NSArray<NSString *> *)firstBranches:(NSUInteger)count;
@end

@implementation Bridge
- (NSArray<NSString *> *)firstBranches:(NSUInteger)count {
    std::vector<std::string> names{"main", "develop", "feature/x"};
    NSMutableArray *result = [NSMutableArray array];
    for (const auto& name : leaf::keepFirst(names, count)) {
        [result addObject:@(name.c_str())];
    }
    return result; /* autoreleased */
}
@end
