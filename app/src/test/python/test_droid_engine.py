"""Tests for assets/setup/bin/droid-engine. Run: python3 -I app/src/test/python/test_droid_engine.py (also run by AdapterTest)."""
import sys
sys.dont_write_bytecode = True  # the script lives in the app assets: no __pycache__ there
import importlib.machinery
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ADAPTER = os.path.join(HERE, '../../main/assets/setup/bin/droid-engine')
SAMPLES = os.path.join(HERE, '../resources/engines')
loader = importlib.machinery.SourceFileLoader('droid_engine', ADAPTER)
de = importlib.util.module_from_spec(importlib.util.spec_from_loader('droid_engine', loader))
loader.exec_module(de)


def convert(engine, name, code=0):
    tr = de.ENGINES[engine](None)
    out = tr.start()
    with open(os.path.join(SAMPLES, name)) as f:
        for line in f:
            out += tr.feed(json.loads(line))
    return out + [tr.end(code, '')]


class Describe(unittest.TestCase):
    def test_wrapped(self):
        self.assertEqual(de.describe_command("/bin/bash -lc ls"), 'ls')
        self.assertEqual(de.describe_command("/bin/bash -lc 'shot'"), 'Looking at the screen')
        self.assertEqual(de.describe_command("/bin/bash -lc \"android 'input -d 4 tap 540 222'\""), 'Tapping the screen')
        self.assertEqual(de.describe_command("android 'am start --display 4 -n x/y'"), 'Opening an app')
        self.assertEqual(de.describe_command('android input -d 4 swipe 1 2 3 4'), 'Swiping')
        self.assertEqual(len(de.describe_command('echo ' + 'x' * 200)), 60)


class Codex(unittest.TestCase):
    def test_new(self):
        out = convert('codex', 'codex-new.jsonl')
        self.assertEqual(out[0], {'type': 'system', 'subtype': 'init', 'session_id': '01a1ddd8-1dbf-7762-afe9-090ed9db7961'})
        steps = [o for o in out if o['type'] == 'assistant' and o['message']['content'][0]['type'] == 'tool_use']
        self.assertEqual(len(steps), 1)  # started and completed are one step
        self.assertEqual(steps[0]['message']['content'][0]['input']['description'], 'ls')
        self.assertFalse(out[-1]['is_error'])
        self.assertIn('PINEAPPLE-7319', out[-1]['result'])

    def test_resume_announces_session_up_front(self):
        tr = de.Codex('abc')
        self.assertEqual(tr.start()[0]['session_id'], 'abc')
        self.assertEqual(tr.feed({'type': 'thread.started', 'thread_id': 'abc'}), [])

    def test_failure(self):
        out = convert('codex', 'codex-error.jsonl', code=1)
        self.assertTrue(out[-1]['is_error'])
        self.assertIn('not supported', out[-1]['result'])

    def test_warning_item_is_not_a_failure(self):
        out = convert('codex', 'codex-error.jsonl')  # first item is a type=error warning; turn.failed decides
        self.assertTrue(out[-1]['is_error'])
        out = convert('codex', 'codex-new.jsonl')
        self.assertFalse(out[-1]['is_error'])

    def test_command(self):
        c = de.Codex.command(None, 'm1')
        self.assertEqual(c[:2], ['codex', 'exec'])
        self.assertNotIn('resume', c)
        self.assertIn('--dangerously-bypass-approvals-and-sandbox', c)
        self.assertEqual(c[-1], '-')
        r = de.Codex.command('sid', '')
        self.assertEqual(r[2:4], ['resume', '--json'])
        self.assertEqual(r[-2:], ['sid', '-'])
        self.assertNotIn('-m', r)


class OpenCode(unittest.TestCase):
    def test_new(self):
        out = convert('opencode', 'opencode-new.jsonl')
        self.assertEqual(out[0]['session_id'], 'ses_e22272822ffepBB7CC0N4KibaD')
        steps = [o['message']['content'][0]['input']['description'] for o in out
                 if o['type'] == 'assistant' and o['message']['content'][0]['type'] == 'tool_use']
        self.assertEqual(steps, ['ls', 'Looking at the screen'])  # reading a PNG is looking at the screen
        self.assertEqual(out[-1]['result'], 'Code text: `CODE PINEAPPLE-7319`. Rectangle color: red.')

    def test_error_prefers_specific_message(self):
        out = convert('opencode', 'opencode-error.jsonl')
        self.assertTrue(out[-1]['is_error'])
        self.assertEqual(out[-1]['result'], 'Model not found: opencode/nonexistent-model.')

    def test_no_answer_is_an_error(self):
        tr = de.OpenCode(None)
        r = tr.end(1, 'boom\nSession not found')
        self.assertTrue(r['is_error'])
        self.assertEqual(r['result'], 'Session not found')

    def test_a_stuck_turn_reports_the_error_opencode_logged(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, 'opencode.log'), 'w') as f:
                f.write('timestamp=2026-11-20T23:36:14.905Z level=INFO run=1 message=init\n'
                        'timestamp=2026-11-20T23:30:00.000Z level=ERROR run=1 message="stream error" error.error="AI_APICallError: old"\n'
                        'timestamp=2026-11-20T23:36:15.490Z level=ERROR run=1 message="stream error" providerID=opencode '
                        'error.error="AI_APICallError: Free usage limit reached. Subscribe to keep going."\n')
            saved, de.OPENCODE_LOG = de.OPENCODE_LOG, d
            try:
                since = de.datetime.fromisoformat('2026-11-20T23:36:00+00:00').timestamp()
                self.assertEqual(de.opencode_log_error(since), 'Free usage limit reached. Subscribe to keep going.')
                self.assertIsNone(de.opencode_log_error(since + 3600))  # nothing since then
            finally:
                de.OPENCODE_LOG = saved

    def test_default_model_is_a_free_one_that_sees_images(self):
        def model(provider, name, image, cost=0, tools=True):
            return provider + '/' + name + '\n' + json.dumps({
                'id': name, 'providerID': provider, 'status': 'active', 'cost': {'input': cost, 'output': cost},
                'capabilities': {'toolcall': tools, 'input': {'text': True, 'image': image}}}, indent=2) + '\n'
        listing = (model('opencode', 'beam-free', False) + model('anthropic', 'claude-x', True, cost=3)
                   + model('opencode', 'vl-free', True, tools=False) + model('opencode', 'super-vl-free', True))
        self.assertEqual(de.pick_opencode_model(listing), 'opencode/super-vl-free')
        self.assertIsNone(de.pick_opencode_model(model('opencode', 'beam-free', False)))
        self.assertIsNone(de.pick_opencode_model('Error: no network'))

    def test_command(self):
        c = de.OpenCode.command('ses_x', 'anthropic/claude-sonnet-4-5')
        self.assertNotIn('--', c)  # the prompt goes on stdin
        self.assertTrue(de.OpenCode.prompt_on_stdin)
        self.assertIn('--auto', c)
        self.assertEqual(c[c.index('--session') + 1], 'ses_x')


