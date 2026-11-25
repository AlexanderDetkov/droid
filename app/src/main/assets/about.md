Droid runs an AI agent on your phone: Claude Code, Codex or OpenCode. It's free and open source, under the Apache License 2.0. It's an independent project, not affiliated with or endorsed by Anthropic or OpenAI.

Source, releases and issues: https://github.com/AlexanderDetkov/droid

## Built into Droid

- **sherpa-onnx** (Apache-2.0), with **ONNX Runtime** (MIT, with its third-party notices): on-device speech. https://github.com/k2-fsa/sherpa-onnx
- **proot**, Termux's fork (GPL-2.0), statically linked with **talloc** (LGPL-3.0): runs Droid's Linux without root. Its exact source is attached to each Droid release, and `tools/proot/build.sh` in Droid's source builds it. https://github.com/termux/proot
- **marked** (MIT): Markdown in the pages the agent makes. https://github.com/markedjs/marked
- **Android Jetpack** (Compose, WebKit and others), **Kotlin**, and **Apache Commons** Compress, Codec, IO and Lang (Apache-2.0).
- **Material Symbols** by Google (Apache-2.0): icons.

## Downloaded at setup

- **Claude Code** by Anthropic, under Anthropic's terms, from claude.ai. https://claude.com/claude-code
- **Alpine Linux** and its packages, each under its own license. https://alpinelinux.org
- **Codex** (Apache-2.0) and **OpenCode** (MIT), if you set them up.

## Voice models, downloaded if you set up voice

- **Silero VAD** (MIT). https://github.com/snakers4/silero-vad
- **NVIDIA Parakeet Unified EN 0.6B** (NVIDIA Open Model License): speech recognition. https://huggingface.co/nvidia/parakeet-unified-en-0.6b
- **NVIDIA Parakeet TDT 0.6B v2** (CC-BY-4.0): speech recognition on phones set up before Unified, until updated. https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2
- **Pipecat Smart Turn v3.2** (BSD-2-Clause): hearing when you've finished talking. https://huggingface.co/pipecat-ai/smart-turn-v3
- **NVIDIA TitaNet small** (Apache-2.0, NeMo): voice match.
- **Kyutai Pocket TTS** in sherpa-onnx's ONNX export, for non-commercial use only: Droid's voice, which imitates its "bria" sample. https://github.com/kyutai-labs/pocket-tts

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
