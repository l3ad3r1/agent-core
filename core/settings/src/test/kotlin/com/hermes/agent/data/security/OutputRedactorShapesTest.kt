package com.hermes.agent.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pattern layer of [OutputRedactor]: secrets the user never configured but a tool read. */
class OutputRedactorShapesTest {

    private fun mask(s: String) = OutputRedactor.maskShapes(s)

    @Test
    fun `a pem private key block is masked whole`() {
        val key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0abc\nxyz==\n-----END RSA PRIVATE KEY-----"
        assertEquals("before [redacted:private-key] after", mask("before $key after"))
        val ssh = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals("[redacted:private-key]", mask(ssh))
    }

    @Test
    fun `a public key is not a secret`() {
        val pub = "-----BEGIN PUBLIC KEY-----\nMFkwEwYH\n-----END PUBLIC KEY-----"
        assertEquals(pub, mask(pub))
    }

    @Test
    fun `a jwt such as a home assistant token is masked`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJhYmNkZWYxMjM0In0.Zm9vYmFyYmF6cXV4MTIz"
        assertEquals("token [redacted:jwt]", mask("token $jwt"))
    }

    @Test
    fun `only the password of a connection string is masked`() {
        assertEquals(
            "postgres://app:[redacted:password]@db.local:5432/main",
            mask("postgres://app:hunter22secret@db.local:5432/main"),
        )
        assertEquals("redis://:[redacted:password]@cache:6379", mask("redis://:s3cretpw@cache:6379"))
    }

    @Test
    fun `credential assignments keep their name and lose their value`() {
        assertEquals("password=[redacted:secret]", mask("password=correcthorse"))
        assertEquals("\"api_key\": \"[redacted:secret]\"", mask("\"api_key\": \"abcd1234efgh\""))
        assertEquals("CLIENT_SECRET: '[redacted:secret]'", mask("CLIENT_SECRET: 'zzzz9999yyyy'"))
    }

    @Test
    fun `placeholders and plain token lines are left alone`() {
        listOf("password=\${DB_PASSWORD}", "api_key: ***", "password: <your password>", "token=abc123def456")
            .forEach { assertEquals(it, mask(it)) }
    }

    @Test
    fun `provider token prefixes are masked`() {
        val cases = mapOf(
            "hf_" + "a".repeat(34) to "huggingface-token",
            "gsk_" + "b".repeat(48) to "groq-api-key",
            "xai-" + "c".repeat(60) to "xai-api-key",
            "glpat-" + "d".repeat(20) to "gitlab-token",
            "sk_live_" + "e".repeat(24) to "stripe-key",
            "gho_" + "f".repeat(36) to "github-token",
            "123456789:AA" + "g".repeat(33) to "telegram-bot-token",
            "AccountKey=" + "h".repeat(60) to "azure-storage-key",
        )
        for ((secret, label) in cases) assertEquals(secret, "[redacted:$label]", mask(secret))
    }

    @Test
    fun `ordinary text survives`() {
        val text = "The password field is empty. See https://example.com/docs?page=2 and call setApiKey(key)."
        assertEquals(text, mask(text))
        assertFalse(mask("git sha 3b1ce49ad3 and version 0.23.3").contains("redacted"))
        assertTrue(mask("Bearer abcdefghijklmnopqrstuvwxyz").contains("[redacted:bearer-token]"))
    }
}
