#!/usr/bin/env python3
"""Offline, frame-by-frame simulator of Droid's end-of-turn loop (Voice.kt Session.run + judge, Turn.kt readsFinished)
with the real models in the loop: Silero VAD (sherpa-onnx's silero_vad.onnx, the one the app downloads, run per 512
window with its state carried, like vad.compute(frame)), Smart Turn v3.2 (Pipecat's inference.py features) and Parakeet
Unified EN 0.6B int8 (sherpa-onnx, greedy). Public data only: SLURP (900 real recordings) and LibriSpeech test-clean
(300 utterances >= 8 s, seed 0), each clean and through a simulated Bluetooth headset channel; 3 s of digital silence is
appended to every clip so the true end is always reachable (2.5 s is the longest wait).

Needs tools/asr_eval.py's data and Parakeet model (its `prepare`), Smart Turn's ONNX in
~/.cache/droid-turn-eval/downloads/smart-turn-model, and sherpa-onnx's
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx in $TURN_SIM_DIR/dl. A venv with
numpy, onnxruntime, sherpa-onnx and transformers. Work and results go in $TURN_SIM_DIR (~/.cache/droid-turn-eval/turnsim).

    PY="$HOME/.cache/droid-turn-eval/venv/bin/python -I"
    $PY tools/turn_sim.py prep   --jobs 12     # manifest, VAD probabilities, reference speech segments
    $PY tools/turn_sim.py judge  --jobs 4 --threads 3   # Smart Turn + Parakeet at every pause the variants can reach (cached)
    $PY tools/turn_sim.py report               # tables + per-clip outcomes in results/
"""
import argparse
import json
import os
import random
import re
import sys
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np

SCR = Path(os.environ.get("TURN_SIM_DIR", "~/.cache/droid-turn-eval/turnsim")).expanduser()
WORK, RESULTS = SCR / "work", SCR / "results"
ASR_DATA = Path("~/.cache/droid-asr-eval/data").expanduser()
PARAKEET = Path("~/.cache/droid-asr-eval/models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/"
                "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming").expanduser()
SMART_TURN = Path("~/.cache/droid-turn-eval/downloads/smart-turn-model/smart-turn-v3.2-cpu.onnx").expanduser()
SILERO = SCR / "dl" / "silero_vad.onnx"  # sha256 9e2449e1...cbb1fd6, sherpa-onnx asr-models/silero_vad.onnx (Silero v4)

# ---- Voice.kt constants (samples at 16 kHz) ----
SR, WIN = 16000, 512
SPEECH_PROB, SURE = 0.5, 0.95
MIN_SPEECH_FRAMES = 8
END_SILENCE, LONG_SILENCE, PAUSE = SR * 6 // 5, SR * 5 // 2, SR // 4
NO_SPEECH_TIMEOUT, MAX_LENGTH, PARTIALS_UP_TO = SR * 7, SR * 120, SR * 30
LEAD, SETTLE = SR * 3 // 10, SR // 2
APPEND = 3 * SR
CUT_SPEECH = int(0.150 * SR)  # more reference speech than this after the stop = cut off

SETS = ("slurp", "libri")
CONDS = ("clean", "bt")


# ------------------------------------------------------------------------------------------------ transcript rule

TRAILING = set("""and but so or because cause nor then if when while although though unless until since whereas
um uh er erm ah eh hmm mm uhm like well also plus
the a an my your his her its our their some any each every
to of in on at for with from by about into onto over under between through than as via
is are was were am be been being have has had do does did will would can could should shall may might must
i we you he she they it's i'm i'd i'll we're you're there's what's which who whom whose
just very""".split())
WORD = re.compile(r"[\w'%$]+", re.ASCII)  # Java's \w is ASCII-only


def reads_finished(text, stop=True):
    """Turn.kt readsFinished."""
    t = text.strip().rstrip(" \"'”’)")
    if t.endswith("...") or t.endswith("…"):
        return False
    if not t:
        return False
    end = t[-1]
    if stop and end not in ".?!":
        return False
    words = WORD.findall(t.lower())
    if not words:
        return False
    return end == "?" or words[-1] not in TRAILING


# ------------------------------------------------------------------------------------------------ data

