module Stats

export churn

"""
    churn(added, removed)

Lines changed, per file.
"""
function churn(added::Vector{Int}, removed::Vector{Int})::Vector{Int}
    @assert length(added) == length(removed) "lengths differ"
    return added .+ removed
end

const THRESHOLD = 1_000

for (i, c) in enumerate(churn([10, 2_000], [3, 400]))
    c > THRESHOLD && println("file $i: $(c) lines")  # big
end

import Base: show
end # module
