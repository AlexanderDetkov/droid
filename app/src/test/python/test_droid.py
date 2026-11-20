"""Tests for assets/setup/bin/droid. Run: python3 -I app/src/test/python/test_droid.py (also run by DroidCommandTest)."""
import sys
sys.dont_write_bytecode = True  # the script lives in the app assets: no __pycache__ there
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import signal
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader('droid', os.path.join(HERE, '../../main/assets/setup/bin/droid'))
d = importlib.util.module_from_spec(importlib.util.spec_from_loader('droid', loader))
loader.exec_module(d)
phone_offset = d.phone_offset  # the real one; tests have the phone in PST

PST = timezone(timedelta(hours=-8))
NOW = datetime(2026, 11, 19, 10, 0, tzinfo=PST)  # a Thursday


class Command(unittest.TestCase):
    """Runs `droid ARGS` in-process, with the app's pipe a file and the phone in PST."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, 'out')
        open(self.out, 'w').close()
        os.environ['DROID_OUT'] = self.out
        d.REPLIES = os.path.join(self.dir, 'replies')
        d.phone_offset = lambda: PST

    def tearDown(self):
        for sig in (signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, signal.SIG_DFL)
        signal.signal(signal.SIGINT, signal.default_int_handler)

    def run_droid(self, *args):
        """(exit code, stdout, stderr, the events the app got)."""
        sys.argv = ['droid', *args]
        out, err = io.StringIO(), io.StringIO()
        code = 0
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                d.main()
            except SystemExit as e:
                code = e.code
        with open(self.out) as f:
            events = [json.loads(line) for line in f]
        open(self.out, 'w').close()
        return code, out.getvalue(), err.getvalue(), events


class Times(unittest.TestCase):
    def test_clock(self):
        self.assertEqual(d.clock('9am'), (9, 0))
        self.assertEqual(d.clock('5:30pm'), (17, 30))
        self.assertEqual(d.clock('12am'), (0, 0))
        self.assertEqual(d.clock('17:30'), (17, 30))
        for bad in ('13pm', '0am', '24', '9:60', 'nine'):
            self.assertIsNone(d.clock(bad), bad)

    def test_days(self):
        self.assertEqual(d.weekday('fri'), 4)
        self.assertEqual(d.weekday('friday'), 4)
        self.assertIsNone(d.weekday('friends'))
        self.assertIsNone(d.weekday('fr'))

    def test_when(self):
        self.assertEqual(d.when('tomorrow 9am', NOW), datetime(2026, 11, 20, 9, 0, tzinfo=PST))
        self.assertEqual(d.when('fri 14:00', NOW), datetime(2026, 11, 20, 14, 0, tzinfo=PST))
        self.assertEqual(d.when('thu 9am', NOW), datetime(2026, 11, 26, 9, 0, tzinfo=PST))  # this one has passed
        self.assertEqual(d.when('in 2h', NOW), NOW + timedelta(hours=2))
        self.assertEqual(d.when('9am', NOW), datetime(2026, 11, 20, 9, 0, tzinfo=PST))  # tomorrow's
        self.assertIsNone(d.when('friends', NOW))

    def test_the_longest_run_of_words_that_reads_as_a_time(self):
        self.assertEqual(d.leading_when(['tomorrow', '9am', 'Call', 'mom'], NOW)[1], 2)
        self.assertEqual(d.leading_when(['tomorrow 9am', 'Call mom'], NOW)[1], 1)
        self.assertEqual(d.leading_when(['in', '1', 'hour', '30', 'minutes', 'stretch'], NOW),
                         (NOW + timedelta(minutes=90), 5))
        self.assertEqual(d.leading_when(['17:30', '5', 'minutes', 'of', 'yoga'], NOW)[1], 1)
        self.assertEqual(d.leading_when(['Call', 'mom'], NOW), (None, 0))


class Later(Command):
    def test_when_takes_as_many_words_as_read_as_a_time(self):
        code, out, _, events = self.run_droid('later', 'tomorrow', '9am', 'Call', 'mom')
        at = (datetime.now(PST) + timedelta(days=1)).replace(hour=9, minute=0, second=0, microsecond=0)
        self.assertEqual(code, 0)
        self.assertEqual(events, [{'type': 'droid', 'op': 'later', 'at': int(at.timestamp() * 1000), 'text': 'Call mom'}])
        self.assertIn('at 09:00 (phone time)', out)

    def test_no_time_is_an_error(self):
        code, _, err, events = self.run_droid('later', 'Call', 'mom')
        self.assertEqual(code, 1)
        self.assertIn("can't tell when 'Call' is", err)
        self.assertEqual(events, [])

    def test_a_time_alone_has_no_message(self):
        code, _, err, events = self.run_droid('later', 'tomorrow', '9am')
        self.assertEqual(code, 1)
        self.assertEqual(events, [])

    def test_the_phone_time_zone_must_be_known(self):
        d.phone_offset = phone_offset
        with mock.patch.object(d.subprocess, 'run', side_effect=OSError('no android')):
            code, _, err, events = self.run_droid('later', 'in 2h', 'x')
        self.assertEqual(code, 1)
        self.assertIn("phone's time zone", err)
        self.assertEqual(events, [])


class Notify(Command):
    def test_at_takes_a_time_of_several_words(self):
        code, out, _, events = self.run_droid('notify', '--at', 'tomorrow', '14:00', 'Standup', 'in room 3', '--quiet')
        self.assertEqual(code, 0, out)
        e = events[0]
        self.assertEqual((e['title'], e['text'], e['level']), ('Standup', 'in room 3', 'quiet'))
        self.assertEqual(datetime.fromtimestamp(e['at'] / 1000, PST).strftime('%H:%M'), '14:00')

    def test_bad_values_are_errors_not_tracebacks(self):
        for args in (['T', '--wait', '--timeout', 'abc'], ['T', '--id', 'x' * 63], ['--cancel', '../x'],
                     ['T', '--at', 'whenever']):
            code, _, err, events = self.run_droid('notify', *args)
            self.assertEqual(code, 1, args)
            self.assertTrue(err.startswith('droid: '), err)
            self.assertEqual(events, [], args)

    def test_an_unanswered_wait_tells_the_app(self):
        code, _, err, events = self.run_droid('notify', 'T', '--id', 'q', '--wait', '--timeout', '0.3')
        self.assertEqual(code, 2)
        self.assertEqual([(e['op'], e['id']) for e in events], [('notify', 'q'), ('gave_up', 'n-q')])
        self.assertIn('droid wait n-q', err)


class Show(Command):
    def page(self, text='<p>Hi</p>'):
        p = os.path.join(self.dir, 'p.html')
        with open(p, 'w') as f:
            f.write(text)
        return p

    def test_full_or_float_not_both(self):
        code, _, err, events = self.run_droid('show', self.page(), '--full', '--float')
        self.assertEqual((code, events), (1, []))

    def test_an_empty_page_is_refused_here(self):
        code, _, err, events = self.run_droid('show', self.page(''))
        self.assertEqual((code, events), (1, []))

    def test_shown(self):
        code, out, _, events = self.run_droid('show', self.page(), '--float', '--id', 'p1')
        self.assertEqual(code, 0)
        self.assertEqual([(e['op'], e['where'], e['id'], e['wait']) for e in events], [('show', 'float', 'p1', False)])


class Wait(Command):
    def test_only_an_id(self):
        os.makedirs(d.REPLIES)
        outside = os.path.join(self.dir, 'x.json')
        with open(outside, 'w') as f:
            f.write('{"secret": 1}')
        code, out, _, events = self.run_droid('wait', '../x')
        self.assertEqual((code, out, events), (1, '', []))
        self.assertTrue(os.path.exists(outside))

    def test_waits_again_then_prints_the_answer(self):
        os.makedirs(d.REPLIES)
        with open(os.path.join(d.REPLIES, 'p1.json'), 'w') as f:
            f.write('{"text": "Dec 20"}')
        code, out, _, events = self.run_droid('wait', 'p1', '--timeout', '5')
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out), {'text': 'Dec 20'})
        self.assertEqual(events, [{'type': 'droid', 'op': 'wait', 'id': 'p1'}])

    def test_giving_up_tells_the_app(self):
        code, _, _, events = self.run_droid('wait', 'p1', '--timeout', '0.2')
        self.assertEqual(code, 2)
        self.assertEqual([e['op'] for e in events], ['wait', 'gave_up'])


if __name__ == '__main__':
    unittest.main()
