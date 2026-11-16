import unittest

from server import norm_code, norm_name


class InputNormalizationTests(unittest.TestCase):
    def test_norm_code_removes_whitespace_and_lowercases(self):
        self.assertEqual(norm_code("  Sunny River 42  "), "sunnyriver42")

    def test_norm_code_limits_length(self):
        self.assertEqual(len(norm_code("x" * 100)), 40)

    def test_norm_code_handles_empty_values(self):
        self.assertEqual(norm_code(None), "")

    def test_norm_name_removes_markup_and_control_characters(self):
        self.assertEqual(norm_name("  <b>Alex</b>\n"), "bAlex/b")

    def test_norm_name_uses_guest_for_empty_values(self):
        self.assertEqual(norm_name(" \x00 "), "Guest")

    def test_norm_name_limits_length(self):
        self.assertEqual(len(norm_name("x" * 100)), 24)


if __name__ == "__main__":
    unittest.main()
