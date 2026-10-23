package dev.textmate.grammar

import dev.textmate.grammar.tokenize.StateStack

public data class Token(
    public val startIndex: Int,
    public val endIndex: Int,
    public val scopes: List<String>
)

public data class TokenizeLineResult(
    public val tokens: List<Token>,
    public val ruleStack: StateStack,
    /**
     * Whether the line took longer than the time limit given to [Grammar.tokenizeLine]. Then [tokens] end where the
     * tokenizer stopped, and [ruleStack] is the state there, not at the end of the line.
     */
    public val stoppedEarly: Boolean = false
)