def manifest():
    f = WORK / "clips.json"
    if f.exists():
        return json.loads(f.read_text())
    import soundfile as sf
    clips = []
    for x in json.loads((ASR_DATA / "slurp.json").read_text()):
        clips.append(dict(set="slurp", id=x["id"], wav=str(ASR_DATA / x["wav"]), ref=x["ref"], mic=x["mic"]))
    root = ASR_DATA / "LibriSpeech" / "test-clean"
    refs = {}
    for t in sorted(root.glob("*/*/*.trans.txt")):
        for line in t.read_text().splitlines():
            k, v = line.split(" ", 1)
            refs[k] = v.lower()
    long = []
    for w in sorted(root.glob("*/*/*.flac")):
        if sf.info(str(w)).duration >= 8.0:
            long.append(w)
    pick = sorted(random.Random(0).sample(long, 300))
    for w in pick:
        clips.append(dict(set="libri", id=w.stem, wav=str(w), ref=refs[w.stem], mic="read"))
    WORK.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(clips, indent=0))
    print(f"{len(long)} LibriSpeech test-clean utterances >= 8 s, picked 300; {len(clips)} clips in all")
    return clips


def fir_lowpass(cut, taps=255):
    n = np.arange(taps) - (taps - 1) / 2
    h = 2 * cut / SR * np.sinc(2 * cut / SR * n) * np.blackman(taps)
    return (h / h.sum()).astype(np.float64)


LP = fir_lowpass(3700.0)
GATE_BELOW_DB, GATE_ATTEN_DB, GATE_BLOCK = 30.0, 20.0, 160  # 10 ms blocks


def bluetooth(x):
    """A narrowband Bluetooth headset mic (SCO/CVSD is 8 kHz): band-limit to < 4 kHz, decimate to 8 kHz and back
    (zero-phase FIR, so timing is unchanged); then a mild noise gate like a headset's voice isolation: 10 ms blocks
    more than 30 dB below the clip's loud speech (95th-percentile block level) are attenuated by 20 dB, the gain
    smoothed over 30 ms."""
    y = np.convolve(x.astype(np.float64), LP, mode="same")[::2]          # 8 kHz
    up = np.zeros(len(y) * 2)
    up[::2] = y
    z = (np.convolve(up, LP, mode="same") * 2)[: len(x)]
    nb = len(z) // GATE_BLOCK
    if nb > 3:
        blocks = z[: nb * GATE_BLOCK].reshape(nb, GATE_BLOCK)
        db = 10 * np.log10(np.mean(blocks ** 2, axis=1) + 1e-12)
        ref = np.percentile(db, 95)
        g = np.where(db < ref - GATE_BELOW_DB, 10 ** (-GATE_ATTEN_DB / 20), 1.0)
        g = np.convolve(g, np.ones(3) / 3, mode="same")
        gain = np.interp(np.arange(len(z)), np.arange(nb) * GATE_BLOCK + GATE_BLOCK / 2, g)
        z = z * gain
    return z.astype(np.float32)


def load(clip, cond):
    import soundfile as sf
    x, sr = sf.read(clip["wav"], dtype="float32", always_2d=True)
    assert sr == SR, sr
    x = np.ascontiguousarray(x.mean(axis=1), dtype=np.float32)
    if cond == "bt":
        x = bluetooth(x)
    return np.concatenate([x, np.zeros(APPEND, np.float32)]), len(x)


def zslice(x, a, b):
    """Samples a..b of x, zeros outside it (Voice.kt Samples.slice)."""
    out = np.zeros(max(0, b - a), np.float32)
    lo, hi = min(max(a, 0), len(x)), min(max(b, 0), len(x))
    if hi > lo:
        out[lo - a: hi - a] = x[lo:hi]
    return out


# ------------------------------------------------------------------------------------------------ models

_m = {}


def silero():
    if "vad" not in _m:
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = so.inter_op_num_threads = 1
        _m["vad"] = ort.InferenceSession(str(SILERO), so, providers=["CPUExecutionProvider"])
    return _m["vad"]


def vad_probs(x):
    """Silero probability per 512-sample window, state carried across windows (sherpa-onnx Vad.compute)."""
    s = silero()
    h = np.zeros((2, 1, 64), np.float32)
    c = np.zeros((2, 1, 64), np.float32)
    n = len(x) // WIN
    out = np.empty(n, np.float32)
    for k in range(n):
        p, h, c = s.run(None, {"x": x[None, k * WIN:(k + 1) * WIN], "h": h, "c": c})
        out[k] = p[0, 0]
    return out


