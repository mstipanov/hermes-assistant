import sys, numpy as np, sherpa_onnx, soundfile as sf
M = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
kws = sherpa_onnx.KeywordSpotter(
    tokens=f"{M}/tokens.txt",
    encoder=f"{M}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    decoder=f"{M}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    joiner=f"{M}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    keywords_file="keywords.txt", num_threads=2, provider="cpu",
)
for path in sys.argv[1:]:
    audio, sr = sf.read(path, dtype="float32")
    if audio.ndim > 1: audio = audio[:, 0]
    tail = np.zeros(int(0.8 * sr), dtype=np.float32)
    s = kws.create_stream()
    s.accept_waveform(sr, audio)
    s.accept_waveform(sr, tail)
    s.input_finished()
    hits = []
    while kws.is_ready(s):
        kws.decode_stream(s)
        r = kws.get_result(s)
        if r:
            hits.append((r, list(kws.timestamps(s))))
            kws.reset_stream(s)
    print(f"{path}: {hits if hits else 'NO DETECTION'}")
