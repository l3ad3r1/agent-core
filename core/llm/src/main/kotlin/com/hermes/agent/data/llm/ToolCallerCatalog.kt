package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

/**
 * The on-device tool-calling model, kept out of [ModelCatalog] on purpose.
 *
 * [ModelCatalog] lists models the user picks between for *chat*; exactly one of
 * them is selected and downloaded at a time. This model is not an alternative
 * to those — it runs alongside one, on a different path, and is never a chat
 * model. Listing it in the same dropdown would let a user select a 270M model
 * as their assistant and conclude the local model is broken.
 *
 * Q8_0 rather than the Q4_K_M the chat catalogue uses. At 270M parameters the
 * weights are mostly embedding tables, which do not shrink the way transformer
 * blocks do: Q4_K_M is 253 MB against Q8_0's 291 MB. 38 MB is not worth the
 * quantisation damage on the one job this model has, where a single wrong
 * token in a tool name or an enum value is a failed call.
 *
 * URL, revision, size and SHA-256 verified live against HuggingFace
 * (verified 2026-09-03) via the `paths-info` API.
 */
object ToolCallerCatalog {

    /**
     * Google's 270M tool-calling model. Needle's own README names FunctionGemma
     * 270M as one of the models it trades benchmark wins with, so this is the
     * accessible equivalent of the model that motivated this path — and it runs
     * on the llama.cpp runtime the app already builds, with no new native
     * dependency.
     */
    val FUNCTION_GEMMA_270M: DownloadableModel = DownloadableModel(
        id = "functiongemma-270m-it-q8",
        displayName = "FunctionGemma 270M (Q8_0)",
        fileName = "functiongemma-270m-it-Q8_0.gguf",
        url = "https://huggingface.co/unsloth/functiongemma-270m-it-GGUF/resolve/" +
            "1de0f7fc7cae0062012c2e91ef02e642c161aa57/functiongemma-270m-it-Q8_0.gguf",
        sizeBytes = 291_558_624L,
        revision = "1de0f7fc7cae0062012c2e91ef02e642c161aa57",
        sha256 = "8a17a4eda2b28eaf685a487129fd5295eeff3f722b9c6451dd3d98c18c23a98d",
    )

    /** The model the tool-caller path uses. Single-entry for now, by design. */
    val DEFAULT: DownloadableModel = FUNCTION_GEMMA_270M
}
