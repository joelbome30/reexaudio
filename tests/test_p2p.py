"""Isolated checks for the NetworkManager pairing bridge."""
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import p2p


class PeerSocket:
    def __init__(self, answer):
        self.answer = answer
        self.sent = b""

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def sendall(self, data):
        self.sent += data

    def settimeout(self, _seconds):
        pass

    def makefile(self, _mode):
        return self

    def readline(self, _limit):
        return self.answer


class P2pTests(unittest.TestCase):
    def test_confirm_uses_gateway_and_qr_token(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "token").write_text("example-token\n")
            peer = PeerSocket(b"OK\n")
            with patch.object(p2p, "STATE", Path(directory)), \
                    patch.object(p2p, "command", return_value=SimpleNamespace(stdout="192.168.49.1\n")), \
                    patch.object(p2p.socket, "create_connection", return_value=peer) as connect:
                self.assertTrue(p2p.confirm_p2p("p2p-dev-wlan0"))
            connect.assert_called_once_with(("192.168.49.1", 53318), timeout=10)
            self.assertEqual(peer.sent, b"example-token\n")

    def test_failed_confirmation_removes_connection(self):
        calls = []

        def command(*args, **_kwargs):
            calls.append(args)
            return SimpleNamespace(stdout="")

        with patch.object(p2p, "p2p_device", return_value="p2p-dev-wlan0"), \
                patch.object(p2p, "command", side_effect=command), \
                patch.object(p2p, "confirm_p2p", side_effect=RuntimeError("wrong token")):
            with self.assertRaises(RuntimeError):
                p2p.connect_p2p("02:00:00:00:00:01")
        self.assertEqual(calls[-1], ("nmcli", "connection", "delete", "redmi-audio-p2p"))
        self.assertTrue(any("wifi-p2p.peer" in args for args in calls))


if __name__ == "__main__":
    unittest.main()
