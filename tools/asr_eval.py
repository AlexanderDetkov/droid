#!/usr/bin/env python3
"""Evaluate on-device speech-to-text models for Droid (sherpa-onnx offline NeMo transducers) on public data.

Measures, per model: WER on fixed random subsets of LibriSpeech test-clean / test-other (300 utterances each,
seed 0), test-clean with 10 dB babble from three other LibriSpeech speakers, and SLURP (900 real recordings of
spoken voice-assistant requests, headset and far-field); contextual biasing (hotwords) with the LibriSpeech
biasing lists of Le et al. 2021 (WER / U-WER / B-WER); the effect of an irrelevant phone list (app and contact
names); load time and real-time factor. Text is compared after Whisper's English normaliser. No personal
recordings are used.

Everything (venv, models, data, results) lives in ~/.cache/droid-asr-eval ($ASR_EVAL_DIR overrides it;
$ASR_EVAL_RESULTS keeps results apart, e.g. per sherpa-onnx version):

    cd ~/.cache/droid-asr-eval && uv venv -p 3.12 venv && VIRTUAL_ENV=$PWD/venv uv pip install \\
        sherpa-onnx==2.0.2 numpy soundfile jiwer whisper-normalizer sentencepiece protobuf pyarrow
    PY="$HOME/.cache/droid-asr-eval/venv/bin/python -I"
    $PY tools/asr_eval.py prepare      # models, tokenizers (bpe.vocab), LibriSpeech, biasing lists, SLURP; subsets
    $PY tools/asr_eval.py all --best unified --jobs 5   # the suite; resumable, a few CPU-hours on 24 threads
    $PY tools/asr_eval.py speed        # load time and RTF, 4 threads pinned to P-cores; run on an idle machine
    $PY tools/asr_eval.py report       # markdown tables from results/

Single runs: `decode --model v2 --set other` (greedy), `... --beam 4 --hotwords b100 --score 2` (hotwords given as
pieces from tokens.txt, `--encoding bpe` lets sherpa-onnx tokenise them), `--length-reward 0.5`. To check a new
model add it to MODELS. `requant` (needs `uv pip install onnxruntime==1.31.0 onnx`) rebuilds an int8 encoder from
the fp32 export with the depthwise and subsampling convs kept in float32, as sherpa-onnx PR #4179 does for Ultra.
"""
import argparse
import functools
import json
import os
import random
import re
import subprocess
import sys
import tarfile
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(os.environ.get("ASR_EVAL_DIR", "~/.cache/droid-asr-eval")).expanduser()
MODELS_DIR, DATA, TOK = ROOT / "models", ROOT / "data", ROOT / "tokenizers"
RESULTS = Path(os.environ.get("ASR_EVAL_RESULTS", ROOT / "results")).expanduser()  # e.g. one per sherpa-onnx version
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
HF = "https://huggingface.co/"
V3_NEMO = "nvidia/parakeet-tdt-0.6b-v3/resolve/main/parakeet-tdt-0.6b-v3.nemo"

