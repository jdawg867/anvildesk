import importlib.util
from pathlib import Path
import unittest

script = Path(__file__).with_name("verify-m8-upstream-no-tcp.py")
spec = importlib.util.spec_from_file_location("m8_guard", script)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

class NetworkGuardTest(unittest.TestCase):
    def test_rejects_pinned_upstream_pattern(self):
        source = "int fd = socket(AF_INET, SOCK_STREAM, 0); struct sockaddr_in a = {.sin_addr={.s_addr=INADDR_ANY}}; void lorieListenForKnocks(int port) {}"
        self.assertTrue(guard.inspect(source))
    def test_rejects_ipv6(self):
        self.assertTrue(guard.inspect("socket(AF_INET6, SOCK_STREAM, 0);"))
    def test_accepts_local_socketpair(self):
        self.assertEqual([], guard.inspect("socketpair(AF_UNIX, SOCK_STREAM, 0, fds);"))
    def test_does_not_confuse_commentary_for_test_pass(self):
        self.assertTrue(guard.inspect("lorieListenForKnocks(6000);"))

if __name__ == "__main__":
    unittest.main()