def ref_segments(probs, thr=0.5, neg=0.35, min_sil=int(0.1 * SR), min_speech=int(0.25 * SR)):
    """Silero's get_speech_timestamps (no padding) on window probabilities: [(start, end)] in samples."""
    segs, triggered, temp_end, start = [], False, 0, 0
    for k, p in enumerate(probs):
        cur = k * WIN
        if p >= thr and temp_end:
            temp_end = 0
        if p >= thr and not triggered:
            triggered, start = True, cur
            continue
        if p < neg and triggered:
            if not temp_end:
                temp_end = cur
            if cur - temp_end < min_sil:
                continue
            segs.append((start, temp_end))
            temp_end, triggered = 0, False
    if triggered:
        segs.append((start, len(probs) * WIN))
    return [s for s in segs if s[1] - s[0] >= min_speech]


def init_judge(threads):
    import onnxruntime as ort
    import sherpa_onnx
    from transformers import WhisperFeatureExtractor
    so = ort.SessionOptions()
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    so.inter_op_num_threads = 1
    so.intra_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    _m["st"] = ort.InferenceSession(str(SMART_TURN), sess_options=so, providers=["CPUExecutionProvider"])
    _m["fe"] = WhisperFeatureExtractor(chunk_length=8)
    d = PARAKEET
    _m["asr"] = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), joiner=str(d / "joiner.int8.onnx"),
        tokens=str(d / "tokens.txt"), num_threads=threads, sample_rate=SR, feature_dim=80,
        decoding_method="greedy_search", model_type="nemo_transducer", provider="cpu")


def smart_turn(audio):
    """Pipecat inference.py: last 8 s (zeros in front), WhisperFeatureExtractor normalized, sigmoid output."""
    n = 8 * SR
    a = audio[-n:] if len(audio) > n else np.pad(audio, (n - len(audio), 0))
    f = _m["fe"](a, sampling_rate=SR, return_tensors="np", padding="max_length", max_length=n, truncation=True,
                 do_normalize=True).input_features.astype(np.float32)
    return float(_m["st"].run(None, {"input_features": f})[0][0].item())


def parakeet(audio):
    s = _m["asr"].create_stream()
    s.accept_waveform(SR, audio)
    _m["asr"].decode_stream(s)
    return s.result.text.strip()


# ------------------------------------------------------------------------------------------------ prep

def _prep_one(clip):
    out = {}
    for cond in CONDS:
        x, n = load(clip, cond)
        out[cond] = vad_probs(x)
    return clip["set"], clip["id"], out


def prep(args):
    clips = manifest()
    f = WORK / "vad.npz"
    if f.exists():
        print("VAD probabilities already there")
        return
    probs = {}
    t0 = time.time()
    with ProcessPoolExecutor(args.jobs) as ex:
        for i, (s, cid, out) in enumerate(ex.map(_prep_one, clips, chunksize=4)):
            for cond, p in out.items():
                probs[f"{cond}|{s}|{cid}"] = p
            if (i + 1) % 200 == 0:
                print(f"vad {i + 1}/{len(clips)} {time.time() - t0:.0f} s", flush=True)
    np.savez_compressed(f, **probs)
    refs = {}
    for c in clips:
        refs[f"{c['set']}|{c['id']}"] = ref_segments(probs[f"clean|{c['set']}|{c['id']}"])
    (WORK / "refs.json").write_text(json.dumps(refs))
    print(f"prep done in {time.time() - t0:.0f} s")


# ------------------------------------------------------------------------------------------------ the loop

def judge_rule(p, text, v):
    """Voice.kt judge(): samples of silence that end the turn after this pause, and which path."""
    if p is None:
        return END_SILENCE, "end"
    sounds = p > 0.5
    reads = reads_finished(text) if text is not None else None
    sure = v.get("sure", True) and p > SURE and text is not None and reads_finished(text, stop=False)
    if p > v.get("fast_st", 0.5) and (reads is True or sure):
        return v.get("fast", PAUSE), ("reads" if reads else "sure")
    if not sounds and reads is not True:
        return LONG_SILENCE, "long"
    return END_SILENCE, "end"