# short name -> (release package, .nemo holding the SentencePiece tokenizer, tokenizer dir name)
MODELS = {
    "v2": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
           "nvidia/parakeet-tdt-0.6b-v2/resolve/main/parakeet-tdt-0.6b-v2.nemo", "v2"),
    "unified": ("sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
                "nvidia/parakeet-unified-en-0.6b/resolve/main/parakeet-unified-en-0.6b.nemo", "unified"),
    "ultra": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8", V3_NEMO, "v3"),
    "redux": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-redux-int8", V3_NEMO, "v3"),
    "v3": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", V3_NEMO, "v3"),
}
# Locally re-quantised encoders (see `requant`): name -> (base model, fp32 export on HuggingFace)
REQUANT = {
    "ultra-q": ("ultra", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra"),
    "v2-q": ("v2", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2"),
}

N_UTTS, N_SLURP, SEED, SNR_DB, BABBLE_TALKERS = 300, 900, 0, 10.0, 3
SLURP_PARQUET = "datasets/marcel-gohsen/slurp/resolve/main/data/test-00000-of-00002.parquet"

# What Droid would pass on every request: installed app labels, contact names, names from the chat. Made up but
# typical, cased as the app would show them; split on spaces, so one word per hotword (~370 distinct).
PHONE_LIST = """
WhatsApp Duolingo Spotify YouTube YouTube Music Gmail Google Maps Google Photos Google Drive Google Docs Google Sheets
Google Calendar Google Keep Google Pay Google Translate Google Fit Google Home Google Wallet Chrome Firefox Messages
Phone Contacts Camera Clock Calculator Settings Files Play Store Gallery Recorder Weather Instagram Facebook Messenger
Threads TikTok Snapchat Telegram Signal Discord Slack Zoom Microsoft Teams Outlook OneDrive Word Excel PowerPoint
Netflix Hulu Disney Plus Prime Video HBO Max Peacock Paramount Plus Twitch Reddit Pinterest LinkedIn Tumblr Uber Lyft
DoorDash Grubhub Instacart Uber Eats Postmates Starbucks McDonald's Chipotle Domino's Amazon Shopping eBay Etsy Walmart
Target Costco Best Buy Home Depot IKEA Shein Temu Venmo PayPal Cash App Zelle Chase Wells Fargo Bank of America
Capital One Citi Robinhood Coinbase Fidelity Schwab Mint Splitwise Airbnb Booking Expedia Kayak Hopper Delta United
American Airlines Southwest Waze Citymapper Transit Strava Peloton MyFitnessPal Fitbit Headspace Calm Audible Kindle
Libby Goodreads Pocket Casts Shazam SoundCloud Pandora Tidal Deezer Notion Evernote Todoist Trello Asana Dropbox
Authenticator Duo Mobile Okta LastPass Bitwarden 1Password ChatGPT Claude Gemini Perplexity Copilot Grammarly
Canva CapCut Lightroom Snapseed VSCO Pixel Tips Wear OS Nest Ring SmartThings Philips Hue Alexa Tinder Hinge Bumble
Nextdoor Yelp OpenTable Zillow Redfin Craigslist OfferUp Poshmark Depop Quizlet Khan Academy Coursera Photomath
Babbel Memrise Anki Wikipedia Feedly Flipboard Medium Substack Patreon Ticketmaster StubHub Eventbrite Fandango
Mama Papa Grandma Grandpa Babushka Dedushka Auntie Olivia Liam Emma Noah Ava Elijah Sophia Lucas Isabella Mateo Mia
Ethan Amelia Mason Harper Logan Evelyn Aiden Abigail Jackson Emily Sebastian Ella Henry Scarlett Owen Chloe Daniel
Grace Samuel Zoe Gabriel Nora Julian Riley Levi Lily Isaac Aria Anthony Layla Dylan Hannah Wyatt Addison Andrew
Natalie Joshua Leah Christopher Hazel Jaxon Violet Lincoln Aurora Adrian Savannah Nathan Audrey Thomas Brooklyn
Aaron Bella Ezra Claire Charles Skylar Caleb Lucy Josiah Paisley Isaiah Anna Hunter Caroline Eli Genesis Jonathan
Kennedy Connor Sadie Jeremiah Maya Cameron Willow Xavier Naomi Dmitri Svetlana Natasha Sergei Katya Anastasia Mikhail
Priya Arjun Rohan Ananya Wei Mei Hiroshi Yuki Sakura Kenji Jae-won Min-ji Fatima Omar Aisha Yusuf Mohammed Leila
Santiago Valentina Camila Diego Lucia Alejandro Rodriguez Nguyen Patel Kowalski Novak Schmidt Rossi Dubois Johansson
O'Brien McAllister Okafor Haddad Kim Park Chen Wang Ivanova Petrov Garcia Martinez Hernandez Lopez Gonzalez Wilson
Anderson Taylor Moore Jackson Martin Lee Thompson White Harris Clark Lewis Robinson Walker Young Allen King
""".split()


def ensure_dirs():
    for d in (MODELS_DIR, DATA, TOK, RESULTS):
        d.mkdir(parents=True, exist_ok=True)


def fetch(url, dst):
    """Download url to dst (skipped if present)."""
    dst = Path(dst)
    if dst.exists():
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    print("downloading", url, flush=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    subprocess.run(["curl", "-sSfL", "-o", str(tmp), url], check=True)
    tmp.rename(dst)
    return dst


def extract(archive, into):
    """Extract a tarball into its own directory (data, never run)."""
    into = Path(into)
    if into.exists():
        return into
    tmp = into.with_name(into.name + ".part")
    tmp.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as t:
        t.extractall(tmp, filter="data")
    tmp.rename(into)
    Path(archive).unlink()
    return into


# ---------------------------------------------------------------------------------------------- models

def model_dir(name):
    if name in REQUANT:
        return MODELS_DIR / name
    pkg = MODELS[name][0]
    return MODELS_DIR / pkg / pkg


def tokenizer_dir(name):
    return TOK / MODELS[REQUANT[name][0] if name in REQUANT else name][2]


def nemo_tar_members(url, want):
    """Walk the tar of a .nemo checkpoint with HTTP range requests and save the small members matching want.

    Gets the SentencePiece tokenizer without downloading the 2.5 GB checkpoint."""
    def get(start, n):
        req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{start + n - 1}"})
        with urllib.request.urlopen(req) as r:
            return r.read()
    off, found = 0, {}
    while True:
        h = get(off, 512)
        if len(h) < 512 or h == bytes(512):
            return found
        ti = tarfile.TarInfo.frombuf(h, "utf-8", "surrogateescape")
        if ti.isfile() and re.search(want, ti.name) and ti.size < 50_000_000:
            found[Path(ti.name).name] = get(off + 512, ti.size)
        off += 512 + (ti.size + 511) // 512 * 512


def prepare_models():
    for name, (pkg, nemo, tok) in MODELS.items():
        d = MODELS_DIR / pkg
        if not d.exists():
            extract(fetch(RELEASE + pkg + ".tar.bz2", MODELS_DIR / f"{pkg}.tar.bz2"), d)
        vocab = TOK / tok / "bpe.vocab"
        if not vocab.exists():
            import sentencepiece as spm
            (TOK / tok).mkdir(parents=True, exist_ok=True)
            for fname, data in nemo_tar_members(HF + nemo, r"tokenizer\.model$").items():
                (TOK / tok / "tokenizer.model").write_bytes(data)
            sp = spm.SentencePieceProcessor(model_file=str(TOK / tok / "tokenizer.model"))
            # The same file scripts/nemo/generate_bpe_vocab.py in sherpa-onnx writes: piece <tab> score.
            vocab.write_text("".join(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n" for i in range(sp.get_piece_size())),
                             encoding="utf-8")
        tokens = [l.rsplit(" ", 1)[0] for l in (model_dir(name) / "tokens.txt").read_text(encoding="utf-8").splitlines()]
        pieces = [l.split("\t")[0] for l in vocab.read_text(encoding="utf-8").splitlines()]
        assert tokens[:-1] == pieces and tokens[-1] == "<blk>", f"{name}: tokenizer does not match tokens.txt"


def requant(name):
    """int8 encoder with the 24 depthwise and 5 subsampling convs left in float32 (sherpa-onnx PR #4179)."""
    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic
    base, repo = REQUANT[name]
    src, out = ROOT / "fp32" / name, MODELS_DIR / name
    for f in ("encoder.onnx", "encoder.weights"):
        fetch(f"{HF}{repo}/resolve/main/{f}", src / f)
    out.mkdir(parents=True, exist_ok=True)
    for f in ("decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
        if not (out / f).exists():
            (out / f).symlink_to(model_dir(base) / f)
    graph = onnx.load(str(src / "encoder.onnx"), load_external_data=False)
    keep = [n.name for n in graph.graph.node
            if n.op_type == "Conv" and ("/depthwise_conv/" in n.name or n.name.startswith("/pre_encode/"))]
    assert len(keep) == 29, keep
    meta = {p.key: p.value for p in graph.metadata_props}
    quantize_dynamic(model_input=str(src / "encoder.onnx"), model_output=str(out / "encoder.int8.onnx"),
                     weight_type=QuantType.QUInt8, nodes_to_exclude=keep)
    q = onnx.load(str(out / "encoder.int8.onnx"))
    del q.metadata_props[:]
    for k, v in meta.items():  # sherpa-onnx reads model type, vocab size, normalisation, "tdt" from here
        q.metadata_props.add(key=k, value=v)
    onnx.save(q, str(out / "encoder.int8.onnx"))
    print("wrote", out / "encoder.int8.onnx", (out / "encoder.int8.onnx").stat().st_size)


# ------------------------------------------------------------------------------------------------ data

def librispeech(subset):
    root = DATA / "LibriSpeech" / subset
    utts = {}
    for trans in sorted(root.glob("*/*/*.trans.txt")):
        for line in trans.read_text().splitlines():
            uid, text = line.split(" ", 1)
            utts[uid] = {"id": uid, "wav": str((trans.parent / f"{uid}.flac").relative_to(DATA)), "ref": text.lower()}
    return utts


def prepare_data():
    import numpy as np
    import soundfile as sf
    for s in ("test-clean", "test-other"):
        if not (DATA / "LibriSpeech" / s).exists():
            tgz = fetch(f"https://www.openslr.org/resources/12/{s}.tar.gz", DATA / f"{s}.tar.gz")
            tmp = extract(tgz, DATA / f"ls_{s}")
            (DATA / "LibriSpeech").mkdir(exist_ok=True)
            (tmp / "LibriSpeech" / s).rename(DATA / "LibriSpeech" / s)
    fb = "https://raw.githubusercontent.com/facebookresearch/fbai-speech/main/is21_deep_bias/ref/"
    for s in ("test-clean", "test-other"):
        for n in (100, 500, 1000):
            fetch(f"{fb}{s}.biasing_{n}.tsv", DATA / "fbai" / f"{s}.biasing_{n}.tsv")

    sets = {}
    for name, s in (("clean", "test-clean"), ("other", "test-other")):
        utts = librispeech(s)
        bias = {}
        for n in (100, 500, 1000):
            for line in (DATA / "fbai" / f"{s}.biasing_{n}.tsv").read_text().splitlines():
                uid, _, rare, lst = line.split("\t")
                bias.setdefault(uid, {"rare": json.loads(rare)})[f"b{n}"] = json.loads(lst)
        chosen = random.Random(SEED).sample(sorted(utts), N_UTTS)
        sets[name] = [dict(utts[u], **bias[u]) for u in chosen]

    # test-clean subset + babble of 3 other test-clean speakers (not in the subset) at 10 dB SNR
    clean = librispeech("test-clean")
    pool = sorted(set(clean) - {u["id"] for u in sets["clean"]})
    rng = np.random.default_rng(SEED)
    (DATA / "noisy").mkdir(exist_ok=True)
    sets["noisy"] = []
    for u in sets["clean"]:
        out = DATA / "noisy" / f"{u['id']}.flac"
        if not out.exists():
            speech, sr = sf.read(DATA / u["wav"], dtype="float32")
            spk, picks, babble = u["id"].split("-")[0], [], np.zeros_like(speech)
            for v in rng.permutation(pool):
                if v.split("-")[0] != spk and v.split("-")[0] not in {p.split("-")[0] for p in picks}:
                    picks.append(v)
                if len(picks) == BABBLE_TALKERS:
                    break
            for v in picks:
                x, _ = sf.read(DATA / clean[v]["wav"], dtype="float32")
                x = np.tile(x, len(speech) // len(x) + 2)
                start = rng.integers(0, len(x) - len(speech))
                x = x[start:start + len(speech)]
                babble += x / (np.sqrt(np.mean(x ** 2)) + 1e-9)
            p_s, p_b = np.mean(speech ** 2), np.mean(babble ** 2)
            mix = speech + babble * np.sqrt(p_s / p_b / 10 ** (SNR_DB / 10))
            mix *= min(1.0, 0.99 / np.max(np.abs(mix)))
            sf.write(out, mix, sr, subtype="PCM_16")
        sets["noisy"].append(dict(u, wav=str(out.relative_to(DATA))))

    sets["slurp"] = prepare_slurp()
    for name, utts in sets.items():
        (DATA / f"{name}.json").write_text(json.dumps(utts, indent=0))
        print(name, len(utts), "utterances,", round(sum(sf.info(DATA / u["wav"]).duration for u in utts) / 60, 1), "min")


def prepare_slurp():
    """900 random recordings (headset and far-field mics) from the first of the two SLURP test shards."""
    import io
    import pyarrow.parquet as pq
    import soundfile as sf
    pq_path = fetch(HF + SLURP_PARQUET, DATA / "slurp" / Path(SLURP_PARQUET).name)
    t = pq.read_table(pq_path, columns=["id", "transcript", "audio"])
    rows = random.Random(SEED).sample(range(t.num_rows), N_SLURP)
    core = set(rows[:N_UTTS])  # the first 300 draws: an earlier, smaller version of this set
    out = []
    for i in sorted(rows):
        audio = t.column("audio")[i].as_py()
        name = Path(audio["path"]).stem
        wav = DATA / "slurp" / f"{name}.flac"
        if not wav.exists():
            x, sr = sf.read(io.BytesIO(audio["bytes"]), dtype="float32")
            assert sr == 16000, sr
            sf.write(wav, x, sr, subtype="PCM_16")
        out.append({"id": name, "wav": str(wav.relative_to(DATA)), "ref": t.column("transcript")[i].as_py(),
                    "mic": "headset" if "headset" in name else "far-field", "core": i in core})
    return out


# ---------------------------------------------------------------------------------------------- decode

def recognizer(model, beam, score, encoding, threads=4):
    import sherpa_onnx
    d = model_dir(model)
    kw = dict(encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"),
              joiner=str(d / "joiner.int8.onnx"), tokens=str(d / "tokens.txt"), num_threads=threads,
              model_type="nemo_transducer")
    if beam:
        kw.update(decoding_method="modified_beam_search", max_active_paths=beam, hotwords_score=score)
        if encoding == "bpe":  # sherpa-onnx tokenises plain-text hotwords itself
            kw.update(modeling_unit="bpe", bpe_vocab=str(tokenizer_dir(model) / "bpe.vocab"))
    return sherpa_onnx.OfflineRecognizer.from_transducer(**kw)


def hotword_lines(utt, mode, case):
    if mode == "none":
        return []
    if mode == "phone":  # irrelevant by construction: drop entries sharing a word with the reference
        said = set(utt["ref"].lower().split())
        words = [w for w in PHONE_LIST if not set(w.lower().replace("'s", "").split()) & said]
    elif mode.startswith("d"):  # the biasing list without the words that are said: N distractors
        words = [w for w in utt["b" + mode[1:]] if w not in set(utt["rare"])]
    else:
        words = utt[mode]
    if case == "lower":
        return words
    if case == "cap":
        return [w[:1].upper() + w[1:] for w in words]
    return [v for w in words for v in dict.fromkeys([w, w[:1].upper() + w[1:]])]  # both spellings


def pretokenize(lines, model):
    """Hotwords as tokens from tokens.txt, each phrase starting with a ▁ piece (sherpa-onnx >= 2.0, PR #4088)."""
    sp = sentencepiece_model(tokenizer_dir(model))
    return [" ".join(sp.encode(line, out_type=str)) for line in lines]


@functools.lru_cache
def sentencepiece_model(d):
    import sentencepiece as spm
    return spm.SentencePieceProcessor(model_file=str(d / "tokenizer.model"))


def tag(a):
    t = f"{a.model}.{a.set}." + (f"beam{a.beam}" if a.beam else "greedy")
    if a.hotwords != "none":
        t += f".{a.hotwords}.s{a.score:g}.{a.case}.{a.encoding}"
    t += f".lr{a.length_reward:g}" if getattr(a, "length_reward", 0) else ""
    return t + (f".n{a.limit}" if a.limit else "")


def decode(a):
    out = RESULTS / f"{tag(a)}.json"
    utts = json.loads((DATA / f"{a.set}.json").read_text())[: a.limit or None]
    done = {}
    if out.exists() and not a.force:  # finished, or to be extended because its set has grown
        done = {r["id"]: r for r in json.loads(out.read_text())["utts"]}
        if all(u["id"] in done for u in utts):
            return out
    lock = out.with_suffix(".running")  # lets parallel runners skip runs in progress
    lock.touch()
    try:
        return _decode(a, out, utts, done)
    finally:
        lock.unlink(missing_ok=True)


def _decode(a, out, utts, done):
    import sherpa_onnx
    import soundfile as sf
    t0 = time.perf_counter()
    rec = recognizer(a.model, a.beam, a.score, a.encoding, a.threads)
    load = time.perf_counter() - t0
    rows = []
    for u in utts:
        if u["id"] in done:
            rows.append(done[u["id"]])
            continue
        x, sr = sf.read(DATA / u["wav"], dtype="float32")
        lines = hotword_lines(u, a.hotwords, a.case)
        if a.encoding == "pretok":
            lines = pretokenize(lines, a.model)
        s = rec.create_stream(hotwords="/".join(lines)) if lines else rec.create_stream()
        if a.length_reward:  # added per emitted token by modified_beam_search (counters dropped speech)
            s.set_option("length_reward", str(a.length_reward))
        s.accept_waveform(sr, x)
        t = time.perf_counter()
        rec.decode_stream(s)
        rows.append({"id": u["id"], "hyp": s.result.text, "secs": round(time.perf_counter() - t, 4),
                     "dur": len(x) / sr})
    audio_s, decode_s = sum(r["dur"] for r in rows), sum(r["secs"] for r in rows)
    res = {"config": vars(a) | {"tag": tag(a), "sherpa_onnx": sherpa_onnx.__version__}, "load_s": load,
           "audio_s": audio_s, "decode_s": decode_s, "rtf": decode_s / audio_s, "utts": rows}
    res.update(score(a.set, rows))
    out.write_text(json.dumps(res, indent=1))
    print(f"{tag(a)}: WER {res['wer']:.2f}" + (f" B-WER {res['b_wer']:.2f} U-WER {res['u_wer']:.2f}"
          if "b_wer" in res else "") + f" RTF {res['rtf']:.3f}", flush=True)
    return out


# ----------------------------------------------------------------------------------------------- score

_norm = None


def normalize(text):
    global _norm
    if _norm is None:
        from whisper_normalizer.english import EnglishTextNormalizer
        _norm = EnglishTextNormalizer()
    return _norm(text).split()


def align(ref, hyp):
    """Edit-distance alignment as in fbai-speech/is21_deep_bias/score.py (sub 4, ins/del 3): [(code, r, h)]."""
    n, m = len(ref), len(hyp)
    cost = [[0] * (m + 1) for _ in range(n + 1)]
    back = [[None] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        for j in range(m + 1):
            if i == 0 and j == 0:
                continue
            if i == 0:
                cost[i][j], back[i][j] = cost[i][j - 1] + 3, "I"
            elif j == 0:
                cost[i][j], back[i][j] = cost[i - 1][j] + 3, "D"
            else:
                best = (cost[i - 1][j - 1] + (0 if ref[i - 1] == hyp[j - 1] else 4), "M")
                if cost[i][j - 1] + 3 < best[0]:
                    best = (cost[i][j - 1] + 3, "I")
                if cost[i - 1][j] + 3 < best[0]:
                    best = (cost[i - 1][j] + 3, "D")
                cost[i][j], back[i][j] = best
    i, j, path = n, m, []
    while i or j:
        b = back[i][j]
        if b == "M":
            path.append(("C" if ref[i - 1] == hyp[j - 1] else "S", i - 1, j - 1))
            i, j = i - 1, j - 1
        elif b == "I":
            path.append(("I", None, j - 1))
            j -= 1
        else:
            path.append(("D", i - 1, None))
            i -= 1
    return path[::-1]


@functools.lru_cache
def manifest(set_name):
    return {u["id"]: u for u in json.loads((DATA / f"{set_name}.json").read_text())}


def score(set_name, rows):
    """WER after Whisper's English normaliser; for LibriSpeech also U-WER and B-WER (Le et al. 2021),
    B over the reference's rare words (the biasing lists' third column); for SLURP also WER per microphone."""
    utts = manifest(set_name)
    err, words = {}, {}
    for r in rows:
        u = utts[r["id"]]
        ref, hyp = normalize(u["ref"]), normalize(r["hyp"])
        rare = {t for w in u.get("rare", []) for t in normalize(w)}
        for code, i, j in align(ref, hyp):
            keys = ["wer", "b_wer" if (ref[i] if i is not None else hyp[j]) in rare else "u_wer"]
            if "mic" in u:
                keys.append(f"wer_{u['mic']}")
            if u.get("core"):
                keys.append("wer_core")
            for k in keys:
                words[k] = words.get(k, 0) + (code != "I")
                err[k] = err.get(k, 0) + (code != "C")
    res = {k: 100 * err[k] / words[k] for k in words if words[k]}
    if "b_wer" not in res:
        res.pop("u_wer", None)
    return res | {"ref_words": words["wer"], "errors": err["wer"], "b_words": words.get("b_wer", 0)}


# ------------------------------------------------------------------------------------------------ suite

def suite(best):
    """(args) for every run in the report. `best` is the model the biasing study is run on besides v2."""
    runs = []
    def add(model, set_, beam=0, hotwords="none", score=2.0, case="both", encoding="pretok"):
        runs.append(dict(model=model, set=set_, beam=beam, hotwords=hotwords, score=score, case=case,
                         encoding=encoding))
    sets = ("clean", "other", "noisy", "slurp")
    models = list(MODELS) + [m for m in REQUANT if (MODELS_DIR / m / "encoder.int8.onnx").exists()]
    for m in models:
        for s in sets:
            add(m, s)
    for m in dict.fromkeys(["v2", best]):
        for s in sets:
            add(m, s, beam=4)                          # beam alone
            add(m, s, beam=4, hotwords="phone")        # irrelevant phone list
        for s in ("other", "slurp"):
            add(m, s, beam=4, hotwords="phone", score=1.0)
        for s in ("clean", "other"):
            for n in ("b100", "b1000"):
                add(m, s, beam=4, hotwords=n, score=1.0)
                add(m, s, beam=4, hotwords=n)
            add(m, s, beam=4, hotwords="b100", encoding="bpe")  # sherpa-onnx tokenises with bpe.vocab
        add(m, "other", beam=4, hotwords="d1000")      # 1000 distractors, none said
    add(best, "other", beam=4, hotwords="b100", case="lower")  # spelling, beam width, a higher score
    add(best, "other", beam=8, hotwords="b100")
    add(best, "other", beam=4, hotwords="b100", score=3.0)
    return runs


def run_all(a):
    def one(r):
        cmd = [sys.executable, "-I", __file__, "decode", "--threads", "4"] + [
            x for k, v in r.items() for x in (f"--{k.replace('_', '-')}", str(v))]
        subprocess.run(cmd, check=True)
    runs = [r for r in suite(a.best) if (not a.only or r["model"] in a.only)
            and not (RESULTS / f"{tag(argparse.Namespace(**r, limit=0))}.running").exists()]
    print(len(runs), "runs,", sum(not (RESULTS / f"{tag(argparse.Namespace(**r, limit=0))}.json").exists()
                                  for r in runs), "to do", flush=True)
    with ThreadPoolExecutor(a.jobs) as pool:
        list(pool.map(one, runs))


# ------------------------------------------------------------------------------------------------ speed

def evict(paths):
    for p in paths:
        fd = os.open(p, os.O_RDONLY)
        os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
        os.close(fd)


def speed(a):
    """Load time (page cache cold and warm) and RTF with 4 threads pinned to four P-cores, one model at a time."""
    import soundfile as sf
    os.sched_setaffinity(0, a.cpus)
    utts = json.loads((DATA / "clean.json").read_text())[: a.n]
    audio = [sf.read(DATA / u["wav"], dtype="float32")[0] for u in utts]
    biasing = {m: [pretokenize(hotword_lines(u, "phone", "both"), m) for u in utts] for m in a.models}
    out = {}
    for model in a.models:
        d = model_dir(model)
        files = [str(d / f) for f in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")]
        size = sum(os.path.getsize(os.path.realpath(f)) for f in files)
        evict([os.path.realpath(f) for f in files])
        t = time.perf_counter(); recognizer(model, 0, 0, "bpe"); cold = time.perf_counter() - t
        warm = []
        for _ in range(3):
            t = time.perf_counter(); recognizer(model, 0, 0, "bpe"); warm.append(time.perf_counter() - t)
        row = {"size_mb": size / 1e6, "load_cold_s": cold, "load_warm_s": min(warm)}
        for label, beam, hw in (("greedy", 0, False), ("beam4", 4, False), ("beam4_phone", 4, True)):
            rec = recognizer(model, beam, 2.0, "pretok")
            for x in audio[:3]:  # warm-up
                s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s)
            dec = 0.0
            for x, lines in zip(audio, biasing[model]):
                s = rec.create_stream(hotwords="/".join(lines)) if hw else rec.create_stream()
                s.accept_waveform(16000, x)
                t = time.perf_counter(); rec.decode_stream(s); dec += time.perf_counter() - t
            row[f"rtf_{label}"] = dec / (sum(map(len, audio)) / 16000)
        out[model] = row
        print(model, {k: round(v, 3) for k, v in row.items()}, flush=True)
    (RESULTS / "speed.json").write_text(json.dumps({"cpus": sorted(a.cpus), "utts": a.n, "models": out}, indent=1))


# ----------------------------------------------------------------------------------------------- report

def delta(res, base, other, n=2000):
    """WER(other) - WER(base) with a 95% paired bootstrap interval over utterances."""
    if base not in res or other not in res:
        return "–"
    set_name = res[base]["config"]["set"]
    a = {r["id"]: score(set_name, [r]) for r in res[base]["utts"]}
    b = {r["id"]: score(set_name, [r]) for r in res[other]["utts"]}
    ids = sorted(set(a) & set(b))
    rng, diffs = random.Random(SEED), []
    for _ in range(n):
        pick = [rng.choice(ids) for _ in ids]
        words = sum(a[i]["ref_words"] for i in pick)
        diffs.append(100 * sum(b[i]["errors"] - a[i]["errors"] for i in pick) / words)
    diffs.sort()
    d = 100 * sum(b[i]["errors"] - a[i]["errors"] for i in ids) / sum(a[i]["ref_words"] for i in ids)
    return f"{d:+.2f} [{diffs[int(0.025 * n)]:+.2f}, {diffs[int(0.975 * n)]:+.2f}]"


def report(a):
    """Markdown tables from RESULTS (scores recomputed from the stored hypotheses)."""
    res = {}
    for p in sorted(RESULTS.glob("*.json")):
        if p.stem != "speed":
            r = json.loads(p.read_text())
            res[p.stem] = r | score(r["config"]["set"], r["utts"])
    n_slurp = len(manifest("slurp"))
    def cell(t, keys=("wer",)):
        r = res.get(t)
        if r and keys[0] == "slurp900":  # only runs that cover the whole SLURP subset
            keys = ("wer",) if len(r["utts"]) == n_slurp else ()
        return " / ".join(f"{r[k]:.2f}" if k in r else "–" for k in keys) or "–" if r else "–"
    cols = [("clean", "clean", ("wer",)), ("other", "other", ("wer",)), ("noisy", "noisy", ("wer",)),
            ("slurp-300", "slurp", ("wer_core",)), (f"slurp-{n_slurp}", "slurp", ("slurp900",))]
    models = [m for m in list(MODELS) + list(REQUANT) if f"{m}.clean.greedy" in res]
    print("## WER %, greedy search (Whisper-normalised)\n")
    print("| model | " + " | ".join(c[0] for c in cols) + " | slurp-300 headset / far-field |")
    print("|---|" + "---|" * (len(cols) + 1))
    for m in models:
        r = res[f"{m}.slurp.greedy"] if f"{m}.slurp.greedy" in res else None
        mic = "–"
        if r:
            core = [u for u in r["utts"] if manifest("slurp")[u["id"]]["core"]]
            sc = score("slurp", core)
            mic = f"{sc.get('wer_headset', 0):.2f} / {sc.get('wer_far-field', 0):.2f}"
        print(f"| {m} | " + " | ".join(cell(f"{m}.{s}.greedy", k) for _, s, k in cols) + f" | {mic} |")
    print("\nChange in greedy WER vs v2, percentage points, 95% paired bootstrap interval over the utterances "
          "both runs decoded:\n")
    sets = ("clean", "other", "noisy", "slurp")
    print("| model | " + " | ".join(sets) + " |\n|---|" + "---|" * len(sets))
    for m in models[1:]:
        print(f"| {m} | " + " | ".join(delta(res, f"v2.{s}.greedy", f"{m}.{s}.greedy") for s in sets) + " |")
    beams = [m for m in models if any(t.startswith(f"{m}.") and ".beam" in t for t in res)]
    print("\n## Beam search and hotwords, WER % (slurp column: the 300-recording core every run covers)\n")
    print("Config: decoding[.hotwords.score.case.encoding][.lr length_reward]; hotwords: b100/b1000 = LibriSpeech "
          "biasing lists (said words + distractors), d1000 = the 1000-list minus the said words, phone = the "
          "irrelevant phone list; both = as listed + Capitalised; pretok = pieces from tokens.txt; bpe = plain "
          "text tokenised by sherpa-onnx with bpe.vocab. LibriSpeech cells: WER / U-WER / B-WER.\n")
    print("| model | config | clean | other | noisy | slurp-300 |\n|---|---|---|---|---|---|")
    for m in beams:
        configs = sorted({t.split(".", 2)[2] for t in res if t.startswith(m + ".") and ".n" not in t},
                         key=lambda c: (c != "greedy", c != "beam4", ".b1" in c, ".d" in c, c))
        for c in configs:
            row = [cell(f"{m}.{s}.{c}", ("wer", "u_wer", "b_wer") if s != "noisy" else ("wer",))
                   for s in ("clean", "other", "noisy")] + [cell(f"{m}.slurp.{c}", ("wer_core",))]
            print(f"| {m} | {c} | " + " | ".join(row) + " |")
    rtf = {}
    for t, r in res.items():
        rtf.setdefault(t.split(".", 2)[2].split(".")[0], []).append(r["rtf"])
    print("\n## Mean RTF of the accuracy runs (parallel jobs on loaded machines; `speed` gives clean numbers)\n")
    for k in sorted(rtf):
        print(f"- {k}: {sum(rtf[k]) / len(rtf[k]):.3f} over {len(rtf[k])} runs")
    sp = RESULTS / "speed.json"
    if sp.exists():
        s = json.loads(sp.read_text())
        print(f"\n## Speed (4 threads pinned to CPUs {s['cpus']}, {s['utts']} test-clean utterances)\n")
        print("| model | int8 files MB | load cold s | load warm s | RTF greedy | RTF beam 4 | RTF beam 4 + phone list |")
        print("|---|---|---|---|---|---|---|")
        for m, r in s["models"].items():
            print(f"| {m} | {r['size_mb']:.0f} | {r['load_cold_s']:.2f} | {r['load_warm_s']:.2f} | "
                  f"{r['rtf_greedy']:.3f} | {r['rtf_beam4']:.3f} | {r['rtf_beam4_phone']:.3f} |")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("prepare")
    q = sub.add_parser("requant")
    q.add_argument("models", nargs="*", default=list(REQUANT))
    d = sub.add_parser("decode")
    d.add_argument("--model", required=True, choices=list(MODELS) + list(REQUANT))
    d.add_argument("--set", required=True, choices=["clean", "other", "noisy", "slurp"])
    d.add_argument("--beam", type=int, default=0, help="max_active_paths for modified_beam_search; 0 = greedy")
    d.add_argument("--hotwords", default="none", help="none, b100/b500/b1000 (biasing lists), "
                   "d100/d500/d1000 (the same lists minus the words said), phone (irrelevant phone list)")
    d.add_argument("--score", type=float, default=2.0, help="hotwords_score")
    d.add_argument("--case", default="both", choices=["lower", "cap", "both"])
    d.add_argument("--encoding", default="pretok", choices=["bpe", "pretok"],
                   help="bpe: plain text + modeling_unit=bpe + bpe.vocab; pretok: pieces from tokens.txt")
    d.add_argument("--length-reward", type=float, default=0.0, help="per-stream option of modified_beam_search")
    d.add_argument("--threads", type=int, default=4)
    d.add_argument("--limit", type=int, default=0)
    d.add_argument("--force", action="store_true")
    r = sub.add_parser("all")
    r.add_argument("--best", default="v2", help="second model for the biasing study")
    r.add_argument("--jobs", type=int, default=4)
    r.add_argument("--only", nargs="*", help="only the runs of these models")
    s = sub.add_parser("speed")
    s.add_argument("--models", nargs="*", default=list(MODELS))
    s.add_argument("--cpus", type=lambda x: {int(c) for c in x.split(",")}, default={0, 2, 4, 6})
    s.add_argument("--n", type=int, default=40)
    sub.add_parser("report")
    a = p.parse_args()
    ensure_dirs()
    if a.cmd == "prepare":
        prepare_models()
        prepare_data()
    elif a.cmd == "requant":
        for m in a.models:
            requant(m)
    elif a.cmd == "decode":
        decode(a)
    elif a.cmd == "all":
        run_all(a)
    elif a.cmd == "speed":
        speed(a)
    else:
        report(a)


if __name__ == "__main__":
    main()
