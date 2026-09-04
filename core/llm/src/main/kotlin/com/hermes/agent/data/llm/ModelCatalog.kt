package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

/**
 * A downloadable on-device GGUF model shown in the Assistant Settings dropdown.
 *
 * @param id        stable key persisted in settings (never shown to the user).
 * @param displayName label for the dropdown.
 * @param fileName  the .gguf filename it is saved as (also the on-disk identity;
 *                  used to detect "already downloaded").
 * @param url       direct HuggingFace `resolve` URL (302s to the CDN).
 * @param sizeBytes real download size, used for the label and the free-space
 *                  pre-check. Verified against HuggingFace `x-linked-size`.
 */
data class DownloadableModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val revision: String,
    val sha256: String,
) {
    /** e.g. "770 MB" / "1.9 GB" for labels. */
    val sizeLabel: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }
}

/**
 * The registry of models the user can download. Add entries here as better
 * models become available — the settings dropdown and the download/load paths
 * read from this single list, so no other code needs to change.
 *
 * Every URL, commit SHA, size, and SHA-256 digest below was verified live against
 * HuggingFace (verified 2026-08-24; MiniCPM5 1B added and verified
 * 2026-09-04). All are Q4_K_M quantisations.
 */
object ModelCatalog {
    val MODELS: List<DownloadableModel> = listOf(
        DownloadableModel(
            id = "llama-3.2-1b-q4km",
            displayName = "Llama 3.2 1B Instruct (Q4_K_M)",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/067b946cf014b7c697f3654f621d577a3e3afd1c/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            revision = "067b946cf014b7c697f3654f621d577a3e3afd1c",
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
        ),
        DownloadableModel(
            id = "qwen2.5-1.5b-q4km",
            displayName = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/9eadc66189c7641e1ddd226b8267a9119b2ce2d4/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 986_048_768L,
            revision = "9eadc66189c7641e1ddd226b8267a9119b2ce2d4",
            sha256 = "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370",
        ),
        DownloadableModel(
            id = "qwen2.5-3b-q4km",
            displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/f302c64a2269a69fb27b2f9473b362f5bb8e78d8/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_929_903_264L,
            revision = "f302c64a2269a69fb27b2f9473b362f5bb8e78d8",
            sha256 = "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
        ),
        DownloadableModel(
            id = "llama-3.2-3b-q4km",
            displayName = "Llama 3.2 3B Instruct (Q4_K_M)",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/5ab33fa94d1d04e903623ae72c95d1696f09f9e8/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
            revision = "5ab33fa94d1d04e903623ae72c95d1696f09f9e8",
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
        ),
        // openbmb's own GGUF. Despite the name it declares
        // general.architecture = "llama", so it loads on the runtime already
        // built here -- and the MiniCPM5 pre-tokenizer landed in the vendored
        // llama.cpp (b9976). A newer generation than MiniCPM3 4B below at a
        // third of the size, which matters on a phone.
        DownloadableModel(
            id = "minicpm5-1b-q4km",
            displayName = "MiniCPM5 1B Instruct (Q4_K_M)",
            fileName = "MiniCPM5-1B-Q4_K_M.gguf",
            url = "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/3d55fac80935ae6456986ad2384b5cbcc4d6c948/MiniCPM5-1B-Q4_K_M.gguf",
            sizeBytes = 688_065_920L,
            revision = "3d55fac80935ae6456986ad2384b5cbcc4d6c948",
            sha256 = "81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa",
        ),
        DownloadableModel(
            id = "minicpm3-4b-q4km",
            displayName = "MiniCPM3 4B Instruct (Q4_K_M)",
            fileName = "minicpm3-4b-q4_k_m.gguf",
            url = "https://huggingface.co/openbmb/MiniCPM3-4B-GGUF/resolve/816dc79b35f92827e0d2d87aacea3567e49661a8/minicpm3-4b-q4_k_m.gguf",
            sizeBytes = 2_469_791_584L,
            revision = "816dc79b35f92827e0d2d87aacea3567e49661a8",
            sha256 = "64913247e927414ecf47fd3e9ea8e3f0c9acae293f583dfa7e24b8872e20fa4c",
        ),
    )

    /** The model selected when the user has never picked one. */
    val DEFAULT: DownloadableModel = MODELS.first()

    /** Resolve a persisted id back to a model, falling back to [DEFAULT]. */
    fun byId(id: String): DownloadableModel =
        MODELS.firstOrNull { it.id == id } ?: DEFAULT

    /** The default top-level folder name on shared storage. */
    const val DEFAULT_DIR_NAME = "AI Models"
}