def simulate(probs, v, L, J, enum=None):
    """Runs Session.run's loop over the window probabilities. J(first, last, pos) -> (p, text) is the judgment of the
    pause after speech ending at `last`, on audio heard up to `pos`; it takes effect at the end of the first frame at
    or past ask time + L (so the stop comes a frame later, as on the Pixel: judged in 322 ms -> over after 608 ms).
    With enum set, never stops and appends every (first, last, pos) it could ask about.
    Variant keys: hyst (VAD hysteresis down to neg), fast (silence for the both-say-finished end), sure, confirm (silence at which a fast verdict is asked again and must repeat), long_fast ((speech length,
    fast silence) for longer requests), fast_st (Smart Turn threshold for the fast end), min_fast_speech (no fast end before this much speech)."""
    hyst, neg = v.get("hyst", False), v.get("neg", 0.35)
    confirm = v.get("confirm")
    speech_frames, first, last, in_speech, judged = 0, -1, 0, False, -1
    pending = None  # verdict on its way: (spoke, ready_at, enough, path, p, text, stage)
    ending = None   # verdict in force: (spoke, enough, path, p, text)
    second = None   # second look to ask: (spoke, at)

    def verdict(first, last, pos, asked, stage, before=None):
        p, text = J(first, last, pos)
        if asked >= PARTIALS_UP_TO:
            text = None
        enough, path = judge_rule(p, text, v)
        if stage == 2 and v.get("grew") and text is not None and before is not None \
                and len(WORD.findall(text)) > len(WORD.findall(before)):
            # Parakeet heard words in what the VAD called silence: not a pause
            return (last, asked + L, END_SILENCE, "grew", p, text, stage)
        if path in ("reads", "sure"):
            if v.get("min_fast_speech") and last - first < v["min_fast_speech"]:
                enough, path = END_SILENCE, "end"
            elif v.get("long_fast") and last - first >= v["long_fast"][0]:
                enough = v["long_fast"][1]
            if stage == 2:
                enough, path = PAUSE, path + "+again"
        return (last, asked + L, enough, path, p, text, stage)

    n = len(probs)
    for k in range(n):
        size = (k + 1) * WIN
        pr = probs[k]
        in_speech = pr > SPEECH_PROB or (hyst and in_speech and pr >= neg)
        if in_speech:
            speech_frames += 1
            if first < 0:
                first = size - WIN
            last = size
        heard = speech_frames >= MIN_SPEECH_FRAMES
        silence = size - last
        if not heard and size > NO_SPEECH_TIMEOUT:
            return dict(stop=size, why="nothing")
        live = ending is not None and ending[0] == last
        if heard and silence >= (ending[1] if live else END_SILENCE) and enum is None:
            e = ending if live else (last, END_SILENCE, "default", None, None)
            return dict(stop=size, why=e[2], last=last, first=first, p=e[3], text=e[4], silence=silence)
        if size > MAX_LENGTH:
            return dict(stop=size, why="max")
        if heard and silence >= PAUSE and judged != last:
            judged = last
            if enum is not None:
                enum.append((first, last, size))
                if confirm:
                    enum.append((first, last, last + confirm))
                continue
            pending = verdict(first, last, size, size, 1)
        if pending is not None:
            if pending[0] != last:
                pending = None
            elif size >= pending[1]:
                spoke, _, enough, path, p, text, stage = pending
                pending = None
                if confirm and stage == 1 and path in ("reads", "sure"):
                    ending = (spoke, END_SILENCE, "end", p, text)  # unless the second look agrees
                    second = (spoke, spoke + confirm, text)
                else:
                    ending = (spoke, enough, path, p, text)
        if second is not None:
            if second[0] != last:
                second = None
            elif size >= second[1]:
                pending = verdict(first, last, second[1], size, 2, second[2])
                second = None
    return dict(stop=n * WIN, why="eof")


# ------------------------------------------------------------------------------------------------ variants

S = SR  # seconds -> samples
BASE = {
    "V0": dict(),
    "V1": dict(hyst=True),
    "V2a": dict(fast=int(0.5 * S)),
    "V2b": dict(fast=int(0.7 * S)),
    "V2c": dict(fast=int(0.9 * S)),
    "V1+V2a": dict(hyst=True, fast=int(0.5 * S)),
    "V1+V2b": dict(hyst=True, fast=int(0.7 * S)),
    "V1+V2c": dict(hyst=True, fast=int(0.9 * S)),
    "V3": dict(sure=False),
    "V1+V3": dict(hyst=True, sure=False),
    "V1+V3+V2a": dict(hyst=True, sure=False, fast=int(0.5 * S)),
    "V1+V3+V2b": dict(hyst=True, sure=False, fast=int(0.7 * S)),
}


