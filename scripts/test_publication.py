"""Security regressions for the publication scanner; synthetic inputs only."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("publication", Path(__file__).with_name("check_publication.py"))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class PublicationScannerTest(unittest.TestCase):
    def test_credentials_in_java_yaml_and_docs_are_reported_without_values(self):
        synthetic = "synthetic-credential-for-test"
        for path, content in [("Config.java", 'public static final String EMAIL_PASSWORD = "' + synthetic + '";'),
                              ("application.yml", "    password: " + synthetic),
                              ("guide.md", 'secret-key: "' + synthetic + '"')]:
            with self.subTest(path=path):
                result = scanner.findings(path, content.encode())
                self.assertEqual("credential-literal", result[0]["rule"])
                self.assertNotIn(synthetic, str(result))

    def test_expressions_environment_refs_and_redaction_are_not_credentials(self):
        for path, content in [("Config.java", "String password = request.getPassword();"),
                              ("Config.java", 'private String password = "";'),
                              ("Config.java", 'public String toString() { return "apiKey=[REDACTED]"; }'),
                              ("application.yml", "password: ${DB_PASSWORD}"),
                              ("mvnw", 'case "$MVNW_PASSWORD" in')]:
            with self.subTest(path=path):
                self.assertEqual([], scanner.findings(path, content.encode()))

    def test_tokens_are_detected_in_comments_without_exposing_values(self):
        token = "sk-" + "x" * 30
        result = scanner.findings("notes.md", ("# " + token).encode())
        self.assertEqual("model-key", result[0]["rule"])
        self.assertNotIn(token, str(result))

    def test_binary_is_not_decoded_and_history_finding_keeps_blob_identity(self):
        self.assertEqual([], scanner.findings("asset.bin", b"\x00\xff"))
        result = scanner.findings("deleted.yml", b"password: synthetic-private-value", "blob-id")
        self.assertEqual("blob-id", result[0]["blob"])


if __name__ == "__main__":
    unittest.main()
