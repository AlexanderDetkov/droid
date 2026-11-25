"""Tests for `shot` (assets/setup/bin/shot): what `shot --text` reads out of a dump in the form of Android's
`uiautomator dump --windows`, and how `shot` asks the display's helper for a picture or text. Run: python3 -I
app/src/test/python/test_shot.py (also run by AndroidCommandTest). The dump is a real one from an Android 15 emulator,
with Clock open on hidden display 2 (and the owner's screen cut to its status bar)."""
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
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


    def test_an_empty_field_goes_by_its_hint_and_says_where_typing_goes(self):
        with tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False) as f:
            f.write('<?xml version="1.0"?><displays><display id="3"><window>'
                    '<node class="android.widget.EditText" text="" hint="Search contacts" focused="true" bounds="[0,0][100,50]"/>'
                    '<node class="android.widget.EditText" text="Mama" hint="To" focused="false" bounds="[0,60][100,110]"/>'
                    '</window></display></displays>')
        try:
            code, out = self.read(f.name, '3')
        finally:
            os.unlink(f.name)
        self.assertEqual(out.splitlines(), ['Search contacts  @50,25  [field, focused]', 'Mama  @50,85  [field]'])


    def test_a_list_says_which_way_it_has_more(self):
        with tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False) as f:
            f.write('<?xml version="1.0"?><displays><display id="3"><window>'
                    '<node class="androidx.recyclerview.widget.RecyclerView" scrollable="true" more-before="true" more-after="false" bounds="[0,200][1080,2000]"/>'
                    '<node class="android.widget.HorizontalScrollView" scrollable="true" more-before="false" more-after="true" bounds="[0,100][1080,180]"/>'
                    '<node class="android.widget.ScrollView" scrollable="true" more-before="false" more-after="false" bounds="[0,0][1080,90]"/>'
                    '</window></display></displays>')
        try:
            code, out = self.read(f.name, '3')
        finally:
            os.unlink(f.name)
        self.assertEqual(out.splitlines(), ['RecyclerView  @540,1100  [scrolls: more above]',
                                            'HorizontalScrollView  @540,140  [scrolls: more right]'])