class Messages(unittest.TestCase):
    def test_text(self):
        self.assertEqual(de.message_text('{"type":"user","message":{"role":"user","content":"hi"}}'), 'hi')
        self.assertEqual(de.message_text('{"type":"user","message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}'), 'a\nb')
        self.assertIsNone(de.message_text('not json'))
        self.assertIsNone(de.message_text('{"type":"assistant"}'))


class EndToEnd(unittest.TestCase):
    """The real script against a fake `codex` that answers each prompt, to check queueing and exit behavior."""

    ANSWERS = '''\
        #!/bin/sh
        prompt=$(cat)
        sleep 1
        echo '{"type":"thread.started","thread_id":"sess-1"}'
        echo '{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"answer to: '"$prompt"'"}}'
        echo '{"type":"turn.completed"}'
        '''

    def run_adapter(self, lines, delays, args=('codex',), fake_codex=ANSWERS):
        with tempfile.TemporaryDirectory() as d:
            fake = os.path.join(d, 'codex')
            open(fake, 'w').write(textwrap.dedent(fake_codex))
            os.chmod(fake, os.stat(fake).st_mode | stat.S_IXUSR)
            # only the system's own tools besides the fake: a fake that can't run must not fall through to a real agent
            env = dict(os.environ, PATH=d + os.pathsep + os.defpath)
            p = subprocess.Popen([sys.executable, '-I', ADAPTER, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 text=True, env=env)
            import time
            for line, delay in zip(lines, delays):
                p.stdin.write(json.dumps({'type': 'user', 'message': {'role': 'user', 'content': line}}) + '\n')
                p.stdin.flush()
                time.sleep(delay)
            p.stdin.close()
            out = p.stdout.read()
            p.wait(timeout=30)
            return [json.loads(l) for l in out.splitlines()], p.returncode

    def test_one_turn(self):
        out, rc = self.run_adapter(['one'], [0])
        self.assertEqual(rc, 0)
        self.assertEqual(sum(o['type'] == 'result' for o in out), 1)
        self.assertEqual(out[-1]['result'], 'answer to: one')

    def test_message_during_a_turn_is_queued_and_each_turn_gets_its_result(self):
        out, rc = self.run_adapter(['first', 'second'], [0.3, 0])
        results = [o['result'] for o in out if o['type'] == 'result']
        self.assertEqual(results, ['answer to: first', 'answer to: second'])
        texts = [o['message']['content'][0]['text'] for o in out if o['type'] == 'assistant']
        self.assertEqual(texts, ['answer to: first', 'answer to: second'])

    def test_a_failure_reports_the_last_line_on_stderr(self):
        noisy = '''\
            #!/bin/sh
            cat >/dev/null
            noise=$(seq 1 5000 | sed 's/^/warning /')
            { echo "$noise"; echo 'the real reason'; } >&2
            exit 3
            '''
        out, rc = self.run_adapter(['one'], [0], fake_codex=noisy)
        self.assertEqual(out[-1]['result'], 'the real reason')
        self.assertTrue(out[-1]['is_error'])

    def test_eof_without_messages_exits(self):
        out, rc = self.run_adapter([], [])
        self.assertEqual((out, rc), ([], 0))

    def test_missing_engine_binary_is_an_error_result(self):
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, PATH=d)  # no codex here
            p = subprocess.run([sys.executable, '-I', ADAPTER, 'codex'], input=json.dumps(
                {'type': 'user', 'message': {'content': 'x'}}) + '\n', capture_output=True, text=True, env=env)
            res = json.loads(p.stdout.splitlines()[-1])
            self.assertTrue(res['is_error'])
            self.assertIn('Could not start codex', res['result'])


if __name__ == '__main__':
    unittest.main()
