"""Tests for `shot --text` (assets/setup/bin/shot): what it reads out of Android's `uiautomator dump --windows`.
Run: python3 -I app/src/test/python/test_shot.py (also run by AndroidCommandTest). The dump is a real one from an
Android 15 emulator, with Clock open on hidden display 2 (and the owner's screen cut to its status bar)."""
import os
import re
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(HERE, '../../main/assets/setup/bin/shot')
DUMP = os.path.join(HERE, '../resources/shot/clock-windows.xml')


def parser():
    """The Python that shot runs on the dump (its heredoc)."""
    with open(SHOT) as f:
        return re.search(r"<<'EOF'\n(.*?)\nEOF\n", f.read(), re.S).group(1)


class Text(unittest.TestCase):
    def read(self, dump, display):
        with tempfile.NamedTemporaryFile('w', suffix='.py', delete=False) as f:
            f.write(parser())
        try:
            r = subprocess.run(['python3', '-I', f.name, dump, display], capture_output=True, text=True, timeout=10)
        finally:
            os.unlink(f.name)
        return r.returncode, (r.stdout + r.stderr).strip()

    def test_lists_the_hidden_displays_words_and_where_to_tap(self):
        code, out = self.read(DUMP, '2')
        self.assertEqual(code, 0, out)
        self.assertEqual(out.splitlines(), [
            'Clock  @138,83', 'More options  @1027,83', '4:34 PM  @308,318',
            'Fri, Nov 20 (Friday, November 20)  @175,502', 'Add city  @540,2022',
            'Alarm  @108,2295', 'Clock  @324,2295  [selected]', 'Timer  @540,2295', 'Stopwatch  @756,2295',
            'Bedtime  @972,2295'])

    def test_only_its_own_display(self):
        code, out = self.read(DUMP, '7')
        self.assertNotEqual(code, 0)
        self.assertIn('nothing is open on the hidden display', out)

    def test_fields_and_switches_say_so(self):
        with tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False) as f:
            f.write('<?xml version="1.0"?><displays><display id="3"><window><hierarchy>'
                    '<node class="android.widget.EditText" text="" content-desc="Search" password="false" bounds="[0,0][100,50]"/>'
                    '<node class="android.widget.EditText" text="" password="true" bounds="[0,60][100,110]"/>'
                    '<node class="android.widget.Switch" text="Wi-Fi" checkable="true" checked="true" bounds="[0,120][100,170]"/>'
                    '<node class="androidx.recyclerview.widget.RecyclerView" scrollable="true" bounds="[0,200][1080,2000]"/>'
                    '<node class="android.widget.Button" text="Send" enabled="false" bounds="[0,2000][200,2100]"/>'
                    '</hierarchy></window></display></displays>')
        try:
            code, out = self.read(f.name, '3')
        finally:
            os.unlink(f.name)
        self.assertEqual(out.splitlines(), ['Search  @50,25  [field]', 'EditText  @50,85  [field, password]',
                                            'Wi-Fi  @50,145  [on]', 'RecyclerView  @540,1100  [scrolls]', 'Send  @100,2050  [disabled]'])


if __name__ == '__main__':
    unittest.main()
