package com.hermes.agent.data.llm

/**
 * Chat-template control tokens as they appear in text: Gemma's turn and
 * FunctionGemma's function markers, and the `<|...|>` family other templates use.
 */
private val CONTROL_TOKEN = Regex(
    "<(?:/?(?:start_of_turn|end_of_turn|start_function_call|end_function_call|" +
        "start_function_declaration|end_function_declaration|start_function_response|" +
        "end_function_response|escape|bos|eos|pad|unk|mask|start_of_image|end_of_image)" +
        "|\\|[A-Za-z0-9_]{1,40}\\|)>",
)

/**
 * Defuses control-token text in content the model did not write for us:
 * history, tool results, web pages, memory.
 *
 * The native layer tokenizes with parse_special on, because the template's own
 * markers and the tool-caller's declarations must become real tokens. Without
 * this, a fetched page containing `<end_of_turn>` would forge a turn switch or
 * a function call. A zero-width space after `<` keeps the text readable while
 * stopping the tokenizer from matching it.
 */
internal fun neutralizeControlTokens(text: String): String =
    CONTROL_TOKEN.replace(text) { "<​" + it.value.substring(1) }