class Helper(unittest.TestCase):
    """`shot` against a stand-in for the display's helper, which answers each request as VdHelper does."""

    def setUp(self):
        self.host = tempfile.mkdtemp()  # Android's root, as DROID_HOST sees it
        self.vd = os.path.join(self.host, 'data/local/tmp/droid/vd/5')
        os.makedirs(self.vd)
        self.note = ''      # what the helper says besides its answer: 'changing', or nothing
        self.filled = None  # the fill request it got: (field, text)
        self.fill_says = '“To” now holds: Sam'
        self.answer = True  # whether it answers at all
        with open(DUMP, 'rb') as f:
            self.dump = f.read().replace(b'display id="2"', b'display id="5"')
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.stop.set()
        self.thread.join()
        shutil.rmtree(self.host)

    def serve(self):
        while not self.stop.wait(0.01):
            if not self.answer:
                continue
            for kind, out, body in (('shot', 'shot.png', b'\x89PNG picture'),
                                    ('text', 'ui.xml', self.dump)):
                req = os.path.join(self.vd, kind + '.req')
                if os.path.exists(req):
                    os.unlink(req)
                    with open(os.path.join(self.vd, out), 'wb') as f:
                        f.write(body)
                    with open(os.path.join(self.vd, kind + '.done'), 'w') as f:
                        f.write(self.note)
            req = os.path.join(self.vd, 'fill.req')
            if os.path.exists(req):
                with open(req, encoding='utf-8') as f:
                    field, _, text = f.read().partition('\n')
                os.unlink(req)
                self.filled = (field, text)
                with open(os.path.join(self.vd, 'fill.done'), 'w', encoding='utf-8') as f:
                    f.write(self.note + self.fill_says + '\n')

    def shot(self, *args, stdin=None):
        stand = os.path.join(self.host, 'bin')
        os.makedirs(stand, exist_ok=True)
        with open(os.path.join(stand, 'android'), 'w') as f:  # what the fallback would ask Android for
            f.write('#!/bin/sh\necho "android $*" >> %s/android.log\n' % self.host)
        os.chmod(os.path.join(stand, 'android'), 0o755)
        env = {'PATH': stand + ':/usr/bin:/bin', 'DROID_HOST': self.host, 'AGENT_DISPLAY': '5'}
        start = time.monotonic()
        r = subprocess.run(['sh', SHOT, *args], env=env, capture_output=True, text=True, timeout=20, input=stdin)
        return r.returncode, (r.stdout + r.stderr).strip(), time.monotonic() - start

    def test_a_picture_at_the_same_path_each_time(self):
        for _ in range(2):
            code, out, _ = self.shot()
            self.assertEqual((code, out), (0, '/tmp/shot-5.png'))
            with open('/tmp/shot-5.png', 'rb') as f:
                self.assertEqual(f.read(), b'\x89PNG picture')
        os.unlink('/tmp/shot-5.png')

    def test_says_when_the_screen_was_still_changing(self):
        self.note = 'changing\n'
        code, out, _ = self.shot()
        self.assertEqual(out.splitlines(), ['/tmp/shot-5.png', '(the screen was still changing: shot again for a later look)'])
        os.unlink('/tmp/shot-5.png')
        code, out, _ = self.shot('--text')
        self.assertEqual(out.splitlines()[-1], '(the screen was still changing: shot again for a later look)')

    def test_text_comes_from_the_helper(self):
        code, out, took = self.shot('--text')
        self.assertEqual(code, 0, out)
        self.assertIn('Bedtime  @972,2295', out.splitlines())
        self.assertFalse(os.path.exists(os.path.join(self.host, 'android.log')))  # no uiautomator
        self.assertLess(took, 2)

    def test_text_falls_back_to_android_when_the_helper_cant(self):
        self.answer = False
        code, out, took = self.shot('--text')
        with open(os.path.join(self.host, 'android.log')) as f:
            self.assertEqual(f.read(), 'android uiautomator dump --windows /data/local/tmp/droid/vd/5/ui.xml\n')
        self.assertIn("no text: Android couldn't read the hidden display", out)  # the stand-in wrote nothing
        self.assertFalse(os.path.exists(os.path.join(self.vd, 'text.req')))  # nothing left for a later helper

    def android_said(self):
        try:
            with open(os.path.join(self.host, 'android.log')) as f:
                return f.read()
        except FileNotFoundError:
            return ''

    def test_tap_taps_what_text_lists_by_that_name(self):
        code, out, _ = self.shot('--tap', 'timer')  # any case
        self.assertEqual((code, out), (0, 'tapped Timer  @540,2295'))
        self.assertEqual(self.android_said(), 'android input tap 540 2295\n')

    def test_tap_a_place(self):
        code, out, _ = self.shot('--tap', '12', '34')
        self.assertEqual(code, 0, out)
        self.assertEqual(self.android_said(), 'android input tap 12 34\n')

    def test_tap_never_guesses(self):
        code, out, _ = self.shot('--tap', 'Clock')  # the app's title and its tab
        self.assertEqual(code, 1)
        self.assertEqual(out.splitlines(), ['“Clock” is in 2 places: tap one by where it is (tap X Y)',
                                            '  Clock  @138,83', '  Clock  @324,2295  [selected]'])
        code, out, _ = self.shot('--tap', 'Stop')  # part of a name isn't enough
        self.assertEqual((code, out.splitlines()), (1, ['no “Stop” on the screen, only:', '  Stopwatch  @756,2295']))
        code, out, _ = self.shot('--tap', 'Snooze')  # none: what there is
        self.assertEqual(code, 1)
        self.assertEqual(out.splitlines()[:2], ['no “Snooze” on the screen. It shows:', 'Clock  @138,83'])
        self.assertEqual(self.android_said(), '')

    def test_fill_asks_the_helper_with_the_field_and_the_text(self):
        code, out, _ = self.shot('--fill', 'To', 'Sam')
        self.assertEqual((code, out), (0, '“To” now holds: Sam'))
        self.assertEqual(self.filled, ('To', 'Sam'))
        text = 'Привет 👋\nsecond line'
        self.fill_says = 'the field now holds: ' + text
        code, out, _ = self.shot('--fill', text)  # no field: the one with the cursor; any language, lines and all
        self.assertEqual(code, 0, out)
        self.assertEqual(self.filled, ('', text))

    def test_fill_fails_when_the_helper_couldnt(self):
        self.fill_says = 'no field “Bcc”. Fields:\n  “To”  @540,300'
        code, out, _ = self.shot('--fill', 'Bcc', 'x')
        self.assertEqual(code, 1)
        self.assertEqual(out.splitlines()[0], 'no field “Bcc”. Fields:')

    def test_reading_the_screen_takes_a_shot_first(self):
        import json
        read = json.dumps({'tool_name': 'Read', 'tool_input': {'file_path': '/tmp/screen.png'}})
        code, out, _ = self.shot('--hook', stdin=read)
        self.assertEqual(code, 0, out)
        said = json.loads(out)['hookSpecificOutput']
        self.assertEqual((said['permissionDecision'], said['updatedInput']), ('allow', {'file_path': '/tmp/shot-5.png'}))
        with open('/tmp/shot-5.png', 'rb') as f:
            self.assertEqual(f.read(), b'\x89PNG picture')
        os.unlink('/tmp/shot-5.png')
        self.note = 'changing\n'
        said = json.loads(self.shot('--hook', stdin=read)[1])['hookSpecificOutput']
        self.assertIn('still changing', said['additionalContext'])
        os.unlink('/tmp/shot-5.png')
        self.answer = False  # no picture: the Read doesn't happen, and says why
        said = json.loads(self.shot('--hook', stdin=read)[1])['hookSpecificOutput']
        self.assertEqual(said['permissionDecision'], 'deny')
        self.assertIn("didn't answer", said['permissionDecisionReason'])

    def test_any_other_read_goes_on_untouched(self):
        import json
        code, out, took = self.shot('--hook', stdin=json.dumps({'tool_name': 'Read', 'tool_input': {'file_path': '/root/notes.md'}}))
        self.assertEqual((code, out), (0, ''))
        self.assertLess(took, 1)


if __name__ == '__main__':
    unittest.main()