def variants():
    v = dict(BASE)
    extra = json.loads((SCR / "extra_variants.json").read_text()) if (SCR / "extra_variants.json").exists() else {}
    v.update(extra)
    return v


def needed_vad_modes(vs):
    modes = {}
    for name, v in vs.items():
        key = (v.get("hyst", False), v.get("neg", 0.35) if v.get("hyst") else None, v.get("confirm"))
        modes.setdefault(key, v)
    return list(modes.values())


# ------------------------------------------------------------------------------------------------ judgments cache

def cache_path():
    return WORK / "judgments.jsonl"


def load_cache():
    c = {}
    f = cache_path()
    if f.exists():
        for line in f.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                c[r["k"]] = (r["p"], r["text"])
    return c


def _judge_clip(task):
    cond, clip, keys = task
    x, _ = load(clip, cond)
    out = []
    for (first, last, pos) in keys:
        st_in = zslice(x, first - LEAD, pos)
        p = smart_turn(st_in)
        asr_in = zslice(x[:pos], first - LEAD, last + SETTLE)  # zeros past what was heard
        text = parakeet(asr_in)
        out.append(dict(k=f"{cond}|{clip['set']}|{clip['id']}|{first}|{last}|{pos}", p=round(p, 6), text=text))
    return out


def judge(args):
    clips = manifest()
    vad = np.load(WORK / "vad.npz")
    cache = load_cache()
    vs = variants()
    tasks = []
    total = 0
    for clip in clips:
        if args.sets and clip["set"] not in args.sets:
            continue
        for cond in CONDS:
            probs = vad[f"{cond}|{clip['set']}|{clip['id']}"]
            keys = set()
            for v in needed_vad_modes(vs):
                enum = []
                simulate(probs, v.get("hyst") and {"hyst": True, "neg": v.get("neg", 0.35)} or {}, 0, None, enum=enum)
                keys.update(enum)
            for v in vs.values():
                if not v.get("confirm"):
                    continue
                # second looks: only after a fast first verdict; a missing one counts as a veto, so the loop goes on
                # to every later pause and the set is complete
                pre = f"{cond}|{clip['set']}|{clip['id']}"

                def J(first, last, pos, _pre=pre):
                    k = f"{_pre}|{first}|{last}|{pos}"
                    if k in cache:
                        return cache[k]
                    keys.add((first, last, pos))
                    return (0.0, "and")
                for Lms in (320, 600):
                    simulate(probs, v, Lms * SR // 1000, J)
            miss = sorted(k for k in keys if f"{cond}|{clip['set']}|{clip['id']}|{k[0]}|{k[1]}|{k[2]}" not in cache)
            total += len(keys)
            if miss:
                tasks.append((cond, clip, miss))
    nmiss = sum(len(t[2]) for t in tasks)
    print(f"{total} judgments needed, {nmiss} to compute in {len(tasks)} clip tasks", flush=True)
    if not tasks:
        return
    # long clips first, so the pool ends evenly
    tasks.sort(key=lambda t: -sum(k[2] - k[0] for k in t[2]))
    t0 = time.time()
    done = 0
    with open(cache_path(), "a") as f, ProcessPoolExecutor(args.jobs, initializer=init_judge, initargs=(args.threads,)) as ex:
        futs = [ex.submit(_judge_clip, t) for t in tasks]
        for fu in as_completed(futs):
            for r in fu.result():
                f.write(json.dumps(r) + "\n")
                done += 1
            f.flush()
            if done and done % 200 < 20:
                el = time.time() - t0
                print(f"{done}/{nmiss} judgments, {el:.0f} s", flush=True)
    print(f"judge done: {done} in {time.time() - t0:.0f} s")


# ------------------------------------------------------------------------------------------------ report

def outcome(res, segs, n_audio):
    """cut-off? and latency (s) after the reference's last speech."""
    stop = res["stop"]
    after = sum(max(0, b - max(a, stop)) for a, b in segs)
    end = segs[-1][1] if segs else 0
    return after > CUT_SPEECH, (stop - end) / SR, after / SR


def classify(res, segs, probs_ref=None):
    """Where a cut-off came from: 'vad drop' (the reference heard speech in the 256 ms the loop called a pause),
    or a real pause, by path (reads / sure / long wait)."""
    last, stop = res.get("last", 0), res["stop"]
    win = (last, last + 4096)
    sp = sum(max(0, min(b, win[1]) - max(a, win[0])) for a, b in segs)
    # the reference's longest silence (gap between its segments) overlapping the silence the loop stopped in: under
    # 250 ms the speaker didn't really pause there (the loop's VAD dropped inside speech)
    gaps = [(segs[i][1], segs[i + 1][0]) for i in range(len(segs) - 1)]
    gap = max((b - a for a, b in gaps if min(b, stop) - max(a, last) > 0), default=0) / SR
    kind = "vad drop" if gap < 0.25 else "real pause"
    return kind, sp / SR, gap


def run_all(vs, L, clips, vad, refs, cache, conds=CONDS):
    rows = {}
    for cond in conds:
        for clip in clips:
            ck = f"{clip['set']}|{clip['id']}"
            probs = vad[f"{cond}|{ck}"]
            segs = refs[ck]

            def J(first, last, pos, _p=f"{cond}|{ck}"):
                return cache[f"{_p}|{first}|{last}|{pos}"]

            for name, v in vs.items():
                res = simulate(probs, v, L, J)
                cut, lat, after = outcome(res, segs, len(probs))
                r = dict(res, cut=cut, lat=lat, after=after)
                if cut:
                    r["kind"], r["ref_speech_in_pause"], r["ref_gap"] = classify(res, segs)
                rows[(cond, clip["set"], clip["id"], name)] = r
    return rows


SUBSETS = {  # (set, condition) -> which clips; a Bluetooth headset is close-talking, so SLURP far-field is left out of bt
    ("slurp", "clean"): lambda c: True, ("slurp", "bt"): lambda c: c["mic"] == "headset",
    ("libri", "clean"): lambda c: True, ("libri", "bt"): lambda c: True,
}


def table(rows, vs, clips, cond, s, L):
    keep = SUBSETS[(s, cond)]
    ids = [c["id"] for c in clips if c["set"] == s and keep(c)]
    label = f"{s} headset recordings" if (s, cond) == ("slurp", "bt") else s
    lines = [f"\n### {label} / {cond}, L = {L * 1000 // SR} ms ({len(ids)} clips)\n",
             "| variant | cut off | median latency | mean latency | p90 latency | fast-path ends | never heard |",
             "|---|---|---|---|---|---|---|"]
    for name in vs:
        rs = [rows[(cond, s, i, name)] for i in ids]
        heard = [r for r in rs if r["why"] not in ("nothing", "eof")]
        cut = sum(r["cut"] for r in heard)
        lats = np.array([r["lat"] for r in heard if not r["cut"]])
        fast = sum(str(r["why"]).startswith(("reads", "sure")) for r in heard if not r["cut"])
        lines.append(f"| {name} | {100 * cut / len(rs):.1f}% ({cut}/{len(rs)}) | {np.median(lats):.2f} s | "
                     f"{lats.mean():.2f} s | {np.percentile(lats, 90):.2f} s | {100 * fast / max(1, len(heard) - cut):.0f}% | {len(rs) - len(heard)} |")
    return lines


NOTES = """Frame-by-frame replay of Voice.kt's loop (32 ms Silero v4 windows; pause judged at 256 ms of silence by Smart Turn
v3.2 + Parakeet Unified 0.6B int8; the verdict takes effect L after the pause, the stop a frame later; any speech frame
resets). Public data only: SLURP test (900 real recordings, headset + far-field) and 300 LibriSpeech test-clean
utterances >= 8 s (seed 0; read multi-sentence passages: a stand-in for a long spoken request). bt = the same audio
band-limited to < 4 kHz, decimated to 8 kHz and back (CVSD/SCO-like narrowband) plus a noise gate (10 ms blocks > 30 dB
under the clip's loud speech attenuated 20 dB); SLURP far-field is left out of bt (a headset mic is close-talking).
3 s of digital silence appended to every clip. Truth: Silero's get_speech_timestamps (0.5/0.35, min silence 100 ms) on
the clean clip. Cut off = more than 150 ms of that reference speech after the stop; latency = stop minus the reference
end, over clips not cut off and heard; never heard = the VAD never found 8 speech frames (Droid says nothing was heard).

Variants: V0 = the app now. V1 = VAD hysteresis (speech continues while p >= 0.35). V2a/V2b/V2_0.8/V2c/V2_1.0 = the
both-say-finished end needs 0.5/0.7/0.8/0.9/1.0 s of silence (V2a is V0 at L = 320 ms: the verdict only lands at
~0.58 s). V3 = no `sure` path. long3s:0.9 = 0.9 s only after 3 s of speech. st0.8 = Smart Turn > 0.8 for the fast end.
again0.6 = a fast verdict is asked again at 0.6 s of silence (new transcript + Smart Turn) and must repeat, ending L
later; +grew = and vetoed if that transcript has more words than the first.
"""


def report(args):
    clips = manifest()
    vad = np.load(WORK / "vad.npz")
    vad = {k: vad[k] for k in vad.files}
    refs = json.loads((WORK / "refs.json").read_text())
    cache = load_cache()
    vs = variants()
    if args.only:
        vs = {k: vs[k] for k in args.only}
    RESULTS.mkdir(exist_ok=True)
    out = ["# Droid end-of-turn simulation", "", NOTES]
    allrows = {}
    for Lms in args.L:
        L = Lms * SR // 1000
        names = vs if Lms == args.L[0] else {k: vs[k] for k in vs if k in args.at_slow}
        rows = run_all(names, L, clips, vad, refs, cache)
        allrows[Lms] = rows
        for s in SETS:
            for cond in CONDS:
                out += table(rows, names, clips, cond, s, L)
    # cut-off sources for V0 (and any --why variants) at the first L
    rows = allrows[args.L[0]]
    byid = {(c["set"], c["id"]): c for c in clips}
    for name in args.why:
        out += [f"\n## Where {name}'s cut-offs come from (L = {args.L[0]} ms)\n",
                "VAD drop = the reference's silence there was under 250 ms (the speaker didn't really pause); real pause = "
                "it was 250 ms or more. Fast path: both say finished (via the transcript's period, or `sure`); wait: the "
                "1.2 s / 2.5 s silence ran out.\n",
                "| set / channel | cut off | VAD drop: reads | VAD drop: sure | VAD drop: wait | real pause: reads | real pause: sure | real pause: wait | median real pause |",
                "|---|---|---|---|---|---|---|---|---|"]
        for s in SETS:
            for cond in CONDS:
                rs = [r for k, r in rows.items() if k[0] == cond and k[1] == s and k[3] == name and r["cut"]
                      and r["why"] not in ("nothing", "eof") and SUBSETS[(s, cond)](byid[(s, k[2])])]
                cnt = {}
                for r in rs:
                    why = str(r["why"]).split("+")[0]
                    why = why if why in ("reads", "sure") else "wait"
                    cnt[(r["kind"], why)] = cnt.get((r["kind"], why), 0) + 1
                gaps = [r["ref_gap"] for r in rs if r["kind"] == "real pause"]
                cells = [str(cnt.get((k, w), 0)) for k in ("vad drop", "real pause") for w in ("reads", "sure", "wait")]
                med = f"{np.median(gaps):.2f} s" if gaps else "-"
                out.append(f"| {s} / {cond} | {len(rs)} | " + " | ".join(cells) + f" | {med} |")
    # per-clip outcomes
    with open(RESULTS / "outcomes.jsonl", "w") as f:
        for Lms, rows in allrows.items():
            for (cond, s, cid, name), r in rows.items():
                rr = {k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()}
                f.write(json.dumps(dict(L_ms=Lms, cond=cond, set=s, id=cid, variant=name, ref=byid[(s, cid)]["ref"], **rr)) + "\n")
    text = "\n".join(out) + "\n"
    (RESULTS / "summary.md").write_text(text)
    print(text)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("prep")
    a.add_argument("--jobs", type=int, default=8)
    a = sub.add_parser("judge")
    a.add_argument("--jobs", type=int, default=4)
    a.add_argument("--threads", type=int, default=3)
    a.add_argument("--sets", nargs="*")
    a = sub.add_parser("report")
    a.add_argument("--L", type=int, nargs="+", default=[320, 600])
    a.add_argument("--at-slow", nargs="*", default=["V0"])
    a.add_argument("--why", nargs="*", default=["V0"])
    a.add_argument("--only", nargs="*")
    args = ap.parse_args()
    {"prep": prep, "judge": judge, "report": report}[args.cmd](args)


if __name__ == "__main__":
    main()
