package refs

import (
	"fmt"
	"strings"
)

// Ref is a git reference.
type Ref struct {
	Name string
	Hash [20]byte
}

const headsPrefix = "refs/heads/"

// Short returns the branch's short name.
func (r Ref) Short() string {
	return strings.TrimPrefix(r.Name, headsPrefix)
}

func Parse(lines []string) (map[string]Ref, error) {
	refs := make(map[string]Ref, len(lines))
	for i, line := range lines {
		name, _, ok := strings.Cut(line, " ")
		if !ok {
			return nil, fmt.Errorf("line %d: %q has no hash", i+1, line)
		}
		refs[name] = Ref{Name: name}
	}
	return refs, nil
}
