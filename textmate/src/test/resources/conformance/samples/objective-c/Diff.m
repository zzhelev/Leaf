#import <Foundation/Foundation.h>
#import "Diff.h"

/* A hunk of a diff. */
@interface Hunk : NSObject
@property (nonatomic, copy) NSString *header;
@property (nonatomic, assign) NSInteger added;
@end

@implementation Hunk

- (instancetype)initWithHeader:(NSString *)header {
    if ((self = [super init])) {
        _header = [header copy];
        _added = 0;
    }
    return self;
}

- (NSString *)description {
    // Prints "@@ -1,3 +1,4 @@ (+1)"
    return [NSString stringWithFormat:@"%@ (+%ld)", self.header, (long)self.added];
}

@end
