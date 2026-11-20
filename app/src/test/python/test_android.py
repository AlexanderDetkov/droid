"""Tests for assets/setup/bin/android's display default. Run: python3 -I app/src/test/python/test_android.py (also run by
AndroidCommandTest). `am`, `input` and the helper are stand-ins that print what they were given."""
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.join(HERE, '../../main/assets/setup/bin/android')

STANDINS = {
    'am': '#!/bin/sh\necho "am $*"\n',
    'input': '#!/bin/sh\necho "input $*"\n',
    # droid-bridge's place: the helper runs the command's text in Android's shell, which gets nothing else
    'python3': '#!/bin/sh\nshift 2\nexec env -i PATH="$PATH" sh -c "$1"\n',
}


class DisplayDefault(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name, body in STANDINS.items():
            path = os.path.join(self.dir, name)
            with open(path, 'w') as f:
                f.write(body)
            os.chmod(path, 0o755)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def android(self, command, display='7'):
        env = {'PATH': self.dir + ':/usr/bin:/bin', 'DROID_BRIDGE': '1'}
        if display is not None:
            env['AGENT_DISPLAY'] = display
        r = subprocess.run(['sh', ANDROID, command], env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.strip()

    def test_apps_open_on_the_hidden_display(self):
        self.assertEqual(self.android('am start -n com.android.deskclock/.DeskClock'), 'am start --display 7 -n com.android.deskclock/.DeskClock')
        self.assertEqual(self.android('am start-activity -W -n a/.b'), 'am start-activity --display 7 -W -n a/.b')

    def test_taps_and_keys_go_to_the_hidden_display(self):
        self.assertEqual(self.android('input tap 10 20'), 'input -d 7 tap 10 20')
        self.assertEqual(self.android('input keyevent 66'), 'input -d 7 keyevent 66')
        # a source comes before -d
        self.assertEqual(self.android('input touchscreen swipe 1 2 3 4'), 'input touchscreen -d 7 swipe 1 2 3 4')

    def test_a_named_display_is_kept(self):
        self.assertEqual(self.android('am start --display 0 -n a/.b'), 'am start --display 0 -n a/.b')
        self.assertEqual(self.android('input -d 0 tap 1 2'), 'input -d 0 tap 1 2')
        self.assertEqual(self.android('input touchscreen -d 0 tap 1 2'), 'input touchscreen -d 0 tap 1 2')

    def test_only_a_real_option_names_a_display(self):
        self.assertEqual(self.android("am start -a X --es m 'say --display'"), 'am start --display 7 -a X --es m say --display')
        self.assertEqual(self.android("input text 'a -d b'"), 'input -d 7 text a -d b')

    def test_other_commands_and_the_agents_variables_are_untouched(self):
        self.assertEqual(self.android('am force-stop com.x'), 'am force-stop com.x')
        self.assertEqual(self.android('for s in 1 2; do input touchscreen tap $s $s; echo s=$s; done'),
                         'input touchscreen -d 7 tap 1 1\ns=1\ninput touchscreen -d 7 tap 2 2\ns=2')
        self.assertEqual(self.android('am start -n a/.b && input keyevent 66; echo rc=$?'), 'am start --display 7 -n a/.b\ninput -d 7 keyevent 66\nrc=0')

    def test_outside_a_task_nothing_changes(self):
        self.assertEqual(self.android('input tap 1 2', display=None), 'input tap 1 2')
        self.assertEqual(self.android('am start -n a/.b', display='4; reboot'), 'am start -n a/.b')


if __name__ == '__main__':
    unittest.main()
