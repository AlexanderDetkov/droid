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
phone_offset = d.phone_offset  # the real ones; tests have the phone in PST
phone_moment = d.phone_moment

PST = timezone(timedelta(hours=-8))
PDT = timezone(timedelta(hours=-7))
NOW = datetime(2026, 11, 19, 10, 0, tzinfo=PST)  # a Thursday


def pacific(wall):
    """The phone's clock in California, which went back an hour at 2:00 on Nov 1, 2026."""
    return wall.replace(tzinfo=PST if wall >= datetime(2026, 11, 1, 1) else PDT)


class Command(unittest.TestCase):
    """Runs `droid ARGS` in-process, with the app's pipe a file and the phone in PST."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, 'out')
        open(self.out, 'w').close()
        os.environ['DROID_OUT'] = self.out
        d.REPLIES = os.path.join(self.dir, 'replies')
        d.phone_offset = lambda: PST
        d.phone_moment = lambda wall: wall.replace(tzinfo=PST)

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



class Say(Command):
    def test_says_it_out_loud(self):
        code, _, _, events = self.run_droid('say', "I'm", 'opening', 'your', 'calendar')
        self.assertEqual(code, 0)
        self.assertEqual(events, [{'type': 'droid', 'op': 'say', 'text': "I'm opening your calendar"}])

    def test_needs_words(self):
        code, _, err, events = self.run_droid('say')
        self.assertNotEqual(code, 0)
        self.assertIn('usage: droid say TEXT', err)
        self.assertEqual(events, [])

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
        # a time on the phone's clock, which the phone places (see test_a_clock_change_between)
        self.assertEqual(d.when('tomorrow 9am', NOW), datetime(2026, 11, 20, 9, 0))
        self.assertEqual(d.when('fri 14:00', NOW), datetime(2026, 11, 20, 14, 0))
        self.assertEqual(d.when('thu 9am', NOW), datetime(2026, 11, 26, 9, 0))  # this one has passed
        self.assertEqual(d.when('9am', NOW), datetime(2026, 11, 20, 9, 0))  # tomorrow's
        self.assertEqual(d.when('2026-11-21 09:00', NOW), datetime(2026, 11, 21, 9, 0))
        # a moment
        self.assertEqual(d.when('in 2h', NOW), NOW + timedelta(hours=2))
        self.assertIsNone(d.when('friends', NOW))

    def test_a_bare_hour_is_the_next_time_the_clock_shows_it(self):
        at = lambda h, m=0: NOW.replace(hour=h, minute=m)
        self.assertEqual(d.when('at 5', at(15)), datetime(2026, 11, 19, 17, 0))
        self.assertEqual(d.when('5', at(3)), datetime(2026, 11, 19, 5, 0))
        self.assertEqual(d.when('9', at(10)), datetime(2026, 11, 19, 21, 0))
        self.assertEqual(d.when('5', at(18)), datetime(2026, 11, 20, 5, 0))
        self.assertEqual(d.when('12', at(15)), datetime(2026, 11, 20, 0, 0))  # midnight
        self.assertEqual(d.when('tonight 8', at(10)), datetime(2026, 11, 19, 20, 0))
        # said in full, it is what it says
        self.assertEqual(d.when('5am', at(15)), datetime(2026, 11, 20, 5, 0))
        self.assertEqual(d.when('05:00', at(15)), datetime(2026, 11, 20, 5, 0))
        self.assertEqual(d.when('17', at(15)), datetime(2026, 11, 19, 17, 0))

    def test_a_clock_change_between(self):
        before = datetime(2026, 10, 31, 10, 0, tzinfo=PDT)  # the day before the clocks go back
        with mock.patch.object(d, 'phone_moment', pacific):
            self.assertEqual(d.moment(d.when('tomorrow 9am', before)), datetime(2026, 11, 1, 17, 0, tzinfo=timezone.utc))
            self.assertEqual(d.moment(d.when('in 24h', before)), before + timedelta(hours=24))  # a moment stays one

    def test_the_phone_places_a_time_on_its_clock(self):
        done = mock.Mock(stdout='1793552400\n')
        with mock.patch.object(d.subprocess, 'run', return_value=done) as run:
            self.assertEqual(phone_moment(datetime(2026, 11, 1, 9, 0)), datetime(2026, 11, 1, 17, 0, tzinfo=timezone.utc))
        self.assertEqual(run.call_args[0][0], ['android', "date -d '2026-11-01 09:00:00' +%s"])
        with mock.patch.object(d.subprocess, 'run', return_value=mock.Mock(stdout='date: bad date\n')), \
                contextlib.redirect_stderr(io.StringIO()) as err, self.assertRaises(SystemExit):
            phone_moment(datetime(2026, 11, 1, 9, 0))
        self.assertIn("couldn't read the phone's time", err.getvalue())

    def test_a_duration_is_written_one_way(self):
        self.assertEqual(d.leading_when(['2h', '10', 'mins', 'of', 'stretching'], NOW), (NOW + timedelta(hours=2), 1))
        self.assertEqual(d.leading_when(['2h30m', 'tea'], NOW), (NOW + timedelta(minutes=150), 1))
        self.assertEqual(d.leading_when(['2h', '30m', 'tea'], NOW), (NOW + timedelta(minutes=150), 2))
        self.assertIsNone(d.when('99999999d', NOW))

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

    def test_too_far_off_is_an_error_not_a_traceback(self):
        code, _, err, events = self.run_droid('later', '99999999d', 'x')
        self.assertEqual((code, events), (1, []))
        self.assertTrue(err.startswith('droid: '), err)

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

    def test_text_is_cut_to_what_the_app_takes(self):
        code, _, _, events = self.run_droid('notify', 'T', '€' * 3000)  # 3 bytes each
        self.assertEqual(code, 0)
        text = events[0]['text']
        self.assertEqual(text, '€' * 666)  # whole characters, in 2000 bytes

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

    def test_on_the_home_screen(self):
        code, _, _, events = self.run_droid('show', self.page(), '--home')
        self.assertEqual(code, 0)
        self.assertEqual([(e['op'], e['where']) for e in events], [('show', 'home')])
        code, _, err, events = self.run_droid('show', self.page(), '--home', '--full')
        self.assertEqual((code, events), (1, []))
        self.assertIn('one place', err)

    def test_shown(self):
        code, out, _, events = self.run_droid('show', self.page(), '--float', '--id', 'p1')
        self.assertEqual(code, 0)
        self.assertEqual([(e['op'], e['where'], e['id'], e['wait']) for e in events], [('show', 'float', 'p1', False)])


class Look(Command):
    def test_draws_it_then_takes_the_screenshot_then_takes_it_away(self):
        p = os.path.join(self.dir, 'p.html')
        with open(p, 'w') as f:
            f.write('<p>Hi</p>')
        shot = os.path.join(self.dir, 'shot')
        with open(shot, 'w') as f:
            f.write('#!/bin/sh\necho "Saved /tmp/shot-1.png"\necho /tmp/shot-1.png\n')
        os.chmod(shot, 0o755)
        d.SHOT = shot
        os.environ['AGENT_DISPLAY'] = '7'
        os.makedirs(d.REPLIES)
        real = d.emit
        def emit(e):  # the app draws it and answers
            real(e)
            if e['op'] == 'look':
                with open(os.path.join(d.REPLIES, e['id'] + '.json'), 'w') as f:
                    f.write('{"ok": true}')
        d.emit = emit
        try:
            code, out, _, events = self.run_droid('look', p)
        finally:
            d.emit = real
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), '/tmp/shot-1.png')
        self.assertEqual([e['op'] for e in events], ['look', 'look_done'])
        self.assertEqual((events[0]['display'], events[0]['path']), (7, os.path.realpath(p)))

    def test_needs_a_display(self):
        os.environ.pop('AGENT_DISPLAY', None)
        p = os.path.join(self.dir, 'p.html')
        with open(p, 'w') as f:
            f.write('<p>Hi</p>')
        code, _, err, events = self.run_droid('look', p)
        self.assertEqual((code, events), (1, []))


class Output(Command):
    """The app's end of the task's output is a pipe, which `droid` reopens."""

    def fifo(self):
        f = os.path.join(self.dir, 'fifo')
        os.mkfifo(f)
        os.environ['DROID_OUT'] = f
        return f

    def test_a_pipe_nobody_reads_is_not_waited_on(self):
        self.fifo()
        code, _, err, _ = self.run_droid('dash', 'happy')
        self.assertEqual(code, 1)
        self.assertIn("isn't reading", err)

    def test_a_pipe_that_is_read_gets_the_event(self):
        r = os.open(self.fifo(), os.O_RDONLY | os.O_NONBLOCK)
        try:
            code, _, _, _ = self.run_droid('dash', 'happy', 'done')
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(os.read(r, 4096)), {'type': 'droid', 'op': 'dash', 'feel': 'happy', 'words': 'done'})
        finally:
            os.close(r)


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
